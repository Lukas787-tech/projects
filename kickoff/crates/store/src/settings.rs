//! Key-value app settings, stored as JSON.

use rusqlite::{OptionalExtension, params};
use serde::Serialize;
use serde::de::DeserializeOwned;

use crate::{Database, Result, time};

impl Database {
    pub fn get_setting<T: DeserializeOwned>(&self, key: &str) -> Result<Option<T>> {
        let raw: Option<String> = self
            .conn()
            .query_row("SELECT value_json FROM settings WHERE key = ?1", params![key], |r| r.get(0))
            .optional()?;
        Ok(raw.map(|s| serde_json::from_str(&s)).transpose()?)
    }

    pub fn set_setting<T: Serialize + ?Sized>(&self, key: &str, value: &T) -> Result<()> {
        let json = serde_json::to_string(value)?;
        self.conn().execute(
            "INSERT INTO settings (key, value_json, updated_at) VALUES (?1, ?2, ?3)
             ON CONFLICT(key) DO UPDATE SET value_json = excluded.value_json, updated_at = excluded.updated_at",
            params![key, json, time::now_rfc3339()],
        )?;
        Ok(())
    }

    pub fn delete_setting(&self, key: &str) -> Result<()> {
        self.conn().execute("DELETE FROM settings WHERE key = ?1", params![key])?;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use serde::Deserialize;

    use super::*;

    #[derive(Debug, PartialEq, Serialize, Deserialize)]
    struct Theme {
        mode: String,
    }

    #[test]
    fn round_trip_and_overwrite() {
        let db = Database::open_in_memory().unwrap();
        assert_eq!(db.get_setting::<Theme>("theme").unwrap(), None);
        db.set_setting("theme", &Theme { mode: "dark".into() }).unwrap();
        db.set_setting("theme", &Theme { mode: "light".into() }).unwrap();
        assert_eq!(db.get_setting::<Theme>("theme").unwrap(), Some(Theme { mode: "light".into() }));
        db.delete_setting("theme").unwrap();
        assert_eq!(db.get_setting::<Theme>("theme").unwrap(), None);
    }
}
