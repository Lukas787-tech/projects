//! Projects, their interview transcript, brief revisions and interview metadata.

use rusqlite::{OptionalExtension, Row, params};
use serde::Serialize;
use ts_rs::TS;

use crate::{Database, Result, time};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, serde::Deserialize, TS)]
#[serde(rename_all = "snake_case")]
#[ts(export)]
pub enum Stage {
    Interview,
    Research,
    Kit,
    Done,
}

impl Stage {
    pub fn as_str(self) -> &'static str {
        match self {
            Stage::Interview => "interview",
            Stage::Research => "research",
            Stage::Kit => "kit",
            Stage::Done => "done",
        }
    }

    fn parse(s: &str) -> Self {
        match s {
            "research" => Stage::Research,
            "kit" => Stage::Kit,
            "done" => Stage::Done,
            _ => Stage::Interview,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, TS)]
#[ts(export)]
pub struct Project {
    pub id: String,
    pub name: String,
    pub pack_id: String,
    pub pack_version: String,
    pub stage: Stage,
    pub engine: Option<String>,
    pub folder_path: Option<String>,
    pub created_at: String,
    pub updated_at: String,
}

#[derive(Debug, Clone, PartialEq, Serialize, TS)]
#[ts(export)]
pub struct StoredMessage {
    #[ts(type = "number")]
    pub id: i64,
    /// `user`, `producer` or `system`.
    pub role: String,
    pub content: String,
    pub input_mode: Option<String>,
    pub provider_id: Option<String>,
    pub model: Option<String>,
    pub created_at: String,
}

#[derive(Debug, Clone, PartialEq)]
pub struct StoredRevision {
    pub rev: i64,
    pub schema_id: String,
    pub brief_json: String,
    pub cause: String,
    pub created_at: String,
}

#[derive(Debug, Clone, PartialEq, Default)]
pub struct InterviewMeta {
    pub flags_json: String,
    pub revisit: Option<String>,
    pub turn: u32,
}

const PROJECT_COLUMNS: &str = "id, name, pack_id, pack_version, stage, engine, folder_path, created_at, updated_at";

fn project_from_row(row: &Row<'_>) -> rusqlite::Result<Project> {
    let stage: String = row.get(4)?;
    Ok(Project {
        id: row.get(0)?,
        name: row.get(1)?,
        pack_id: row.get(2)?,
        pack_version: row.get(3)?,
        stage: Stage::parse(&stage),
        engine: row.get(5)?,
        folder_path: row.get(6)?,
        created_at: row.get(7)?,
        updated_at: row.get(8)?,
    })
}

impl Database {
    pub fn create_project(&self, id: &str, name: &str, pack_id: &str, pack_version: &str) -> Result<Project> {
        let now = time::now_rfc3339();
        self.conn().execute(
            &format!(
                "INSERT INTO projects ({PROJECT_COLUMNS}) VALUES (?1, ?2, ?3, ?4, 'interview', NULL, NULL, ?5, ?5)"
            ),
            params![id, name, pack_id, pack_version, now],
        )?;
        Ok(self.get_project(id)?.expect("just inserted"))
    }

    pub fn get_project(&self, id: &str) -> Result<Option<Project>> {
        Ok(self
            .conn()
            .query_row(&format!("SELECT {PROJECT_COLUMNS} FROM projects WHERE id = ?1"), params![id], project_from_row)
            .optional()?)
    }

    /// Most recently active first.
    pub fn list_projects(&self) -> Result<Vec<Project>> {
        let conn = self.conn();
        let mut stmt = conn.prepare(&format!("SELECT {PROJECT_COLUMNS} FROM projects ORDER BY updated_at DESC"))?;
        let rows = stmt.query_map([], project_from_row)?.collect::<rusqlite::Result<Vec<_>>>()?;
        Ok(rows)
    }

    pub fn update_project(&self, id: &str, name: &str, stage: Stage, engine: Option<&str>) -> Result<()> {
        self.conn().execute(
            "UPDATE projects SET name = ?2, stage = ?3, engine = ?4, updated_at = ?5 WHERE id = ?1",
            params![id, name, stage.as_str(), engine, time::now_rfc3339()],
        )?;
        Ok(())
    }

    pub fn set_project_folder(&self, id: &str, folder: &str) -> Result<()> {
        self.conn().execute(
            "UPDATE projects SET folder_path = ?2, updated_at = ?3 WHERE id = ?1",
            params![id, folder, time::now_rfc3339()],
        )?;
        Ok(())
    }

    pub fn delete_project(&self, id: &str) -> Result<bool> {
        Ok(self.conn().execute("DELETE FROM projects WHERE id = ?1", params![id])? > 0)
    }

    pub fn insert_message(
        &self,
        project_id: &str,
        role: &str,
        content: &str,
        input_mode: Option<&str>,
        provider_id: Option<&str>,
        model: Option<&str>,
    ) -> Result<i64> {
        let conn = self.conn();
        conn.execute(
            "INSERT INTO messages (project_id, role, content, input_mode, provider_id, model, created_at)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)",
            params![project_id, role, content, input_mode, provider_id, model, time::now_rfc3339()],
        )?;
        Ok(conn.last_insert_rowid())
    }

    pub fn list_messages(&self, project_id: &str) -> Result<Vec<StoredMessage>> {
        let conn = self.conn();
        let mut stmt = conn.prepare(
            "SELECT id, role, content, input_mode, provider_id, model, created_at
             FROM messages WHERE project_id = ?1 ORDER BY id",
        )?;
        let rows = stmt
            .query_map(params![project_id], |r| {
                Ok(StoredMessage {
                    id: r.get(0)?,
                    role: r.get(1)?,
                    content: r.get(2)?,
                    input_mode: r.get(3)?,
                    provider_id: r.get(4)?,
                    model: r.get(5)?,
                    created_at: r.get(6)?,
                })
            })?
            .collect::<rusqlite::Result<Vec<_>>>()?;
        Ok(rows)
    }

    /// Store a new brief revision; returns its number (1, 2, …).
    pub fn insert_brief_revision(
        &self,
        project_id: &str,
        schema_id: &str,
        brief_json: &str,
        diff_json: Option<&str>,
        cause: &str,
        message_id: Option<i64>,
    ) -> Result<i64> {
        let conn = self.conn();
        let rev: i64 = conn.query_row(
            "SELECT COALESCE(MAX(rev), 0) + 1 FROM brief_revisions WHERE project_id = ?1",
            params![project_id],
            |r| r.get(0),
        )?;
        conn.execute(
            "INSERT INTO brief_revisions (project_id, rev, schema_id, brief_json, diff_json, cause, message_id, created_at)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)",
            params![project_id, rev, schema_id, brief_json, diff_json, cause, message_id, time::now_rfc3339()],
        )?;
        conn.execute("UPDATE projects SET updated_at = ?2 WHERE id = ?1", params![project_id, time::now_rfc3339()])?;
        Ok(rev)
    }

    pub fn latest_brief_revision(&self, project_id: &str) -> Result<Option<StoredRevision>> {
        Ok(self
            .conn()
            .query_row(
                "SELECT rev, schema_id, brief_json, cause, created_at FROM brief_revisions
                 WHERE project_id = ?1 ORDER BY rev DESC LIMIT 1",
                params![project_id],
                |r| {
                    Ok(StoredRevision {
                        rev: r.get(0)?,
                        schema_id: r.get(1)?,
                        brief_json: r.get(2)?,
                        cause: r.get(3)?,
                        created_at: r.get(4)?,
                    })
                },
            )
            .optional()?)
    }

    pub fn count_brief_revisions(&self, project_id: &str) -> Result<i64> {
        Ok(self.conn().query_row(
            "SELECT COUNT(*) FROM brief_revisions WHERE project_id = ?1",
            params![project_id],
            |r| r.get(0),
        )?)
    }

    pub fn get_interview_meta(&self, project_id: &str) -> Result<InterviewMeta> {
        Ok(self
            .conn()
            .query_row(
                "SELECT flags_json, revisit, turn FROM interview_meta WHERE project_id = ?1",
                params![project_id],
                |r| Ok(InterviewMeta { flags_json: r.get(0)?, revisit: r.get(1)?, turn: r.get(2)? }),
            )
            .optional()?
            .unwrap_or_else(|| InterviewMeta { flags_json: "[]".into(), ..Default::default() }))
    }

    pub fn set_interview_meta(&self, project_id: &str, meta: &InterviewMeta) -> Result<()> {
        self.conn().execute(
            "INSERT INTO interview_meta (project_id, flags_json, revisit, turn, updated_at) VALUES (?1, ?2, ?3, ?4, ?5)
             ON CONFLICT(project_id) DO UPDATE SET flags_json = excluded.flags_json, revisit = excluded.revisit,
               turn = excluded.turn, updated_at = excluded.updated_at",
            params![project_id, meta.flags_json, meta.revisit, meta.turn, time::now_rfc3339()],
        )?;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn project_lifecycle() {
        let db = Database::open_in_memory().unwrap();
        let p = db.create_project("p1", "Untitled idea", "game", "1").unwrap();
        assert_eq!(p.stage, Stage::Interview);

        let m1 = db.insert_message("p1", "user", "a cozy survival game", Some("text"), None, None).unwrap();
        db.insert_message("p1", "producer", "What should it feel like?", None, Some("groq"), Some("gpt-oss")).unwrap();
        assert_eq!(db.list_messages("p1").unwrap().len(), 2);

        assert_eq!(db.insert_brief_revision("p1", "game-brief/1", "{}", None, "import", None).unwrap(), 1);
        assert_eq!(
            db.insert_brief_revision("p1", "game-brief/1", "{\"a\":1}", Some("[\"a\"]"), "extraction", Some(m1))
                .unwrap(),
            2
        );
        let latest = db.latest_brief_revision("p1").unwrap().unwrap();
        assert_eq!((latest.rev, latest.brief_json.as_str(), latest.cause.as_str()), (2, "{\"a\":1}", "extraction"));

        assert_eq!(db.get_interview_meta("p1").unwrap().flags_json, "[]");
        db.set_interview_meta(
            "p1",
            &InterviewMeta { flags_json: "[1]".into(), revisit: Some("pillars".into()), turn: 3 },
        )
        .unwrap();
        assert_eq!(db.get_interview_meta("p1").unwrap().turn, 3);

        db.update_project("p1", "Hearth", Stage::Research, Some("godot")).unwrap();
        let p = db.get_project("p1").unwrap().unwrap();
        assert_eq!((p.name.as_str(), p.stage, p.engine.as_deref()), ("Hearth", Stage::Research, Some("godot")));
        assert_eq!(db.list_projects().unwrap().len(), 1);

        assert!(db.delete_project("p1").unwrap());
        assert!(db.list_messages("p1").unwrap().is_empty(), "messages cascade");
        assert!(db.latest_brief_revision("p1").unwrap().is_none(), "revisions cascade");
    }
}
