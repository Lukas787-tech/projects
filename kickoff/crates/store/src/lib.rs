//! SQLite persistence.
//!
//! One connection behind a mutex; callers on async code use `spawn_blocking`.
//! The schema is managed with forward-only migrations in `src/migrations/`.

use std::path::Path;
use std::sync::{Mutex, MutexGuard};

use rusqlite::Connection;
use rusqlite_migration::{M, Migrations};

pub mod keys;
pub mod settings;
pub mod time;
pub mod usage;

pub use keys::NewKeyMeta;
pub use usage::{UsageDay, UsageDelta};

#[derive(Debug, thiserror::Error)]
pub enum StoreError {
    #[error(transparent)]
    Sqlite(#[from] rusqlite::Error),
    #[error("database migration failed: {0}")]
    Migration(#[from] rusqlite_migration::Error),
    #[error("stored JSON is invalid: {0}")]
    Json(#[from] serde_json::Error),
}

pub type Result<T> = std::result::Result<T, StoreError>;

fn migrations() -> Migrations<'static> {
    Migrations::new(vec![M::up(include_str!("migrations/001_initial.sql"))])
}

pub struct Database {
    conn: Mutex<Connection>,
}

impl Database {
    /// Open (or create) the database file and bring the schema up to date.
    pub fn open(path: &Path) -> Result<Self> {
        let conn = Connection::open(path)?;
        conn.pragma_update(None, "journal_mode", "WAL")?;
        Self::init(conn)
    }

    pub fn open_in_memory() -> Result<Self> {
        Self::init(Connection::open_in_memory()?)
    }

    fn init(mut conn: Connection) -> Result<Self> {
        conn.pragma_update(None, "foreign_keys", "ON")?;
        conn.pragma_update(None, "busy_timeout", 5000)?;
        migrations().to_latest(&mut conn)?;
        Ok(Self { conn: Mutex::new(conn) })
    }

    pub(crate) fn conn(&self) -> MutexGuard<'_, Connection> {
        // A panic while holding the lock leaves SQLite itself consistent.
        self.conn.lock().unwrap_or_else(|e| e.into_inner())
    }

    /// Current schema version (number of applied migrations).
    pub fn schema_version(&self) -> Result<usize> {
        let v: i64 = self.conn().pragma_query_value(None, "user_version", |r| r.get(0))?;
        Ok(usize::try_from(v).unwrap_or(0))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn migrations_are_valid() {
        migrations().validate().unwrap();
    }

    #[test]
    fn opens_file_database_and_migrates() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("test.sqlite3");
        let db = Database::open(&path).unwrap();
        assert_eq!(db.schema_version().unwrap(), 1);
        drop(db);
        // Re-opening an up-to-date database is a no-op.
        let db = Database::open(&path).unwrap();
        assert_eq!(db.schema_version().unwrap(), 1);
    }

    #[test]
    fn foreign_keys_are_enforced() {
        let db = Database::open_in_memory().unwrap();
        let err = db.conn().execute(
            "INSERT INTO messages (project_id, role, content, created_at) VALUES ('missing', 'user', 'hi', 'now')",
            [],
        );
        assert!(err.is_err());
    }
}
