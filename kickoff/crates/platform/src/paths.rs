//! Where Kickoff keeps its files.

use std::io;
use std::path::{Path, PathBuf};

use directories::{BaseDirs, UserDirs};
use kickoff_core::{APP_ID, APP_NAME};

/// Resolved locations for app data. On Windows the root is
/// `%APPDATA%\<APP_ID>`, the same folder Tauri uses for the app.
#[derive(Debug, Clone)]
pub struct AppPaths {
    pub data_dir: PathBuf,
    pub log_dir: PathBuf,
    pub cache_dir: PathBuf,
    pub models_dir: PathBuf,
    pub db_path: PathBuf,
}

impl AppPaths {
    /// Paths under the user's standard data directory.
    pub fn resolve() -> io::Result<Self> {
        let base = BaseDirs::new().ok_or_else(|| io::Error::new(io::ErrorKind::NotFound, "no home directory"))?;
        Ok(Self::under(&base.data_dir().join(APP_ID)))
    }

    /// Paths under an explicit root (tests, portable mode).
    pub fn under(root: &Path) -> Self {
        Self {
            data_dir: root.to_path_buf(),
            log_dir: root.join("logs"),
            cache_dir: root.join("cache"),
            models_dir: root.join("models"),
            db_path: root.join(format!("{APP_ID}.sqlite3")),
        }
    }

    pub fn create_all(&self) -> io::Result<()> {
        for dir in [&self.data_dir, &self.log_dir, &self.cache_dir, &self.models_dir] {
            std::fs::create_dir_all(dir)?;
        }
        Ok(())
    }

    /// Optional user override for the bundled provider presets.
    pub fn provider_overrides(&self) -> PathBuf {
        self.data_dir.join("providers.override.toml")
    }
}

/// Default folder for generated projects: `Documents\<APP_NAME> Projects`.
/// Only a suggestion; the user's choice is stored in settings.
pub fn default_projects_dir() -> Option<PathBuf> {
    let user = UserDirs::new()?;
    let docs = user.document_dir().map(Path::to_path_buf).unwrap_or_else(|| user.home_dir().to_path_buf());
    Some(docs.join(format!("{APP_NAME} Projects")))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn layout_under_root() {
        let p = AppPaths::under(Path::new("/tmp/x"));
        assert_eq!(p.log_dir, Path::new("/tmp/x/logs"));
        assert!(p.db_path.file_name().unwrap().to_string_lossy().starts_with(APP_ID));
    }

    #[test]
    fn create_all_makes_dirs() {
        let root = std::env::temp_dir().join(format!("kickoff-paths-{}", uuid::Uuid::new_v4()));
        let p = AppPaths::under(&root);
        p.create_all().unwrap();
        assert!(p.models_dir.is_dir());
        std::fs::remove_dir_all(root).unwrap();
    }
}
