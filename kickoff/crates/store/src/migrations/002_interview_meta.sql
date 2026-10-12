-- Interview state that isn't the transcript (messages) or the brief
-- (brief_revisions): open flags, a pending "revisit" request, turn count.
CREATE TABLE interview_meta (
    project_id  TEXT PRIMARY KEY REFERENCES projects(id) ON DELETE CASCADE,
    flags_json  TEXT NOT NULL DEFAULT '[]',
    revisit     TEXT,
    turn        INTEGER NOT NULL DEFAULT 0,
    updated_at  TEXT NOT NULL
);
