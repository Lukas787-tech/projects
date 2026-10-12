//! Asset sites with their own search APIs: Poly Haven and Sketchfab.
//!
//! Facts are in `docs/PROVIDERS.md` and `data/asset-sources.toml`.

use std::time::Duration;

use async_trait::async_trait;
use futures::future::join_all;
use kickoff_core::research::{AssetHit, AssetSource, LicenseClaim, SearchError};
use serde::Deserialize;
use serde_json::Value;

use crate::http::{self, error_message};

fn client() -> reqwest::Client {
    // Our User-Agent names the app, as Poly Haven's terms require.
    http::client(Duration::from_secs(20))
}

async fn get_json(name: &str, request: reqwest::RequestBuilder) -> Result<Value, SearchError> {
    let response = request.send().await.map_err(|e| {
        tracing::debug!(source = name, error = %e, "asset search failed");
        SearchError::Unavailable(if e.is_timeout() { "no answer in time".into() } else { "couldn't connect".into() })
    })?;
    let status = response.status().as_u16();
    if !response.status().is_success() {
        let retry_after = response
            .headers()
            .get("retry-after")
            .and_then(|v| v.to_str().ok())
            .and_then(|v| v.trim().parse::<u64>().ok())
            .map(Duration::from_secs);
        let body = response.text().await.unwrap_or_default();
        let message = format!("{status}: {}", error_message(&body));
        return Err(match status {
            429 => SearchError::RateLimited { retry_after },
            401 | 403 => SearchError::Auth(message),
            400 | 404 | 422 => SearchError::Invalid(message),
            _ => SearchError::Unavailable(message),
        });
    }
    response.json().await.map_err(|e| SearchError::Invalid(format!("unexpected answer from {name} ({e})")))
}

fn page_url(template: &str, id: &str) -> String {
    template.replace("{id}", id)
}

// ---------------------------------------------------------------- Poly Haven

/// Poly Haven: everything is CC0 (the source license in the catalog).
pub struct PolyHaven {
    id: String,
    name: String,
    base_url: String,
    page_url: String,
    kinds: Vec<String>,
    client: reqwest::Client,
}

impl PolyHaven {
    pub fn new(id: &str, name: &str, base_url: &str, page_url: &str, kinds: Vec<String>) -> Self {
        Self {
            id: id.into(),
            name: name.into(),
            base_url: base_url.trim_end_matches('/').into(),
            page_url: page_url.into(),
            kinds,
            client: client(),
        }
    }

    /// Our asset kinds -> Poly Haven's `t` parameter.
    fn asset_type(kind: Option<&str>) -> Option<&'static str> {
        match kind {
            None => Some("all"),
            Some("texture") => Some("textures"),
            Some("hdri") => Some("hdris"),
            Some("model") => Some("models"),
            Some(_) => None,
        }
    }

    async fn info(&self, slug: &str) -> Result<AssetHit, SearchError> {
        let url = format!("{}/info/{}", self.base_url, slug);
        let info: Info = serde_json::from_value(get_json(&self.name, self.client.get(url)).await?)
            .map_err(|e| SearchError::Invalid(format!("unexpected answer from {} ({e})", self.name)))?;
        let mut authors: Vec<String> = info.authors.into_keys().collect();
        authors.sort();
        Ok(AssetHit {
            external_id: slug.to_owned(),
            title: info.name.unwrap_or_else(|| slug.to_owned()),
            url: page_url(&self.page_url, slug),
            author: Some(authors.join(", ")).filter(|a| !a.is_empty()),
            description: info.description.filter(|d| !d.trim().is_empty()),
            thumbnail_url: info.thumbnail_url,
            kind: match info.kind {
                Some(0) => Some("hdri".into()),
                Some(1) => Some("texture".into()),
                Some(2) => Some("model".into()),
                _ => None,
            },
            tags: info.tags,
            license: None,
        })
    }
}

#[derive(Deserialize)]
struct Ranked {
    #[serde(default)]
    results: Vec<RankedSlug>,
}

#[derive(Deserialize)]
struct RankedSlug {
    slug: String,
}

#[derive(Deserialize)]
struct Info {
    name: Option<String>,
    description: Option<String>,
    #[serde(rename = "type")]
    kind: Option<u8>,
    #[serde(default)]
    authors: std::collections::BTreeMap<String, Value>,
    #[serde(default)]
    tags: Vec<String>,
    thumbnail_url: Option<String>,
}

#[async_trait]
impl AssetSource for PolyHaven {
    fn id(&self) -> &str {
        &self.id
    }

    fn kinds(&self) -> &[String] {
        &self.kinds
    }

    async fn search(&self, query: &str, kind: Option<&str>, limit: usize) -> Result<Vec<AssetHit>, SearchError> {
        let Some(asset_type) = Self::asset_type(kind) else { return Ok(Vec::new()) };
        let limit = limit.max(1).to_string();
        let request = self.client.get(format!("{}/search", self.base_url)).query(&[
            ("q", query),
            ("t", asset_type),
            ("limit", limit.as_str()),
        ]);
        let ranked: Ranked = serde_json::from_value(get_json(&self.name, request).await?)
            .map_err(|e| SearchError::Invalid(format!("unexpected answer from {} ({e})", self.name)))?;
        // Details come from one call per asset; a single failed lookup only
        // drops that asset.
        let lookups = join_all(ranked.results.iter().map(|r| self.info(&r.slug))).await;
        let mut hits = Vec::new();
        for lookup in lookups {
            match lookup {
                Ok(hit) => hits.push(hit),
                Err(e @ (SearchError::RateLimited { .. } | SearchError::Auth(_) | SearchError::Quota(_)))
                    if hits.is_empty() =>
                {
                    return Err(e);
                }
                Err(e) => tracing::debug!(error = %e, "Poly Haven asset lookup failed"),
            }
        }
        Ok(hits)
    }
}

// ---------------------------------------------------------------- Sketchfab

/// Sketchfab: downloadable models, each with its own license.
pub struct Sketchfab {
    id: String,
    name: String,
    base_url: String,
    page_url: String,
    kinds: Vec<String>,
    client: reqwest::Client,
}

impl Sketchfab {
    pub fn new(id: &str, name: &str, base_url: &str, page_url: &str, kinds: Vec<String>) -> Self {
        Self {
            id: id.into(),
            name: name.into(),
            base_url: base_url.trim_end_matches('/').into(),
            page_url: page_url.into(),
            kinds,
            client: client(),
        }
    }
}

/// Sketchfab's license labels -> SPDX ids for the allowlist. Others (the
/// NonCommercial and NoDerivs variants, Standard, Editorial) keep only their
/// label and are never classified usable.
pub fn sketchfab_license_id(label: &str) -> Option<&'static str> {
    match label.trim() {
        "CC0 Public Domain" => Some("CC0-1.0"),
        "CC Attribution" => Some("CC-BY-4.0"),
        "CC Attribution-ShareAlike" => Some("CC-BY-SA-4.0"),
        _ => None,
    }
}

#[derive(Deserialize)]
struct SketchfabPage {
    #[serde(default)]
    results: Vec<SketchfabModel>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct SketchfabModel {
    uid: String,
    name: String,
    description: Option<String>,
    viewer_url: Option<String>,
    user: Option<SketchfabUser>,
    license: Option<SketchfabLicense>,
    thumbnails: Option<SketchfabThumbnails>,
    #[serde(default)]
    tags: Vec<SketchfabTag>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct SketchfabUser {
    display_name: Option<String>,
    username: Option<String>,
}

#[derive(Deserialize)]
struct SketchfabLicense {
    label: Option<String>,
    url: Option<String>,
}

#[derive(Deserialize)]
struct SketchfabThumbnails {
    #[serde(default)]
    images: Vec<SketchfabImage>,
}

#[derive(Deserialize)]
struct SketchfabImage {
    url: String,
    #[serde(default)]
    width: u32,
}

#[derive(Deserialize)]
struct SketchfabTag {
    name: String,
}

/// The smallest thumbnail at least `min` wide, else the largest.
fn pick_thumbnail(images: &[SketchfabImage], min: u32) -> Option<String> {
    images
        .iter()
        .filter(|i| i.width >= min)
        .min_by_key(|i| i.width)
        .or_else(|| images.iter().max_by_key(|i| i.width))
        .map(|i| i.url.clone())
}

#[async_trait]
impl AssetSource for Sketchfab {
    fn id(&self) -> &str {
        &self.id
    }

    fn kinds(&self) -> &[String] {
        &self.kinds
    }

    async fn search(&self, query: &str, kind: Option<&str>, limit: usize) -> Result<Vec<AssetHit>, SearchError> {
        if kind.is_some_and(|k| k != "model") {
            return Ok(Vec::new());
        }
        let request = self.client.get(format!("{}/search", self.base_url)).query(&[
            ("type", "models"),
            ("downloadable", "true"),
            ("q", query),
        ]);
        let page: SketchfabPage = serde_json::from_value(get_json(&self.name, request).await?)
            .map_err(|e| SearchError::Invalid(format!("unexpected answer from {} ({e})", self.name)))?;
        Ok(page
            .results
            .into_iter()
            .take(limit)
            .map(|m| {
                let license = m.license.map(|l| LicenseClaim {
                    id: l.label.as_deref().and_then(sketchfab_license_id).map(str::to_owned),
                    label: l.label,
                    url: l.url,
                });
                AssetHit {
                    url: m.viewer_url.unwrap_or_else(|| page_url(&self.page_url, &m.uid)),
                    external_id: m.uid,
                    title: m.name,
                    author: m.user.and_then(|u| u.display_name.or(u.username)),
                    description: m.description.filter(|d| !d.trim().is_empty()),
                    thumbnail_url: m.thumbnails.and_then(|t| pick_thumbnail(&t.images, 256)),
                    kind: Some("model".into()),
                    tags: m.tags.into_iter().map(|t| t.name).collect(),
                    license: license.filter(|l| !l.is_empty()),
                }
            })
            .collect())
    }
}

#[cfg(test)]
mod tests {
    use serde_json::json;
    use wiremock::matchers::{header_regex, method, path, query_param};
    use wiremock::{Mock, MockServer, ResponseTemplate};

    use super::*;

    fn poly(server: &MockServer) -> PolyHaven {
        PolyHaven::new(
            "polyhaven",
            "Poly Haven",
            &server.uri(),
            "https://polyhaven.com/a/{id}",
            vec!["texture".into(), "hdri".into(), "model".into()],
        )
    }

    #[tokio::test]
    async fn poly_haven_ranks_then_looks_up_details() {
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/search"))
            .and(query_param("q", "mossy rock"))
            .and(query_param("t", "textures"))
            .and(query_param("limit", "2"))
            // Poly Haven's terms ask for a User-Agent that names the app.
            .and(header_regex("user-agent", &format!("^{}/", kickoff_core::APP_NAME)))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({
                "total": 2,
                "results": [ { "slug": "rock_moss", "score": 0.81 }, { "slug": "gone", "score": 0.5 } ]
            })))
            .expect(1)
            .mount(&server)
            .await;
        Mock::given(method("GET"))
            .and(path("/info/rock_moss"))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({
                "name": "Mossy Rock",
                "type": 1,
                "authors": { "Rico Cilliers": "All", "Dario Barresi": "Photography" },
                "tags": ["rock", "moss"],
                "categories": ["natural"],
                "thumbnail_url": "https://cdn.polyhaven.com/asset_img/thumbs/rock_moss.png?width=256&height=256"
            })))
            .mount(&server)
            .await;
        Mock::given(method("GET"))
            .and(path("/info/gone"))
            .respond_with(ResponseTemplate::new(404))
            .mount(&server)
            .await;

        let hits = poly(&server).search("mossy rock", Some("texture"), 2).await.unwrap();
        assert_eq!(hits.len(), 1, "a failed lookup drops only that asset");
        let rock = &hits[0];
        assert_eq!(rock.title, "Mossy Rock");
        assert_eq!(rock.url, "https://polyhaven.com/a/rock_moss");
        assert_eq!(rock.author.as_deref(), Some("Dario Barresi, Rico Cilliers"));
        assert_eq!(rock.kind.as_deref(), Some("texture"));
        assert!(rock.thumbnail_url.as_deref().unwrap().starts_with("https://cdn.polyhaven.com/"));
        assert!(rock.license.is_none(), "the source license (CC0) applies");
    }

    #[tokio::test]
    async fn poly_haven_skips_kinds_it_does_not_have_and_reports_rate_limits() {
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .respond_with(ResponseTemplate::new(429).insert_header("retry-after", "4"))
            .mount(&server)
            .await;
        let ph = poly(&server);
        assert!(ph.search("drums", Some("audio"), 3).await.unwrap().is_empty());
        assert_eq!(
            ph.search("rock", None, 3).await.unwrap_err(),
            SearchError::RateLimited { retry_after: Some(Duration::from_secs(4)) }
        );
    }

    #[tokio::test]
    async fn sketchfab_maps_licenses_authors_and_thumbnails() {
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/search"))
            .and(query_param("type", "models"))
            .and(query_param("downloadable", "true"))
            .and(query_param("q", "barn"))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({
                "next": "https://api.sketchfab.com/v3/search?cursor=24",
                "results": [
                    {
                        "uid": "abc123", "name": "Old Barn", "viewerUrl": "https://sketchfab.com/3d-models/old-barn-abc123",
                        "user": { "username": "farmer", "displayName": "Farmer Jo" },
                        "license": { "label": "CC Attribution" },
                        "thumbnails": { "images": [
                            { "url": "https://media.sketchfab.com/1024.jpg", "width": 1024, "height": 576 },
                            { "url": "https://media.sketchfab.com/256.jpg", "width": 256, "height": 144 },
                            { "url": "https://media.sketchfab.com/100.jpg", "width": 100, "height": 56 }
                        ] },
                        "tags": [ { "name": "barn" }, { "name": "farm" } ]
                    },
                    { "uid": "nc1", "name": "NC Barn", "user": { "username": "x" }, "license": { "label": "CC Attribution-NonCommercial" } },
                    { "uid": "third", "name": "Third" }
                ]
            })))
            .mount(&server)
            .await;
        let sf = Sketchfab::new(
            "sketchfab",
            "Sketchfab",
            &server.uri(),
            "https://sketchfab.com/models/{id}",
            vec!["model".into()],
        );
        let hits = sf.search("barn", Some("model"), 2).await.unwrap();
        assert_eq!(hits.len(), 2);
        let barn = &hits[0];
        assert_eq!(barn.url, "https://sketchfab.com/3d-models/old-barn-abc123");
        assert_eq!(barn.author.as_deref(), Some("Farmer Jo"));
        assert_eq!(barn.thumbnail_url.as_deref(), Some("https://media.sketchfab.com/256.jpg"));
        assert_eq!(barn.tags, ["barn", "farm"]);
        assert_eq!(barn.license.as_ref().unwrap().id.as_deref(), Some("CC-BY-4.0"));
        let nc = &hits[1];
        assert_eq!(nc.url, "https://sketchfab.com/models/nc1");
        assert_eq!(nc.license.as_ref().unwrap().id, None);
        assert_eq!(nc.license.as_ref().unwrap().label.as_deref(), Some("CC Attribution-NonCommercial"));
        assert!(sf.search("barn", Some("texture"), 2).await.unwrap().is_empty());
    }
}
