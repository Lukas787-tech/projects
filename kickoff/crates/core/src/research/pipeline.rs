//! One research run: a small structured call plans the searches from the
//! brief, then web search and asset sites are queried a few at a time.
//!
//! Search problems never fail a run. They become warnings, and a comparable
//! that couldn't be checked online is kept as unverified model knowledge.

use std::collections::{BTreeSet, HashSet};
use std::sync::Mutex;
use std::time::Duration;

use futures::future::BoxFuture;
use futures::stream::{self, StreamExt};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use ts_rs::TS;

use super::{
    AssetHit, Candidate, Credit, LicensePolicy, Origin, RefCategory, SearchError, SourceAccess, SourceInfo, WebHit,
    WebQuery, WebSearch, normalize_url, simplify,
};
use crate::brief::{self, Brief};
use crate::llm::{ChatMessage, Route, Router, StructuredError, StructuredOptions, structured_call};
use crate::pack::{self, FieldKind, PackError, ProjectTypePack};

/// Searches in flight at once.
const PARALLEL: usize = 3;
/// Longest wait for a rate limit before skipping that search.
const MAX_WAIT: Duration = Duration::from_secs(20);
const VISUAL_PAGES: usize = 3;
const VISUAL_IMAGES: usize = 3;
const ASSETS_PER_SOURCE: usize = 4;
const MAX_PROPOSALS: usize = 4;
/// Titles listed as "already shown" in a planning prompt.
const MAX_EXCLUDED: usize = 30;

#[derive(Debug, thiserror::Error)]
pub enum ResearchError {
    #[error(transparent)]
    Pack(#[from] PackError),
    #[error(transparent)]
    Model(#[from] StructuredError),
    #[error("react to a few references first")]
    NothingToRefine,
}

/// A comparable the model suggested.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct ComparableIdea {
    pub title: String,
    pub why: String,
    pub learn: String,
    pub avoid: String,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct AssetQuery {
    pub kind: String,
    pub query: String,
}

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct ResearchPlan {
    pub comparables: Vec<ComparableIdea>,
    pub visual_queries: Vec<String>,
    pub asset_queries: Vec<AssetQuery>,
}

/// "More like this": the reference to build on.
#[derive(Debug, Clone, PartialEq)]
pub struct Seed {
    pub category: RefCategory,
    pub title: String,
    pub summary: Option<String>,
}

/// A reference the user reacted to, as the refine prompt sees it.
#[derive(Debug, Clone, PartialEq)]
pub struct RefNote {
    pub category: RefCategory,
    pub title: String,
    pub summary: Option<String>,
}

/// A suggested brief change, accepted or ignored by the user.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, TS)]
#[ts(export)]
pub struct BriefProposal {
    pub field: String,
    /// A string, or a list of strings for list fields.
    #[ts(type = "string | string[]")]
    pub value: Value,
    pub reason: String,
}

/// Progress of a run, for the UI.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, TS)]
#[serde(tag = "kind", rename_all = "snake_case")]
#[ts(export)]
pub enum ResearchProgress {
    Planning,
    Searching {
        done: u32,
        total: u32,
    },
    /// A search service asked us to slow down.
    Waiting {
        source: String,
        seconds: u32,
    },
}

#[derive(Debug, Clone, Default, PartialEq)]
pub struct ResearchOutcome {
    pub candidates: Vec<Candidate>,
    /// Sentences for the user, e.g. "Tavily: the key was rejected (401)".
    pub warnings: Vec<String>,
}

/// What a run can search with.
pub struct ResearchTools<'a> {
    pub web: Option<&'a dyn WebSearch>,
    pub sources: &'a [SourceInfo],
    pub policy: &'a LicensePolicy,
}

pub struct Researcher<'a> {
    pack: &'a dyn ProjectTypePack,
    router: &'a Router,
    routes: &'a [Route],
    tools: ResearchTools<'a>,
}

type Found = (Vec<Candidate>, Option<String>);

impl<'a> Researcher<'a> {
    pub fn new(
        pack: &'a dyn ProjectTypePack,
        router: &'a Router,
        routes: &'a [Route],
        tools: ResearchTools<'a>,
    ) -> Self {
        Self { pack, router, routes, tools }
    }

    /// Plan, search and classify. `seen` holds [`Candidate::key`]s already
    /// stored for the project; they're neither suggested again nor returned.
    pub async fn run(
        &self,
        brief: &Brief,
        seed: Option<&Seed>,
        seen: &HashSet<String>,
        exclude_titles: &[String],
        on_progress: &(dyn Fn(ResearchProgress) + Send + Sync),
    ) -> Result<ResearchOutcome, ResearchError> {
        on_progress(ResearchProgress::Planning);
        let plan = self.plan(brief, seed, exclude_titles).await?;
        Ok(self.search(&plan, seed.map(|s| s.category), seen, on_progress).await)
    }

    /// The structured planning call.
    pub async fn plan(
        &self,
        brief: &Brief,
        seed: Option<&Seed>,
        exclude_titles: &[String],
    ) -> Result<ResearchPlan, ResearchError> {
        let manifest = self.pack.manifest();
        let config = &manifest.research;
        // "More like this" asks for a few, not a full run.
        let count = |n: u32| if seed.is_some() { n.div_ceil(2).max(1) } else { n };
        let seed_json = seed.map(|s| {
            let (label, field) = match s.category {
                RefCategory::Comparable => ("comparable game", "comparables"),
                RefCategory::Visual => ("visual reference", "visual_queries"),
                RefCategory::Asset => ("asset", "asset_queries"),
            };
            json!({ "category_label": label, "field": field, "title": s.title, "summary": s.summary })
        });
        let exclude: Vec<&str> = exclude_titles.iter().take(MAX_EXCLUDED).map(String::as_str).collect();
        let system = pack::render(
            self.pack.research_template(),
            json!({
                "brief": brief.summary(manifest),
                "comparables": count(config.comparables),
                "visual_queries": count(config.visual_queries),
                "asset_queries": count(config.asset_queries),
                "asset_kinds": config.asset_kinds.join(", "),
                "seed": seed_json,
                "exclude": exclude.join("; "),
            }),
        )?;
        let messages = vec![ChatMessage::system(system), ChatMessage::user("Return the research plan.")];
        let schema = plan_schema(&config.asset_kinds);
        let mut plan: ResearchPlan =
            structured_call(self.router, self.routes, messages, &schema, &StructuredOptions::named("research_plan"))
                .await?
                .value;

        // Trim to what was asked for; small models overshoot.
        plan.comparables.retain(|c| !c.title.trim().is_empty());
        plan.comparables.truncate(count(config.comparables) as usize);
        plan.visual_queries.retain(|q| !q.trim().is_empty());
        plan.visual_queries.truncate(count(config.visual_queries) as usize);
        plan.asset_queries.retain(|q| !q.query.trim().is_empty());
        plan.asset_queries.truncate(count(config.asset_queries) as usize);
        if let Some(seed) = seed {
            if seed.category != RefCategory::Comparable {
                plan.comparables.clear();
            }
            if seed.category != RefCategory::Visual {
                plan.visual_queries.clear();
            }
            if seed.category != RefCategory::Asset {
                plan.asset_queries.clear();
            }
        }
        Ok(plan)
    }

    /// Run a plan's searches. Results keep the plan's order.
    pub async fn search(
        &self,
        plan: &ResearchPlan,
        only: Option<RefCategory>,
        seen: &HashSet<String>,
        on_progress: &(dyn Fn(ResearchProgress) + Send + Sync),
    ) -> ResearchOutcome {
        let gate = Gate { disabled: Mutex::new(BTreeSet::new()), on_progress };
        let wants = |c| only.is_none_or(|o| o == c);
        let mut tasks: Vec<BoxFuture<'_, Found>> = Vec::new();
        if wants(RefCategory::Comparable) {
            for idea in &plan.comparables {
                tasks.push(Box::pin(self.comparable(idea, &gate)));
            }
        }
        if wants(RefCategory::Visual) && self.tools.web.is_some() {
            for query in &plan.visual_queries {
                tasks.push(Box::pin(self.visual(query, &gate)));
            }
        }
        if wants(RefCategory::Asset) {
            for query in &plan.asset_queries {
                for source in self.tools.sources.iter().filter(|s| s.kinds.contains(&query.kind)) {
                    if matches!(source.access, SourceAccess::Link { .. }) && self.tools.web.is_none() {
                        continue;
                    }
                    tasks.push(Box::pin(self.asset(query, source, &gate)));
                }
            }
        }

        let total = tasks.len() as u32;
        on_progress(ResearchProgress::Searching { done: 0, total });
        let mut done = 0;
        let mut found = Vec::new();
        let mut warnings = Vec::new();
        let mut results = stream::iter(tasks).buffered(PARALLEL);
        while let Some((candidates, warning)) = results.next().await {
            done += 1;
            on_progress(ResearchProgress::Searching { done, total });
            found.extend(candidates);
            warnings.extend(warning);
        }
        drop(results);

        let mut keys = seen.clone();
        found.retain(|c| keys.insert(c.key()));
        let mut unique = HashSet::new();
        warnings.retain(|w| unique.insert(w.clone()));
        ResearchOutcome { candidates: found, warnings }
    }

    /// Check a suggested game exists; keep it as unverified if we can't.
    async fn comparable(&self, idea: &ComparableIdea, gate: &Gate<'_>) -> Found {
        let template = &self.pack.manifest().research.comparable_query;
        let mut metadata = json!({ "learn": idea.learn, "avoid": idea.avoid });
        let mut warning = None;
        let mut hit = None;
        if let Some(web) = self.tools.web {
            let query = WebQuery {
                text: template.replace("{title}", idea.title.trim()),
                max_results: 5,
                ..WebQuery::default()
            };
            match gate.web(web, &query).await {
                Ok(hits) => {
                    let wanted = simplify(&idea.title);
                    hit = hits.into_iter().find(|h| simplify(&h.title).contains(&wanted));
                    if hit.is_none() {
                        metadata["note"] = json!("Search didn't find this game; check it exists.");
                    }
                }
                Err(w) => warning = w,
            }
        }
        let verified = hit.is_some();
        let licensing = self.tools.policy.classify(
            RefCategory::Comparable,
            None,
            None,
            &Credit { title: &idea.title, author: None, url: hit.as_ref().map_or("", |h| h.url.as_str()) },
        );
        let candidate = Candidate {
            category: RefCategory::Comparable,
            asset_kind: None,
            title: idea.title.trim().to_owned(),
            summary: Some(idea.why.trim().to_owned()).filter(|s| !s.is_empty()),
            source_id: self.tools.web.filter(|_| verified).map(|w| w.id().to_owned()),
            source_url: hit.as_ref().map(|h| h.url.clone()),
            author: None,
            licensing,
            thumbnail_url: hit.as_ref().and_then(|h| h.thumbnail_url.clone()),
            origin: if verified { Origin::Search } else { Origin::LlmKnowledge },
            verified,
            metadata,
        };
        (vec![candidate], warning)
    }

    /// Mood and style references: pages and, where offered, images.
    async fn visual(&self, text: &str, gate: &Gate<'_>) -> Found {
        let Some(web) = self.tools.web else { return (Vec::new(), None) };
        let query =
            WebQuery { text: text.to_owned(), max_results: VISUAL_PAGES as u8, images: true, ..WebQuery::default() };
        let hits = match gate.web(web, &query).await {
            Ok(hits) => hits,
            Err(w) => return (Vec::new(), w),
        };
        let (images, pages): (Vec<WebHit>, Vec<WebHit>) = hits.into_iter().partition(|h| h.image);
        let candidates = pages
            .into_iter()
            .take(VISUAL_PAGES)
            .chain(images.into_iter().take(VISUAL_IMAGES))
            .map(|h| self.web_candidate(RefCategory::Visual, None, h, web.id(), None, json!({ "query": text })))
            .collect();
        (candidates, None)
    }

    async fn asset(&self, query: &AssetQuery, source: &SourceInfo, gate: &Gate<'_>) -> Found {
        match &source.access {
            SourceAccess::Api(api) => {
                let hits = match gate.assets(source, api.as_ref(), query).await {
                    Ok(hits) => hits,
                    Err(w) => return (Vec::new(), w),
                };
                let candidates =
                    hits.into_iter().take(ASSETS_PER_SOURCE).map(|h| self.asset_candidate(query, source, h)).collect();
                (candidates, None)
            }
            SourceAccess::Link { site, url_prefix, query_hint } => {
                let Some(web) = self.tools.web else { return (Vec::new(), None) };
                let text = [query.query.as_str(), query.kind.as_str(), query_hint.as_deref().unwrap_or_default()]
                    .join(" ")
                    .trim()
                    .to_owned();
                let web_query =
                    WebQuery { text, max_results: ASSETS_PER_SOURCE as u8, sites: vec![site.clone()], images: false };
                let hits = match gate.web(web, &web_query).await {
                    Ok(hits) => hits,
                    Err(w) => return (Vec::new(), w),
                };
                let prefix = url_prefix.as_deref().map(normalize_url);
                let candidates = hits
                    .into_iter()
                    .filter(|h| prefix.as_ref().is_none_or(|p| normalize_url(&h.url).starts_with(p.as_str())))
                    .take(ASSETS_PER_SOURCE)
                    .map(|h| {
                        let metadata = json!({ "query": query.query, "found_with": web.id() });
                        self.web_candidate(
                            RefCategory::Asset,
                            Some(query.kind.clone()),
                            h,
                            &source.id,
                            Some(source),
                            metadata,
                        )
                    })
                    .collect();
                (candidates, None)
            }
        }
    }

    fn web_candidate(
        &self,
        category: RefCategory,
        asset_kind: Option<String>,
        hit: WebHit,
        source_id: &str,
        source: Option<&SourceInfo>,
        metadata: Value,
    ) -> Candidate {
        let licensing = self.tools.policy.classify(
            category,
            source.and_then(|s| s.source_license.as_ref()),
            None,
            &Credit { title: &hit.title, author: None, url: &hit.url },
        );
        Candidate {
            category,
            asset_kind,
            title: hit.title,
            summary: hit.snippet,
            source_id: Some(source_id.to_owned()),
            source_url: Some(hit.url),
            author: None,
            licensing,
            thumbnail_url: hit.thumbnail_url,
            origin: Origin::Search,
            verified: true,
            metadata,
        }
    }

    fn asset_candidate(&self, query: &AssetQuery, source: &SourceInfo, hit: AssetHit) -> Candidate {
        let licensing = self.tools.policy.classify(
            RefCategory::Asset,
            source.source_license.as_ref(),
            hit.license.as_ref(),
            &Credit { title: &hit.title, author: hit.author.as_deref(), url: &hit.url },
        );
        Candidate {
            category: RefCategory::Asset,
            asset_kind: hit.kind.or_else(|| Some(query.kind.clone())),
            title: hit.title,
            summary: hit.description,
            source_id: Some(source.id.clone()),
            source_url: Some(hit.url),
            author: hit.author,
            licensing,
            thumbnail_url: hit.thumbnail_url,
            origin: Origin::AssetApi,
            verified: true,
            metadata: json!({ "external_id": hit.external_id, "tags": hit.tags, "query": query.query }),
        }
    }

    /// Propose brief changes from what the user kept and rejected.
    pub async fn refine(
        &self,
        brief: &Brief,
        kept: &[RefNote],
        rejected: &[RefNote],
    ) -> Result<Vec<BriefProposal>, ResearchError> {
        if kept.is_empty() && rejected.is_empty() {
            return Err(ResearchError::NothingToRefine);
        }
        let manifest = self.pack.manifest();
        let fields = &manifest.research.refine_fields;
        let mut refinable = manifest.clone();
        refinable.fields.retain(|f| fields.contains(&f.id));
        let list = |notes: &[RefNote]| {
            if notes.is_empty() {
                return "(none)".to_owned();
            }
            notes
                .iter()
                .map(|n| {
                    let about = n.summary.as_deref().map(|s| format!(": {}", truncate(s, 160))).unwrap_or_default();
                    format!("- [{}] {}{about}", n.category.as_str(), n.title)
                })
                .collect::<Vec<_>>()
                .join("\n")
        };
        let system = pack::render(
            self.pack.refine_template(),
            json!({
                "max_proposals": MAX_PROPOSALS,
                "field_catalog": brief::field_catalog(&refinable),
                "brief": brief.summary(manifest),
                "kept": list(kept),
                "rejected": list(rejected),
            }),
        )?;
        let messages = vec![ChatMessage::system(system), ChatMessage::user("Return your proposals.")];
        #[derive(Deserialize)]
        struct Reply {
            proposals: Vec<BriefProposal>,
        }
        let reply: Reply = structured_call(
            self.router,
            self.routes,
            messages,
            &refine_schema(fields),
            &StructuredOptions::named("brief_proposals"),
        )
        .await?
        .value;

        // Keep proposals whose value fits the field and actually changes it.
        let proposals = reply
            .proposals
            .into_iter()
            .filter_map(|mut p| {
                let def = manifest.field(&p.field)?;
                p.value = match (&def.kind, p.value) {
                    (FieldKind::Text, Value::String(s)) if !s.trim().is_empty() => Value::String(s.trim().to_owned()),
                    (FieldKind::List { max_items, .. }, Value::Array(items)) => {
                        let items: Vec<Value> = items
                            .into_iter()
                            .filter_map(|v| v.as_str().map(str::trim).filter(|s| !s.is_empty()).map(Value::from))
                            .take(*max_items as usize)
                            .collect();
                        if items.is_empty() {
                            return None;
                        }
                        Value::Array(items)
                    }
                    _ => return None,
                };
                let current = brief.field(&p.field).map(|s| &s.value);
                (current != Some(&p.value)).then_some(p)
            })
            .take(MAX_PROPOSALS)
            .collect();
        Ok(proposals)
    }
}

/// Shared search state for one run: rate-limit waits, and services that
/// stopped working (a rejected key isn't tried again).
struct Gate<'p> {
    disabled: Mutex<BTreeSet<String>>,
    on_progress: &'p (dyn Fn(ResearchProgress) + Send + Sync),
}

impl Gate<'_> {
    /// `Err(Some(warning))` on a new problem, `Err(None)` when the service
    /// already failed earlier in this run.
    async fn web(&self, web: &dyn WebSearch, query: &WebQuery) -> Result<Vec<WebHit>, Option<String>> {
        self.call(web.name(), || web.search(query)).await
    }

    async fn assets(
        &self,
        source: &SourceInfo,
        api: &dyn super::AssetSource,
        query: &AssetQuery,
    ) -> Result<Vec<AssetHit>, Option<String>> {
        self.call(&source.display_name, || api.search(&query.query, Some(&query.kind), ASSETS_PER_SOURCE)).await
    }

    async fn call<T, F, Fut>(&self, name: &str, mut search: F) -> Result<T, Option<String>>
    where
        F: FnMut() -> Fut,
        Fut: Future<Output = Result<T, SearchError>>,
    {
        if self.is_disabled(name) {
            return Err(None);
        }
        let mut waited = false;
        loop {
            let error = match search().await {
                Ok(v) => return Ok(v),
                Err(e) => e,
            };
            if self.is_disabled(name) {
                return Err(None);
            }
            match error {
                SearchError::RateLimited { retry_after } if !waited => {
                    let wait = retry_after.unwrap_or(Duration::from_secs(2));
                    if wait > MAX_WAIT {
                        return Err(Some(format!("{name} is rate limited; skipped one search.")));
                    }
                    (self.on_progress)(ResearchProgress::Waiting {
                        source: name.to_owned(),
                        seconds: wait.as_secs_f32().ceil() as u32,
                    });
                    tokio::time::sleep(wait).await;
                    waited = true;
                }
                SearchError::RateLimited { .. } => {
                    return Err(Some(format!("{name} is rate limited; skipped one search.")));
                }
                // A rejected key, a used-up allowance or a service that can't
                // be reached won't recover within one run.
                e @ (SearchError::Auth(_) | SearchError::Quota(_) | SearchError::Unavailable(_)) => {
                    self.disabled.lock().unwrap_or_else(|p| p.into_inner()).insert(name.to_owned());
                    return Err(Some(format!("{name}: {e}. Skipped it for this run.")));
                }
                e @ SearchError::Invalid(_) => return Err(Some(format!("{name}: {e}"))),
            }
        }
    }

    fn is_disabled(&self, name: &str) -> bool {
        self.disabled.lock().unwrap_or_else(|p| p.into_inner()).contains(name)
    }
}

fn plan_schema(asset_kinds: &[String]) -> Value {
    let text = json!({ "type": "string" });
    json!({
        "type": "object",
        "required": ["comparables", "visual_queries", "asset_queries"],
        "additionalProperties": false,
        "properties": {
            "comparables": {
                "type": "array",
                "items": {
                    "type": "object",
                    "required": ["title", "why", "learn", "avoid"],
                    "additionalProperties": false,
                    "properties": { "title": text, "why": text, "learn": text, "avoid": text }
                }
            },
            "visual_queries": { "type": "array", "items": text },
            "asset_queries": {
                "type": "array",
                "items": {
                    "type": "object",
                    "required": ["kind", "query"],
                    "additionalProperties": false,
                    "properties": { "kind": { "type": "string", "enum": asset_kinds }, "query": text }
                }
            }
        }
    })
}

fn refine_schema(fields: &[String]) -> Value {
    json!({
        "type": "object",
        "required": ["proposals"],
        "additionalProperties": false,
        "properties": {
            "proposals": {
                "type": "array",
                "items": {
                    "type": "object",
                    "required": ["field", "value", "reason"],
                    "additionalProperties": false,
                    "properties": {
                        "field": { "type": "string", "enum": fields },
                        "value": { "anyOf": [ { "type": "string" }, { "type": "array", "items": { "type": "string" } } ] },
                        "reason": { "type": "string" }
                    }
                }
            }
        }
    })
}

fn truncate(text: &str, max: usize) -> String {
    match text.char_indices().nth(max) {
        Some((i, _)) => format!("{}…", &text[..i]),
        None => text.to_owned(),
    }
}

#[cfg(test)]
mod tests {
    use std::sync::Arc;

    use async_trait::async_trait;

    use super::*;
    use crate::llm::router::RetryPolicy;
    use crate::llm::{ChatRequest, OutputFormat};
    use crate::research::{AssetSource, Classification, KnownLicense, LicenseClaim};
    use crate::testing::{FakeProvider, RecordingObserver, Step};

    /// Answers web queries from a script and records them.
    #[derive(Default)]
    struct FakeWeb {
        fail: Option<SearchError>,
        /// Fail only the first call.
        fail_once: bool,
        queries: Mutex<Vec<WebQuery>>,
    }

    #[async_trait]
    impl WebSearch for FakeWeb {
        fn id(&self) -> &str {
            "tavily"
        }
        fn name(&self) -> &str {
            "Tavily"
        }
        async fn search(&self, query: &WebQuery) -> Result<Vec<WebHit>, SearchError> {
            let first = {
                let mut q = self.queries.lock().unwrap();
                q.push(query.clone());
                q.len() == 1
            };
            if let Some(e) = &self.fail
                && (first || !self.fail_once)
            {
                return Err(e.clone());
            }
            let hit = |title: &str, url: &str| WebHit {
                title: title.into(),
                url: url.into(),
                snippet: Some(format!("about {title}")),
                thumbnail_url: None,
                image: false,
            };
            Ok(match query.text.as_str() {
                "Stardew Valley video game" => vec![
                    hit("Farming games list", "https://example.com/list"),
                    hit("Stardew Valley on Steam", "https://store.example.com/stardew"),
                ],
                "Made Up Quest video game" => vec![hit("Something else", "https://example.com/else")],
                "cozy pixel farm palette" => vec![
                    hit("Cozy palettes", "https://art.example.com/palettes"),
                    WebHit {
                        thumbnail_url: Some("https://img.example.com/farm.png".into()),
                        image: true,
                        ..hit("cozy pixel farm palette", "https://img.example.com/farm.png")
                    },
                ],
                // Link-only source: only /assets/ pages count.
                t if query.sites == ["kenney.nl"] => vec![
                    hit(&format!("Kenney: {t}"), "https://kenney.nl/assets/farm-pack"),
                    hit("Kenney blog", "https://www.kenney.nl/blog/news"),
                ],
                _ => vec![],
            })
        }
    }

    struct FakeAssets {
        license: Option<LicenseClaim>,
    }

    #[async_trait]
    impl AssetSource for FakeAssets {
        fn id(&self) -> &str {
            "models"
        }
        fn kinds(&self) -> &[String] {
            &[]
        }
        async fn search(&self, query: &str, kind: Option<&str>, _limit: usize) -> Result<Vec<AssetHit>, SearchError> {
            Ok(vec![AssetHit {
                external_id: "abc".into(),
                title: format!("{query} model"),
                url: "https://models.example.com/abc".into(),
                author: Some("Rico".into()),
                description: None,
                thumbnail_url: Some("https://models.example.com/abc.png".into()),
                kind: kind.map(str::to_owned),
                tags: vec!["farm".into()],
                license: self.license.clone(),
            }])
        }
    }

    fn policy() -> LicensePolicy {
        LicensePolicy::new([
            KnownLicense {
                id: "CC0-1.0".into(),
                name: "CC0 1.0".into(),
                url: "https://creativecommons.org/publicdomain/zero/1.0/".into(),
                attribution: false,
                share_alike: false,
            },
            KnownLicense {
                id: "CC-BY-4.0".into(),
                name: "CC BY 4.0".into(),
                url: "https://creativecommons.org/licenses/by/4.0/".into(),
                attribution: true,
                share_alike: false,
            },
        ])
    }

    fn sources(item_license: Option<LicenseClaim>) -> Vec<SourceInfo> {
        vec![
            SourceInfo {
                id: "models".into(),
                display_name: "Model Site".into(),
                kinds: vec!["model".into()],
                source_license: None,
                access: SourceAccess::Api(Arc::new(FakeAssets { license: item_license })),
            },
            SourceInfo {
                id: "kenney".into(),
                display_name: "Kenney".into(),
                kinds: vec!["sprite".into(), "model".into()],
                source_license: Some(LicenseClaim::id("CC0-1.0")),
                access: SourceAccess::Link {
                    site: "kenney.nl".into(),
                    url_prefix: Some("kenney.nl/assets/".into()),
                    query_hint: None,
                },
            },
        ]
    }

    fn plan_json() -> String {
        json!({
            "comparables": [
                { "title": "Stardew Valley", "why": "Cozy farming loop", "learn": "Seasons", "avoid": "Scope" },
                { "title": "Made Up Quest", "why": "Maybe", "learn": "-", "avoid": "-" },
                { "title": " ", "why": "", "learn": "", "avoid": "" }
            ],
            "visual_queries": ["cozy pixel farm palette"],
            "asset_queries": [ { "kind": "model", "query": "barn" } ]
        })
        .to_string()
    }

    fn planner(reply: String) -> Arc<FakeProvider> {
        FakeProvider::with_handler("groq", move |req: &ChatRequest| {
            assert!(matches!(req.format, OutputFormat::JsonSchema { .. }));
            Step::reply(&reply)
        })
    }

    fn router() -> Router {
        Router::new(RetryPolicy { max_attempts: 1, ..RetryPolicy::default() }, RecordingObserver::new())
    }

    fn routes(p: &Arc<FakeProvider>) -> Vec<Route> {
        vec![Route { provider: p.clone(), model: "m".into(), rpm: None }]
    }

    fn brief() -> (Arc<dyn ProjectTypePack>, Brief) {
        let pack = pack::find("game").unwrap();
        let mut brief = Brief::new(pack.manifest());
        brief.set_user_value(pack.manifest(), "working_title", &json!("Hearth"), 1).unwrap();
        brief.set_user_value(pack.manifest(), "art_direction", &json!("Pixel art"), 1).unwrap();
        (pack, brief)
    }

    fn progress_log() -> (Arc<Mutex<Vec<ResearchProgress>>>, impl Fn(ResearchProgress) + Send + Sync) {
        let log = Arc::new(Mutex::new(Vec::new()));
        let sink = log.clone();
        (log, move |p| sink.lock().unwrap().push(p))
    }

    #[tokio::test]
    async fn verifies_comparables_and_classifies_what_it_finds() {
        let (pack, brief) = brief();
        let llm = planner(plan_json());
        let (router, routes) = (router(), routes(&llm));
        let web = FakeWeb::default();
        let sources = sources(Some(LicenseClaim {
            label: Some("CC Attribution".into()),
            url: Some("https://creativecommons.org/licenses/by/4.0/".into()),
            ..Default::default()
        }));
        let policy = policy();
        let tools = ResearchTools { web: Some(&web), sources: &sources, policy: &policy };
        let researcher = Researcher::new(pack.as_ref(), &router, &routes, tools);
        let seen: HashSet<String> =
            [super::super::ref_key(RefCategory::Visual, "", Some("https://art.example.com/palettes/"))]
                .into_iter()
                .collect();
        let (log, on_progress) = progress_log();

        let outcome = researcher.run(&brief, None, &seen, &[], &on_progress).await.unwrap();

        let titles: Vec<_> = outcome.candidates.iter().map(|c| (c.category, c.title.as_str())).collect();
        assert_eq!(
            titles,
            [
                (RefCategory::Comparable, "Stardew Valley"),
                (RefCategory::Comparable, "Made Up Quest"),
                // The palettes page was already stored.
                (RefCategory::Visual, "cozy pixel farm palette"),
                (RefCategory::Asset, "barn model"),
                (RefCategory::Asset, "Kenney: barn model"),
            ]
        );
        let [stardew, made_up, image, model, kenney] = &outcome.candidates[..] else { unreachable!() };
        assert!(stardew.verified && stardew.origin == Origin::Search);
        assert_eq!(stardew.source_url.as_deref(), Some("https://store.example.com/stardew"));
        assert_eq!(stardew.metadata["learn"], "Seasons");
        assert_eq!(stardew.licensing.classification, Classification::ReferenceOnly);
        assert!(!made_up.verified && made_up.origin == Origin::LlmKnowledge && made_up.source_url.is_none());
        assert!(made_up.metadata["note"].as_str().unwrap().contains("check it exists"));
        assert_eq!(image.thumbnail_url.as_deref(), Some("https://img.example.com/farm.png"));
        assert_eq!(image.licensing.classification, Classification::ReferenceOnly);

        assert_eq!(model.origin, Origin::AssetApi);
        assert_eq!(model.licensing.classification, Classification::Usable);
        assert_eq!(model.licensing.license_id.as_deref(), Some("CC-BY-4.0"));
        assert!(model.licensing.attribution.as_deref().unwrap().contains("by Rico"));
        assert_eq!(kenney.source_id.as_deref(), Some("kenney"));
        assert_eq!(kenney.licensing.license_id.as_deref(), Some("CC0-1.0"));

        let queries = web.queries.lock().unwrap();
        assert!(queries.iter().any(|q| q.images && q.text == "cozy pixel farm palette"));
        assert!(queries.iter().any(|q| q.sites == ["kenney.nl"] && q.text == "barn model"));
        assert!(outcome.warnings.is_empty(), "{:?}", outcome.warnings);
        let log = log.lock().unwrap();
        assert_eq!(log.first(), Some(&ResearchProgress::Planning));
        assert_eq!(log.last(), Some(&ResearchProgress::Searching { done: 5, total: 5 }));
    }

    #[tokio::test]
    async fn without_web_search_comparables_are_unverified() {
        let (pack, brief) = brief();
        let llm = planner(plan_json());
        let (router, routes) = (router(), routes(&llm));
        let sources =
            sources(Some(LicenseClaim { label: Some("CC Attribution-NonCommercial".into()), ..Default::default() }));
        let policy = policy();
        let tools = ResearchTools { web: None, sources: &sources, policy: &policy };
        let outcome = Researcher::new(pack.as_ref(), &router, &routes, tools)
            .run(&brief, None, &HashSet::new(), &[], &|_| {})
            .await
            .unwrap();

        let comparables: Vec<_> = outcome.candidates.iter().filter(|c| c.category == RefCategory::Comparable).collect();
        assert_eq!(comparables.len(), 2);
        assert!(comparables.iter().all(|c| !c.verified && c.origin == Origin::LlmKnowledge));
        assert!(comparables[0].metadata.get("note").is_none(), "nothing was searched");
        // No visuals or link-only assets without search; the API asset is
        // reference-only because of its non-commercial license.
        let assets: Vec<_> = outcome.candidates.iter().filter(|c| c.category == RefCategory::Asset).collect();
        assert_eq!(assets.len(), 1);
        assert_eq!(assets[0].licensing.classification, Classification::ReferenceOnly);
        assert!(outcome.candidates.iter().all(|c| c.category != RefCategory::Visual));
        assert!(outcome.warnings.is_empty(), "no search is not a failure: {:?}", outcome.warnings);
    }

    #[tokio::test]
    async fn a_rejected_key_is_reported_once_and_not_retried() {
        let (pack, brief) = brief();
        let llm = planner(plan_json());
        let (router, routes) = (router(), routes(&llm));
        let web = FakeWeb { fail: Some(SearchError::Auth("401".into())), ..FakeWeb::default() };
        let sources = sources(None);
        let policy = policy();
        let tools = ResearchTools { web: Some(&web), sources: &sources, policy: &policy };
        let outcome = Researcher::new(pack.as_ref(), &router, &routes, tools)
            .run(&brief, None, &HashSet::new(), &[], &|_| {})
            .await
            .unwrap();

        assert_eq!(outcome.warnings, ["Tavily: the key was rejected (401). Skipped it for this run."]);
        // Searches already in flight when it failed may have gone out; later ones don't.
        assert!(web.queries.lock().unwrap().len() <= PARALLEL);
        assert!(outcome.candidates.iter().filter(|c| c.category == RefCategory::Comparable).all(|c| !c.verified));
        // The API source still worked.
        assert!(outcome.candidates.iter().any(|c| c.origin == Origin::AssetApi));
    }

    struct Offline;

    #[async_trait]
    impl AssetSource for Offline {
        fn id(&self) -> &str {
            "offline"
        }
        fn kinds(&self) -> &[String] {
            &[]
        }
        async fn search(&self, _q: &str, _k: Option<&str>, _l: usize) -> Result<Vec<AssetHit>, SearchError> {
            Err(SearchError::Unavailable("couldn't connect".into()))
        }
    }

    #[tokio::test]
    async fn an_unreachable_source_is_reported_once() {
        let (pack, brief) = brief();
        let two_queries = json!({
            "comparables": [], "visual_queries": [],
            "asset_queries": [ { "kind": "model", "query": "barn" }, { "kind": "model", "query": "fence" } ]
        });
        let llm = planner(two_queries.to_string());
        let (router, routes) = (router(), routes(&llm));
        let sources = vec![SourceInfo {
            id: "offline".into(),
            display_name: "Model Site".into(),
            kinds: vec!["model".into()],
            source_license: None,
            access: SourceAccess::Api(Arc::new(Offline)),
        }];
        let policy = policy();
        let tools = ResearchTools { web: None, sources: &sources, policy: &policy };
        let outcome = Researcher::new(pack.as_ref(), &router, &routes, tools)
            .run(&brief, None, &HashSet::new(), &[], &|_| {})
            .await
            .unwrap();
        assert_eq!(outcome.warnings, ["Model Site: couldn't connect. Skipped it for this run."]);
    }

    #[tokio::test(start_paused = true)]
    async fn waits_out_a_short_rate_limit() {
        let (pack, brief) = brief();
        let one_game = json!({
            "comparables": [ { "title": "Stardew Valley", "why": "w", "learn": "l", "avoid": "a" } ],
            "visual_queries": [], "asset_queries": []
        });
        let llm = planner(one_game.to_string());
        let (router, routes) = (router(), routes(&llm));
        let web = FakeWeb {
            fail: Some(SearchError::RateLimited { retry_after: Some(Duration::from_secs(3)) }),
            fail_once: true,
            ..FakeWeb::default()
        };
        let policy = policy();
        let tools = ResearchTools { web: Some(&web), sources: &[], policy: &policy };
        let (log, on_progress) = progress_log();
        let outcome = Researcher::new(pack.as_ref(), &router, &routes, tools)
            .run(&brief, None, &HashSet::new(), &[], &on_progress)
            .await
            .unwrap();
        assert!(outcome.candidates[0].verified);
        assert!(log.lock().unwrap().contains(&ResearchProgress::Waiting { source: "Tavily".into(), seconds: 3 }));
    }

    #[tokio::test]
    async fn more_like_this_plans_one_category_around_the_seed() {
        let (pack, brief) = brief();
        let llm = planner(plan_json());
        let (router, routes) = (router(), routes(&llm));
        let web = FakeWeb::default();
        let policy = policy();
        let tools = ResearchTools { web: Some(&web), sources: &[], policy: &policy };
        let seed =
            Seed { category: RefCategory::Comparable, title: "Stardew Valley".into(), summary: Some("cozy".into()) };
        let outcome = Researcher::new(pack.as_ref(), &router, &routes, tools)
            .run(&brief, Some(&seed), &HashSet::new(), &["Stardew Valley".into()], &|_| {})
            .await
            .unwrap();

        assert!(outcome.candidates.iter().all(|c| c.category == RefCategory::Comparable));
        // Half the usual count: 5 -> 3.
        let prompt = &llm.requests()[0].messages[0].content;
        assert!(prompt.contains("wants more like it:\n- Stardew Valley: cozy"), "{prompt}");
        assert!(prompt.contains("Fill only \"comparables\""), "{prompt}");
        assert!(prompt.contains("up to 3 existing"), "{prompt}");
        assert!(prompt.contains("Already shown, don't repeat: Stardew Valley"), "{prompt}");
        assert!(prompt.contains("- Working title: Hearth"), "{prompt}");
    }

    #[tokio::test]
    async fn refine_keeps_only_proposals_that_fit_and_change_the_brief() {
        let (pack, brief) = brief();
        let reply = json!({ "proposals": [
            { "field": "art_direction", "value": "Warm pixel art, soft autumn palette", "reason": "You kept two autumn palettes." },
            { "field": "art_direction", "value": "Pixel art", "reason": "unchanged" },
            { "field": "pillars", "value": "not a list", "reason": "wrong shape" },
            { "field": "out_of_scope", "value": ["Multiplayer", " "], "reason": "You rejected online games." }
        ]});
        let llm = planner(reply.to_string());
        let (router, routes) = (router(), routes(&llm));
        let policy = policy();
        let tools = ResearchTools { web: None, sources: &[], policy: &policy };
        let researcher = Researcher::new(pack.as_ref(), &router, &routes, tools);
        let note = |category, title: &str| RefNote { category, title: title.into(), summary: None };

        let proposals = researcher
            .refine(
                &brief,
                &[note(RefCategory::Visual, "Autumn palette")],
                &[note(RefCategory::Comparable, "MMO Farm")],
            )
            .await
            .unwrap();
        let fields: Vec<_> = proposals.iter().map(|p| (p.field.as_str(), p.value.clone())).collect();
        assert_eq!(
            fields,
            [("art_direction", json!("Warm pixel art, soft autumn palette")), ("out_of_scope", json!(["Multiplayer"])),]
        );
        let prompt = &llm.requests()[0].messages[0].content;
        assert!(prompt.contains("- [visual] Autumn palette") && prompt.contains("- [comparable] MMO Farm"), "{prompt}");
        assert!(prompt.contains("- art_direction:") && !prompt.contains("- working_title:"), "{prompt}");

        assert!(matches!(researcher.refine(&brief, &[], &[]).await, Err(ResearchError::NothingToRefine)));
    }
}
