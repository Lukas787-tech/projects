//! Key library metadata. No secret values are stored in this table.

use kickoff_core::keys::{KeyKind, KeyMeta};
use rusqlite::{OptionalExtension, Row, params};

use crate::{Database, Result, StoreError, time};

pub struct NewKeyMeta<'a> {
    pub id: &'a str,
    pub label: &'a str,
    pub kind: KeyKind,
    pub provider_id: Option<&'a str>,
    pub env_var: &'a str,
    pub notes: Option<&'a str>,
}

const COLUMNS: &str =
    "id, label, kind, provider_id, env_var, created_at, last_used_at, last_test_at, last_test_ok, notes";

fn from_row(row: &Row<'_>) -> rusqlite::Result<KeyMeta> {
    let kind: String = row.get(2)?;
    Ok(KeyMeta {
        id: row.get(0)?,
        label: row.get(1)?,
        kind: KeyKind::parse(&kind).unwrap_or(KeyKind::Other),
        provider_id: row.get(3)?,
        env_var: row.get(4)?,
        created_at: row.get(5)?,
        last_used_at: row.get(6)?,
        last_test_at: row.get(7)?,
        last_test_ok: row.get(8)?,
        notes: row.get(9)?,
    })
}

impl Database {
    pub fn insert_key(&self, key: &NewKeyMeta<'_>) -> Result<KeyMeta> {
        self.conn().execute(
            &format!("INSERT INTO keys_meta ({COLUMNS}) VALUES (?1, ?2, ?3, ?4, ?5, ?6, NULL, NULL, NULL, ?7)"),
            params![key.id, key.label, key.kind.as_str(), key.provider_id, key.env_var, time::now_rfc3339(), key.notes],
        )?;
        self.get_key(key.id)?.ok_or(StoreError::Sqlite(rusqlite::Error::QueryReturnedNoRows))
    }

    pub fn get_key(&self, id: &str) -> Result<Option<KeyMeta>> {
        Ok(self
            .conn()
            .query_row(&format!("SELECT {COLUMNS} FROM keys_meta WHERE id = ?1"), params![id], from_row)
            .optional()?)
    }

    pub fn list_keys(&self) -> Result<Vec<KeyMeta>> {
        let conn = self.conn();
        let mut stmt = conn.prepare(&format!("SELECT {COLUMNS} FROM keys_meta ORDER BY kind, label COLLATE NOCASE"))?;
        let rows = stmt.query_map([], from_row)?.collect::<rusqlite::Result<Vec<_>>>()?;
        Ok(rows)
    }

    /// Keys for a provider preset, most recently tested-good first.
    pub fn keys_for_provider(&self, provider_id: &str) -> Result<Vec<KeyMeta>> {
        let conn = self.conn();
        let mut stmt = conn.prepare(&format!(
            "SELECT {COLUMNS} FROM keys_meta WHERE provider_id = ?1
             ORDER BY COALESCE(last_test_ok, 1) DESC, created_at DESC"
        ))?;
        let rows = stmt.query_map(params![provider_id], from_row)?.collect::<rusqlite::Result<Vec<_>>>()?;
        Ok(rows)
    }

    pub fn update_key_meta(&self, id: &str, label: &str, env_var: &str, notes: Option<&str>) -> Result<bool> {
        let n = self.conn().execute(
            "UPDATE keys_meta SET label = ?2, env_var = ?3, notes = ?4 WHERE id = ?1",
            params![id, label, env_var, notes],
        )?;
        Ok(n > 0)
    }

    pub fn record_key_test(&self, id: &str, ok: bool) -> Result<()> {
        self.conn().execute(
            "UPDATE keys_meta SET last_test_at = ?2, last_test_ok = ?3 WHERE id = ?1",
            params![id, time::now_rfc3339(), ok],
        )?;
        Ok(())
    }

    pub fn touch_key(&self, id: &str) -> Result<()> {
        self.conn()
            .execute("UPDATE keys_meta SET last_used_at = ?2 WHERE id = ?1", params![id, time::now_rfc3339()])?;
        Ok(())
    }

    pub fn delete_key(&self, id: &str) -> Result<bool> {
        Ok(self.conn().execute("DELETE FROM keys_meta WHERE id = ?1", params![id])? > 0)
    }

    pub fn env_var_taken(&self, env_var: &str, except_id: Option<&str>) -> Result<bool> {
        let n: i64 = self.conn().query_row(
            "SELECT COUNT(*) FROM keys_meta WHERE env_var = ?1 AND id IS NOT ?2",
            params![env_var, except_id],
            |r| r.get(0),
        )?;
        Ok(n > 0)
    }

    pub fn grant_key(&self, key_id: &str, project_id: &str) -> Result<()> {
        self.conn().execute(
            "INSERT OR IGNORE INTO key_grants (key_id, project_id, created_at) VALUES (?1, ?2, ?3)",
            params![key_id, project_id, time::now_rfc3339()],
        )?;
        Ok(())
    }

    pub fn revoke_key(&self, key_id: &str, project_id: &str) -> Result<()> {
        self.conn()
            .execute("DELETE FROM key_grants WHERE key_id = ?1 AND project_id = ?2", params![key_id, project_id])?;
        Ok(())
    }

    pub fn granted_key_ids(&self, project_id: &str) -> Result<Vec<String>> {
        let conn = self.conn();
        let mut stmt = conn.prepare("SELECT key_id FROM key_grants WHERE project_id = ?1 ORDER BY key_id")?;
        let rows = stmt.query_map(params![project_id], |r| r.get(0))?.collect::<rusqlite::Result<Vec<String>>>()?;
        Ok(rows)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn new<'a>(id: &'a str, env: &'a str) -> NewKeyMeta<'a> {
        NewKeyMeta { id, label: "Groq", kind: KeyKind::Llm, provider_id: Some("groq"), env_var: env, notes: None }
    }

    #[test]
    fn crud_and_env_var_uniqueness() {
        let db = Database::open_in_memory().unwrap();
        let k = db.insert_key(&new("k1", "GROQ_API_KEY")).unwrap();
        assert_eq!(k.kind, KeyKind::Llm);
        assert!(db.env_var_taken("GROQ_API_KEY", None).unwrap());
        assert!(!db.env_var_taken("GROQ_API_KEY", Some("k1")).unwrap());
        assert!(db.insert_key(&new("k2", "GROQ_API_KEY")).is_err(), "env vars are unique");

        assert!(db.update_key_meta("k1", "Groq (work)", "GROQ_WORK_KEY", Some("team")).unwrap());
        db.record_key_test("k1", true).unwrap();
        db.touch_key("k1").unwrap();
        let k = db.get_key("k1").unwrap().unwrap();
        assert_eq!(
            (k.label.as_str(), k.env_var.as_str(), k.last_test_ok),
            ("Groq (work)", "GROQ_WORK_KEY", Some(true))
        );
        assert!(k.last_used_at.is_some());
        assert_eq!(db.keys_for_provider("groq").unwrap().len(), 1);

        assert!(db.delete_key("k1").unwrap());
        assert!(db.list_keys().unwrap().is_empty());
    }
}
