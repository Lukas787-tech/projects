//! References found by research, and the user's reactions to them.

use kickoff_core::research::{Candidate, Classification, Origin, RefCategory};
use rusqlite::{OptionalExtension, Row, params};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use ts_rs::TS;

use crate::{Database, Result, time};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, TS)]
#[serde(rename_all = "snake_case")]
#[ts(export)]
pub enum Reaction {
    Keep,
    Reject,
    /// Kept, and the user asked for similar ones.
    MoreLikeThis,
}

impl Reaction {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Keep => "keep",
            Self::Reject => "reject",
            Self::MoreLikeThis => "more_like_this",
        }
    }

    fn parse(s: &str) -> Option<Self> {
        match s {
            "keep" => Some(Self::Keep),
            "reject" => Some(Self::Reject),
            "more_like_this" => Some(Self::MoreLikeThis),
            _ => None,
        }
    }

    /// Keep and "more like this" both mean the user likes it.
    pub fn is_positive(self) -> bool {
        self != Self::Reject
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, TS)]
#[ts(export)]
pub struct StoredRef {
    pub id: String,
    pub category: RefCategory,
    pub asset_kind: Option<String>,
    pub title: String,
    pub summary: Option<String>,
    pub source_id: Option<String>,
    /// Missing for comparables nobody could check online.
    pub source_url: Option<String>,
    pub author: Option<String>,
    pub classification: Classification,
    pub license_id: Option<String>,
    pub license_name: Option<String>,
    pub license_url: Option<String>,
    pub attribution: Option<String>,
    pub thumbnail_url: Option<String>,
    /// Cached thumbnail on disk; internal, not sent to the UI.
    #[serde(skip)]
    #[ts(skip)]
    pub thumbnail_path: Option<String>,
    pub origin: Origin,
    pub verified: bool,
    #[ts(type = "Record<string, unknown>")]
    pub metadata: Value,
    /// The latest reaction, if any.
    pub reaction: Option<Reaction>,
    pub created_at: String,
}

const COLUMNS: &str = "id, category, asset_kind, title, summary, source_id, source_url, author, classification,
    license_id, license_name, license_url, attribution, thumbnail_url, thumbnail_path, origin, verified,
    metadata_json, created_at,
    (SELECT reaction FROM reactions WHERE ref_id = refs.id ORDER BY id DESC LIMIT 1)";

fn from_row(row: &Row<'_>) -> rusqlite::Result<StoredRef> {
    let text = |i: usize| row.get::<_, String>(i);
    let source_url: String = row.get(6)?;
    let metadata: Option<String> = row.get(17)?;
    let reaction: Option<String> = row.get(19)?;
    Ok(StoredRef {
        id: row.get(0)?,
        category: RefCategory::parse(&text(1)?).unwrap_or(RefCategory::Visual),
        asset_kind: row.get(2)?,
        title: row.get(3)?,
        summary: row.get(4)?,
        source_id: row.get(5)?,
        // The column is NOT NULL; an empty string means "no link".
        source_url: Some(source_url).filter(|u| !u.is_empty()),
        author: row.get(7)?,
        classification: Classification::parse(&text(8)?).unwrap_or(Classification::Unknown),
        license_id: row.get(9)?,
        license_name: row.get(10)?,
        license_url: row.get(11)?,
        attribution: row.get(12)?,
        thumbnail_url: row.get(13)?,
        thumbnail_path: row.get(14)?,
        origin: Origin::parse(&text(15)?).unwrap_or(Origin::LlmKnowledge),
        verified: row.get(16)?,
        metadata: metadata.and_then(|m| serde_json::from_str(&m).ok()).unwrap_or(Value::Null),
        reaction: reaction.as_deref().and_then(Reaction::parse),
        created_at: row.get(18)?,
    })
}

impl Database {
    /// Store research results. `items` pairs each candidate with a new id.
    pub fn insert_refs(&self, project_id: &str, items: &[(String, Candidate)]) -> Result<()> {
        let now = time::now_rfc3339();
        let mut conn = self.conn();
        let tx = conn.transaction()?;
        {
            let mut insert = tx.prepare(
                "INSERT INTO refs (id, project_id, category, asset_kind, title, summary, source_id, source_url,
                    author, license_name, license_url, license_id, classification, attribution, thumbnail_url,
                    origin, verified, metadata_json, created_at)
                 VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14, ?15, ?16, ?17, ?18, ?19)",
            )?;
            for (id, c) in items {
                let l = &c.licensing;
                insert.execute(params![
                    id,
                    project_id,
                    c.category.as_str(),
                    c.asset_kind,
                    c.title,
                    c.summary,
                    c.source_id,
                    c.source_url.as_deref().unwrap_or_default(),
                    c.author,
                    l.license_name,
                    l.license_url,
                    l.license_id,
                    l.classification.as_str(),
                    l.attribution,
                    c.thumbnail_url,
                    c.origin.as_str(),
                    c.verified,
                    serde_json::to_string(&c.metadata)?,
                    now,
                ])?;
            }
        }
        tx.commit()?;
        Ok(())
    }

    /// A project's references, oldest first.
    pub fn list_refs(&self, project_id: &str) -> Result<Vec<StoredRef>> {
        let conn = self.conn();
        let mut stmt =
            conn.prepare(&format!("SELECT {COLUMNS} FROM refs WHERE project_id = ?1 ORDER BY created_at, rowid"))?;
        let rows = stmt.query_map(params![project_id], from_row)?.collect::<rusqlite::Result<Vec<_>>>()?;
        Ok(rows)
    }

    pub fn get_ref(&self, project_id: &str, id: &str) -> Result<Option<StoredRef>> {
        Ok(self
            .conn()
            .query_row(
                &format!("SELECT {COLUMNS} FROM refs WHERE project_id = ?1 AND id = ?2"),
                params![project_id, id],
                from_row,
            )
            .optional()?)
    }

    /// Record a reaction; `None` clears the reference's reactions (undo).
    /// Returns `false` if the reference doesn't exist.
    pub fn set_reaction(&self, project_id: &str, ref_id: &str, reaction: Option<Reaction>) -> Result<bool> {
        let conn = self.conn();
        let exists: bool = conn.query_row(
            "SELECT EXISTS(SELECT 1 FROM refs WHERE project_id = ?1 AND id = ?2)",
            params![project_id, ref_id],
            |r| r.get(0),
        )?;
        if !exists {
            return Ok(false);
        }
        match reaction {
            Some(r) => conn.execute(
                "INSERT INTO reactions (ref_id, project_id, reaction, note, created_at) VALUES (?1, ?2, ?3, NULL, ?4)",
                params![ref_id, project_id, r.as_str(), time::now_rfc3339()],
            )?,
            None => conn.execute("DELETE FROM reactions WHERE ref_id = ?1", params![ref_id])?,
        };
        Ok(true)
    }

    pub fn set_ref_thumbnail_path(&self, id: &str, path: Option<&str>) -> Result<()> {
        self.conn().execute("UPDATE refs SET thumbnail_path = ?2 WHERE id = ?1", params![id, path])?;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use kickoff_core::research::Licensing;
    use serde_json::json;

    use super::*;

    fn candidate(category: RefCategory, title: &str, url: Option<&str>) -> Candidate {
        Candidate {
            category,
            asset_kind: (category == RefCategory::Asset).then(|| "model".into()),
            title: title.into(),
            summary: Some("why".into()),
            source_id: Some("tavily".into()),
            source_url: url.map(str::to_owned),
            author: None,
            licensing: Licensing {
                classification: if category == RefCategory::Asset {
                    Classification::Usable
                } else {
                    Classification::ReferenceOnly
                },
                license_id: Some("CC0-1.0".into()),
                license_name: Some("CC0 1.0".into()),
                license_url: Some("https://creativecommons.org/publicdomain/zero/1.0/".into()),
                attribution: Some("\"Barn\" (…)".into()),
            },
            thumbnail_url: None,
            origin: if url.is_some() { Origin::Search } else { Origin::LlmKnowledge },
            verified: url.is_some(),
            metadata: json!({ "learn": "seasons" }),
        }
    }

    #[test]
    fn stores_references_and_the_latest_reaction() {
        let db = Database::open_in_memory().unwrap();
        db.create_project("p1", "Hearth", "game", "1").unwrap();
        db.insert_refs(
            "p1",
            &[
                ("r1".into(), candidate(RefCategory::Comparable, "Stardew Valley", None)),
                ("r2".into(), candidate(RefCategory::Asset, "Barn", Some("https://example.com/barn"))),
            ],
        )
        .unwrap();

        let refs = db.list_refs("p1").unwrap();
        assert_eq!(refs.len(), 2);
        let game = &refs[0];
        assert_eq!((game.id.as_str(), game.category), ("r1", RefCategory::Comparable));
        assert_eq!(game.source_url, None);
        assert_eq!(game.origin, Origin::LlmKnowledge);
        assert!(!game.verified);
        assert_eq!(game.metadata["learn"], "seasons");
        let barn = &refs[1];
        assert_eq!(barn.classification, Classification::Usable);
        assert_eq!(barn.license_id.as_deref(), Some("CC0-1.0"));
        assert_eq!(barn.reaction, None);

        assert!(db.set_reaction("p1", "r2", Some(Reaction::Keep)).unwrap());
        assert!(db.set_reaction("p1", "r2", Some(Reaction::MoreLikeThis)).unwrap());
        assert_eq!(db.get_ref("p1", "r2").unwrap().unwrap().reaction, Some(Reaction::MoreLikeThis));
        assert!(db.set_reaction("p1", "r2", None).unwrap());
        assert_eq!(db.get_ref("p1", "r2").unwrap().unwrap().reaction, None);
        assert!(!db.set_reaction("p1", "missing", Some(Reaction::Reject)).unwrap());
        assert!(db.get_ref("other", "r2").unwrap().is_none());

        db.set_ref_thumbnail_path("r2", Some("thumbs/r2.png")).unwrap();
        assert_eq!(db.get_ref("p1", "r2").unwrap().unwrap().thumbnail_path.as_deref(), Some("thumbs/r2.png"));

        // Deleting the project removes its references.
        db.delete_project("p1").unwrap();
        assert!(db.list_refs("p1").unwrap().is_empty());
    }
}
