//! Project type packs: the data that makes the interview (and later the
//! research and starter kit) specific to a kind of project.
//!
//! Bundled packs live in `packs/<id>/` and are compiled in. The engine never
//! hard-codes game concepts; it reads fields and prompts from the pack.

use std::collections::HashSet;
use std::sync::{Arc, OnceLock};

use include_dir::{Dir, include_dir};
use serde::{Deserialize, Serialize};
use ts_rs::TS;

static BUNDLED: Dir<'_> = include_dir!("$CARGO_MANIFEST_DIR/../../packs");

#[derive(Debug, thiserror::Error)]
pub enum PackError {
    #[error("pack `{0}` is missing {1}")]
    Missing(String, &'static str),
    #[error("pack `{0}` is invalid: {1}")]
    Invalid(String, String),
    #[error("pack template error: {0}")]
    Template(#[from] minijinja::Error),
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, TS)]
#[ts(export)]
pub struct Variant {
    pub id: String,
    pub name: String,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, TS)]
#[ts(export)]
pub struct NamedPart {
    pub id: String,
    pub label: String,
}

/// What shape a brief field's answer has.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, TS)]
#[serde(tag = "kind", rename_all = "snake_case")]
#[ts(export)]
pub enum FieldKind {
    /// A sentence or short paragraph.
    Text,
    /// Several short items.
    List {
        #[serde(default)]
        min_items: u32,
        #[serde(default = "default_max_items")]
        max_items: u32,
    },
    /// Named sub-answers, each addressable as `<field>.<part>`.
    Parts { parts: Vec<NamedPart> },
    /// One of a fixed set of options, plus a reason (`<field>.reason`).
    Choice { options: Vec<NamedPart> },
}

fn default_max_items() -> u32 {
    10
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, TS)]
#[ts(export)]
pub struct FieldDef {
    pub id: String,
    pub label: String,
    #[serde(default)]
    pub required: bool,
    pub priority: u32,
    #[serde(default)]
    pub hint: String,
    #[serde(flatten)]
    pub kind: FieldKind,
}

impl FieldDef {
    /// Ids the extractor may address for this field (`core_loop.session`, `engine.reason` …).
    pub fn addressable_ids(&self) -> Vec<String> {
        match &self.kind {
            FieldKind::Text | FieldKind::List { .. } => vec![self.id.clone()],
            FieldKind::Parts { parts } => parts.iter().map(|p| format!("{}.{}", self.id, p.id)).collect(),
            FieldKind::Choice { .. } => vec![self.id.clone(), format!("{}.reason", self.id)],
        }
    }
}

/// How research works for this project type.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, TS)]
#[ts(export)]
pub struct ResearchConfig {
    /// Comparable products the model suggests per run.
    pub comparables: u32,
    /// Web searches for mood and style references per run.
    pub visual_queries: u32,
    /// Asset searches per run.
    pub asset_queries: u32,
    /// Asset kinds worth searching for (matched against asset sources).
    pub asset_kinds: Vec<String>,
    /// Web query that checks a comparable exists; `{title}` is replaced.
    pub comparable_query: String,
    /// Brief fields references can refine.
    pub refine_fields: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, TS)]
#[ts(export)]
pub struct PackManifest {
    pub id: String,
    pub name: String,
    pub version: String,
    pub brief_schema: String,
    pub confidence_threshold: f32,
    pub variants: Vec<Variant>,
    pub fields: Vec<FieldDef>,
    pub research: ResearchConfig,
}

impl PackManifest {
    pub fn field(&self, id: &str) -> Option<&FieldDef> {
        self.fields.iter().find(|f| f.id == id)
    }

    /// Fields in the order the interview explores them.
    pub fn fields_by_priority(&self) -> Vec<&FieldDef> {
        let mut fields: Vec<&FieldDef> = self.fields.iter().collect();
        fields.sort_by_key(|f| f.priority);
        fields
    }
}

/// A project type: manifest plus prompt templates.
pub trait ProjectTypePack: Send + Sync {
    fn manifest(&self) -> &PackManifest;
    fn producer_template(&self) -> &str;
    fn extractor_template(&self) -> &str;
    /// Plans research queries from the brief.
    fn research_template(&self) -> &str;
    /// Proposes brief changes from the references the user kept and rejected.
    fn refine_template(&self) -> &str;
}

/// A pack defined entirely by files.
#[derive(Debug, Clone)]
pub struct DataPack {
    manifest: PackManifest,
    prompts: Prompts,
}

/// A pack's prompt templates, by file.
#[derive(Debug, Clone, Default)]
pub struct Prompts {
    pub producer: String,
    pub extractor: String,
    pub research: String,
    pub refine: String,
}

impl ProjectTypePack for DataPack {
    fn manifest(&self) -> &PackManifest {
        &self.manifest
    }
    fn producer_template(&self) -> &str {
        &self.prompts.producer
    }
    fn extractor_template(&self) -> &str {
        &self.prompts.extractor
    }
    fn research_template(&self) -> &str {
        &self.prompts.research
    }
    fn refine_template(&self) -> &str {
        &self.prompts.refine
    }
}

impl DataPack {
    /// Build a pack from its files' contents.
    pub fn from_sources(manifest_toml: &str, prompts: Prompts) -> Result<Self, PackError> {
        let manifest: PackManifest =
            toml::from_str(manifest_toml).map_err(|e| PackError::Invalid("?".into(), e.to_string()))?;
        validate(&manifest)?;
        // Fail early on template syntax errors.
        let env = minijinja::Environment::new();
        for template in [&prompts.producer, &prompts.extractor, &prompts.research, &prompts.refine] {
            env.template_from_str(template)?;
        }
        Ok(Self { manifest, prompts })
    }

    fn from_dir(dir: &Dir<'_>) -> Result<Self, PackError> {
        let name = dir.path().display().to_string();
        let file = |path: &str, what: &'static str| -> Result<&str, PackError> {
            dir.get_file(dir.path().join(path))
                .and_then(|f| f.contents_utf8())
                .ok_or_else(|| PackError::Missing(name.clone(), what))
        };
        let prompt = |name: &'static str| file(name, name).map(str::to_owned);
        Self::from_sources(
            file("pack.toml", "pack.toml")?,
            Prompts {
                producer: prompt("prompts/producer.md")?,
                extractor: prompt("prompts/extractor.md")?,
                research: prompt("prompts/research.md")?,
                refine: prompt("prompts/refine.md")?,
            },
        )
    }
}

fn validate(m: &PackManifest) -> Result<(), PackError> {
    let invalid = |msg: String| PackError::Invalid(m.id.clone(), msg);
    let valid_id =
        |s: &str| !s.is_empty() && s.chars().all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == '_');
    if !(0.0..=1.0).contains(&m.confidence_threshold) {
        return Err(invalid("confidence_threshold must be between 0 and 1".into()));
    }
    let mut seen = HashSet::new();
    for f in &m.fields {
        if !valid_id(&f.id) || !seen.insert(f.id.as_str()) {
            return Err(invalid(format!("field id `{}` is invalid or duplicated", f.id)));
        }
        match &f.kind {
            FieldKind::List { min_items, max_items } if min_items > max_items || *max_items == 0 => {
                return Err(invalid(format!("field `{}` has min_items > max_items", f.id)));
            }
            FieldKind::Parts { parts } if parts.is_empty() || parts.iter().any(|p| !valid_id(&p.id)) => {
                return Err(invalid(format!("field `{}` needs valid parts", f.id)));
            }
            FieldKind::Choice { options } if options.is_empty() || options.iter().any(|o| !valid_id(&o.id)) => {
                return Err(invalid(format!("field `{}` needs valid options", f.id)));
            }
            _ => {}
        }
    }
    if !m.fields.iter().any(|f| f.required) {
        return Err(invalid("at least one field must be required".into()));
    }
    let r = &m.research;
    if !r.comparable_query.contains("{title}") {
        return Err(invalid("research.comparable_query must contain {title}".into()));
    }
    // Proposals carry whole values, so only plain text and list fields qualify.
    let refinable =
        |id: &String| m.field(id).is_some_and(|f| matches!(f.kind, FieldKind::Text | FieldKind::List { .. }));
    if let Some(f) = r.refine_fields.iter().find(|f| !refinable(f)) {
        return Err(invalid(format!("research.refine_fields: `{f}` is not a text or list field")));
    }
    if r.asset_kinds.iter().any(|k| !valid_id(k)) {
        return Err(invalid("research.asset_kinds must be lowercase ids".into()));
    }
    Ok(())
}

/// All bundled packs, loaded once.
pub fn bundled() -> &'static [Arc<dyn ProjectTypePack>] {
    static PACKS: OnceLock<Vec<Arc<dyn ProjectTypePack>>> = OnceLock::new();
    PACKS.get_or_init(|| {
        BUNDLED
            .dirs()
            .map(|dir| {
                Arc::new(DataPack::from_dir(dir).expect("bundled packs are validated by tests"))
                    as Arc<dyn ProjectTypePack>
            })
            .collect()
    })
}

pub fn find(id: &str) -> Option<Arc<dyn ProjectTypePack>> {
    bundled().iter().find(|p| p.manifest().id == id).cloned()
}

/// Render a prompt template with the given context.
pub fn render(template: &str, context: impl Serialize) -> Result<String, PackError> {
    let env = minijinja::Environment::new();
    let rendered = env.render_str(template, context)?;
    // Collapse the blank lines conditional blocks leave behind.
    let mut out = String::with_capacity(rendered.len());
    let mut blank = 0;
    for line in rendered.lines() {
        if line.trim().is_empty() {
            blank += 1;
            if blank > 1 {
                continue;
            }
        } else {
            blank = 0;
        }
        out.push_str(line.trim_end());
        out.push('\n');
    }
    Ok(out.trim().to_owned())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn bundled_game_pack_loads() {
        for dir in BUNDLED.dirs() {
            DataPack::from_dir(dir).unwrap_or_else(|e| panic!("{}: {e}", dir.path().display()));
        }
        let game = find("game").expect("game pack is bundled");
        let m = game.manifest();
        assert_eq!(m.brief_schema, "game-brief/1");
        assert!(m.field("pillars").is_some_and(|f| matches!(f.kind, FieldKind::List { min_items: 3, max_items: 5 })));
        let engine = m.field("engine").unwrap();
        assert_eq!(engine.addressable_ids(), ["engine", "engine.reason"]);
        let variants: Vec<_> = m.variants.iter().map(|v| v.id.as_str()).collect();
        assert_eq!(variants, ["godot", "unity", "web"]);
        // Every engine option has a matching kit variant.
        if let FieldKind::Choice { options } = &engine.kind {
            assert!(options.iter().all(|o| variants.contains(&o.id.as_str())));
        }
    }

    /// Frontend tests and the browser preview render the real game pack.
    /// Regenerated by `cargo test`; CI fails if the committed copy is stale.
    #[test]
    fn export_game_pack_fixture() {
        let manifest = find("game").unwrap().manifest().clone();
        let json = serde_json::to_string_pretty(&manifest).unwrap() + "\n";
        let path = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../../src/bindings/fixtures/gamePack.json");
        std::fs::create_dir_all(path.parent().unwrap()).unwrap();
        if std::fs::read_to_string(&path).ok().as_deref() != Some(json.as_str()) {
            std::fs::write(&path, json).unwrap();
        }
    }

    #[test]
    fn rejects_bad_manifests() {
        let manifest = |min_items: u32, refine: &str| {
            format!(
                r#"
                id = "x"
                name = "X"
                version = "1"
                brief_schema = "x/1"
                confidence_threshold = 0.7
                variants = []
                [[fields]]
                id = "a"
                label = "A"
                kind = "list"
                required = true
                priority = 1
                min_items = {min_items}
                max_items = 2
                [research]
                comparables = 3
                visual_queries = 2
                asset_queries = 2
                asset_kinds = ["texture"]
                comparable_query = "{{title}} game"
                refine_fields = ["{refine}"]
                "#
            )
        };
        assert!(DataPack::from_sources(&manifest(1, "a"), Prompts::default()).is_ok());
        for bad in [manifest(5, "a"), manifest(1, "nope"), "not toml".into()] {
            assert!(matches!(DataPack::from_sources(&bad, Prompts::default()), Err(PackError::Invalid(..))), "{bad}");
        }
    }

    #[test]
    fn render_drops_empty_conditional_lines() {
        let out = render("A\n{% if x %}\nB\n{% endif %}\n\n\nC", serde_json::json!({ "x": false })).unwrap();
        assert_eq!(out, "A\n\nC");
    }
}
