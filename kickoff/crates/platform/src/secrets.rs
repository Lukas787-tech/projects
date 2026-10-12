//! The OS credential store. Raw secrets live here and nowhere else.

use std::collections::HashMap;
use std::sync::Mutex;

use kickoff_core::{APP_ID, Secret};

#[derive(Debug, thiserror::Error)]
pub enum SecretStoreError {
    #[error("the OS credential store is not available on this platform: {0}")]
    Unavailable(String),
    #[error("the secret is too long for the credential store")]
    TooLong,
    #[error("credential store error: {0}")]
    Backend(String),
}

/// Storage for raw secret values, addressed by key-library id.
pub trait SecretStore: Send + Sync {
    fn set(&self, id: &str, secret: &Secret) -> Result<(), SecretStoreError>;
    /// `Ok(None)` when no secret is stored under `id`.
    fn get(&self, id: &str) -> Result<Option<Secret>, SecretStoreError>;
    /// Deleting a missing entry is not an error.
    fn delete(&self, id: &str) -> Result<(), SecretStoreError>;
}

/// Windows Credential Manager (via `keyring-core`), one generic credential per
/// key with service = [`APP_ID`]. On other platforms every call returns
/// [`SecretStoreError::Unavailable`].
pub struct OsSecretStore {
    _private: (),
}

impl OsSecretStore {
    pub fn new() -> Result<Self, SecretStoreError> {
        os::init()?;
        Ok(Self { _private: () })
    }
}

impl SecretStore for OsSecretStore {
    fn set(&self, id: &str, secret: &Secret) -> Result<(), SecretStoreError> {
        entry(id)?.set_password(secret.expose()).map_err(map_err)
    }

    fn get(&self, id: &str) -> Result<Option<Secret>, SecretStoreError> {
        match entry(id)?.get_password() {
            Ok(value) => Ok(Some(Secret::new(value))),
            Err(keyring_core::Error::NoEntry) => Ok(None),
            Err(e) => Err(map_err(e)),
        }
    }

    fn delete(&self, id: &str) -> Result<(), SecretStoreError> {
        match entry(id)?.delete_credential() {
            Ok(()) | Err(keyring_core::Error::NoEntry) => Ok(()),
            Err(e) => Err(map_err(e)),
        }
    }
}

fn entry(id: &str) -> Result<keyring_core::Entry, SecretStoreError> {
    // "Local" persistence keeps keys on this computer. The store's default
    // ("Enterprise") would roam them to other PCs with a domain roaming profile.
    let modifiers = std::collections::HashMap::from([("persistence", "Local")]);
    keyring_core::Entry::new_with_modifiers(APP_ID, id, &modifiers).map_err(map_err)
}

fn map_err(e: keyring_core::Error) -> SecretStoreError {
    match e {
        keyring_core::Error::TooLong(..) => SecretStoreError::TooLong,
        keyring_core::Error::NoDefaultStore => SecretStoreError::Unavailable("no credential store configured".into()),
        // keyring-core errors never contain the secret value itself.
        other => SecretStoreError::Backend(other.to_string()),
    }
}

#[cfg(windows)]
mod os {
    use std::sync::OnceLock;

    use super::SecretStoreError;

    pub fn init() -> Result<(), SecretStoreError> {
        static INIT: OnceLock<Result<(), String>> = OnceLock::new();
        INIT.get_or_init(|| {
            let store = windows_native_keyring_store::Store::new().map_err(|e| e.to_string())?;
            keyring_core::set_default_store(store);
            Ok(())
        })
        .clone()
        .map_err(SecretStoreError::Backend)
    }
}

#[cfg(not(windows))]
mod os {
    use super::SecretStoreError;

    pub fn init() -> Result<(), SecretStoreError> {
        Err(SecretStoreError::Unavailable(format!(
            "{} is not supported yet; Kickoff stores keys in Windows Credential Manager",
            std::env::consts::OS
        )))
    }
}

/// In-memory store for tests and for previewing the UI on unsupported platforms.
/// Never persists anything.
#[derive(Default)]
pub struct MemorySecretStore {
    values: Mutex<HashMap<String, String>>,
}

impl MemorySecretStore {
    pub fn new() -> Self {
        Self::default()
    }
}

impl SecretStore for MemorySecretStore {
    fn set(&self, id: &str, secret: &Secret) -> Result<(), SecretStoreError> {
        self.values.lock().unwrap().insert(id.to_owned(), secret.expose().to_owned());
        Ok(())
    }

    fn get(&self, id: &str) -> Result<Option<Secret>, SecretStoreError> {
        Ok(self.values.lock().unwrap().get(id).cloned().map(Secret::new))
    }

    fn delete(&self, id: &str) -> Result<(), SecretStoreError> {
        self.values.lock().unwrap().remove(id);
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn round_trip(store: &dyn SecretStore) {
        let id = format!("test-{}", uuid::Uuid::new_v4());
        assert!(store.get(&id).unwrap().is_none());
        store.set(&id, &Secret::new("round-trip-value-123")).unwrap();
        assert_eq!(store.get(&id).unwrap().unwrap().expose(), "round-trip-value-123");
        store.set(&id, &Secret::new("updated-value-456")).unwrap();
        assert_eq!(store.get(&id).unwrap().unwrap().expose(), "updated-value-456");
        store.delete(&id).unwrap();
        assert!(store.get(&id).unwrap().is_none());
        store.delete(&id).unwrap();
    }

    #[test]
    fn memory_store_round_trip() {
        round_trip(&MemorySecretStore::new());
    }

    /// Real Windows Credential Manager round trip; runs in Windows CI.
    #[cfg(windows)]
    #[test]
    fn os_store_round_trip() {
        round_trip(&OsSecretStore::new().unwrap());
    }

    #[cfg(not(windows))]
    #[test]
    fn os_store_reports_unavailable() {
        assert!(matches!(OsSecretStore::new(), Err(SecretStoreError::Unavailable(_))));
    }
}
