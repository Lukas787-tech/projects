//! Research for a project: runs the core pipeline with the user's search key
//! and the asset catalog, stores what it finds, records reactions, caches
//! thumbnails and proposes brief changes from what the user kept.

use std::collections::HashSet;
use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use async_trait::async_trait;
use base64::Engine;
use futures::StreamExt;
use kickoff_core::Task;
use kickoff_core::brief::Brief;
use kickoff_core::pack::{self, PackManifest, ProjectTypePack};
use kickoff_core::research::pipeline::{
    BriefProposal, RefNote, ResearchError, ResearchProgress, ResearchTools, Researcher, Seed,
};
use kickoff_core::research::{
    Candidate, LicensePolicy, RefCategory, SearchError, SourceInfo, WebHit, WebQuery, WebSearch, ref_key,
};
use kickoff_providers::presets::SearchPreset;
use kickoff_providers::sources::{SourceAccessKind, SourceCatalog};
use kickoff_providers::{Presets, build_search};
use kickoff_store::{Database, Project, Reaction, Stage, StoreError, StoredRef, UsageDelta, time};
use serde::Serialize;
use ts_rs::TS;

use crate::ai::AiError;
use crate::interview::Ai;
use crate::keys::KeyLibrary;

/// Largest thumbnail kept.
const MAX_THUMBNAIL_BYTES: usize = 3 * 1024 * 1024;

#[derive(Debug, thiserror::Error)]
pub enum ResearchServiceError {
    #[error("project or reference not found")]
    NotFound,
    #[error("research is already running for this project")]
    Busy,
    #[error("no AI provider is ready. Set one up in Settings or the Key Library.")]
    NoAi,
    #[error("this project type isn't available: {0}")]
    UnknownPack(String),
    #[error(transparent)]
    Store(#[from] StoreError),
    #[error(transparent)]
    Ai(#[from] AiError),
    #[error(transparent)]
    Research(#[from] ResearchError),
    #[error("saved project data is unreadable: {0}")]
    Corrupt(#[from] serde_json::Error),
}

type Result<T> = std::result::Result<T, ResearchServiceError>;

// ------------------------------------------------------------- web search

/// A search service research can use.
#[derive(Debug, Clone, PartialEq, Serialize, TS)]
#[ts(export)]
pub struct SearchProviderInfo {
    pub id: String,
    pub display_name: String,
    pub signup_url: Option<String>,
    pub card_required: bool,
    pub monthly_free_units: Option<u32>,
    pub unit: Option<String>,
    #[ts(type = "number")]
    pub used_this_month: u64,
}

#[derive(Debug, Clone, PartialEq, Serialize, TS)]
#[ts(export)]
pub struct SearchStatus {
    /// The service research uses; none means comparables stay unverified
    /// and there are no mood references.
    pub active: Option<SearchProviderInfo>,
    /// Every service a key could be added for, the recommended one first.
    pub options: Vec<SearchProviderInfo>,
}

/// Which web search research uses. Reads the credential store, so callers
/// run it on a blocking thread.
pub trait SearchAccess: Send + Sync {
    fn web(&self) -> Option<Arc<dyn WebSearch>>;
    fn status(&self) -> SearchStatus;
}

/// The first search service with a key in the Key Library, in preset order
/// (the default first). Calls are counted in the usage meter.
pub struct KeyedSearch {
    presets: Arc<Presets>,
    keys: Arc<KeyLibrary>,
    db: Arc<Database>,
}

impl KeyedSearch {
    pub fn new(presets: Arc<Presets>, keys: Arc<KeyLibrary>, db: Arc<Database>) -> Self {
        Self { presets, keys, db }
    }

    fn ordered(&self) -> Vec<&SearchPreset> {
        let default = &self.presets.defaults.search;
        let mut presets: Vec<&SearchPreset> = self.presets.search.iter().collect();
        presets.sort_by_key(|p| &p.id != default);
        presets
    }

    fn info(&self, p: &SearchPreset) -> SearchProviderInfo {
        let month = &time::local_day()[..7];
        SearchProviderInfo {
            id: p.id.clone(),
            display_name: p.display_name.clone(),
            signup_url: p.signup_url.clone(),
            card_required: p.card_required,
            monthly_free_units: p.monthly_free_units,
            unit: p.unit.clone(),
            used_this_month: self.db.units_for_month(&p.id, month).unwrap_or(0),
        }
    }
}

impl SearchAccess for KeyedSearch {
    fn web(&self) -> Option<Arc<dyn WebSearch>> {
        self.ordered().into_iter().find_map(|p| {
            let (_, key) = self.keys.secret_for_provider(&p.id).ok().flatten()?;
            let inner = build_search(p, Some(key)).ok()?;
            Some(Arc::new(Metered { inner, db: self.db.clone() }) as Arc<dyn WebSearch>)
        })
    }

    fn status(&self) -> SearchStatus {
        let ordered = self.ordered();
        let active = ordered
            .iter()
            .find(|p| self.keys.secret_for_provider(&p.id).ok().flatten().is_some())
            .map(|p| self.info(p));
        SearchStatus { active, options: ordered.into_iter().map(|p| self.info(p)).collect() }
    }
}

/// Counts each search (one credit or request) in the usage meter.
struct Metered {
    inner: Arc<dyn WebSearch>,
    db: Arc<Database>,
}

#[async_trait]
impl WebSearch for Metered {
    fn id(&self) -> &str {
        self.inner.id()
    }

    fn name(&self) -> &str {
        self.inner.name()
    }

    async fn search(&self, query: &WebQuery) -> std::result::Result<Vec<WebHit>, SearchError> {
        let result = self.inner.search(query).await;
        let delta = match &result {
            Ok(_) => UsageDelta { calls: 1, units: 1, ..UsageDelta::default() },
            Err(SearchError::RateLimited { .. }) => UsageDelta { rate_limited: 1, ..UsageDelta::default() },
            Err(_) => UsageDelta { calls: 1, ..UsageDelta::default() },
        };
        let (db, id) = (self.db.clone(), self.inner.id().to_owned());
        tokio::task::spawn_blocking(move || {
            if let Err(e) = db.record_usage(&id, &time::local_day(), delta) {
                tracing::warn!(error = %e, "couldn't record search usage");
            }
        });
        result
    }
}

// ------------------------------------------------------------- thumbnails

/// Downloads thumbnail images.
#[async_trait]
pub trait ThumbnailFetcher: Send + Sync {
    /// The image bytes and their media type.
    async fn fetch(&self, url: &str) -> std::result::Result<(Vec<u8>, String), String>;
}

pub struct HttpThumbnails {
    client: reqwest::Client,
}

impl Default for HttpThumbnails {
    fn default() -> Self {
        let client = reqwest::Client::builder()
            .user_agent(kickoff_core::branding::user_agent())
            .connect_timeout(Duration::from_secs(10))
            .timeout(Duration::from_secs(20))
            .redirect(reqwest::redirect::Policy::custom(|attempt| {
                if attempt.previous().len() >= 5 || !is_public_https(attempt.url()) {
                    attempt.stop()
                } else {
                    attempt.follow()
                }
            }))
            .build()
            .expect("HTTP client configuration is valid");
        Self { client }
    }
}

/// Thumbnail links come from search results, so only fetch public HTTPS
/// addresses: never this machine or the local network.
fn is_public_https(url: &reqwest::Url) -> bool {
    use std::net::IpAddr;
    if url.scheme() != "https" {
        return false;
    }
    let Some(host) = url.host_str() else { return false };
    let host = host.trim_start_matches('[').trim_end_matches(']');
    if let Ok(ip) = host.parse::<IpAddr>() {
        return !is_local(ip);
    }
    let host = host.trim_end_matches('.').to_ascii_lowercase();
    let local_suffixes = [".localhost", ".local", ".internal", ".lan", ".home.arpa"];
    host != "localhost" && !local_suffixes.iter().any(|s| host.ends_with(s))
}

/// Loopback, private, link-local, carrier-grade NAT or unspecified.
fn is_local(ip: std::net::IpAddr) -> bool {
    match ip {
        std::net::IpAddr::V4(v4) => {
            let [a, b, ..] = v4.octets();
            let shared = a == 100 && (64..128).contains(&b);
            v4.is_loopback() || v4.is_private() || v4.is_link_local() || v4.is_unspecified() || shared
        }
        std::net::IpAddr::V6(v6) => {
            if let Some(v4) = v6.to_ipv4_mapped() {
                return is_local(v4.into());
            }
            let first = v6.segments()[0];
            let unique_local = (first & 0xfe00) == 0xfc00;
            let link_local = (first & 0xffc0) == 0xfe80;
            v6.is_loopback() || v6.is_unspecified() || unique_local || link_local
        }
    }
}

/// Image types shown as thumbnails (no SVG), with their file extensions.
fn image_extension(media_type: &str) -> Option<&'static str> {
    match media_type.split(';').next().unwrap_or_default().trim().to_ascii_lowercase().as_str() {
        "image/png" => Some("png"),
        "image/jpeg" | "image/jpg" => Some("jpg"),
        "image/webp" => Some("webp"),
        "image/gif" => Some("gif"),
        "image/avif" => Some("avif"),
        _ => None,
    }
}

fn media_type_for(extension: &str) -> &'static str {
    match extension {
        "png" => "image/png",
        "webp" => "image/webp",
        "gif" => "image/gif",
        "avif" => "image/avif",
        _ => "image/jpeg",
    }
}

#[async_trait]
impl ThumbnailFetcher for HttpThumbnails {
    async fn fetch(&self, url: &str) -> std::result::Result<(Vec<u8>, String), String> {
        let parsed = reqwest::Url::parse(url).map_err(|e| e.to_string())?;
        if !is_public_https(&parsed) {
            return Err("not a public https address".into());
        }
        let response = self.client.get(parsed).send().await.map_err(|e| e.to_string())?;
        if !response.status().is_success() {
            return Err(format!("HTTP {}", response.status().as_u16()));
        }
        let media_type = response
            .headers()
            .get(reqwest::header::CONTENT_TYPE)
            .and_then(|v| v.to_str().ok())
            .unwrap_or_default()
            .to_owned();
        if image_extension(&media_type).is_none() {
            return Err(format!("not an image ({media_type})"));
        }
        if response.content_length().is_some_and(|n| n as usize > MAX_THUMBNAIL_BYTES) {
            return Err("image too large".into());
        }
        let mut bytes = Vec::new();
        let mut body = response.bytes_stream();
        while let Some(chunk) = body.next().await {
            bytes.extend_from_slice(&chunk.map_err(|e| e.to_string())?);
            if bytes.len() > MAX_THUMBNAIL_BYTES {
                return Err("image too large".into());
            }
        }
        Ok((bytes, media_type))
    }
}

// ------------------------------------------------------------- service

/// An asset site, as the research screen describes it.
#[derive(Debug, Clone, PartialEq, Serialize, TS)]
#[ts(export)]
pub struct SourceCard {
    pub id: String,
    pub display_name: String,
    /// Found through web search (needs a search key) rather than an API.
    pub needs_search: bool,
    pub kinds: Vec<String>,
    /// The license everything on the site carries, if there is one.
    pub site_license: Option<String>,
    /// A credit to show next to its results.
    pub ui_credit: Option<String>,
}

/// Everything the research screen shows.
#[derive(Debug, Clone, Serialize, TS)]
#[ts(export)]
pub struct ResearchView {
    pub project: Project,
    pub pack: PackManifest,
    pub brief: Brief,
    pub refs: Vec<StoredRef>,
    pub search: SearchStatus,
    pub sources: Vec<SourceCard>,
}

/// What a research run added.
#[derive(Debug, Clone, Serialize, TS)]
#[ts(export)]
pub struct ResearchRun {
    pub added: Vec<StoredRef>,
    pub warnings: Vec<String>,
}

pub struct ResearchService {
    db: Arc<Database>,
    ai: Arc<dyn Ai>,
    search: Arc<dyn SearchAccess>,
    catalog: Arc<SourceCatalog>,
    sources: Vec<SourceInfo>,
    policy: LicensePolicy,
    thumbnails: Arc<dyn ThumbnailFetcher>,
    thumbnails_dir: PathBuf,
    busy: Mutex<HashSet<String>>,
}

struct BusyGuard<'a> {
    set: &'a Mutex<HashSet<String>>,
    id: String,
}

impl Drop for BusyGuard<'_> {
    fn drop(&mut self) {
        self.set.lock().unwrap_or_else(|e| e.into_inner()).remove(&self.id);
    }
}

struct Loaded {
    project: Project,
    pack: Arc<dyn ProjectTypePack>,
    brief: Brief,
    refs: Vec<StoredRef>,
}

impl ResearchService {
    pub fn new(
        db: Arc<Database>,
        ai: Arc<dyn Ai>,
        search: Arc<dyn SearchAccess>,
        catalog: Arc<SourceCatalog>,
        sources: Vec<SourceInfo>,
        thumbnails: Arc<dyn ThumbnailFetcher>,
        thumbnails_dir: PathBuf,
    ) -> Self {
        let policy = catalog.policy();
        Self { db, ai, search, catalog, sources, policy, thumbnails, thumbnails_dir, busy: Mutex::new(HashSet::new()) }
    }

    async fn db<T: Send + 'static>(
        &self,
        f: impl FnOnce(&Database) -> std::result::Result<T, StoreError> + Send + 'static,
    ) -> Result<T> {
        let db = self.db.clone();
        tokio::task::spawn_blocking(move || f(&db)).await.expect("database task panicked").map_err(Into::into)
    }

    fn lock(&self, project_id: &str) -> Result<BusyGuard<'_>> {
        let mut busy = self.busy.lock().unwrap_or_else(|e| e.into_inner());
        if !busy.insert(project_id.to_owned()) {
            return Err(ResearchServiceError::Busy);
        }
        Ok(BusyGuard { set: &self.busy, id: project_id.to_owned() })
    }

    async fn load(&self, project_id: &str) -> Result<Loaded> {
        let id = project_id.to_owned();
        let (project, revision, refs) = self
            .db(move |db| {
                let Some(project) = db.get_project(&id)? else { return Ok(None) };
                Ok(Some((project, db.latest_brief_revision(&id)?, db.list_refs(&id)?)))
            })
            .await?
            .ok_or(ResearchServiceError::NotFound)?;
        let pack =
            pack::find(&project.pack_id).ok_or_else(|| ResearchServiceError::UnknownPack(project.pack_id.clone()))?;
        let brief = match revision {
            Some(r) => serde_json::from_str(&r.brief_json)?,
            None => Brief::new(pack.manifest()),
        };
        Ok(Loaded { project, pack, brief, refs })
    }

    async fn search_status(&self) -> SearchStatus {
        let search = self.search.clone();
        tokio::task::spawn_blocking(move || search.status()).await.expect("search status task panicked")
    }

    fn source_cards(&self) -> Vec<SourceCard> {
        self.catalog
            .sources
            .iter()
            .map(|s| SourceCard {
                id: s.id.clone(),
                display_name: s.display_name.clone(),
                needs_search: s.access == SourceAccessKind::LinkOnly,
                kinds: s.kinds.clone(),
                site_license: s
                    .source_license
                    .as_ref()
                    .map(|id| self.policy.get(id).map_or_else(|| id.clone(), |l| l.name.clone())),
                ui_credit: s.ui_credit.clone(),
            })
            .collect()
    }

    pub async fn get(&self, project_id: &str) -> Result<ResearchView> {
        let loaded = self.load(project_id).await?;
        Ok(ResearchView {
            pack: loaded.pack.manifest().clone(),
            project: loaded.project,
            brief: loaded.brief,
            refs: loaded.refs,
            search: self.search_status().await,
            sources: self.source_cards(),
        })
    }

    /// A full research run from the brief.
    pub async fn run(
        &self,
        project_id: &str,
        on_progress: &(dyn Fn(ResearchProgress) + Send + Sync),
    ) -> Result<ResearchRun> {
        let _guard = self.lock(project_id)?;
        let loaded = self.load(project_id).await?;
        let exclude = titles(&loaded.refs, RefCategory::Comparable);
        self.research(loaded, None, exclude, on_progress).await
    }

    /// Find more like one reference (and remember that the user liked it).
    pub async fn more_like_this(
        &self,
        project_id: &str,
        ref_id: &str,
        on_progress: &(dyn Fn(ResearchProgress) + Send + Sync),
    ) -> Result<ResearchRun> {
        let _guard = self.lock(project_id)?;
        let (pid, rid) = (project_id.to_owned(), ref_id.to_owned());
        if !self.db(move |db| db.set_reaction(&pid, &rid, Some(Reaction::MoreLikeThis))).await? {
            return Err(ResearchServiceError::NotFound);
        }
        let loaded = self.load(project_id).await?;
        let seed_ref = loaded.refs.iter().find(|r| r.id == ref_id).ok_or(ResearchServiceError::NotFound)?;
        let seed =
            Seed { category: seed_ref.category, title: seed_ref.title.clone(), summary: seed_ref.summary.clone() };
        let exclude = titles(&loaded.refs, seed.category);
        self.research(loaded, Some(seed), exclude, on_progress).await
    }

    async fn research(
        &self,
        loaded: Loaded,
        seed: Option<Seed>,
        exclude: Vec<String>,
        on_progress: &(dyn Fn(ResearchProgress) + Send + Sync),
    ) -> Result<ResearchRun> {
        let routes = self.ai.routes(Task::Research).await?;
        if routes.is_empty() {
            return Err(ResearchServiceError::NoAi);
        }
        let search = self.search.clone();
        let web = tokio::task::spawn_blocking(move || search.web()).await.expect("search setup task panicked");
        let seen: HashSet<String> =
            loaded.refs.iter().map(|r| ref_key(r.category, &r.title, r.source_url.as_deref())).collect();
        let tools = ResearchTools { web: web.as_deref(), sources: &self.sources, policy: &self.policy };
        let researcher = Researcher::new(loaded.pack.as_ref(), self.ai.router(), &routes, tools);
        let outcome = researcher.run(&loaded.brief, seed.as_ref(), &seen, &exclude, on_progress).await?;

        let items: Vec<(String, Candidate)> =
            outcome.candidates.into_iter().map(|c| (format!("ref-{}", uuid::Uuid::new_v4()), c)).collect();
        let ids: Vec<String> = items.iter().map(|(id, _)| id.clone()).collect();
        let project = loaded.project;
        let added = self
            .db(move |db| {
                db.insert_refs(&project.id, &items)?;
                if project.stage == Stage::Interview {
                    db.update_project(&project.id, &project.name, Stage::Research, project.engine.as_deref())?;
                }
                let mut refs = db.list_refs(&project.id)?;
                refs.retain(|r| ids.contains(&r.id));
                Ok(refs)
            })
            .await?;
        Ok(ResearchRun { added, warnings: outcome.warnings })
    }

    /// Record (or with `None`, clear) the user's reaction to a reference.
    pub async fn react(&self, project_id: &str, ref_id: &str, reaction: Option<Reaction>) -> Result<StoredRef> {
        let (pid, rid) = (project_id.to_owned(), ref_id.to_owned());
        self.db(move |db| {
            if !db.set_reaction(&pid, &rid, reaction)? {
                return Ok(None);
            }
            db.get_ref(&pid, &rid)
        })
        .await?
        .ok_or(ResearchServiceError::NotFound)
    }

    /// The reference's thumbnail as a `data:` URL, cached on disk after the
    /// first download. `None` when it has none or it can't be fetched.
    pub async fn thumbnail(&self, project_id: &str, ref_id: &str) -> Result<Option<String>> {
        let (pid, rid) = (project_id.to_owned(), ref_id.to_owned());
        let stored = self.db(move |db| db.get_ref(&pid, &rid)).await?.ok_or(ResearchServiceError::NotFound)?;
        let encode = |bytes: &[u8], media_type: &str| {
            format!("data:{media_type};base64,{}", base64::engine::general_purpose::STANDARD.encode(bytes))
        };
        if let Some(name) = &stored.thumbnail_path
            && let Ok(bytes) = tokio::fs::read(self.thumbnails_dir.join(name)).await
        {
            let extension = name.rsplit('.').next().unwrap_or_default();
            return Ok(Some(encode(&bytes, media_type_for(extension))));
        }
        let Some(url) = stored.thumbnail_url.as_deref() else { return Ok(None) };
        let (bytes, media_type) = match self.thumbnails.fetch(url).await {
            Ok(image) => image,
            Err(e) => {
                tracing::debug!(error = %e, "thumbnail unavailable");
                return Ok(None);
            }
        };
        let Some(extension) = image_extension(&media_type) else { return Ok(None) };
        let name = format!("{}.{extension}", stored.id);
        let saved = async {
            tokio::fs::create_dir_all(&self.thumbnails_dir).await?;
            tokio::fs::write(self.thumbnails_dir.join(&name), &bytes).await
        };
        match saved.await {
            Ok(()) => {
                let id = stored.id.clone();
                self.db(move |db| db.set_ref_thumbnail_path(&id, Some(&name))).await?;
            }
            Err(e) => tracing::warn!(error = %e, "couldn't cache a thumbnail"),
        }
        Ok(Some(encode(&bytes, media_type_for(extension))))
    }

    /// Brief changes suggested by what the user kept and rejected. The user
    /// accepts each one as an ordinary brief edit.
    pub async fn refine(&self, project_id: &str) -> Result<Vec<BriefProposal>> {
        let _guard = self.lock(project_id)?;
        let loaded = self.load(project_id).await?;
        let routes = self.ai.routes(Task::Research).await?;
        if routes.is_empty() {
            return Err(ResearchServiceError::NoAi);
        }
        let note = |r: &StoredRef| RefNote { category: r.category, title: r.title.clone(), summary: r.summary.clone() };
        let kept: Vec<RefNote> =
            loaded.refs.iter().filter(|r| r.reaction.is_some_and(Reaction::is_positive)).map(note).collect();
        let rejected: Vec<RefNote> =
            loaded.refs.iter().filter(|r| r.reaction == Some(Reaction::Reject)).map(note).collect();
        let tools = ResearchTools { web: None, sources: &[], policy: &self.policy };
        let researcher = Researcher::new(loaded.pack.as_ref(), self.ai.router(), &routes, tools);
        Ok(researcher.refine(&loaded.brief, &kept, &rejected).await?)
    }
}

fn titles(refs: &[StoredRef], category: RefCategory) -> Vec<String> {
    refs.iter().filter(|r| r.category == category).map(|r| r.title.clone()).collect()
}

#[cfg(test)]
mod tests {
    use kickoff_core::llm::router::RetryPolicy;
    use kickoff_core::llm::{Route, Router};
    use kickoff_core::research::{AssetHit, AssetSource, Classification, LicenseClaim, Origin, SourceAccess};
    use kickoff_core::testing::{FakeProvider, RecordingObserver, Step};
    use serde_json::json;

    use super::*;

    struct FakeAi {
        router: Router,
        routes: Vec<Route>,
    }

    #[async_trait]
    impl Ai for FakeAi {
        async fn routes(&self, _task: Task) -> std::result::Result<Vec<Route>, AiError> {
            Ok(self.routes.clone())
        }
        fn router(&self) -> &Router {
            &self.router
        }
    }

    struct NoSearch;

    impl SearchAccess for NoSearch {
        fn web(&self) -> Option<Arc<dyn WebSearch>> {
            None
        }
        fn status(&self) -> SearchStatus {
            SearchStatus { active: None, options: Vec::new() }
        }
    }

    struct Textures;

    #[async_trait]
    impl AssetSource for Textures {
        fn id(&self) -> &str {
            "polyhaven"
        }
        fn kinds(&self) -> &[String] {
            &[]
        }
        async fn search(
            &self,
            query: &str,
            _kind: Option<&str>,
            _limit: usize,
        ) -> std::result::Result<Vec<AssetHit>, SearchError> {
            Ok(vec![AssetHit {
                external_id: query.replace(' ', "_"),
                title: format!("{query} texture"),
                url: format!("https://polyhaven.com/a/{}", query.replace(' ', "_")),
                author: Some("Rico".into()),
                description: None,
                thumbnail_url: Some("https://cdn.example.com/thumb.png".into()),
                kind: Some("texture".into()),
                tags: vec![],
                license: None,
            }])
        }
    }

    struct FakeThumbnails(Mutex<u32>);

    #[async_trait]
    impl ThumbnailFetcher for FakeThumbnails {
        async fn fetch(&self, _url: &str) -> std::result::Result<(Vec<u8>, String), String> {
            *self.0.lock().unwrap() += 1;
            Ok((vec![0x89, b'P', b'N', b'G'], "image/png".into()))
        }
    }

    /// Answers the planning and refine calls.
    fn planner() -> Arc<FakeProvider> {
        FakeProvider::with_handler("groq", |req| {
            let system = &req.messages[0].content;
            if system.contains("refine a game design brief") {
                return Step::reply(
                    &json!({ "proposals": [
                        { "field": "art_direction", "value": "Mossy, overgrown ruins", "reason": "You kept mossy textures." }
                    ]})
                    .to_string(),
                );
            }
            let more = system.contains("wants more like it");
            Step::reply(
                &json!({
                    "comparables": if more { json!([]) } else { json!([{ "title": "Stardew Valley", "why": "farming", "learn": "seasons", "avoid": "scope" }]) },
                    "visual_queries": ["mossy ruins concept art"],
                    "asset_queries": [ { "kind": "texture", "query": if more { "wet moss" } else { "mossy stone" } } ]
                })
                .to_string(),
            )
        })
    }

    fn service(dir: &std::path::Path) -> (ResearchService, Arc<FakeProvider>, Arc<FakeThumbnails>) {
        let llm = planner();
        let ai = FakeAi {
            router: Router::new(RetryPolicy { max_attempts: 1, ..Default::default() }, RecordingObserver::new()),
            routes: vec![Route { provider: llm.clone(), model: "m".into(), rpm: None }],
        };
        let catalog = Arc::new(SourceCatalog::bundled().unwrap());
        let sources = vec![SourceInfo {
            id: "polyhaven".into(),
            display_name: "Poly Haven".into(),
            kinds: vec!["texture".into()],
            source_license: Some(LicenseClaim::id("CC0-1.0")),
            access: SourceAccess::Api(Arc::new(Textures)),
        }];
        let thumbs = Arc::new(FakeThumbnails(Mutex::new(0)));
        let svc = ResearchService::new(
            Arc::new(Database::open_in_memory().unwrap()),
            Arc::new(ai),
            Arc::new(NoSearch),
            catalog,
            sources,
            thumbs.clone(),
            dir.join("thumbnails"),
        );
        (svc, llm, thumbs)
    }

    fn project(svc: &ResearchService) -> String {
        svc.db.create_project("p1", "Hearth", "game", "1").unwrap();
        "p1".into()
    }

    #[tokio::test]
    async fn runs_research_reacts_and_refines() {
        let dir = tempfile::tempdir().unwrap();
        let (svc, llm, _) = service(dir.path());
        let id = project(&svc);

        let run = svc.run(&id, &|_| {}).await.unwrap();
        let summary: Vec<_> = run.added.iter().map(|r| (r.category, r.title.as_str(), r.classification)).collect();
        assert_eq!(
            summary,
            [
                (RefCategory::Comparable, "Stardew Valley", Classification::ReferenceOnly),
                (RefCategory::Asset, "mossy stone texture", Classification::Usable),
            ]
        );
        assert_eq!(run.added[0].origin, Origin::LlmKnowledge);
        assert!(!run.added[0].verified);
        let view = svc.get(&id).await.unwrap();
        assert_eq!(view.project.stage, Stage::Research);
        assert_eq!(view.refs.len(), 2);
        assert!(view.sources.iter().any(|s| s.ui_credit.as_deref() == Some("Powered by Poly Haven")));
        assert!(view.sources.iter().any(|s| s.id == "kenney" && s.needs_search));

        // A second run doesn't add the same things again, and tells the
        // planner which games were already suggested.
        let again = svc.run(&id, &|_| {}).await.unwrap();
        assert!(again.added.is_empty(), "{:?}", again.added);
        assert!(llm.requests()[1].messages[0].content.contains("Already shown, don't repeat: Stardew Valley"));

        let stone = view.refs.iter().find(|r| r.category == RefCategory::Asset).unwrap();
        let more = svc.more_like_this(&id, &stone.id, &|_| {}).await.unwrap();
        assert_eq!(more.added.len(), 1);
        assert_eq!(more.added[0].title, "wet moss texture");
        assert_eq!(svc.db.get_ref(&id, &stone.id).unwrap().unwrap().reaction, Some(Reaction::MoreLikeThis));

        let game = &view.refs[0];
        assert_eq!(svc.react(&id, &game.id, Some(Reaction::Reject)).await.unwrap().reaction, Some(Reaction::Reject));
        let proposals = svc.refine(&id).await.unwrap();
        assert_eq!(proposals[0].field, "art_direction");
        let requests = llm.requests();
        let prompt = &requests.last().unwrap().messages[0].content;
        assert!(prompt.contains("- [asset] mossy stone texture") && prompt.contains("- [comparable] Stardew Valley"));

        assert!(matches!(svc.react(&id, "nope", None).await, Err(ResearchServiceError::NotFound)));
    }

    #[tokio::test]
    async fn thumbnails_are_cached_as_data_urls() {
        let dir = tempfile::tempdir().unwrap();
        let (svc, _, thumbs) = service(dir.path());
        let id = project(&svc);
        let run = svc.run(&id, &|_| {}).await.unwrap();
        let (game, stone) = (&run.added[0], &run.added[1]);

        assert_eq!(svc.thumbnail(&id, &game.id).await.unwrap(), None, "no thumbnail to fetch");
        let first = svc.thumbnail(&id, &stone.id).await.unwrap().unwrap();
        assert_eq!(first, "data:image/png;base64,iVBORw==");
        assert_eq!(svc.thumbnail(&id, &stone.id).await.unwrap().as_deref(), Some(first.as_str()));
        assert_eq!(*thumbs.0.lock().unwrap(), 1, "the second read comes from disk");
        assert!(dir.path().join("thumbnails").join(format!("{}.png", stone.id)).exists());
    }

    #[tokio::test]
    async fn one_run_at_a_time_per_project() {
        let dir = tempfile::tempdir().unwrap();
        let (svc, _, _) = service(dir.path());
        let id = project(&svc);
        let _guard = svc.lock(&id).unwrap();
        assert!(matches!(svc.run(&id, &|_| {}).await, Err(ResearchServiceError::Busy)));
    }

    #[test]
    fn only_public_https_thumbnails_are_fetched() {
        let ok = |u: &str| is_public_https(&reqwest::Url::parse(u).unwrap());
        assert!(ok("https://cdn.polyhaven.com/a.png"));
        assert!(ok("https://8.8.8.8/a.png"));
        for bad in [
            "http://cdn.example.com/a.png",
            "https://localhost/a.png",
            "https://printer.local/a.png",
            "https://127.0.0.1/a.png",
            "https://192.168.1.10/a.png",
            "https://10.0.0.1/a.png",
            "https://169.254.169.254/latest",
            "https://[::1]/a.png",
            "https://[fd00::1]/a.png",
            "https://[::ffff:192.168.0.1]/a.png",
        ] {
            assert!(!ok(bad), "{bad}");
        }
    }

    #[test]
    fn image_types() {
        assert_eq!(image_extension("image/jpeg; charset=binary"), Some("jpg"));
        assert_eq!(image_extension("image/svg+xml"), None);
        assert_eq!(image_extension("text/html"), None);
    }
}
