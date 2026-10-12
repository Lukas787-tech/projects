//! Web search: Tavily (free, no card) and Brave (optional).
//!
//! Facts are in `docs/PROVIDERS.md`. Both clients map failures onto
//! [`SearchError`] so research can skip a broken service and keep going.

use std::sync::Arc;
use std::time::Duration;

use async_trait::async_trait;
use kickoff_core::Secret;
use kickoff_core::research::{SearchError, WebHit, WebQuery, WebSearch};
use reqwest::header::HeaderMap;
use serde::Deserialize;
use serde_json::{Value, json};

use crate::http::{self, error_message};
use crate::presets::SearchPreset;

/// Tavily allows 0–20 results, Brave 1–20.
const MAX_RESULTS: u8 = 20;

#[derive(Debug, thiserror::Error)]
pub enum SearchBuildError {
    #[error("{0} needs an API key; add one in the Key Library")]
    MissingKey(String),
    #[error("unknown search kind `{0}`")]
    UnknownKind(String),
}

/// Build the search client a preset describes.
pub fn build_search(preset: &SearchPreset, key: Option<Secret>) -> Result<Arc<dyn WebSearch>, SearchBuildError> {
    let key = key.filter(|k| !k.is_empty()).ok_or_else(|| SearchBuildError::MissingKey(preset.display_name.clone()))?;
    Ok(match preset.kind.as_str() {
        "tavily" => Arc::new(Tavily::new(&preset.id, &preset.display_name, &preset.endpoint, key)),
        "brave" => Arc::new(Brave::new(&preset.id, &preset.display_name, &preset.endpoint, key)),
        other => return Err(SearchBuildError::UnknownKind(other.to_owned())),
    })
}

fn client() -> reqwest::Client {
    http::client(Duration::from_secs(30))
}

fn transport_error(name: &str, e: reqwest::Error) -> SearchError {
    tracing::debug!(service = name, error = %e, "search request failed");
    SearchError::Unavailable(if e.is_timeout() { "no answer in time".into() } else { "couldn't connect".into() })
}

/// Status codes shared by both services; anything else is "unavailable".
fn status_error(status: u16, headers: &HeaderMap, body: &str, retry_after: Option<Duration>) -> SearchError {
    let message = format!("{status}: {}", error_message(body));
    match status {
        401 => SearchError::Auth(message),
        429 => SearchError::RateLimited { retry_after: retry_after.or_else(|| retry_after_header(headers)) },
        400 | 422 => SearchError::Invalid(message),
        _ => SearchError::Unavailable(message),
    }
}

fn retry_after_header(headers: &HeaderMap) -> Option<Duration> {
    let secs: f64 = headers.get("retry-after")?.to_str().ok()?.trim().parse().ok()?;
    (secs.is_finite() && secs >= 0.0).then(|| Duration::from_secs_f64(secs))
}

// ---------------------------------------------------------------- Tavily

pub struct Tavily {
    id: String,
    name: String,
    endpoint: String,
    key: Secret,
    client: reqwest::Client,
}

impl Tavily {
    pub fn new(id: &str, name: &str, endpoint: &str, key: Secret) -> Self {
        Self { id: id.into(), name: name.into(), endpoint: endpoint.into(), key, client: client() }
    }
}

#[derive(Deserialize)]
struct TavilyResponse {
    #[serde(default)]
    results: Vec<TavilyResult>,
    #[serde(default)]
    images: Vec<TavilyImage>,
}

#[derive(Deserialize)]
struct TavilyResult {
    title: String,
    url: String,
    content: Option<String>,
}

/// A bare URL, or `{url, description}` when descriptions were requested.
#[derive(Deserialize)]
#[serde(untagged)]
enum TavilyImage {
    Url(String),
    Described { url: String, description: Option<String> },
}

#[async_trait]
impl WebSearch for Tavily {
    fn id(&self) -> &str {
        &self.id
    }

    fn name(&self) -> &str {
        &self.name
    }

    async fn search(&self, query: &WebQuery) -> Result<Vec<WebHit>, SearchError> {
        let mut body = json!({
            "query": query.text,
            "search_depth": "basic",
            "max_results": query.max_results.min(MAX_RESULTS),
        });
        if !query.sites.is_empty() {
            body["include_domains"] = json!(query.sites);
        }
        if query.images {
            body["include_images"] = json!(true);
        }
        let response = self
            .client
            .post(&self.endpoint)
            .bearer_auth(self.key.expose())
            .json(&body)
            .send()
            .await
            .map_err(|e| transport_error(&self.name, e))?;
        let status = response.status().as_u16();
        if !response.status().is_success() {
            let headers = response.headers().clone();
            let body = response.text().await.unwrap_or_default();
            return Err(match status {
                // 432: plan limit reached; 433: pay-as-you-go limit reached.
                432 | 433 => SearchError::Quota(format!("{status}: {}", error_message(&body))),
                403 => SearchError::Auth(format!("{status}: {}", error_message(&body))),
                _ => status_error(status, &headers, &body, None),
            });
        }
        let parsed: TavilyResponse = response
            .json()
            .await
            .map_err(|e| SearchError::Invalid(format!("unexpected answer from {} ({e})", self.name)))?;
        let pages = parsed.results.into_iter().map(|r| WebHit {
            title: r.title,
            url: r.url,
            snippet: r.content.filter(|c| !c.trim().is_empty()),
            thumbnail_url: None,
            image: false,
        });
        let images = parsed.images.into_iter().map(|image| {
            let (url, description) = match image {
                TavilyImage::Url(url) => (url, None),
                TavilyImage::Described { url, description } => (url, description),
            };
            WebHit {
                title: description.clone().unwrap_or_else(|| query.text.clone()),
                url: url.clone(),
                snippet: description,
                thumbnail_url: Some(url),
                image: true,
            }
        });
        Ok(pages.chain(images).collect())
    }
}

// ---------------------------------------------------------------- Brave

pub struct Brave {
    id: String,
    name: String,
    /// The web search endpoint; image search is its sibling.
    endpoint: String,
    key: Secret,
    client: reqwest::Client,
}

impl Brave {
    pub fn new(id: &str, name: &str, endpoint: &str, key: Secret) -> Self {
        Self { id: id.into(), name: name.into(), endpoint: endpoint.into(), key, client: client() }
    }

    fn images_endpoint(&self) -> String {
        self.endpoint.replace("/web/search", "/images/search")
    }

    async fn get(&self, url: &str, query: &WebQuery) -> Result<Value, SearchError> {
        let sites: Vec<String> = query.sites.iter().map(|s| format!("site:{s}")).collect();
        let q = [query.text.clone(), sites.join(" OR ")].join(" ").trim().to_owned();
        let count = query.max_results.clamp(1, MAX_RESULTS).to_string();
        let response = self
            .client
            .get(url)
            .header("Accept", "application/json")
            .header("X-Subscription-Token", self.key.expose())
            .query(&[("q", q.as_str()), ("count", count.as_str())])
            .send()
            .await
            .map_err(|e| transport_error(&self.name, e))?;
        let status = response.status().as_u16();
        if !response.status().is_success() {
            let headers = response.headers().clone();
            let body = response.text().await.unwrap_or_default();
            return Err(match status {
                // Out of monthly credit.
                402 => SearchError::Quota(format!("{status}: {}", error_message(&body))),
                403 => SearchError::Auth(format!("{status}: {}", error_message(&body))),
                // `X-RateLimit-Reset: 1, 1419704` = seconds until the
                // per-second and per-month windows reset.
                _ => {
                    let reset = headers
                        .get("x-ratelimit-reset")
                        .and_then(|v| v.to_str().ok())
                        .and_then(|v| v.split(',').next()?.trim().parse::<u64>().ok())
                        .map(Duration::from_secs);
                    status_error(status, &headers, &body, reset)
                }
            });
        }
        response.json().await.map_err(|e| SearchError::Invalid(format!("unexpected answer from {} ({e})", self.name)))
    }
}

#[derive(Deserialize)]
struct BraveThumbnail {
    src: Option<String>,
}

#[derive(Deserialize)]
struct BraveWebResult {
    title: String,
    url: String,
    description: Option<String>,
    thumbnail: Option<BraveThumbnail>,
}

#[derive(Deserialize)]
struct BraveImageResult {
    title: Option<String>,
    url: Option<String>,
    thumbnail: Option<BraveThumbnail>,
}

#[async_trait]
impl WebSearch for Brave {
    fn id(&self) -> &str {
        &self.id
    }

    fn name(&self) -> &str {
        &self.name
    }

    /// Web results; with `images`, image results instead (each one names
    /// the page it's on). Either way one request.
    async fn search(&self, query: &WebQuery) -> Result<Vec<WebHit>, SearchError> {
        let thumb = |t: Option<BraveThumbnail>| t.and_then(|t| t.src);
        if query.images {
            let json = self.get(&self.images_endpoint(), query).await?;
            let results: Vec<BraveImageResult> =
                serde_json::from_value(json.get("results").cloned().unwrap_or(json!([])))
                    .map_err(|e| SearchError::Invalid(format!("unexpected answer from {} ({e})", self.name)))?;
            return Ok(results
                .into_iter()
                .filter_map(|r| {
                    let thumbnail_url = thumb(r.thumbnail)?;
                    Some(WebHit {
                        title: r.title.unwrap_or_else(|| query.text.clone()),
                        url: r.url.unwrap_or_else(|| thumbnail_url.clone()),
                        snippet: None,
                        thumbnail_url: Some(thumbnail_url),
                        image: true,
                    })
                })
                .collect());
        }
        let json = self.get(&self.endpoint, query).await?;
        let results: Vec<BraveWebResult> =
            serde_json::from_value(json.pointer("/web/results").cloned().unwrap_or(json!([])))
                .map_err(|e| SearchError::Invalid(format!("unexpected answer from {} ({e})", self.name)))?;
        Ok(results
            .into_iter()
            .map(|r| WebHit {
                title: r.title,
                url: r.url,
                snippet: r.description,
                thumbnail_url: thumb(r.thumbnail),
                image: false,
            })
            .collect())
    }
}

#[cfg(test)]
mod tests {
    use wiremock::matchers::{body_partial_json, header, method, path, query_param};
    use wiremock::{Mock, MockServer, ResponseTemplate};

    use super::*;

    fn query(text: &str) -> WebQuery {
        WebQuery { text: text.into(), max_results: 3, ..WebQuery::default() }
    }

    fn tavily(server: &MockServer) -> Tavily {
        Tavily::new("tavily", "Tavily", &format!("{}/search", server.uri()), Secret::new("tvly-test"))
    }

    fn brave(server: &MockServer) -> Brave {
        Brave::new("brave", "Brave Search", &format!("{}/res/v1/web/search", server.uri()), Secret::new("brave-test"))
    }

    #[tokio::test]
    async fn tavily_searches_with_sites_and_images() {
        let server = MockServer::start().await;
        Mock::given(method("POST"))
            .and(path("/search"))
            .and(header("authorization", "Bearer tvly-test"))
            .and(body_partial_json(json!({
                "query": "cozy farm art",
                "search_depth": "basic",
                "max_results": 3,
                "include_domains": ["kenney.nl"],
                "include_images": true
            })))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({
                "query": "cozy farm art",
                "results": [ { "title": "Farm art", "url": "https://a.example/farm", "content": "Pixel farms", "score": 0.9 } ],
                "images": [ "https://img.example/1.png", { "url": "https://img.example/2.png", "description": "barn at dusk" } ],
                "response_time": 1.2
            })))
            .expect(1)
            .mount(&server)
            .await;
        let q = WebQuery { sites: vec!["kenney.nl".into()], images: true, ..query("cozy farm art") };
        let hits = tavily(&server).search(&q).await.unwrap();
        assert_eq!(hits.len(), 3);
        assert_eq!(hits[0].snippet.as_deref(), Some("Pixel farms"));
        assert!(!hits[0].image);
        assert!(hits[1].image && hits[1].thumbnail_url.as_deref() == Some("https://img.example/1.png"));
        assert_eq!(hits[1].title, "cozy farm art");
        assert_eq!(hits[2].title, "barn at dusk");
    }

    #[tokio::test]
    async fn tavily_errors_are_classified() {
        for (status, check) in [
            (401, (|e: &SearchError| matches!(e, SearchError::Auth(_))) as fn(&SearchError) -> bool),
            (432, |e| matches!(e, SearchError::Quota(_))),
            (429, |e| matches!(e, SearchError::RateLimited { retry_after: Some(d) } if d.as_secs() == 7)),
            (500, |e| matches!(e, SearchError::Unavailable(_))),
        ] {
            let server = MockServer::start().await;
            Mock::given(method("POST"))
                .respond_with(
                    ResponseTemplate::new(status)
                        .insert_header("retry-after", "7")
                        .set_body_json(json!({ "detail": { "error": "nope" } })),
                )
                .mount(&server)
                .await;
            let e = tavily(&server).search(&query("x")).await.unwrap_err();
            assert!(check(&e), "{status}: {e:?}");
        }
    }

    #[tokio::test]
    async fn brave_web_search_uses_site_operators_and_thumbnails() {
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/res/v1/web/search"))
            .and(header("x-subscription-token", "brave-test"))
            .and(query_param("q", "wooden crate site:kenney.nl OR site:opengameart.org"))
            .and(query_param("count", "3"))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({
                "type": "search",
                "web": { "results": [
                    { "title": "Crate pack", "url": "https://kenney.nl/assets/crates", "description": "Crates",
                      "thumbnail": { "src": "https://imgs.search.brave.com/x.jpg", "original": "https://kenney.nl/x.jpg" } },
                    { "title": "No thumb", "url": "https://opengameart.org/content/crate" }
                ] }
            })))
            .expect(1)
            .mount(&server)
            .await;
        let q = WebQuery { sites: vec!["kenney.nl".into(), "opengameart.org".into()], ..query("wooden crate") };
        let hits = brave(&server).search(&q).await.unwrap();
        assert_eq!(hits.len(), 2);
        assert_eq!(hits[0].thumbnail_url.as_deref(), Some("https://imgs.search.brave.com/x.jpg"));
        assert!(hits[1].thumbnail_url.is_none());
    }

    #[tokio::test]
    async fn brave_image_search_names_the_page() {
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/res/v1/images/search"))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({
                "type": "images",
                "results": [
                    { "type": "image_result", "title": "Moody forest", "url": "https://art.example/forest",
                      "thumbnail": { "src": "https://imgs.search.brave.com/f.jpg" },
                      "properties": { "url": "https://art.example/forest.jpg" } },
                    { "type": "image_result", "title": "No thumbnail" }
                ]
            })))
            .mount(&server)
            .await;
        let hits = brave(&server).search(&WebQuery { images: true, ..query("moody forest") }).await.unwrap();
        assert_eq!(hits.len(), 1);
        assert!(hits[0].image);
        assert_eq!(hits[0].url, "https://art.example/forest");
    }

    #[tokio::test]
    async fn brave_rate_limits_use_the_reset_header() {
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .respond_with(ResponseTemplate::new(429).insert_header("x-ratelimit-reset", "1, 1419704"))
            .mount(&server)
            .await;
        let e = brave(&server).search(&query("x")).await.unwrap_err();
        assert_eq!(e, SearchError::RateLimited { retry_after: Some(Duration::from_secs(1)) });
    }

    #[test]
    fn builds_every_bundled_search_preset() {
        let presets = crate::Presets::bundled().unwrap();
        for preset in &presets.search {
            let search = build_search(preset, Some(Secret::new("k-123456"))).unwrap();
            assert_eq!(search.id(), preset.id);
            assert!(matches!(build_search(preset, None), Err(SearchBuildError::MissingKey(_))));
        }
    }
}
