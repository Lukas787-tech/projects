//! Interview persistence and orchestration: loads a project's interview
//! state, runs a turn through the core engine, and saves the transcript,
//! brief revisions and metadata. Streams progress through an event sink.

use std::collections::HashSet;
use std::sync::{Arc, Mutex};

use async_trait::async_trait;
use kickoff_core::brief::{ApplyReport, Brief, BriefError, Completeness, Flag, Focus, Suggestion};
use kickoff_core::interview::{InterviewError, InterviewState, Interviewer, Speaker, TurnOutcome, Utterance};
use kickoff_core::llm::{Route, Router};
use kickoff_core::pack::{self, PackManifest, ProjectTypePack};
use kickoff_core::{APP_NAME, Task};
use kickoff_store::{Database, InterviewMeta, Project, Stage, StoreError, StoredMessage};
use serde::Serialize;
use serde_json::Value;
use ts_rs::TS;

use crate::ai::{AiError, AiService};

/// The pack new projects use until other project types exist.
pub const DEFAULT_PACK: &str = "game";
const UNTITLED: &str = "Untitled idea";

/// Where interview turns get their models from.
#[async_trait]
pub trait Ai: Send + Sync {
    async fn routes(&self, task: Task) -> std::result::Result<Vec<Route>, AiError>;
    fn router(&self) -> &Router;
}

#[async_trait]
impl Ai for AiService {
    async fn routes(&self, task: Task) -> std::result::Result<Vec<Route>, AiError> {
        AiService::routes(self, task).await
    }
    fn router(&self) -> &Router {
        AiService::router(self)
    }
}

#[derive(Debug, thiserror::Error)]
pub enum InterviewServiceError {
    #[error("project not found")]
    NotFound,
    #[error("the producer is still answering; wait for it to finish")]
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
    Interview(#[from] InterviewError),
    #[error(transparent)]
    Brief(#[from] BriefError),
    #[error("saved interview data is unreadable: {0}")]
    Corrupt(#[from] serde_json::Error),
}

type Result<T> = std::result::Result<T, InterviewServiceError>;

/// Streamed while a turn runs.
#[derive(Debug, Clone, PartialEq, Serialize, TS)]
#[serde(tag = "type", rename_all = "snake_case")]
#[ts(export)]
pub enum InterviewEvent {
    /// A piece of the producer's reply.
    Delta {
        text: String,
    },
    ProducerDone {
        provider_id: String,
        model: String,
    },
    ProducerFailed {
        message: String,
    },
    /// Sent as soon as the brief changes, while the producer may still be talking.
    BriefUpdated {
        changed: Vec<String>,
        brief: Brief,
        completeness: Completeness,
    },
    ExtractionFailed {
        message: String,
    },
}

/// Everything the interview screen shows.
#[derive(Debug, Clone, Serialize, TS)]
#[ts(export)]
pub struct InterviewView {
    pub project: Project,
    pub pack: PackManifest,
    pub messages: Vec<StoredMessage>,
    pub brief: Brief,
    pub completeness: Completeness,
    /// What the producer will explore next.
    pub topics: Vec<Focus>,
    pub flags: Vec<Flag>,
    pub revisit: Option<String>,
    /// AI-proposed changes to fields the user edited (from the last turn).
    pub suggestions: Vec<Suggestion>,
    /// The last message is the user's and still needs a reply.
    pub awaiting_reply: bool,
}

/// A row in the projects list.
#[derive(Debug, Clone, Serialize, TS)]
#[ts(export)]
pub struct ProjectCard {
    pub project: Project,
    pub pitch: Option<String>,
    pub completeness: f32,
}

pub struct InterviewService {
    db: Arc<Database>,
    ai: Arc<dyn Ai>,
    busy: Mutex<HashSet<String>>,
}

/// Marks a project busy for the duration of a turn.
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
    state: InterviewState,
    messages: Vec<StoredMessage>,
}

impl InterviewService {
    pub fn new(db: Arc<Database>, ai: Arc<dyn Ai>) -> Self {
        Self { db, ai, busy: Mutex::new(HashSet::new()) }
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
            return Err(InterviewServiceError::Busy);
        }
        Ok(BusyGuard { set: &self.busy, id: project_id.to_owned() })
    }

    pub async fn create_project(&self) -> Result<Project> {
        let pack = pack::find(DEFAULT_PACK).ok_or_else(|| InterviewServiceError::UnknownPack(DEFAULT_PACK.into()))?;
        let manifest = pack.manifest().clone();
        let brief = serde_json::to_string(&Brief::new(&manifest))?;
        let id = format!("proj-{}", uuid::Uuid::new_v4());
        self.db(move |db| {
            let project = db.create_project(&id, UNTITLED, &manifest.id, &manifest.version)?;
            db.insert_brief_revision(&id, &manifest.brief_schema, &brief, None, "import", None)?;
            Ok(project)
        })
        .await
    }

    async fn load(&self, project_id: &str) -> Result<Loaded> {
        let id = project_id.to_owned();
        let (project, revision, messages, meta) = self
            .db(move |db| {
                let Some(project) = db.get_project(&id)? else { return Ok(None) };
                let revision = db.latest_brief_revision(&id)?;
                Ok(Some((project, revision, db.list_messages(&id)?, db.get_interview_meta(&id)?)))
            })
            .await?
            .ok_or(InterviewServiceError::NotFound)?;
        let pack =
            pack::find(&project.pack_id).ok_or_else(|| InterviewServiceError::UnknownPack(project.pack_id.clone()))?;
        let brief = match revision {
            Some(r) => serde_json::from_str(&r.brief_json)?,
            None => Brief::new(pack.manifest()),
        };
        let transcript = messages
            .iter()
            .filter_map(|m| match m.role.as_str() {
                "user" => Some(Utterance { speaker: Speaker::User, text: m.content.clone() }),
                "producer" => Some(Utterance { speaker: Speaker::Producer, text: m.content.clone() }),
                _ => None,
            })
            .collect();
        let state = InterviewState {
            brief,
            transcript,
            flags: serde_json::from_str(&meta.flags_json).unwrap_or_default(),
            revisit: meta.revisit,
            turn: meta.turn,
        };
        Ok(Loaded { project, pack, state, messages })
    }

    fn view(&self, loaded: Loaded, suggestions: Vec<Suggestion>) -> InterviewView {
        let manifest = loaded.pack.manifest();
        let completeness = loaded.state.brief.completeness(manifest);
        let topics = loaded.state.brief.open_topics(manifest, loaded.state.revisit.as_deref(), 2);
        InterviewView {
            awaiting_reply: loaded.messages.last().is_some_and(|m| m.role == "user"),
            project: loaded.project,
            pack: manifest.clone(),
            messages: loaded.messages,
            brief: loaded.state.brief,
            completeness,
            topics,
            flags: loaded.state.flags,
            revisit: loaded.state.revisit,
            suggestions,
        }
    }

    pub async fn get(&self, project_id: &str) -> Result<InterviewView> {
        let loaded = self.load(project_id).await?;
        Ok(self.view(loaded, Vec::new()))
    }

    pub async fn list(&self) -> Result<Vec<ProjectCard>> {
        let rows = self
            .db(|db| {
                db.list_projects()?
                    .into_iter()
                    .map(|p| {
                        let rev = db.latest_brief_revision(&p.id)?;
                        Ok((p, rev))
                    })
                    .collect::<std::result::Result<Vec<_>, StoreError>>()
            })
            .await?;
        Ok(rows
            .into_iter()
            .map(|(project, rev)| {
                let brief: Option<Brief> = rev.and_then(|r| serde_json::from_str(&r.brief_json).ok());
                let pack = pack::find(&project.pack_id);
                let completeness = match (&brief, &pack) {
                    (Some(b), Some(p)) => b.completeness(p.manifest()).ratio,
                    _ => 0.0,
                };
                let pitch =
                    brief.as_ref().and_then(|b| b.field("pitch")).and_then(|f| f.value.as_str()).map(str::to_owned);
                ProjectCard { project, pitch, completeness }
            })
            .collect())
    }

    pub async fn delete(&self, project_id: &str) -> Result<()> {
        let _guard = self.lock(project_id)?;
        let id = project_id.to_owned();
        if !self.db(move |db| db.delete_project(&id)).await? {
            return Err(InterviewServiceError::NotFound);
        }
        Ok(())
    }

    /// Run one interview turn for the user's message.
    pub async fn send(
        &self,
        project_id: &str,
        text: &str,
        input_mode: &str,
        emit: &(dyn Fn(InterviewEvent) + Send + Sync),
    ) -> Result<InterviewView> {
        let _guard = self.lock(project_id)?;
        let mut loaded = self.load(project_id).await?;
        let (producer_routes, extractor_routes) = self.turn_routes().await?;
        let text = text.trim().to_owned();
        if text.is_empty() {
            return Err(InterviewError::EmptyMessage.into());
        }

        // Save the user's words before anything can fail.
        let (id, msg, mode) = (project_id.to_owned(), text.clone(), input_mode.to_owned());
        let user_message_id = self.db(move |db| db.insert_message(&id, "user", &msg, Some(&mode), None, None)).await?;

        let before = loaded.state.brief.clone();
        let manifest = loaded.pack.manifest().clone();
        let interviewer = Interviewer::new(loaded.pack.as_ref(), self.ai.router(), APP_NAME);
        let outcome = interviewer
            .run_turn(
                &mut loaded.state,
                &text,
                &producer_routes,
                &extractor_routes,
                &mut |t| emit(InterviewEvent::Delta { text: t.to_owned() }),
                &mut |brief, report| emit_brief(emit, &manifest, brief, report),
            )
            .await?;
        self.conclude(loaded, &before, outcome, emit, user_message_id).await
    }

    /// Retry the turn for the user's last message after the reply failed.
    pub async fn continue_turn(
        &self,
        project_id: &str,
        emit: &(dyn Fn(InterviewEvent) + Send + Sync),
    ) -> Result<InterviewView> {
        let _guard = self.lock(project_id)?;
        let mut loaded = self.load(project_id).await?;
        let (producer_routes, extractor_routes) = self.turn_routes().await?;
        let user_message_id = match loaded.messages.last() {
            Some(m) if m.role == "user" => m.id,
            _ => return Err(InterviewError::NothingToReplyTo.into()),
        };

        let before = loaded.state.brief.clone();
        let manifest = loaded.pack.manifest().clone();
        let interviewer = Interviewer::new(loaded.pack.as_ref(), self.ai.router(), APP_NAME);
        let outcome = interviewer
            .continue_turn(
                &mut loaded.state,
                &producer_routes,
                &extractor_routes,
                &mut |t| emit(InterviewEvent::Delta { text: t.to_owned() }),
                &mut |brief, report| emit_brief(emit, &manifest, brief, report),
            )
            .await?;
        self.conclude(loaded, &before, outcome, emit, user_message_id).await
    }

    async fn turn_routes(&self) -> Result<(Vec<Route>, Vec<Route>)> {
        let producer = self.ai.routes(Task::Interview).await?;
        if producer.is_empty() {
            return Err(InterviewServiceError::NoAi);
        }
        Ok((producer, self.ai.routes(Task::Extraction).await?))
    }

    /// Save a turn's reply and brief changes, report failures, and return the
    /// updated view.
    async fn conclude(
        &self,
        loaded: Loaded,
        before: &Brief,
        outcome: TurnOutcome,
        emit: &(dyn Fn(InterviewEvent) + Send + Sync),
        user_message_id: i64,
    ) -> Result<InterviewView> {
        let project_id = loaded.project.id.clone();
        match &outcome.producer {
            Ok(reply) => {
                let (id, r) = (project_id.clone(), reply.clone());
                self.db(move |db| {
                    db.insert_message(&id, "producer", &r.text, None, Some(&r.provider_id), Some(&r.model))
                })
                .await?;
                emit(InterviewEvent::ProducerDone {
                    provider_id: reply.provider_id.clone(),
                    model: reply.model.clone(),
                });
            }
            Err(e) => {
                tracing::warn!(project = %project_id, error = %e, "producer failed");
                emit(InterviewEvent::ProducerFailed { message: e.to_string() });
            }
        }
        if let Err(e) = &outcome.extraction {
            tracing::warn!(project = %project_id, error = %e, "brief extraction failed");
            emit(InterviewEvent::ExtractionFailed { message: e.to_string() });
        }
        self.save_state(&loaded, before, "extraction", Some(user_message_id)).await?;
        let loaded = self.load(&project_id).await?;
        Ok(self.view(loaded, outcome.suggestions))
    }

    /// The user edits a brief field directly. `null` clears it.
    pub async fn edit_field(&self, project_id: &str, field: &str, value: &Value) -> Result<InterviewView> {
        let _guard = self.lock(project_id)?;
        let mut loaded = self.load(project_id).await?;
        let before = loaded.state.brief.clone();
        let turn = loaded.state.turn;
        loaded.state.brief.set_user_value(loaded.pack.manifest(), field, value, turn)?;
        self.save_state(&loaded, &before, "user_edit", None).await?;
        let loaded = self.load(project_id).await?;
        Ok(self.view(loaded, Vec::new()))
    }

    /// Ask the producer to come back to a field on the next turn.
    pub async fn revisit(&self, project_id: &str, field: &str) -> Result<InterviewView> {
        let _guard = self.lock(project_id)?;
        let mut loaded = self.load(project_id).await?;
        if loaded.pack.manifest().field(field).is_none() {
            return Err(BriefError::UnknownField(field.to_owned()).into());
        }
        loaded.state.revisit = Some(field.to_owned());
        let before = loaded.state.brief.clone();
        self.save_state(&loaded, &before, "user_edit", None).await?;
        let loaded = self.load(project_id).await?;
        Ok(self.view(loaded, Vec::new()))
    }

    /// Move on from the interview (allowed even when the brief isn't complete).
    pub async fn finish(&self, project_id: &str) -> Result<InterviewView> {
        let _guard = self.lock(project_id)?;
        let loaded = self.load(project_id).await?;
        let (id, name, engine) = (project_id.to_owned(), loaded.project.name.clone(), loaded.project.engine.clone());
        self.db(move |db| db.update_project(&id, &name, Stage::Research, engine.as_deref())).await?;
        let loaded = self.load(project_id).await?;
        Ok(self.view(loaded, Vec::new()))
    }

    /// Persist brief (as a new revision if it changed), metadata, and the
    /// project's name and engine as derived from the brief.
    async fn save_state(
        &self,
        loaded: &Loaded,
        before: &Brief,
        cause: &'static str,
        message_id: Option<i64>,
    ) -> Result<()> {
        let brief = &loaded.state.brief;
        let changed = brief.diff(before);
        let brief_json = (!changed.is_empty()).then(|| serde_json::to_string(brief)).transpose()?;
        let diff_json = serde_json::to_string(&changed)?;
        let meta = InterviewMeta {
            flags_json: serde_json::to_string(&loaded.state.flags)?,
            revisit: loaded.state.revisit.clone(),
            turn: loaded.state.turn,
        };
        let name = brief
            .field("working_title")
            .and_then(|f| f.value.as_str())
            .map(str::to_owned)
            .unwrap_or_else(|| UNTITLED.to_owned());
        let engine =
            brief.field("engine").and_then(|f| f.value.get("choice")).and_then(Value::as_str).map(str::to_owned);
        let (id, schema, stage) = (loaded.project.id.clone(), brief.schema.clone(), loaded.project.stage);
        self.db(move |db| {
            if let Some(json) = brief_json {
                db.insert_brief_revision(&id, &schema, &json, Some(&diff_json), cause, message_id)?;
            }
            db.set_interview_meta(&id, &meta)?;
            db.update_project(&id, &name, stage, engine.as_deref())
        })
        .await
    }
}

/// Tell the UI about brief changes as soon as the extraction lands.
fn emit_brief(
    emit: &(dyn Fn(InterviewEvent) + Send + Sync),
    manifest: &PackManifest,
    brief: &Brief,
    report: &ApplyReport,
) {
    if !report.changed.is_empty() {
        emit(InterviewEvent::BriefUpdated {
            changed: report.changed.clone(),
            brief: brief.clone(),
            completeness: brief.completeness(manifest),
        });
    }
}

#[cfg(test)]
mod tests {
    use std::sync::Mutex as StdMutex;

    use kickoff_core::llm::router::RetryPolicy;
    use kickoff_core::llm::{ChatRequest, LlmError, LlmProvider, OutputFormat};
    use kickoff_core::testing::{FakeProvider, RecordingObserver, Step};
    use serde_json::json;

    use super::*;

    struct FakeAi {
        router: Router,
        producer: Vec<Route>,
        extractor: Vec<Route>,
    }

    #[async_trait]
    impl Ai for FakeAi {
        async fn routes(&self, task: Task) -> std::result::Result<Vec<Route>, AiError> {
            Ok(if task == Task::Extraction { self.extractor.clone() } else { self.producer.clone() })
        }
        fn router(&self) -> &Router {
            &self.router
        }
    }

    fn is_extraction(req: &ChatRequest) -> bool {
        matches!(req.format, OutputFormat::JsonSchema { .. })
    }

    fn route(p: Arc<FakeProvider>) -> Vec<Route> {
        let model = format!("{}-model", p.id());
        vec![Route { provider: p, model, rpm: None }]
    }

    fn service(producer: Arc<FakeProvider>, extractor: Arc<FakeProvider>) -> InterviewService {
        let ai = FakeAi {
            router: Router::new(RetryPolicy { max_attempts: 1, ..Default::default() }, RecordingObserver::new()),
            producer: route(producer),
            extractor: route(extractor),
        };
        InterviewService::new(Arc::new(Database::open_in_memory().unwrap()), Arc::new(ai))
    }

    fn scripted() -> Arc<FakeProvider> {
        FakeProvider::with_handler("groq", |req| {
            if is_extraction(req) {
                Step::reply(
                    &json!({"updates": [
                        {"field": "working_title", "value": "Hearth", "confidence": 0.9},
                        {"field": "engine", "value": "godot", "confidence": 0.9}
                    ], "flags": []})
                    .to_string(),
                )
            } else {
                Step::stream(&["Nice. ", "Who is it for?"])
            }
        })
    }

    #[tokio::test]
    async fn a_turn_is_saved_and_streamed() {
        let p = scripted();
        let svc = service(p.clone(), p);
        let project = svc.create_project().await.unwrap();
        assert_eq!(project.name, UNTITLED);

        let events = StdMutex::new(Vec::new());
        let view = svc
            .send(&project.id, "a cozy survival game called Hearth", "text", &|e| events.lock().unwrap().push(e))
            .await
            .unwrap();

        assert_eq!(view.messages.len(), 2);
        assert_eq!(view.messages[1].content, "Nice. Who is it for?");
        assert_eq!(view.messages[1].provider_id.as_deref(), Some("groq"));
        assert_eq!(view.project.name, "Hearth", "name follows the working title");
        assert_eq!(view.project.engine.as_deref(), Some("godot"));
        assert!(!view.awaiting_reply);
        assert_eq!(view.brief.field("working_title").unwrap().value, json!("Hearth"));
        assert!(svc.db.count_brief_revisions(&project.id).unwrap() == 2);

        let events = events.into_inner().unwrap();
        assert_eq!(events[0], InterviewEvent::Delta { text: "Nice. ".into() });
        assert!(
            events.contains(&InterviewEvent::ProducerDone { provider_id: "groq".into(), model: "groq-model".into() })
        );
        assert!(events.iter().any(|e| matches!(e,
            InterviewEvent::BriefUpdated { changed, brief, .. }
                if changed == &["working_title", "engine"] && brief.field("working_title").unwrap().value == json!("Hearth"))));

        // Reloading gives the same state.
        let again = svc.get(&project.id).await.unwrap();
        assert_eq!(again.brief, view.brief);
        let cards = svc.list().await.unwrap();
        assert_eq!(cards.len(), 1);
        assert!(cards[0].completeness > 0.0);
    }

    #[tokio::test]
    async fn a_failed_turn_can_be_retried() {
        let down = FakeProvider::with_handler("gemini", |_| Step::error(LlmError::Unavailable("offline".into())));
        let svc = service(down.clone(), down);
        let project = svc.create_project().await.unwrap();
        let events = StdMutex::new(Vec::new());
        let view = svc.send(&project.id, "a roguelike", "voice", &|e| events.lock().unwrap().push(e)).await.unwrap();
        assert!(view.awaiting_reply);
        assert_eq!(view.messages[0].input_mode.as_deref(), Some("voice"));
        let failed = events.into_inner().unwrap();
        assert!(failed.iter().any(|e| matches!(e, InterviewEvent::ProducerFailed { .. })));
        assert!(failed.iter().any(|e| matches!(e, InterviewEvent::ExtractionFailed { .. })));
        assert_eq!(view.project.name, UNTITLED);

        // Swap in a working producer and retry.
        let back = scripted();
        let svc = InterviewService::new(
            svc.db.clone(),
            Arc::new(FakeAi {
                router: Router::new(RetryPolicy::default(), RecordingObserver::new()),
                producer: route(back.clone()),
                extractor: route(back),
            }),
        );
        let events = StdMutex::new(Vec::new());
        let view = svc.continue_turn(&project.id, &|e| events.lock().unwrap().push(e)).await.unwrap();
        assert!(!view.awaiting_reply);
        assert_eq!(view.messages.len(), 2);
        // The retry also reads the answer into the brief.
        assert_eq!(view.project.name, "Hearth");
        assert!(events.into_inner().unwrap().iter().any(|e| matches!(e, InterviewEvent::BriefUpdated { .. })));
        assert!(matches!(
            svc.continue_turn(&project.id, &|_| {}).await,
            Err(InterviewServiceError::Interview(InterviewError::NothingToReplyTo))
        ));
    }

    #[tokio::test]
    async fn edits_revisits_and_finishing() {
        let p = scripted();
        let svc = service(p.clone(), p);
        let project = svc.create_project().await.unwrap();
        let view = svc.edit_field(&project.id, "working_title", &json!("Ember Hollow")).await.unwrap();
        assert_eq!(view.project.name, "Ember Hollow");

        // The AI's later proposal becomes a suggestion, not an overwrite.
        let view = svc.send(&project.id, "maybe call it Hearth", "text", &|_| {}).await.unwrap();
        assert_eq!(view.brief.field("working_title").unwrap().value, json!("Ember Hollow"));
        assert_eq!(view.suggestions.len(), 1);

        let view = svc.revisit(&project.id, "pillars").await.unwrap();
        assert_eq!(view.revisit.as_deref(), Some("pillars"));
        assert_eq!(view.topics[0].field, "pillars");
        assert!(svc.revisit(&project.id, "nope").await.is_err());

        let view = svc.finish(&project.id).await.unwrap();
        assert_eq!(view.project.stage, Stage::Research);

        svc.delete(&project.id).await.unwrap();
        assert!(matches!(svc.get(&project.id).await, Err(InterviewServiceError::NotFound)));
    }

    #[tokio::test]
    async fn no_ai_is_reported_before_saving_anything() {
        let svc = InterviewService::new(
            Arc::new(Database::open_in_memory().unwrap()),
            Arc::new(FakeAi {
                router: Router::new(RetryPolicy::default(), RecordingObserver::new()),
                producer: vec![],
                extractor: vec![],
            }),
        );
        let project = svc.create_project().await.unwrap();
        assert!(matches!(svc.send(&project.id, "hi", "text", &|_| {}).await, Err(InterviewServiceError::NoAi)));
        assert!(svc.get(&project.id).await.unwrap().messages.is_empty());
    }

    #[tokio::test]
    async fn one_turn_at_a_time() {
        let p = scripted();
        let svc = service(p.clone(), p);
        let project = svc.create_project().await.unwrap();
        let _held = svc.lock(&project.id).unwrap();
        assert!(matches!(svc.send(&project.id, "hi", "text", &|_| {}).await, Err(InterviewServiceError::Busy)));
    }
}
