//! Shared app state, created once at startup.

use std::sync::Arc;

use kickoff_platform::{AppPaths, MemorySecretStore, OsSecretStore, SecretStore};
use kickoff_providers::{Presets, SourceCatalog};
use kickoff_store::Database;

use crate::ai::{AiService, AppObserver};
use crate::interview::InterviewService;
use crate::keys::KeyLibrary;
use crate::research::{HttpThumbnails, KeyedSearch, ResearchService};
use crate::voice::VoiceService;

pub struct AppState {
    pub paths: AppPaths,
    pub db: Arc<Database>,
    pub presets: Arc<Presets>,
    pub keys: Arc<KeyLibrary>,
    pub ai: Arc<AiService>,
    pub observer: Arc<AppObserver>,
    pub interview: Arc<InterviewService>,
    pub research: Arc<ResearchService>,
    pub voice: Arc<VoiceService>,
    /// `None` when the OS credential store works; otherwise why it doesn't.
    pub secret_store_problem: Option<String>,
}

impl AppState {
    pub fn init(paths: AppPaths) -> anyhow::Result<Self> {
        paths.create_all()?;
        let db = Arc::new(Database::open(&paths.db_path)?);
        let presets = Arc::new(load_presets(&paths));

        let (secrets, secret_store_problem): (Arc<dyn SecretStore>, _) = match OsSecretStore::new() {
            Ok(store) => (Arc::new(store), None),
            Err(e) => {
                // Only happens off Windows (development). The memory store never
                // persists, so no secret is ever written anywhere in plain text.
                tracing::warn!(error = %e, "OS credential store unavailable; keys will not persist");
                (Arc::new(MemorySecretStore::new()), Some(e.to_string()))
            }
        };

        let keys = Arc::new(KeyLibrary::new(db.clone(), secrets));
        let observer = AppObserver::new(db.clone());
        let ai = Arc::new(AiService::new(db.clone(), presets.clone(), keys.clone(), observer.clone()));

        let interview = Arc::new(InterviewService::new(db.clone(), ai.clone()));
        let catalog = Arc::new(SourceCatalog::bundled().expect("the bundled asset catalog is validated by tests"));
        let research = Arc::new(ResearchService::new(
            db.clone(),
            ai.clone(),
            Arc::new(KeyedSearch::new(presets.clone(), keys.clone(), db.clone())),
            catalog.clone(),
            catalog.build(),
            Arc::new(HttpThumbnails::default()),
            paths.cache_dir.join("thumbnails"),
        ));
        let voice = Arc::new(VoiceService::new(db.clone(), presets.clone(), keys.clone(), &paths.models_dir));

        Ok(Self { paths, db, presets, keys, ai, observer, interview, research, voice, secret_store_problem })
    }
}

/// Bundled presets plus the user's override file, if present and valid.
/// A broken override is logged and ignored rather than blocking startup.
fn load_presets(paths: &AppPaths) -> Presets {
    let override_path = paths.provider_overrides();
    let overrides = std::fs::read_to_string(&override_path).ok();
    if let Some(text) = overrides.as_deref() {
        match Presets::parse(kickoff_providers::presets::BUNDLED, Some(text)) {
            Ok(p) => return p,
            Err(e) => {
                tracing::error!(path = %override_path.display(), error = %e, "ignoring invalid provider override file")
            }
        }
    }
    Presets::bundled().expect("bundled presets are validated by tests")
}
