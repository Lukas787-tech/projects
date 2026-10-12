//! The asset-source catalog (`data/asset-sources.toml`): which sites research
//! can draw from, and the licenses that count as usable.

use std::collections::BTreeMap;
use std::sync::Arc;

use kickoff_core::research::{KnownLicense, LicenseClaim, LicensePolicy, SourceAccess, SourceInfo};
use serde::Deserialize;

use crate::assets::{PolyHaven, Sketchfab};
use crate::presets::Confidence;

pub const BUNDLED: &str = include_str!("../../../data/asset-sources.toml");

#[derive(Debug, thiserror::Error)]
pub enum SourcesError {
    #[error("asset source catalog is not valid TOML: {0}")]
    Parse(#[from] toml::de::Error),
    #[error("asset source catalog is invalid: {0}")]
    Invalid(String),
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum SourceAccessKind {
    Api,
    LinkOnly,
}

#[derive(Debug, Clone, Deserialize)]
pub struct SourceDef {
    pub id: String,
    pub display_name: String,
    pub access: SourceAccessKind,
    /// API client: `polyhaven` or `sketchfab`.
    pub client: Option<String>,
    pub base_url: Option<String>,
    /// Item page, with `{id}` for the item's id.
    pub page_url: Option<String>,
    pub kinds: Vec<String>,
    /// Set when the site's own policy puts everything under one license.
    pub source_license: Option<String>,
    pub license_url: Option<String>,
    /// A credit the UI must show next to results (Poly Haven ToS 2.5).
    pub ui_credit: Option<String>,
    pub site: Option<String>,
    pub url_prefix: Option<String>,
    pub query_hint: Option<String>,
    pub docs_url: Option<String>,
    pub notes: Option<String>,
    pub confidence: Confidence,
    pub verified_at: String,
}

#[derive(Debug, Clone, Deserialize)]
pub struct SourceCatalog {
    pub schema_version: u32,
    pub updated: String,
    #[serde(rename = "source")]
    pub sources: Vec<SourceDef>,
    pub licenses: BTreeMap<String, KnownLicense>,
}

impl SourceCatalog {
    pub fn bundled() -> Result<Self, SourcesError> {
        Self::parse(BUNDLED)
    }

    pub fn parse(text: &str) -> Result<Self, SourcesError> {
        let mut catalog: SourceCatalog = toml::from_str(text)?;
        for (id, license) in &mut catalog.licenses {
            license.id = id.clone();
        }
        catalog.validate()?;
        Ok(catalog)
    }

    fn validate(&self) -> Result<(), SourcesError> {
        let invalid = |id: &str, problem: &str| Err(SourcesError::Invalid(format!("source `{id}`: {problem}")));
        let mut seen = std::collections::HashSet::new();
        for s in &self.sources {
            if !seen.insert(s.id.as_str()) {
                return invalid(&s.id, "duplicate id");
            }
            if let Some(license) = &s.source_license
                && !self.licenses.contains_key(license)
            {
                return invalid(&s.id, "source_license must be one of [licenses]");
            }
            match s.access {
                SourceAccessKind::Api if s.client.is_none() || s.base_url.is_none() || s.page_url.is_none() => {
                    return invalid(&s.id, "api sources need client, base_url and page_url");
                }
                SourceAccessKind::LinkOnly if s.site.is_none() => return invalid(&s.id, "link_only sources need site"),
                _ => {}
            }
        }
        Ok(())
    }

    /// The license allowlist.
    pub fn policy(&self) -> LicensePolicy {
        LicensePolicy::new(self.licenses.values().cloned())
    }

    pub fn source(&self, id: &str) -> Option<&SourceDef> {
        self.sources.iter().find(|s| s.id == id)
    }

    /// Live sources for research. API sources with an unknown client are
    /// left out (a newer catalog than this build).
    pub fn build(&self) -> Vec<SourceInfo> {
        self.sources.iter().filter_map(|s| self.build_one(s)).collect()
    }

    fn build_one(&self, s: &SourceDef) -> Option<SourceInfo> {
        let access = match s.access {
            SourceAccessKind::Api => {
                let (base, page) = (s.base_url.as_deref()?, s.page_url.as_deref()?);
                let api: Arc<dyn kickoff_core::research::AssetSource> = match s.client.as_deref()? {
                    "polyhaven" => Arc::new(PolyHaven::new(&s.id, &s.display_name, base, page, s.kinds.clone())),
                    "sketchfab" => Arc::new(Sketchfab::new(&s.id, &s.display_name, base, page, s.kinds.clone())),
                    other => {
                        tracing::warn!(source = %s.id, client = other, "unknown asset source client");
                        return None;
                    }
                };
                SourceAccess::Api(api)
            }
            SourceAccessKind::LinkOnly => SourceAccess::Link {
                site: s.site.clone()?,
                url_prefix: s.url_prefix.clone(),
                query_hint: s.query_hint.clone(),
            },
        };
        let source_license = s.source_license.as_ref().map(|id| LicenseClaim {
            id: Some(id.clone()),
            label: None,
            url: s.license_url.clone().or_else(|| self.licenses.get(id).map(|l| l.url.clone())),
        });
        Some(SourceInfo {
            id: s.id.clone(),
            display_name: s.display_name.clone(),
            kinds: s.kinds.clone(),
            source_license,
            access,
        })
    }
}

#[cfg(test)]
mod tests {
    use kickoff_core::research::{Classification, Credit, RefCategory};

    use super::*;

    #[test]
    fn bundled_catalog_builds_every_source() {
        let catalog = SourceCatalog::bundled().unwrap();
        let built = catalog.build();
        assert_eq!(built.len(), catalog.sources.len());
        let poly = built.iter().find(|s| s.id == "polyhaven").unwrap();
        assert!(matches!(poly.access, SourceAccess::Api(_)));
        assert_eq!(poly.source_license.as_ref().unwrap().id.as_deref(), Some("CC0-1.0"));
        let kenney = built.iter().find(|s| s.id == "kenney").unwrap();
        assert!(matches!(&kenney.access, SourceAccess::Link { site, .. } if site == "kenney.nl"));
        assert_eq!(catalog.source("polyhaven").unwrap().ui_credit.as_deref(), Some("Powered by Poly Haven"));
    }

    #[test]
    fn allowlist_classifies_sketchfab_labels() {
        let policy = SourceCatalog::bundled().unwrap().policy();
        let credit = Credit { title: "Barn", author: Some("Jo"), url: "https://example.com" };
        let classify = |label: &str| {
            let claim = LicenseClaim {
                id: crate::assets::sketchfab_license_id(label).map(str::to_owned),
                label: Some(label.into()),
                url: None,
            };
            policy.classify(RefCategory::Asset, None, Some(&claim), &credit).classification
        };
        assert_eq!(classify("CC0 Public Domain"), Classification::Usable);
        assert_eq!(classify("CC Attribution"), Classification::Usable);
        assert_eq!(classify("CC Attribution-ShareAlike"), Classification::Usable);
        assert_eq!(classify("CC Attribution-NonCommercial"), Classification::ReferenceOnly);
        assert_eq!(classify("CC Attribution-NoDerivs"), Classification::ReferenceOnly);
        assert_eq!(classify("Editorial"), Classification::ReferenceOnly);
        assert_eq!(classify("Standard"), Classification::Unknown);
    }

    #[test]
    fn rejects_incomplete_sources() {
        let bad = r#"
            schema_version = 1
            updated = "2026-10-11"
            [[source]]
            id = "x"
            display_name = "X"
            access = "api"
            kinds = ["model"]
            confidence = "low"
            verified_at = "2026-10-11"
            [licenses]
        "#;
        assert!(matches!(SourceCatalog::parse(bad), Err(SourcesError::Invalid(_))));
    }
}
