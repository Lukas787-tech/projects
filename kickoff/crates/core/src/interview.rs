//! The producer interview.
//!
//! Each user turn runs two AI calls at once, for latency:
//! - the **producer** (streamed text) replies and asks the next question;
//! - the **extractor** (structured JSON) turns the exchange into brief updates
//!   and honesty flags.
//!
//! The producer sees the brief as it was before this turn, plus the user's
//! latest words and the next two open topics, so it can skip one that was
//! just answered. Flags found this turn are raised on the next one.
//!
//! An extractor failure never fails the turn. A producer failure leaves the
//! user's message and any extraction in place; [`Interviewer::continue_turn`]
//! retries the turn for that message.

use serde::{Deserialize, Serialize};
use serde_json::json;
use ts_rs::TS;

use crate::brief::{self, ApplyReport, Brief, Completeness, Extraction, Flag, FlagKind, Focus, Suggestion};
use crate::llm::{
    ChatMessage, ChatRequest, Route, Router, RouterError, StructuredError, StructuredOptions, structured_call,
};
use crate::pack::{self, FieldKind, PackError, ProjectTypePack};

/// Messages of history sent to the producer. Older context lives in the brief.
const HISTORY_MESSAGES: usize = 12;
/// Open topics the producer may choose from.
const TOPICS: usize = 2;
/// Flags carried into the next producer prompt.
const MAX_FLAGS: usize = 3;
/// The producer is told to stay under 80 words; this is a hard cap.
const PRODUCER_MAX_TOKENS: u32 = 2048;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, TS)]
#[serde(rename_all = "snake_case")]
#[ts(export)]
pub enum Speaker {
    User,
    Producer,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, TS)]
#[ts(export)]
pub struct Utterance {
    pub speaker: Speaker,
    pub text: String,
}

/// Everything the interview needs between turns. Persisted by the app.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct InterviewState {
    pub brief: Brief,
    pub transcript: Vec<Utterance>,
    /// Problems to raise on the next producer turn.
    pub flags: Vec<Flag>,
    /// A field the user asked to revisit.
    pub revisit: Option<String>,
    /// Number of user turns so far.
    pub turn: u32,
}

impl InterviewState {
    pub fn new(pack: &dyn ProjectTypePack) -> Self {
        Self { brief: Brief::new(pack.manifest()), transcript: Vec::new(), flags: Vec::new(), revisit: None, turn: 0 }
    }
}

#[derive(Debug, Clone)]
pub struct ProducerReply {
    pub text: String,
    pub provider_id: String,
    pub model: String,
}

#[derive(Debug, Clone)]
pub struct ExtractionResult {
    pub report: ApplyReport,
    pub flags: Vec<Flag>,
    pub provider_id: String,
    pub model: String,
}

#[derive(Debug, Clone)]
pub struct TurnOutcome {
    pub producer: Result<ProducerReply, RouterError>,
    pub extraction: Result<ExtractionResult, StructuredError>,
    pub completeness: Completeness,
    pub suggestions: Vec<Suggestion>,
}

#[derive(Debug, thiserror::Error)]
pub enum InterviewError {
    #[error(transparent)]
    Pack(#[from] PackError),
    #[error("nothing to reply to")]
    NothingToReplyTo,
    #[error("the message is empty")]
    EmptyMessage,
}

pub struct Interviewer<'a> {
    pack: &'a dyn ProjectTypePack,
    router: &'a Router,
    app_name: &'a str,
}

impl<'a> Interviewer<'a> {
    pub fn new(pack: &'a dyn ProjectTypePack, router: &'a Router, app_name: &'a str) -> Self {
        Self { pack, router, app_name }
    }

    /// Run one turn: record the user's message, extract brief updates and
    /// stream the producer's reply (both at once).
    pub async fn run_turn(
        &self,
        state: &mut InterviewState,
        user_text: &str,
        producer_routes: &[Route],
        extractor_routes: &[Route],
        on_text: &mut (dyn FnMut(&str) + Send),
        on_brief: &mut (dyn FnMut(&Brief, &ApplyReport) + Send),
    ) -> Result<TurnOutcome, InterviewError> {
        let user_text = user_text.trim();
        if user_text.is_empty() {
            return Err(InterviewError::EmptyMessage);
        }
        state.turn += 1;
        state.transcript.push(Utterance { speaker: Speaker::User, text: user_text.to_owned() });
        self.answer(state, producer_routes, extractor_routes, on_text, on_brief).await
    }

    /// Retry a turn whose reply failed. The last message is read into the
    /// brief again too, since the extraction usually failed with it.
    pub async fn continue_turn(
        &self,
        state: &mut InterviewState,
        producer_routes: &[Route],
        extractor_routes: &[Route],
        on_text: &mut (dyn FnMut(&str) + Send),
        on_brief: &mut (dyn FnMut(&Brief, &ApplyReport) + Send),
    ) -> Result<TurnOutcome, InterviewError> {
        if state.transcript.last().is_none_or(|u| u.speaker != Speaker::User) {
            return Err(InterviewError::NothingToReplyTo);
        }
        self.answer(state, producer_routes, extractor_routes, on_text, on_brief).await
    }

    /// Answer the user's last message: extract brief updates and stream the
    /// producer's reply concurrently.
    async fn answer(
        &self,
        state: &mut InterviewState,
        producer_routes: &[Route],
        extractor_routes: &[Route],
        on_text: &mut (dyn FnMut(&str) + Send),
        on_brief: &mut (dyn FnMut(&Brief, &ApplyReport) + Send),
    ) -> Result<TurnOutcome, InterviewError> {
        let mut earlier = state.transcript.iter().rev();
        let user_text = earlier.next().map(|u| u.text.clone()).unwrap_or_default();
        let last_producer = earlier.find(|u| u.speaker == Speaker::Producer).map(|u| u.text.clone());

        let producer_request = self.producer_request(state)?;
        let extractor_messages = self.extractor_messages(state, last_producer.as_deref(), &user_text)?;
        let schema = brief::extraction_schema(self.pack.manifest());
        let options = StructuredOptions::named("brief_update");

        // The extraction applies its updates as soon as it lands, so the UI
        // can show the new brief while the producer is still talking.
        let turn = state.turn;
        let manifest = self.pack.manifest();
        let brief = &mut state.brief;
        let extract = async {
            let structured =
                structured_call::<Extraction>(self.router, extractor_routes, extractor_messages, &schema, &options)
                    .await?;
            let report = brief.apply_extraction(manifest, &structured.value.updates, turn);
            on_brief(brief, &report);
            Ok::<_, StructuredError>(ExtractionResult {
                report,
                flags: structured.value.flags,
                provider_id: structured.provider_id,
                model: structured.model,
            })
        };
        let (producer, extraction) =
            tokio::join!(self.router.chat_streaming(producer_routes, &producer_request, on_text), extract);
        if let Ok(result) = &extraction {
            state.flags = result.flags.iter().take(MAX_FLAGS).cloned().collect();
        }
        let suggestions = extraction.as_ref().map(|r| r.report.suggestions.clone()).unwrap_or_default();

        let producer = producer.map(|answer| {
            let text = answer.response.content.trim().to_owned();
            state.transcript.push(Utterance { speaker: Speaker::Producer, text: text.clone() });
            // The revisit request has had its turn.
            state.revisit = None;
            ProducerReply { text, provider_id: answer.provider_id, model: answer.response.model }
        });

        Ok(TurnOutcome {
            producer,
            extraction,
            completeness: state.brief.completeness(self.pack.manifest()),
            suggestions,
        })
    }

    /// Topics the producer will be pointed at next (also shown in the UI).
    pub fn open_topics(&self, state: &InterviewState) -> Vec<Focus> {
        state.brief.open_topics(self.pack.manifest(), state.revisit.as_deref(), TOPICS)
    }

    fn producer_request(&self, state: &InterviewState) -> Result<ChatRequest, InterviewError> {
        let manifest = self.pack.manifest();
        let completeness = state.brief.completeness(manifest);
        let flags: Vec<String> = state.flags.iter().map(describe_flag).collect();
        let system = pack::render(
            self.pack.producer_template(),
            json!({
                "app_name": self.app_name,
                "brief": state.brief.summary(manifest),
                "flags": flags,
                "complete": completeness.complete,
                "topics": self.open_topics(state),
            }),
        )?;
        let mut messages = vec![ChatMessage::system(system)];
        let start = state.transcript.len().saturating_sub(HISTORY_MESSAGES);
        // History must start with the user.
        let history = state.transcript[start..].iter().skip_while(|u| u.speaker != Speaker::User);
        messages.extend(history.map(|u| match u.speaker {
            Speaker::User => ChatMessage::user(&u.text),
            Speaker::Producer => ChatMessage::assistant(&u.text),
        }));
        Ok(ChatRequest {
            messages,
            temperature: Some(0.7),
            max_tokens: Some(PRODUCER_MAX_TOKENS),
            ..Default::default()
        })
    }

    fn extractor_messages(
        &self,
        state: &InterviewState,
        producer: Option<&str>,
        user: &str,
    ) -> Result<Vec<ChatMessage>, InterviewError> {
        let manifest = self.pack.manifest();
        let engine_options = manifest
            .fields
            .iter()
            .find_map(|f| match &f.kind {
                FieldKind::Choice { options } => {
                    Some(options.iter().map(|o| o.id.as_str()).collect::<Vec<_>>().join(", "))
                }
                _ => None,
            })
            .unwrap_or_default();
        let system = pack::render(
            self.pack.extractor_template(),
            json!({
                "engine_options": engine_options,
                "field_catalog": brief::field_catalog(manifest),
                "brief": state.brief.summary(manifest),
                "producer": producer.unwrap_or("(start of the interview: the user describes their idea)"),
                "user": user,
            }),
        )?;
        Ok(vec![ChatMessage::system(system), ChatMessage::user("Return the brief updates for this exchange.")])
    }
}

fn describe_flag(flag: &Flag) -> String {
    let kind = match flag.kind {
        FlagKind::Scope => "Scope",
        FlagKind::Contradiction => "Contradiction",
        FlagKind::Vague => "Too vague",
    };
    format!("{kind}: {}", flag.note)
}

#[cfg(test)]
mod tests {
    use std::sync::Arc;

    use serde_json::{Value, json};

    use super::*;
    use crate::brief::FieldStatus;
    use crate::llm::router::RetryPolicy;
    use crate::llm::{ChatRequest, LlmError, OutputFormat};
    use crate::testing::{FakeProvider, RecordingObserver, Step};

    fn game() -> Arc<dyn ProjectTypePack> {
        pack::find("game").unwrap()
    }

    fn router() -> Router {
        Router::new(RetryPolicy { max_attempts: 1, ..RetryPolicy::default() }, RecordingObserver::new())
    }

    fn route(p: &Arc<FakeProvider>) -> Vec<Route> {
        vec![Route {
            provider: p.clone(),
            model: format!("{}-model", crate::llm::LlmProvider::id(p.as_ref())),
            rpm: None,
        }]
    }

    fn is_extraction(req: &ChatRequest) -> bool {
        matches!(req.format, OutputFormat::JsonSchema { .. })
    }

    /// One fake model that plays both roles, as a single free provider would.
    fn fake(producer_reply: &'static str, extraction: Value) -> Arc<FakeProvider> {
        let extraction = extraction.to_string();
        FakeProvider::with_handler("groq", move |req| {
            if is_extraction(req) { Step::reply(&extraction) } else { Step::stream(&[producer_reply]) }
        })
    }

    #[tokio::test]
    async fn first_turn_fills_the_brief_and_asks_a_question() {
        let pack = game();
        let router = router();
        let p = fake(
            "Love it. What should a single evening of play feel like?",
            json!({
                "updates": [
                    {"field": "genre", "value": "cozy survival", "confidence": 0.9},
                    {"field": "pitch", "value": "Survive a gentle winter by building a warm home", "confidence": 0.6}
                ],
                "flags": []
            }),
        );
        let interviewer = Interviewer::new(pack.as_ref(), &router, "Kickoff");
        let mut state = InterviewState::new(pack.as_ref());
        let mut streamed = String::new();
        let outcome = interviewer
            .run_turn(
                &mut state,
                "  a cozy survival game  ",
                &route(&p),
                &route(&p),
                &mut |t| streamed.push_str(t),
                &mut |_, _| {},
            )
            .await
            .unwrap();

        let reply = outcome.producer.unwrap();
        assert_eq!(reply.text, streamed);
        assert_eq!(state.transcript.len(), 2);
        assert_eq!(state.transcript[0].text, "a cozy survival game");
        assert_eq!(state.brief.field("genre").unwrap().status, FieldStatus::Confirmed);
        assert_eq!(state.brief.field("pitch").unwrap().status, FieldStatus::Draft);
        assert_eq!(outcome.extraction.unwrap().report.changed, ["genre", "pitch"]);
        assert!(!outcome.completeness.complete);

        // The producer saw the persona, the brief and the open topics; the
        // extractor saw the field catalog and the exchange.
        let requests = p.requests();
        let producer = requests.iter().find(|r| !is_extraction(r)).unwrap();
        let system = &producer.messages[0].content;
        assert!(system.contains("producer in Kickoff"));
        assert!(system.contains("- Working title: (not discussed yet)"));
        assert!(system.contains("Open topics, most important first:\n- Working title:"));
        assert_eq!(producer.messages.last().unwrap().content, "a cozy survival game");
        let extractor = requests.iter().find(|r| is_extraction(r)).unwrap();
        assert!(extractor.messages[0].content.contains("- core_loop.session: Core loop, one session (text)"));
        assert!(extractor.messages[0].content.contains("Producer: (start of the interview"));
        assert!(extractor.messages[0].content.contains("User: a cozy survival game"));
    }

    #[tokio::test]
    async fn flags_are_raised_on_the_next_turn() {
        let pack = game();
        let router = router();
        let p = fake(
            "That's a big one.",
            json!({"updates": [], "flags": [{"kind": "scope", "field": "scope", "note": "An open-world MMO is too much for one person in 3 months"}]}),
        );
        let interviewer = Interviewer::new(pack.as_ref(), &router, "Kickoff");
        let mut state = InterviewState::new(pack.as_ref());
        interviewer
            .run_turn(
                &mut state,
                "an open world MMO, solo, 3 months",
                &route(&p),
                &route(&p),
                &mut |_| {},
                &mut |_, _| {},
            )
            .await
            .unwrap();
        assert_eq!(state.flags.len(), 1);
        interviewer.run_turn(&mut state, "ok", &route(&p), &route(&p), &mut |_| {}, &mut |_, _| {}).await.unwrap();
        let second_producer = p.requests().into_iter().filter(|r| !is_extraction(r)).nth(1).unwrap();
        assert!(second_producer.messages[0].content.contains("Scope: An open-world MMO is too much"));
        // History alternates and ends with the user.
        let roles: Vec<_> = second_producer.messages.iter().skip(1).map(|m| m.role).collect();
        use crate::llm::Role::*;
        assert_eq!(roles, [User, Assistant, User]);
    }

    #[tokio::test]
    async fn extraction_failure_does_not_fail_the_turn() {
        let pack = game();
        let router = router();
        let p = FakeProvider::with_handler("groq", |req| {
            if is_extraction(req) { Step::reply("I can't do JSON today") } else { Step::stream(&["Tell me more."]) }
        });
        let interviewer = Interviewer::new(pack.as_ref(), &router, "Kickoff");
        let mut state = InterviewState::new(pack.as_ref());
        let outcome = interviewer
            .run_turn(&mut state, "a puzzle game", &route(&p), &route(&p), &mut |_| {}, &mut |_, _| {})
            .await
            .unwrap();
        assert!(outcome.producer.is_ok());
        assert!(outcome.extraction.is_err());
        assert_eq!(state.transcript.len(), 2);
    }

    #[tokio::test]
    async fn producer_failure_keeps_the_message_and_can_be_retried() {
        let pack = game();
        let router = router();
        let down = FakeProvider::with_handler("gemini", |_| Step::error(LlmError::Unavailable("offline".into())));
        let interviewer = Interviewer::new(pack.as_ref(), &router, "Kickoff");
        let mut state = InterviewState::new(pack.as_ref());
        state.transcript.push(Utterance { speaker: Speaker::Producer, text: "What kind of game?".into() });
        let outcome = interviewer
            .run_turn(&mut state, "a roguelike", &route(&down), &route(&down), &mut |_| {}, &mut |_, _| {})
            .await
            .unwrap();
        assert!(outcome.producer.is_err());
        assert!(outcome.extraction.is_err());
        assert_eq!(state.transcript.len(), 2, "user message kept");
        assert_eq!(state.turn, 1);

        // The retry answers the same message and reads it into the brief.
        let back = fake(
            "What's the twist?",
            json!({"updates": [{"field": "genre", "value": "roguelike", "confidence": 0.9}], "flags": []}),
        );
        let mut changed = Vec::new();
        let outcome = interviewer
            .continue_turn(&mut state, &route(&back), &route(&back), &mut |_| {}, &mut |_, r| {
                changed.extend(r.changed.clone())
            })
            .await
            .unwrap();
        assert_eq!(outcome.producer.unwrap().text, "What's the twist?");
        assert_eq!(changed, vec!["genre".to_owned()]);
        assert_eq!(state.brief.field("genre").unwrap().value, json!("roguelike"));
        assert_eq!(state.turn, 1, "a retry is not a new turn");
        assert_eq!(state.transcript.len(), 3);
        let extractor_prompt = back.requests().into_iter().find(is_extraction).unwrap().messages[0].content.clone();
        assert!(extractor_prompt.contains("Producer: What kind of game?\nUser: a roguelike"));

        assert!(matches!(
            interviewer.continue_turn(&mut state, &route(&back), &route(&back), &mut |_| {}, &mut |_, _| {}).await,
            Err(InterviewError::NothingToReplyTo)
        ));
    }

    #[tokio::test]
    async fn user_edits_turn_extractions_into_suggestions() {
        let pack = game();
        let router = router();
        let p = fake(
            "Nice.",
            json!({"updates": [{"field": "working_title", "value": "Hearth", "confidence": 0.9}], "flags": []}),
        );
        let interviewer = Interviewer::new(pack.as_ref(), &router, "Kickoff");
        let mut state = InterviewState::new(pack.as_ref());
        state.brief.set_user_value(pack.manifest(), "working_title", &json!("Ember Hollow"), 0).unwrap();
        let outcome = interviewer
            .run_turn(&mut state, "call it Hearth?", &route(&p), &route(&p), &mut |_| {}, &mut |_, _| {})
            .await
            .unwrap();
        assert_eq!(outcome.suggestions.len(), 1);
        assert_eq!(state.brief.field("working_title").unwrap().value, json!("Ember Hollow"));
    }

    #[tokio::test]
    async fn revisit_is_offered_once_and_complete_brief_changes_the_prompt() {
        let pack = game();
        let router = router();
        let p = fake("OK.", json!({"updates": [], "flags": []}));
        let interviewer = Interviewer::new(pack.as_ref(), &router, "Kickoff");
        let mut state = InterviewState::new(pack.as_ref());
        state.revisit = Some("pillars".into());
        interviewer
            .run_turn(&mut state, "let's revisit pillars", &route(&p), &route(&p), &mut |_| {}, &mut |_, _| {})
            .await
            .unwrap();
        let first = p.requests().into_iter().find(|r| !is_extraction(r)).unwrap();
        assert!(first.messages[0].content.contains("Design pillars: 3 to 5 short, specific principles that decide every design argument. (they asked to revisit this)"));
        assert_eq!(state.revisit, None);

        // Fill every required field; the producer is told the brief is ready.
        for def in pack.manifest().fields.iter().filter(|f| f.required) {
            let value = match &def.kind {
                FieldKind::Text => json!("x"),
                FieldKind::List { min_items, .. } => {
                    json!((0..(*min_items).max(1)).map(|i| format!("item {i}")).collect::<Vec<_>>())
                }
                FieldKind::Parts { parts } => Value::Object(parts.iter().map(|p| (p.id.clone(), json!("x"))).collect()),
                FieldKind::Choice { options } => json!({"choice": options[0].id, "reason": "x"}),
            };
            state.brief.set_user_value(pack.manifest(), &def.id, &value, 1).unwrap();
        }
        interviewer
            .run_turn(&mut state, "anything else?", &route(&p), &route(&p), &mut |_| {}, &mut |_, _| {})
            .await
            .unwrap();
        let last = p.requests().into_iter().rfind(|r| !is_extraction(r)).unwrap();
        assert!(last.messages[0].content.contains("complete enough to start building"));
        assert!(!last.messages[0].content.contains("Open topics"));
    }

    #[tokio::test]
    async fn empty_message_is_rejected() {
        let pack = game();
        let router = router();
        let p = fake("", json!({}));
        let interviewer = Interviewer::new(pack.as_ref(), &router, "Kickoff");
        let mut state = InterviewState::new(pack.as_ref());
        assert!(matches!(
            interviewer.run_turn(&mut state, "   ", &route(&p), &route(&p), &mut |_| {}, &mut |_, _| {}).await,
            Err(InterviewError::EmptyMessage)
        ));
        assert_eq!(state.turn, 0);
    }
}
