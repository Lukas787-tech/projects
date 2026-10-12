//! The key library: metadata in SQLite, secret values in the OS credential
//! store, and every loaded value registered with the log redactor.

use std::sync::Arc;

use kickoff_core::keys::{KeyKind, KeyMeta, validate_env_var};
use kickoff_core::{Secret, redact};
use kickoff_platform::{SecretStore, SecretStoreError};
use kickoff_store::{Database, NewKeyMeta, StoreError};
use serde::{Deserialize, Serialize};
use ts_rs::TS;

/// Longest secret accepted (Windows Credential Manager stores up to 2,560 bytes).
const MAX_SECRET_LEN: usize = 2048;

#[derive(Debug, thiserror::Error)]
pub enum KeyError {
    #[error("{0}")]
    Invalid(String),
    #[error("key not found")]
    NotFound,
    #[error(transparent)]
    Store(#[from] StoreError),
    #[error(transparent)]
    SecretStore(#[from] SecretStoreError),
}

/// A key as shown in the UI.
#[derive(Debug, Clone, Serialize, TS)]
#[ts(export)]
pub struct KeyEntry {
    #[serde(flatten)]
    #[ts(flatten)]
    pub meta: KeyMeta,
    /// False when the value is missing from the credential store (for
    /// example deleted in Windows Credential Manager).
    pub stored: bool,
}

/// What the UI sends to add a key. The secret travels once, from the input
/// field to the credential store.
#[derive(Deserialize, TS)]
#[ts(export)]
pub struct NewKeyInput {
    pub label: String,
    pub kind: KeyKind,
    pub provider_id: Option<String>,
    pub env_var: String,
    pub secret: String,
    pub notes: Option<String>,
}

impl std::fmt::Debug for NewKeyInput {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("NewKeyInput")
            .field("label", &self.label)
            .field("env_var", &self.env_var)
            .finish_non_exhaustive()
    }
}

pub struct KeyLibrary {
    db: Arc<Database>,
    secrets: Arc<dyn SecretStore>,
}

/// Trim a pasted secret and check it looks usable.
pub fn clean_secret(raw: &str) -> Result<Secret, KeyError> {
    let value = raw.trim();
    if value.is_empty() {
        return Err(KeyError::Invalid("the key is empty".into()));
    }
    if value.len() > MAX_SECRET_LEN {
        return Err(KeyError::Invalid("the key is too long".into()));
    }
    if value.chars().any(char::is_whitespace) {
        return Err(KeyError::Invalid("the key contains spaces or line breaks; paste only the key".into()));
    }
    Ok(Secret::new(value))
}

impl KeyLibrary {
    pub fn new(db: Arc<Database>, secrets: Arc<dyn SecretStore>) -> Self {
        Self { db, secrets }
    }

    pub fn list(&self) -> Result<Vec<KeyEntry>, KeyError> {
        self.db
            .list_keys()?
            .into_iter()
            .map(|meta| {
                let stored = self.secrets.get(&meta.id)?.is_some();
                Ok(KeyEntry { meta, stored })
            })
            .collect()
    }

    pub fn add(&self, input: NewKeyInput) -> Result<KeyMeta, KeyError> {
        let label = input.label.trim();
        if label.is_empty() {
            return Err(KeyError::Invalid("give the key a name".into()));
        }
        let env_var = input.env_var.trim();
        validate_env_var(env_var).map_err(KeyError::Invalid)?;
        if self.db.env_var_taken(env_var, None)? {
            return Err(KeyError::Invalid(format!("another key already uses {env_var}")));
        }
        let secret = clean_secret(&input.secret)?;

        let id = format!("key-{}", uuid::Uuid::new_v4());
        // Secret first: if the metadata insert fails, remove the secret again
        // so nothing is left orphaned in the credential store.
        self.secrets.set(&id, &secret)?;
        let meta = NewKeyMeta {
            id: &id,
            label,
            kind: input.kind,
            provider_id: input.provider_id.as_deref().filter(|p| !p.is_empty()),
            env_var,
            notes: input.notes.as_deref().map(str::trim).filter(|n| !n.is_empty()),
        };
        match self.db.insert_key(&meta) {
            Ok(meta) => {
                redact::global().register(&secret);
                tracing::info!(key = %meta.id, provider = ?meta.provider_id, "key added");
                Ok(meta)
            }
            Err(e) => {
                let _ = self.secrets.delete(&id);
                Err(e.into())
            }
        }
    }

    pub fn update(&self, id: &str, label: &str, env_var: &str, notes: Option<&str>) -> Result<KeyMeta, KeyError> {
        let label = label.trim();
        let env_var = env_var.trim();
        if label.is_empty() {
            return Err(KeyError::Invalid("give the key a name".into()));
        }
        validate_env_var(env_var).map_err(KeyError::Invalid)?;
        if self.db.env_var_taken(env_var, Some(id))? {
            return Err(KeyError::Invalid(format!("another key already uses {env_var}")));
        }
        let notes = notes.map(str::trim).filter(|n| !n.is_empty());
        if !self.db.update_key_meta(id, label, env_var, notes)? {
            return Err(KeyError::NotFound);
        }
        self.db.get_key(id)?.ok_or(KeyError::NotFound)
    }

    pub fn replace_secret(&self, id: &str, raw: &str) -> Result<(), KeyError> {
        if self.db.get_key(id)?.is_none() {
            return Err(KeyError::NotFound);
        }
        let secret = clean_secret(raw)?;
        if let Some(old) = self.secrets.get(id)? {
            redact::global().forget(&old);
        }
        self.secrets.set(id, &secret)?;
        redact::global().register(&secret);
        Ok(())
    }

    pub fn delete(&self, id: &str) -> Result<(), KeyError> {
        if let Some(old) = self.secrets.get(id)? {
            redact::global().forget(&old);
        }
        self.secrets.delete(id)?;
        if !self.db.delete_key(id)? {
            return Err(KeyError::NotFound);
        }
        tracing::info!(key = %id, "key deleted");
        Ok(())
    }

    pub fn meta(&self, id: &str) -> Result<KeyMeta, KeyError> {
        self.db.get_key(id)?.ok_or(KeyError::NotFound)
    }

    /// Load a secret for use. Registers it with the redactor and marks the key used.
    pub fn secret(&self, id: &str) -> Result<Option<Secret>, KeyError> {
        let secret = self.secrets.get(id)?;
        if let Some(s) = &secret {
            redact::global().register(s);
            self.db.touch_key(id)?;
        }
        Ok(secret)
    }

    /// The best key for a provider preset, with its value.
    pub fn secret_for_provider(&self, provider_id: &str) -> Result<Option<(KeyMeta, Secret)>, KeyError> {
        for meta in self.db.keys_for_provider(provider_id)? {
            if let Some(secret) = self.secret(&meta.id)? {
                return Ok(Some((meta, secret)));
            }
        }
        Ok(None)
    }

    pub fn record_test(&self, id: &str, ok: bool) -> Result<(), KeyError> {
        Ok(self.db.record_key_test(id, ok)?)
    }

    /// Register every stored secret with the redactor (at startup), so a key
    /// is scrubbed from logs even before it is first used.
    pub fn register_all_with_redactor(&self) {
        let Ok(keys) = self.db.list_keys() else { return };
        for meta in keys {
            if let Ok(Some(secret)) = self.secrets.get(&meta.id) {
                redact::global().register(&secret);
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use kickoff_platform::MemorySecretStore;

    use super::*;

    fn library() -> (KeyLibrary, Arc<MemorySecretStore>, tempfile::TempDir) {
        let dir = tempfile::tempdir().unwrap();
        let db = Arc::new(Database::open(&dir.path().join("t.sqlite3")).unwrap());
        let store = Arc::new(MemorySecretStore::new());
        (KeyLibrary::new(db, store.clone()), store, dir)
    }

    fn input(env: &str, secret: &str) -> NewKeyInput {
        NewKeyInput {
            label: "Groq".into(),
            kind: KeyKind::Llm,
            provider_id: Some("groq".into()),
            env_var: env.into(),
            secret: secret.into(),
            notes: None,
        }
    }

    #[test]
    fn add_list_replace_delete() {
        let (lib, store, _dir) = library();
        let meta = lib.add(input("GROQ_API_KEY", "  gsk_first_value_123456789  ")).unwrap();
        assert_eq!(store.get(&meta.id).unwrap().unwrap().expose(), "gsk_first_value_123456789", "trimmed");
        assert!(lib.list().unwrap()[0].stored);

        lib.replace_secret(&meta.id, "gsk_second_value_987654321").unwrap();
        let (found, secret) = lib.secret_for_provider("groq").unwrap().unwrap();
        assert_eq!((found.id.as_str(), secret.expose()), (meta.id.as_str(), "gsk_second_value_987654321"));
        assert!(lib.meta(&meta.id).unwrap().last_used_at.is_some());

        lib.delete(&meta.id).unwrap();
        assert!(store.get(&meta.id).unwrap().is_none());
        assert!(lib.list().unwrap().is_empty());
        assert!(matches!(lib.delete(&meta.id), Err(KeyError::NotFound)));
    }

    #[test]
    fn rejects_bad_input_without_storing_anything() {
        let (lib, _store, _dir) = library();
        assert!(matches!(lib.add(input("ANTHROPIC_API_KEY", "x-123")), Err(KeyError::Invalid(_))));
        assert!(matches!(lib.add(input("GROQ_API_KEY", "   ")), Err(KeyError::Invalid(_))));
        assert!(matches!(lib.add(input("GROQ_API_KEY", "two words")), Err(KeyError::Invalid(_))));
        lib.add(input("GROQ_API_KEY", "gsk_ok_value_1234567890")).unwrap();
        let err = lib.add(input("GROQ_API_KEY", "gsk_other_value_123456")).unwrap_err();
        assert!(err.to_string().contains("already uses"));
        assert_eq!(lib.list().unwrap().len(), 1);
    }

    #[test]
    fn missing_credential_is_reported_as_not_stored() {
        let (lib, store, _dir) = library();
        let meta = lib.add(input("GROQ_API_KEY", "gsk_value_1234567890ab")).unwrap();
        store.delete(&meta.id).unwrap();
        assert!(!lib.list().unwrap()[0].stored);
        assert!(lib.secret_for_provider("groq").unwrap().is_none());
    }

    /// The core promise of the key library: the secret value never reaches
    /// the database, in any table or in the file on disk (including the WAL).
    #[test]
    fn secret_never_reaches_the_database_file() {
        let (lib, _store, dir) = library();
        let secret = "gsk_NEVER_IN_SQLITE_0123456789abcdef";
        let meta = lib.add(input("GROQ_API_KEY", secret)).unwrap();
        lib.update(&meta.id, "Groq renamed", "GROQ_KEY_2", Some("notes")).unwrap();
        lib.replace_secret(&meta.id, "gsk_SECOND_NEVER_IN_SQLITE_987654").unwrap();
        lib.record_test(&meta.id, true).unwrap();
        let _ = lib.secret(&meta.id).unwrap();

        for entry in std::fs::read_dir(dir.path()).unwrap() {
            let bytes = std::fs::read(entry.unwrap().path()).unwrap();
            let text = String::from_utf8_lossy(&bytes);
            assert!(!text.contains("NEVER_IN_SQLITE"), "secret found in database file");
        }
    }

    #[test]
    fn loaded_secrets_are_redacted_from_logs() {
        let (lib, _store, _dir) = library();
        let meta = lib.add(input("GROQ_API_KEY", "plainlooking-secret-value-42")).unwrap();
        let _ = lib.secret(&meta.id).unwrap();
        assert_eq!(redact::global().redact("auth: plainlooking-secret-value-42"), "auth: [redacted]");
    }

    #[test]
    fn debug_output_of_input_hides_the_secret() {
        let i = input("GROQ_API_KEY", "gsk_debug_should_hide_123");
        assert!(!format!("{i:?}").contains("gsk_debug"));
    }
}
