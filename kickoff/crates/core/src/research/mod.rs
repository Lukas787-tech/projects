//! Research and references: comparable games, visual references and assets,
//! each with a license classification decided by code, never by a model.
//!
//! - [`WebSearch`] and [`AssetSource`] are implemented in `kickoff-providers`.
//! - [`LicensePolicy`] turns what a source says about a license into
//!   `usable`, `reference_only` or `unknown`.
//! - [`pipeline`] plans queries from the brief, runs them and collects
//!   [`Candidate`]s.

pub mod pipeline;

use std::collections::BTreeMap;
use std::time::Duration;

use async_trait::async_trait;
use serde::{Deserialize, Serialize};
use ts_rs::TS;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize, TS)]
#[serde(rename_all = "snake_case")]
#[ts(export)]
pub enum RefCategory {
    /// Games to learn from.
    Comparable,
    /// Mood and style references (other people's work: look, don't use).
    Visual,
    /// Assets that might go into the game, if their license allows.
    Asset,
}

impl RefCategory {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Comparable => "comparable",
            Self::Visual => "visual",
            Self::Asset => "asset",
        }
    }

    pub fn parse(s: &str) -> Option<Self> {
        match s {
            "comparable" => Some(Self::Comparable),
            "visual" => Some(Self::Visual),
            "asset" => Some(Self::Asset),
            _ => None,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, TS)]
#[serde(rename_all = "snake_case")]
#[ts(export)]
pub enum Classification {
    /// A recognised license that allows use in a game (maybe with credit).
    Usable,
    /// Look but don't use: someone else's game or art, or a license that
    /// forbids commercial use or changes.
    ReferenceOnly,
    /// No license we can rely on. Treated like `reference_only`.
    Unknown,
}

impl Classification {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Usable => "usable",
            Self::ReferenceOnly => "reference_only",
            Self::Unknown => "unknown",
        }
    }

    pub fn parse(s: &str) -> Option<Self> {
        match s {
            "usable" => Some(Self::Usable),
            "reference_only" => Some(Self::ReferenceOnly),
            "unknown" => Some(Self::Unknown),
            _ => None,
        }
    }
}

impl Candidate {
    /// Identity for de-duplication; see [`ref_key`].
    pub fn key(&self) -> String {
        ref_key(self.category, &self.title, self.source_url.as_deref())
    }
}

/// Identity of a reference: comparables by title (the same game found on
/// two sites is one game), everything else by URL.
pub fn ref_key(category: RefCategory, title: &str, url: Option<&str>) -> String {
    match url.filter(|u| !u.is_empty() && category != RefCategory::Comparable) {
        Some(url) => {
            let url = url.split('#').next().unwrap_or(url);
            format!("url:{}", normalize_url(url))
        }
        None => format!("{}:{}", category.as_str(), simplify(title)),
    }
}

/// Lowercase letters and digits, single spaces: "Hades II — Steam" -> "hades ii steam".
pub fn simplify(text: &str) -> String {
    text.chars()
        .map(|c| if c.is_alphanumeric() { c.to_ascii_lowercase() } else { ' ' })
        .collect::<String>()
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, TS)]
#[serde(rename_all = "snake_case")]
#[ts(export)]
pub enum Origin {
    /// Found by web search.
    Search,
    /// From an asset site's own API.
    AssetApi,
    /// Suggested by the model from its own knowledge, not checked online.
    LlmKnowledge,
}

impl Origin {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Search => "search",
            Self::AssetApi => "asset_api",
            Self::LlmKnowledge => "llm_knowledge",
        }
    }

    pub fn parse(s: &str) -> Option<Self> {
        match s {
            "search" => Some(Self::Search),
            "asset_api" => Some(Self::AssetApi),
            "llm_knowledge" => Some(Self::LlmKnowledge),
            _ => None,
        }
    }
}

// ------------------------------------------------------------- licenses

/// A license the app knows to be fine for use in a game.
#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
pub struct KnownLicense {
    #[serde(skip)]
    pub id: String,
    pub name: String,
    pub url: String,
    pub attribution: bool,
    pub share_alike: bool,
}

/// What a source or an item says about its license. Any part may be missing.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct LicenseClaim {
    /// SPDX-style id such as `CC-BY-4.0`.
    pub id: Option<String>,
    /// The source's own wording, e.g. "CC Attribution-NonCommercial".
    pub label: Option<String>,
    pub url: Option<String>,
}

impl LicenseClaim {
    pub fn id(id: &str) -> Self {
        Self { id: Some(id.to_owned()), ..Self::default() }
    }

    pub fn is_empty(&self) -> bool {
        self.id.is_none() && self.label.is_none() && self.url.is_none()
    }
}

/// The verdict for one reference, with everything needed to credit it.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Licensing {
    pub classification: Classification,
    pub license_id: Option<String>,
    pub license_name: Option<String>,
    pub license_url: Option<String>,
    /// Ready-to-paste credit line, when the item can be used.
    pub attribution: Option<String>,
}

/// Wording that marks a license as not allowing use in a (possibly
/// commercial) game.
const RESTRICTIVE: &[&str] = &[
    "noncommercial",
    "non-commercial",
    "-nc",
    "nc-",
    "noderiv",
    "-nd",
    "editorial",
    "all rights reserved",
    "personal use",
];

/// Decides `usable` / `reference_only` / `unknown` from an allowlist.
#[derive(Debug, Clone, Default)]
pub struct LicensePolicy {
    known: BTreeMap<String, KnownLicense>,
}

/// Lowercase, no scheme, `www.`, license `/deed…` suffix or trailing slash.
pub fn normalize_url(url: &str) -> String {
    let lower = url.trim().to_lowercase();
    let no_scheme = lower.trim_start_matches("https://").trim_start_matches("http://");
    let no_www = no_scheme.trim_start_matches("www.");
    let no_deed = no_www.split("/deed").next().unwrap_or(no_www);
    no_deed.trim_end_matches('/').to_owned()
}

impl LicensePolicy {
    pub fn new(known: impl IntoIterator<Item = KnownLicense>) -> Self {
        Self { known: known.into_iter().map(|l| (l.id.to_lowercase(), l)).collect() }
    }

    pub fn get(&self, id: &str) -> Option<&KnownLicense> {
        self.known.get(&id.to_lowercase())
    }

    /// The allowlisted license a claim refers to, by id, URL or exact name.
    pub fn identify(&self, claim: &LicenseClaim) -> Option<&KnownLicense> {
        if let Some(l) = claim.id.as_deref().and_then(|id| self.get(id)) {
            return Some(l);
        }
        if let Some(url) = claim.url.as_deref().map(normalize_url)
            && let Some(l) = self.known.values().find(|l| normalize_url(&l.url) == url)
        {
            return Some(l);
        }
        let label = claim.label.as_deref()?.trim().to_lowercase();
        self.known.values().find(|l| l.name.to_lowercase() == label || l.id.to_lowercase() == label)
    }

    fn is_restrictive(claim: &LicenseClaim) -> bool {
        [&claim.id, &claim.label, &claim.url]
            .into_iter()
            .flatten()
            .map(|s| s.to_lowercase())
            .any(|s| RESTRICTIVE.iter().any(|r| s.contains(r)))
    }

    /// Classify one item. Comparables and visual references are always
    /// reference-only. Assets are usable only with an allowlisted license,
    /// either one the source applies to everything it offers or one stated
    /// for the item.
    pub fn classify(
        &self,
        category: RefCategory,
        source_license: Option<&LicenseClaim>,
        item_license: Option<&LicenseClaim>,
        credit: &Credit<'_>,
    ) -> Licensing {
        let claim = source_license.filter(|c| !c.is_empty()).or(item_license.filter(|c| !c.is_empty()));
        let described = |classification| Licensing {
            classification,
            license_id: claim.and_then(|c| c.id.clone()),
            license_name: claim.and_then(|c| c.label.clone().or_else(|| c.id.clone())),
            license_url: claim.and_then(|c| c.url.clone()),
            attribution: None,
        };
        if category != RefCategory::Asset {
            return described(Classification::ReferenceOnly);
        }
        let Some(claim) = claim else { return described(Classification::Unknown) };
        if let Some(known) = self.identify(claim) {
            return Licensing {
                classification: Classification::Usable,
                license_id: Some(known.id.clone()),
                license_name: Some(known.name.clone()),
                license_url: Some(known.url.clone()),
                attribution: Some(credit.line(known)),
            };
        }
        if Self::is_restrictive(claim) {
            described(Classification::ReferenceOnly)
        } else {
            described(Classification::Unknown)
        }
    }
}

/// What a credit line names.
#[derive(Debug, Clone, Copy)]
pub struct Credit<'a> {
    pub title: &'a str,
    pub author: Option<&'a str>,
    pub url: &'a str,
}

impl Credit<'_> {
    fn line(&self, license: &KnownLicense) -> String {
        let by = self.author.map(|a| format!(" by {a}")).unwrap_or_default();
        let note = if license.attribution { "" } else { " (credit not required)" };
        format!("\"{}\"{by} ({}), {} ({}){note}", self.title, self.url, license.name, license.url)
    }
}

// ------------------------------------------------------------- sources

#[derive(Debug, Clone, PartialEq, thiserror::Error)]
pub enum SearchError {
    #[error("the key was rejected ({0})")]
    Auth(String),
    #[error("the free allowance is used up ({0})")]
    Quota(String),
    #[error("rate limited")]
    RateLimited { retry_after: Option<Duration> },
    #[error("{0}")]
    Unavailable(String),
    #[error("{0}")]
    Invalid(String),
}

/// A web search.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct WebQuery {
    pub text: String,
    pub max_results: u8,
    /// Restrict to these domains (e.g. `kenney.nl`).
    pub sites: Vec<String>,
    /// Also return image results, where the search service offers them.
    pub images: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct WebHit {
    pub title: String,
    /// The page (for an image result, the page the image is on, when known).
    pub url: String,
    pub snippet: Option<String>,
    pub thumbnail_url: Option<String>,
    /// An image result rather than a page.
    #[serde(default)]
    pub image: bool,
}

#[async_trait]
pub trait WebSearch: Send + Sync {
    fn id(&self) -> &str;
    /// Shown to the user, e.g. "Tavily".
    fn name(&self) -> &str;
    async fn search(&self, query: &WebQuery) -> Result<Vec<WebHit>, SearchError>;
}

/// An asset found through a site's own API.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct AssetHit {
    pub external_id: String,
    pub title: String,
    pub url: String,
    pub author: Option<String>,
    pub description: Option<String>,
    pub thumbnail_url: Option<String>,
    /// `texture`, `hdri`, `model` …
    pub kind: Option<String>,
    pub tags: Vec<String>,
    /// The item's own license, when the source states one per item.
    pub license: Option<LicenseClaim>,
}

#[async_trait]
pub trait AssetSource: Send + Sync {
    fn id(&self) -> &str;
    /// Asset kinds this source can search (`texture`, `model` …).
    fn kinds(&self) -> &[String];
    async fn search(&self, query: &str, kind: Option<&str>, limit: usize) -> Result<Vec<AssetHit>, SearchError>;
}

/// An asset site research can draw from.
#[derive(Clone)]
pub struct SourceInfo {
    pub id: String,
    pub display_name: String,
    pub kinds: Vec<String>,
    /// The license everything on this site carries (Poly Haven, Kenney: CC0).
    pub source_license: Option<LicenseClaim>,
    pub access: SourceAccess,
}

#[derive(Clone)]
pub enum SourceAccess {
    /// Searched through the site's own API.
    Api(std::sync::Arc<dyn AssetSource>),
    /// Found through web search restricted to `site`. Only hits whose URL
    /// starts with `url_prefix` (no scheme or `www.`) count as assets.
    Link { site: String, url_prefix: Option<String>, query_hint: Option<String> },
}

impl std::fmt::Debug for SourceInfo {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        let access = match &self.access {
            SourceAccess::Api(_) => "api",
            SourceAccess::Link { .. } => "link",
        };
        f.debug_struct("SourceInfo").field("id", &self.id).field("access", &access).finish_non_exhaustive()
    }
}

/// Something research found, ready to store and triage.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Candidate {
    pub category: RefCategory,
    pub asset_kind: Option<String>,
    pub title: String,
    pub summary: Option<String>,
    pub source_id: Option<String>,
    /// Missing only for comparables nobody could check online.
    pub source_url: Option<String>,
    pub author: Option<String>,
    pub licensing: Licensing,
    pub thumbnail_url: Option<String>,
    pub origin: Origin,
    /// Checked online (a search found it, or an API returned it).
    pub verified: bool,
    /// Extra notes, e.g. what to learn from a comparable game.
    pub metadata: serde_json::Value,
}

#[cfg(test)]
mod tests {
    use super::*;

    fn policy() -> LicensePolicy {
        let known = |id: &str, name: &str, url: &str, attribution| KnownLicense {
            id: id.into(),
            name: name.into(),
            url: url.into(),
            attribution,
            share_alike: false,
        };
        LicensePolicy::new([
            known("CC0-1.0", "CC0 1.0", "https://creativecommons.org/publicdomain/zero/1.0/", false),
            known("CC-BY-4.0", "CC BY 4.0", "https://creativecommons.org/licenses/by/4.0/", true),
        ])
    }

    const CREDIT: Credit<'static> =
        Credit { title: "Mossy Rock", author: Some("Rico"), url: "https://example.com/rock" };

    #[test]
    fn source_wide_licenses_make_assets_usable() {
        let l = policy().classify(RefCategory::Asset, Some(&LicenseClaim::id("CC0-1.0")), None, &CREDIT);
        assert_eq!(l.classification, Classification::Usable);
        assert_eq!(l.license_name.as_deref(), Some("CC0 1.0"));
        assert_eq!(
            l.attribution.as_deref(),
            Some(
                "\"Mossy Rock\" by Rico (https://example.com/rock), CC0 1.0 (https://creativecommons.org/publicdomain/zero/1.0/) (credit not required)"
            )
        );
    }

    #[test]
    fn item_licenses_are_matched_by_id_url_or_name() {
        let p = policy();
        let by_url = LicenseClaim {
            url: Some("http://creativecommons.org/licenses/by/4.0/deed.en".into()),
            ..Default::default()
        };
        assert_eq!(
            p.classify(RefCategory::Asset, None, Some(&by_url), &CREDIT).license_id.as_deref(),
            Some("CC-BY-4.0")
        );
        let by_name = LicenseClaim { label: Some("cc by 4.0".into()), ..Default::default() };
        assert_eq!(
            p.classify(RefCategory::Asset, None, Some(&by_name), &CREDIT).classification,
            Classification::Usable
        );
    }

    #[test]
    fn restrictive_and_missing_licenses_are_not_usable() {
        let p = policy();
        let nc = LicenseClaim { label: Some("CC Attribution-NonCommercial".into()), ..Default::default() };
        let l = p.classify(RefCategory::Asset, None, Some(&nc), &CREDIT);
        assert_eq!(l.classification, Classification::ReferenceOnly);
        assert!(l.attribution.is_none());
        let odd = LicenseClaim { label: Some("Free for everything, ask me".into()), ..Default::default() };
        assert_eq!(p.classify(RefCategory::Asset, None, Some(&odd), &CREDIT).classification, Classification::Unknown);
        assert_eq!(p.classify(RefCategory::Asset, None, None, &CREDIT).classification, Classification::Unknown);
    }

    #[test]
    fn other_peoples_games_and_art_are_reference_only() {
        let p = policy();
        for category in [RefCategory::Comparable, RefCategory::Visual] {
            let l = p.classify(category, Some(&LicenseClaim::id("CC0-1.0")), None, &CREDIT);
            assert_eq!(l.classification, Classification::ReferenceOnly);
        }
    }

    #[test]
    fn keys_ignore_url_noise_and_identify_games_by_title() {
        let a = ref_key(RefCategory::Visual, "x", Some("https://www.example.com/a/#top"));
        assert_eq!(a, ref_key(RefCategory::Visual, "y", Some("http://example.com/a")));
        let game = ref_key(RefCategory::Comparable, "Hades II", Some("https://store.example/hades"));
        assert_eq!(game, ref_key(RefCategory::Comparable, "hades ii", None));
        assert_ne!(game, ref_key(RefCategory::Visual, "Hades II", None));
        assert_eq!(simplify("  Hades II — Steam!"), "hades ii steam");
    }

    #[test]
    fn enums_round_trip_their_database_names() {
        for c in [RefCategory::Comparable, RefCategory::Visual, RefCategory::Asset] {
            assert_eq!(RefCategory::parse(c.as_str()), Some(c));
        }
        for c in [Classification::Usable, Classification::ReferenceOnly, Classification::Unknown] {
            assert_eq!(Classification::parse(c.as_str()), Some(c));
        }
        for o in [Origin::Search, Origin::AssetApi, Origin::LlmKnowledge] {
            assert_eq!(Origin::parse(o.as_str()), Some(o));
        }
    }
}
