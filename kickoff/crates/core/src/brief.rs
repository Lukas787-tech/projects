//! The project brief: one entry per pack field, each with a value, a
//! confidence and a status. The interview fills it; the user can edit any
//! field, and user edits are never overwritten by the AI (they become
//! suggestions instead). Completeness is decided here, in code, not by a model.

use std::collections::BTreeMap;

use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use ts_rs::TS;

use crate::pack::{FieldDef, FieldKind, PackManifest};

/// Longest text kept for one field value.
const MAX_TEXT_CHARS: usize = 600;
/// Longest value shown per field in prompt summaries.
const SUMMARY_CHARS: usize = 220;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, TS)]
#[serde(rename_all = "snake_case")]
#[ts(export)]
pub enum FieldStatus {
    Empty,
    /// Filled, but not confident enough yet.
    Draft,
    Confirmed,
    /// Set by the user directly; the AI only suggests changes.
    UserEdited,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, TS)]
#[ts(export)]
pub struct FieldState {
    /// `null`, a string, a list of strings, `{part: text}` or `{choice, reason}`.
    #[ts(type = "unknown")]
    pub value: Value,
    pub confidence: f32,
    /// Per-part confidence for `parts` and `choice` fields.
    #[serde(default)]
    pub part_confidence: BTreeMap<String, f32>,
    pub status: FieldStatus,
    pub updated_turn: Option<u32>,
}

impl FieldState {
    fn empty() -> Self {
        Self {
            value: Value::Null,
            confidence: 0.0,
            part_confidence: BTreeMap::new(),
            status: FieldStatus::Empty,
            updated_turn: None,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, TS)]
#[ts(export)]
pub struct Brief {
    pub schema: String,
    pub fields: BTreeMap<String, FieldState>,
}

/// One change proposed by the extractor, addressed by field id or
/// `<field>.<part>`.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct FieldUpdate {
    pub field: String,
    pub value: Value,
    pub confidence: f32,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, TS)]
#[serde(rename_all = "snake_case")]
#[ts(export)]
pub enum FlagKind {
    /// Too big for the team and time.
    Scope,
    /// Conflicts with something already in the brief.
    Contradiction,
    /// Too generic to guide decisions.
    Vague,
}

/// A problem the producer should raise.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, TS)]
#[ts(export)]
pub struct Flag {
    pub kind: FlagKind,
    #[serde(default)]
    pub field: Option<String>,
    pub note: String,
}

/// The extractor's reply.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct Extraction {
    #[serde(default)]
    pub updates: Vec<FieldUpdate>,
    #[serde(default)]
    pub flags: Vec<Flag>,
}

/// An AI-proposed change to a field the user edited themselves.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, TS)]
#[ts(export)]
pub struct Suggestion {
    pub field: String,
    #[ts(type = "unknown")]
    pub value: Value,
}

#[derive(Debug, Clone, Default, PartialEq)]
pub struct ApplyReport {
    /// Field ids whose value changed.
    pub changed: Vec<String>,
    pub suggestions: Vec<Suggestion>,
    /// Updates that couldn't be used (unknown field, wrong shape).
    pub ignored: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, Serialize, TS)]
#[ts(export)]
pub struct Completeness {
    /// Share of required fields that are settled, 0 to 1.
    pub ratio: f32,
    pub complete: bool,
    /// Required fields with no answer yet.
    pub missing: Vec<String>,
    /// Required fields with an answer that isn't settled yet.
    pub weak: Vec<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, TS)]
#[serde(rename_all = "snake_case")]
#[ts(export)]
pub enum FocusReason {
    Revisit,
    Missing,
    Weak,
    Optional,
}

#[derive(Debug, Clone, PartialEq, Serialize, TS)]
#[ts(export)]
pub struct Focus {
    pub field: String,
    pub label: String,
    pub hint: String,
    pub reason: FocusReason,
}

#[derive(Debug, Clone, PartialEq, thiserror::Error)]
pub enum BriefError {
    #[error("unknown field `{0}`")]
    UnknownField(String),
    #[error("`{field}`: {problem}")]
    BadValue { field: String, problem: String },
}

fn clamp_confidence(c: f32) -> f32 {
    if c.is_finite() { c.clamp(0.0, 1.0) } else { 0.5 }
}

fn clean_text(v: &Value) -> Option<String> {
    let text = match v {
        Value::String(s) => s.trim().to_owned(),
        Value::Array(items) => items.iter().filter_map(Value::as_str).map(str::trim).collect::<Vec<_>>().join("; "),
        Value::Number(n) => n.to_string(),
        _ => return None,
    };
    if text.is_empty() {
        return None;
    }
    Some(match text.char_indices().nth(MAX_TEXT_CHARS) {
        Some((i, _)) => text[..i].to_owned(),
        None => text,
    })
}

fn clean_list(v: &Value, max_items: u32) -> Option<Vec<String>> {
    let raw: Vec<String> = match v {
        Value::Array(items) => items.iter().filter_map(clean_text_item).collect(),
        Value::String(_) => clean_text(v).into_iter().collect(),
        _ => return None,
    };
    let mut seen = std::collections::HashSet::new();
    let items: Vec<String> =
        raw.into_iter().filter(|s| seen.insert(s.to_lowercase())).take(max_items as usize).collect();
    (!items.is_empty()).then_some(items)
}

fn clean_text_item(v: &Value) -> Option<String> {
    clean_text(v).map(|s| s.trim_start_matches(['-', '*', '•', ' ']).to_owned()).filter(|s| !s.is_empty())
}

/// Match a choice by option id or label, case-insensitively.
fn match_option(def: &FieldDef, raw: &str) -> Option<String> {
    let FieldKind::Choice { options } = &def.kind else { return None };
    let raw = raw.trim().to_lowercase();
    options
        .iter()
        .find(|o| o.id == raw || o.label.to_lowercase() == raw || o.label.to_lowercase().starts_with(&raw))
        .map(|o| o.id.clone())
}

impl Brief {
    pub fn new(pack: &PackManifest) -> Self {
        Self {
            schema: pack.brief_schema.clone(),
            fields: pack.fields.iter().map(|f| (f.id.clone(), FieldState::empty())).collect(),
        }
    }

    pub fn field(&self, id: &str) -> Option<&FieldState> {
        self.fields.get(id)
    }

    /// Apply the extractor's updates for one turn.
    pub fn apply_extraction(&mut self, pack: &PackManifest, updates: &[FieldUpdate], turn: u32) -> ApplyReport {
        let mut report = ApplyReport::default();
        for update in updates {
            let (field_id, part) = match update.field.split_once('.') {
                Some((f, p)) => (f, Some(p)),
                None => (update.field.as_str(), None),
            };
            let Some(def) = pack.field(field_id) else {
                report.ignored.push(update.field.clone());
                continue;
            };
            let confidence = clamp_confidence(update.confidence);
            let state = self.fields.entry(def.id.clone()).or_insert_with(FieldState::empty);

            let Some(new_value) = next_value(def, &state.value, part, &update.value) else {
                report.ignored.push(update.field.clone());
                continue;
            };
            if state.status == FieldStatus::UserEdited {
                if new_value != state.value {
                    report.suggestions.push(Suggestion { field: def.id.clone(), value: new_value });
                }
                continue;
            }
            let unchanged = new_value == state.value;
            match &def.kind {
                FieldKind::Text | FieldKind::List { .. } => {
                    state.confidence = if unchanged { state.confidence.max(confidence) } else { confidence };
                }
                FieldKind::Parts { .. } | FieldKind::Choice { .. } => {
                    let key = part.unwrap_or("choice").to_owned();
                    let previous = state.part_confidence.get(&key).copied().unwrap_or(0.0);
                    state.part_confidence.insert(key, if unchanged { previous.max(confidence) } else { confidence });
                    state.confidence = sub_confidence(def, state);
                }
            }
            if !unchanged {
                state.value = new_value;
                state.updated_turn = Some(turn);
                report.changed.push(def.id.clone());
            }
            state.status = status_for(state.confidence, pack.confidence_threshold);
        }
        report.changed.dedup();
        report
    }

    /// Set a field from the user's own edit. `value` is the whole field
    /// (string, list, `{part: text}` or `{choice, reason}`); `null` clears it.
    pub fn set_user_value(
        &mut self,
        pack: &PackManifest,
        field: &str,
        value: &Value,
        turn: u32,
    ) -> Result<(), BriefError> {
        let def = pack.field(field).ok_or_else(|| BriefError::UnknownField(field.to_owned()))?;
        let bad = |problem: &str| BriefError::BadValue { field: field.to_owned(), problem: problem.to_owned() };
        let state = self.fields.entry(def.id.clone()).or_insert_with(FieldState::empty);
        if value.is_null() {
            *state = FieldState::empty();
            return Ok(());
        }
        let normalized = match &def.kind {
            FieldKind::Text => Value::String(clean_text(value).ok_or_else(|| bad("expected text"))?),
            FieldKind::List { max_items, .. } => {
                json!(clean_list(value, *max_items).ok_or_else(|| bad("expected a list of items"))?)
            }
            FieldKind::Parts { parts } => {
                let obj = value.as_object().ok_or_else(|| bad("expected one answer per part"))?;
                let mut out = serde_json::Map::new();
                for p in parts {
                    if let Some(text) = obj.get(&p.id).and_then(clean_text) {
                        out.insert(p.id.clone(), Value::String(text));
                    }
                }
                Value::Object(out)
            }
            FieldKind::Choice { .. } => {
                let raw = value.get("choice").and_then(Value::as_str).ok_or_else(|| bad("expected a choice"))?;
                let choice = match_option(def, raw).ok_or_else(|| bad("not one of the options"))?;
                let reason = value.get("reason").and_then(clean_text);
                json!({ "choice": choice, "reason": reason })
            }
        };
        state.part_confidence = match &def.kind {
            FieldKind::Parts { parts } => {
                parts.iter().filter(|p| normalized.get(&p.id).is_some()).map(|p| (p.id.clone(), 1.0)).collect()
            }
            FieldKind::Choice { .. } => BTreeMap::from([("choice".to_owned(), 1.0), ("reason".to_owned(), 1.0)]),
            _ => BTreeMap::new(),
        };
        state.value = normalized;
        state.confidence = 1.0;
        state.status = FieldStatus::UserEdited;
        state.updated_turn = Some(turn);
        Ok(())
    }

    /// Whether a field's answer is good enough to build on.
    pub fn is_settled(&self, def: &FieldDef, threshold: f32) -> bool {
        let Some(state) = self.fields.get(&def.id) else { return false };
        if is_empty_value(def, &state.value) {
            return false;
        }
        if let FieldKind::List { min_items, .. } = def.kind
            && state.value.as_array().map_or(0, Vec::len) < min_items as usize
        {
            return false;
        }
        state.status == FieldStatus::UserEdited || state.confidence >= threshold
    }

    pub fn completeness(&self, pack: &PackManifest) -> Completeness {
        let required: Vec<&FieldDef> = pack.fields_by_priority().into_iter().filter(|f| f.required).collect();
        let mut missing = Vec::new();
        let mut weak = Vec::new();
        for def in &required {
            let empty = self.fields.get(&def.id).is_none_or(|s| is_empty_value(def, &s.value));
            if empty {
                missing.push(def.id.clone());
            } else if !self.is_settled(def, pack.confidence_threshold) {
                weak.push(def.id.clone());
            }
        }
        let settled = required.len() - missing.len() - weak.len();
        let ratio = if required.is_empty() { 1.0 } else { settled as f32 / required.len() as f32 };
        Completeness { ratio, complete: missing.is_empty() && weak.is_empty(), missing, weak }
    }

    /// The next `n` fields worth exploring, most important first.
    pub fn open_topics(&self, pack: &PackManifest, revisit: Option<&str>, n: usize) -> Vec<Focus> {
        let mut topics: Vec<Focus> = Vec::new();
        let mut skip: Vec<String> = Vec::new();
        while topics.len() < n {
            let mut probe = self.clone();
            // Pretend already-listed fields are settled so the next one surfaces.
            for id in &skip {
                if let (Some(state), Some(def)) = (probe.fields.get_mut(id), pack.field(id)) {
                    state.status = FieldStatus::UserEdited;
                    state.value = placeholder_value(def);
                }
            }
            let revisit = revisit.filter(|r| !skip.iter().any(|s| s == r));
            match probe.focus(pack, revisit) {
                Some(f) => {
                    skip.push(f.field.clone());
                    topics.push(f);
                }
                None => break,
            }
        }
        topics
    }

    /// The field the producer should explore next.
    pub fn focus(&self, pack: &PackManifest, revisit: Option<&str>) -> Option<Focus> {
        let make = |def: &FieldDef, reason| Focus {
            field: def.id.clone(),
            label: def.label.clone(),
            hint: def.hint.clone(),
            reason,
        };
        if let Some(def) = revisit.and_then(|id| pack.field(id)) {
            return Some(make(def, FocusReason::Revisit));
        }
        let c = self.completeness(pack);
        if let Some(def) = c.missing.first().and_then(|id| pack.field(id)) {
            return Some(make(def, FocusReason::Missing));
        }
        if let Some(def) = c.weak.first().and_then(|id| pack.field(id)) {
            return Some(make(def, FocusReason::Weak));
        }
        pack.fields_by_priority()
            .into_iter()
            .find(|f| !f.required && self.fields.get(&f.id).is_none_or(|s| is_empty_value(f, &s.value)))
            .map(|def| make(def, FocusReason::Optional))
    }

    /// Compact text view for prompts.
    pub fn summary(&self, pack: &PackManifest) -> String {
        pack.fields_by_priority()
            .into_iter()
            .map(|def| {
                let state = self.fields.get(&def.id);
                let shown = state.map(|s| render_value(def, &s.value)).filter(|s| !s.is_empty());
                let note = match (state, &shown) {
                    (_, None) => "(not discussed yet)".to_owned(),
                    (Some(s), Some(_))
                        if !self.is_settled(def, pack.confidence_threshold) && s.status != FieldStatus::UserEdited =>
                    {
                        " (tentative)".to_owned()
                    }
                    _ => String::new(),
                };
                format!("- {}: {}{}", def.label, shown.map(|s| truncate(&s, SUMMARY_CHARS)).unwrap_or_default(), note)
            })
            .collect::<Vec<_>>()
            .join("\n")
    }

    /// The brief as plain values, `{field: value}`, for export and the kit.
    pub fn values(&self) -> Value {
        Value::Object(self.fields.iter().map(|(k, v)| (k.clone(), v.value.clone())).collect())
    }

    /// Field ids whose value differs from `other`.
    pub fn diff(&self, other: &Brief) -> Vec<String> {
        self.fields
            .iter()
            .filter(|(k, v)| other.fields.get(*k).is_none_or(|o| o.value != v.value || o.status != v.status))
            .map(|(k, _)| k.clone())
            .collect()
    }
}

/// A value that counts as filled and settled for `def` (used to look past a field).
fn placeholder_value(def: &FieldDef) -> Value {
    match &def.kind {
        FieldKind::Text => json!("listed"),
        FieldKind::List { min_items, .. } => json!(vec!["listed"; (*min_items).max(1) as usize]),
        FieldKind::Parts { parts } => Value::Object(parts.iter().map(|p| (p.id.clone(), json!("listed"))).collect()),
        FieldKind::Choice { options } => json!({ "choice": options[0].id, "reason": "listed" }),
    }
}

fn status_for(confidence: f32, threshold: f32) -> FieldStatus {
    if confidence >= threshold { FieldStatus::Confirmed } else { FieldStatus::Draft }
}

/// The field value after applying one update, or `None` if the update doesn't fit.
fn next_value(def: &FieldDef, current: &Value, part: Option<&str>, raw: &Value) -> Option<Value> {
    match (&def.kind, part) {
        (FieldKind::Text, None) => clean_text(raw).map(Value::String),
        (FieldKind::List { max_items, .. }, None) => clean_list(raw, *max_items).map(|items| json!(items)),
        (FieldKind::Parts { parts }, Some(part)) => {
            let part = parts.iter().find(|p| p.id == part)?;
            let text = clean_text(raw)?;
            let mut obj = current.as_object().cloned().unwrap_or_default();
            obj.insert(part.id.clone(), Value::String(text));
            Some(Value::Object(obj))
        }
        (FieldKind::Choice { .. }, None) => {
            let choice = match_option(def, raw.as_str()?)?;
            let mut obj = current.as_object().cloned().unwrap_or_default();
            obj.insert("choice".into(), Value::String(choice));
            obj.entry("reason").or_insert(Value::Null);
            Some(Value::Object(obj))
        }
        (FieldKind::Choice { .. }, Some("reason")) => {
            let mut obj = current.as_object().cloned().unwrap_or_default();
            obj.insert("reason".into(), Value::String(clean_text(raw)?));
            obj.entry("choice").or_insert(Value::Null);
            Some(Value::Object(obj))
        }
        _ => None,
    }
}

/// Field confidence for parts/choice: the weakest required sub-answer.
fn sub_confidence(def: &FieldDef, state: &FieldState) -> f32 {
    let keys: Vec<&str> = match &def.kind {
        FieldKind::Parts { parts } => parts.iter().map(|p| p.id.as_str()).collect(),
        FieldKind::Choice { .. } => vec!["choice"],
        _ => return state.confidence,
    };
    keys.iter().map(|k| state.part_confidence.get(*k).copied().unwrap_or(0.0)).fold(1.0, f32::min)
}

fn is_empty_value(def: &FieldDef, value: &Value) -> bool {
    match &def.kind {
        FieldKind::Text => value.as_str().is_none_or(|s| s.trim().is_empty()),
        FieldKind::List { .. } => value.as_array().is_none_or(Vec::is_empty),
        FieldKind::Parts { parts } => parts.iter().all(|p| value.get(&p.id).and_then(Value::as_str).is_none()),
        FieldKind::Choice { .. } => value.get("choice").and_then(Value::as_str).is_none(),
    }
}

/// Human-readable value text (also used by the UI via the same rules).
pub fn render_value(def: &FieldDef, value: &Value) -> String {
    match &def.kind {
        FieldKind::Text => value.as_str().unwrap_or_default().to_owned(),
        FieldKind::List { .. } => value
            .as_array()
            .map(|items| items.iter().filter_map(Value::as_str).collect::<Vec<_>>().join("; "))
            .unwrap_or_default(),
        FieldKind::Parts { parts } => parts
            .iter()
            .filter_map(|p| {
                value.get(&p.id).and_then(Value::as_str).map(|t| format!("{}: {t}", p.label.to_lowercase()))
            })
            .collect::<Vec<_>>()
            .join("; "),
        FieldKind::Choice { options } => {
            let choice = value.get("choice").and_then(Value::as_str);
            let label = choice.and_then(|c| options.iter().find(|o| o.id == c)).map(|o| o.label.as_str());
            match (label, value.get("reason").and_then(Value::as_str)) {
                (Some(l), Some(r)) => format!("{l} ({r})"),
                (Some(l), None) => l.to_owned(),
                _ => String::new(),
            }
        }
    }
}

fn truncate(text: &str, max: usize) -> String {
    match text.char_indices().nth(max) {
        Some((i, _)) => format!("{}…", &text[..i]),
        None => text.to_owned(),
    }
}

/// One line per addressable id, for the extractor prompt.
pub fn field_catalog(pack: &PackManifest) -> String {
    let mut lines = Vec::new();
    for def in pack.fields_by_priority() {
        match &def.kind {
            FieldKind::Text => lines.push(format!("- {}: {} (text). {}", def.id, def.label, def.hint)),
            FieldKind::List { min_items, max_items } => lines.push(format!(
                "- {}: {} (list of {min_items} to {max_items} short items). {}",
                def.id, def.label, def.hint
            )),
            FieldKind::Parts { parts } => {
                for p in parts {
                    lines.push(format!("- {}.{}: {}, {} (text)", def.id, p.id, def.label, p.label.to_lowercase()));
                }
            }
            FieldKind::Choice { options } => {
                let ids: Vec<&str> = options.iter().map(|o| o.id.as_str()).collect();
                lines.push(format!("- {}: {} (one of: {})", def.id, def.label, ids.join(", ")));
                lines.push(format!("- {}.reason: why that {} (text)", def.id, def.label.to_lowercase()));
            }
        }
    }
    lines.join("\n")
}

/// JSON Schema for the extractor's reply.
pub fn extraction_schema(pack: &PackManifest) -> Value {
    let ids: Vec<String> = pack.fields.iter().flat_map(FieldDef::addressable_ids).collect();
    json!({
        "type": "object",
        "required": ["updates", "flags"],
        "additionalProperties": false,
        "properties": {
            "updates": {
                "type": "array",
                "items": {
                    "type": "object",
                    "required": ["field", "value", "confidence"],
                    "additionalProperties": false,
                    "properties": {
                        "field": { "type": "string", "enum": ids },
                        "value": { "anyOf": [ { "type": "string" }, { "type": "array", "items": { "type": "string" } } ] },
                        "confidence": { "type": "number", "minimum": 0, "maximum": 1 }
                    }
                }
            },
            "flags": {
                "type": "array",
                "items": {
                    "type": "object",
                    "required": ["kind", "note"],
                    "additionalProperties": false,
                    "properties": {
                        "kind": { "type": "string", "enum": ["scope", "contradiction", "vague"] },
                        "field": { "type": "string" },
                        "note": { "type": "string" }
                    }
                }
            }
        }
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::pack;

    fn game() -> PackManifest {
        pack::find("game").unwrap().manifest().clone()
    }

    fn up(field: &str, value: Value, confidence: f32) -> FieldUpdate {
        FieldUpdate { field: field.into(), value, confidence }
    }

    #[test]
    fn new_brief_is_empty_and_incomplete() {
        let pack = game();
        let brief = Brief::new(&pack);
        let c = brief.completeness(&pack);
        assert!(!c.complete);
        assert_eq!(c.ratio, 0.0);
        assert_eq!(c.missing.first().map(String::as_str), Some("working_title"));
        assert_eq!(brief.focus(&pack, None).unwrap().field, "working_title");
    }

    #[test]
    fn applies_text_list_parts_and_choice_updates() {
        let pack = game();
        let mut brief = Brief::new(&pack);
        let report = brief.apply_extraction(
            &pack,
            &[
                up("working_title", json!("  Hearth  "), 0.9),
                up("pillars", json!(["- Cozy", "Craft with intent", "cozy", "Seasons matter"]), 0.9),
                up("core_loop.moment_to_moment", json!("Gather and craft"), 0.9),
                up("engine", json!("Godot"), 0.9),
                up("engine.reason", json!("free and light for 2D"), 0.9),
                up("nonsense", json!("x"), 0.9),
                up("core_loop", json!("ambiguous whole-parts update"), 0.9),
            ],
            1,
        );
        assert_eq!(report.ignored, ["nonsense", "core_loop"]);
        assert_eq!(brief.field("working_title").unwrap().value, json!("Hearth"));
        assert_eq!(
            brief.field("pillars").unwrap().value,
            json!(["Cozy", "Craft with intent", "Seasons matter"]),
            "bullets stripped, duplicates dropped"
        );
        let loop_state = brief.field("core_loop").unwrap();
        assert_eq!(loop_state.value, json!({"moment_to_moment": "Gather and craft"}));
        assert_eq!(loop_state.confidence, 0.0, "other parts still missing");
        assert_eq!(loop_state.status, FieldStatus::Draft);
        assert_eq!(brief.field("engine").unwrap().value, json!({"choice": "godot", "reason": "free and light for 2D"}));
        assert_eq!(brief.field("engine").unwrap().status, FieldStatus::Confirmed);
    }

    #[test]
    fn lists_below_minimum_are_not_settled() {
        let pack = game();
        let mut brief = Brief::new(&pack);
        brief.apply_extraction(&pack, &[up("pillars", json!(["Cozy", "Crafting"]), 0.95)], 1);
        let c = brief.completeness(&pack);
        assert!(c.weak.contains(&"pillars".to_owned()));
    }

    #[test]
    fn user_edits_win_and_ai_changes_become_suggestions() {
        let pack = game();
        let mut brief = Brief::new(&pack);
        brief.set_user_value(&pack, "working_title", &json!("Ember Hollow"), 2).unwrap();
        let report = brief.apply_extraction(&pack, &[up("working_title", json!("Hearth"), 0.9)], 3);
        assert_eq!(brief.field("working_title").unwrap().value, json!("Ember Hollow"));
        assert_eq!(report.suggestions, [Suggestion { field: "working_title".into(), value: json!("Hearth") }]);
        assert!(report.changed.is_empty());
    }

    #[test]
    fn user_edit_validation() {
        let pack = game();
        let mut brief = Brief::new(&pack);
        assert!(matches!(brief.set_user_value(&pack, "nope", &json!("x"), 1), Err(BriefError::UnknownField(_))));
        assert!(brief.set_user_value(&pack, "engine", &json!({"choice": "unreal"}), 1).is_err());
        brief.set_user_value(&pack, "engine", &json!({"choice": "Unity", "reason": "I know C#"}), 1).unwrap();
        assert_eq!(brief.field("engine").unwrap().value, json!({"choice": "unity", "reason": "I know C#"}));
        brief.set_user_value(&pack, "feeling", &json!({"during": "calm", "after": "rested", "extra": "x"}), 1).unwrap();
        assert_eq!(brief.field("feeling").unwrap().value, json!({"during": "calm", "after": "rested"}));
        brief.set_user_value(&pack, "feeling", &Value::Null, 1).unwrap();
        assert_eq!(brief.field("feeling").unwrap().status, FieldStatus::Empty);
    }

    #[test]
    fn low_confidence_stays_draft_and_repeats_raise_confidence() {
        let pack = game();
        let mut brief = Brief::new(&pack);
        brief.apply_extraction(&pack, &[up("genre", json!("cozy survival"), 0.3)], 1);
        assert_eq!(brief.field("genre").unwrap().status, FieldStatus::Draft);
        let report = brief.apply_extraction(&pack, &[up("genre", json!("cozy survival"), 0.9)], 2);
        assert!(report.changed.is_empty(), "same value isn't a change");
        assert_eq!(brief.field("genre").unwrap().status, FieldStatus::Confirmed);
        // A lower-confidence repeat doesn't undo it.
        brief.apply_extraction(&pack, &[up("genre", json!("cozy survival"), 0.3)], 3);
        assert_eq!(brief.field("genre").unwrap().status, FieldStatus::Confirmed);
    }

    #[test]
    fn complete_when_every_required_field_is_settled() {
        let pack = game();
        let mut brief = Brief::new(&pack);
        let updates = vec![
            up("working_title", json!("Hearth"), 0.9),
            up("pitch", json!("Survive a gentle winter by crafting a home"), 0.9),
            up("genre", json!("cozy survival"), 0.9),
            up("target_player", json!("Stardew fans"), 0.9),
            up("pillars", json!(["Cozy", "Craft", "Seasons"]), 0.9),
            up("core_loop.moment_to_moment", json!("gather"), 0.9),
            up("core_loop.session", json!("prepare for night"), 0.9),
            up("core_loop.long_term", json!("grow the village"), 0.9),
            up("feeling.during", json!("calm"), 0.9),
            up("feeling.after", json!("rested"), 0.9),
            up("scope", json!("solo, 6 months, 1 biome"), 0.9),
            up("platforms", json!(["Windows"]), 0.9),
            up("engine", json!("godot"), 0.9),
            up("art_direction", json!("soft low-poly"), 0.9),
            up("audio_direction", json!("acoustic"), 0.9),
            up("out_of_scope", json!(["multiplayer"]), 0.9),
            up("risks", json!(["scope creep"]), 0.9),
        ];
        brief.apply_extraction(&pack, &updates, 1);
        let c = brief.completeness(&pack);
        assert!(c.complete, "missing {:?} weak {:?}", c.missing, c.weak);
        assert_eq!(c.ratio, 1.0);
        // Optional fields come next.
        let focus = brief.focus(&pack, None).unwrap();
        assert_eq!((focus.field.as_str(), focus.reason), ("open_questions", FocusReason::Optional));
        assert_eq!(brief.focus(&pack, Some("pillars")).unwrap().reason, FocusReason::Revisit);
    }

    #[test]
    fn open_topics_lists_the_next_fields_in_priority_order() {
        let pack = game();
        let brief = Brief::new(&pack);
        let topics: Vec<_> = brief.open_topics(&pack, None, 2).into_iter().map(|t| t.field).collect();
        assert_eq!(topics, ["working_title", "pitch"]);
        let topics: Vec<_> =
            brief.open_topics(&pack, Some("engine"), 3).into_iter().map(|t| (t.field, t.reason)).collect();
        assert_eq!(
            topics,
            [
                ("engine".to_owned(), FocusReason::Revisit),
                ("working_title".to_owned(), FocusReason::Missing),
                ("pitch".to_owned(), FocusReason::Missing)
            ]
        );
    }

    #[test]
    fn summary_and_catalog_are_compact() {
        let pack = game();
        let mut brief = Brief::new(&pack);
        brief.apply_extraction(
            &pack,
            &[up("working_title", json!("Hearth"), 0.9), up("genre", json!("survival"), 0.3)],
            1,
        );
        let summary = brief.summary(&pack);
        assert!(summary.contains("- Working title: Hearth\n"));
        assert!(summary.contains("- Genre: survival (tentative)"));
        assert!(summary.contains("- Design pillars: (not discussed yet)"));
        let catalog = field_catalog(&pack);
        assert!(catalog.contains("- core_loop.session: Core loop, one session (text)"));
        assert!(catalog.contains("- engine: Engine (one of: godot, unity, web)"));
    }

    #[test]
    fn extraction_schema_validates_typical_output() {
        let pack = game();
        let schema = extraction_schema(&pack);
        let validator = jsonschema::validator_for(&schema).unwrap();
        assert!(validator.is_valid(&json!({
            "updates": [{"field": "pillars", "value": ["a", "b"], "confidence": 0.9}],
            "flags": [{"kind": "scope", "note": "an MMO is too big for one person"}]
        })));
        assert!(
            !validator
                .is_valid(&json!({"updates": [{"field": "made_up", "value": "x", "confidence": 1}], "flags": []}))
        );
    }

    #[test]
    fn diff_lists_changed_fields() {
        let pack = game();
        let before = Brief::new(&pack);
        let mut after = before.clone();
        after.apply_extraction(&pack, &[up("genre", json!("roguelike"), 0.9)], 1);
        assert_eq!(after.diff(&before), ["genre"]);
    }
}
