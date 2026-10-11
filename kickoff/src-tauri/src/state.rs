//! Shared app state, created once at startup.

use std::sync::{Arc, RwLock};

use kickoff_platform::{AppPaths, MemorySecretStore, OsSecretStore, SecretStore};
use kickoff_providers::Presets;
use kickoff_store::Database;

pub struct AppState {
    pub paths: AppPaths,
    pub db: Arc<Database>,
    pub presets: RwLock<Arc<Presets>>,
    #[allow(dead_code)] // read by the key library commands (M1)
    pub secrets: Arc<dyn SecretStore>,
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

        Ok(Self { paths, db, presets: RwLock::new(presets), secrets, secret_store_problem })
    }

    pub fn presets(&self) -> Arc<Presets> {
        self.presets.read().unwrap_or_else(|e| e.into_inner()).clone()
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
