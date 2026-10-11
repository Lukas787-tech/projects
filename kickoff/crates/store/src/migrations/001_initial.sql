-- Kickoff schema v1.
-- Raw secrets are never stored here: keys_meta holds labels and env-var names
-- only; the values live in the OS credential store.
-- Timestamps are RFC 3339 UTC strings.

CREATE TABLE settings (
    key         TEXT PRIMARY KEY,
    value_json  TEXT NOT NULL,
    updated_at  TEXT NOT NULL
);

CREATE TABLE projects (
    id            TEXT PRIMARY KEY,
    name          TEXT NOT NULL,
    pack_id       TEXT NOT NULL,
    pack_version  TEXT NOT NULL,
    stage         TEXT NOT NULL DEFAULT 'interview'
                  CHECK (stage IN ('interview', 'research', 'kit', 'done')),
    engine        TEXT,
    folder_path   TEXT,
    created_at    TEXT NOT NULL,
    updated_at    TEXT NOT NULL
);

-- Interview transcript.
CREATE TABLE messages (
    id          INTEGER PRIMARY KEY,
    project_id  TEXT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    role        TEXT NOT NULL CHECK (role IN ('user', 'producer', 'system')),
    content     TEXT NOT NULL,
    input_mode  TEXT CHECK (input_mode IN ('text', 'voice')),
    provider_id TEXT,
    model       TEXT,
    created_at  TEXT NOT NULL
);
CREATE INDEX messages_project ON messages(project_id, id);

-- Every change to a project brief is a new revision.
CREATE TABLE brief_revisions (
    id          INTEGER PRIMARY KEY,
    project_id  TEXT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    rev         INTEGER NOT NULL,
    schema_id   TEXT NOT NULL,
    brief_json  TEXT NOT NULL,
    diff_json   TEXT,
    cause       TEXT NOT NULL CHECK (cause IN ('extraction', 'user_edit', 'triage', 'import')),
    message_id  INTEGER REFERENCES messages(id),
    created_at  TEXT NOT NULL,
    UNIQUE (project_id, rev)
);

-- References and candidate assets. License fields are mandatory metadata;
-- `unknown` is treated as reference-only until verified.
CREATE TABLE refs (
    id              TEXT PRIMARY KEY,
    project_id      TEXT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    category        TEXT NOT NULL CHECK (category IN ('comparable', 'visual', 'asset')),
    asset_kind      TEXT,
    title           TEXT NOT NULL,
    summary         TEXT,
    source_id       TEXT,
    source_url      TEXT NOT NULL,
    author          TEXT,
    license_name    TEXT,
    license_url     TEXT,
    license_id      TEXT,
    classification  TEXT NOT NULL DEFAULT 'unknown'
                    CHECK (classification IN ('reference_only', 'usable', 'unknown')),
    attribution     TEXT,
    thumbnail_url   TEXT,
    thumbnail_path  TEXT,
    origin          TEXT NOT NULL CHECK (origin IN ('search', 'asset_api', 'llm_knowledge')),
    verified        INTEGER NOT NULL DEFAULT 0,
    metadata_json   TEXT,
    created_at      TEXT NOT NULL
);
CREATE INDEX refs_project ON refs(project_id, category);

CREATE TABLE reactions (
    id          INTEGER PRIMARY KEY,
    ref_id      TEXT NOT NULL REFERENCES refs(id) ON DELETE CASCADE,
    project_id  TEXT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    reaction    TEXT NOT NULL CHECK (reaction IN ('keep', 'reject', 'more_like_this')),
    note        TEXT,
    created_at  TEXT NOT NULL
);

-- Notable decisions (engine choice, scope cuts) with their reasons.
CREATE TABLE decisions (
    id          INTEGER PRIMARY KEY,
    project_id  TEXT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    kind        TEXT NOT NULL,
    summary     TEXT NOT NULL,
    rationale   TEXT,
    field_path  TEXT,
    created_at  TEXT NOT NULL
);

CREATE TABLE kit_runs (
    id             TEXT PRIMARY KEY,
    project_id     TEXT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    brief_rev      INTEGER NOT NULL,
    engine         TEXT NOT NULL,
    status         TEXT NOT NULL CHECK (status IN ('running', 'review', 'written', 'failed', 'cancelled')),
    output_path    TEXT,
    manifest_json  TEXT NOT NULL,
    created_at     TEXT NOT NULL,
    finished_at    TEXT
);

CREATE TABLE kit_files (
    id            INTEGER PRIMARY KEY,
    run_id        TEXT NOT NULL REFERENCES kit_runs(id) ON DELETE CASCADE,
    path          TEXT NOT NULL,
    generator     TEXT NOT NULL CHECK (generator IN ('template', 'data', 'llm_sections')),
    status        TEXT NOT NULL CHECK (status IN ('pending', 'done', 'failed')),
    content_hash  TEXT,
    error         TEXT,
    created_at    TEXT NOT NULL,
    UNIQUE (run_id, path)
);

-- Daily usage per provider, for the free-tier meter. `units` counts the
-- provider's own unit (TTS bytes, STT audio seconds, search credits).
CREATE TABLE usage_daily (
    provider_id        TEXT NOT NULL,
    day                TEXT NOT NULL,
    calls              INTEGER NOT NULL DEFAULT 0,
    prompt_tokens      INTEGER NOT NULL DEFAULT 0,
    completion_tokens  INTEGER NOT NULL DEFAULT 0,
    units              INTEGER NOT NULL DEFAULT 0,
    rate_limited       INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (provider_id, day)
);

-- Key library metadata. The secret itself is in the OS credential store under
-- the same id.
CREATE TABLE keys_meta (
    id            TEXT PRIMARY KEY,
    label         TEXT NOT NULL,
    kind          TEXT NOT NULL CHECK (kind IN ('llm', 'stt', 'tts', 'search', 'asset', 'analytics', 'other')),
    provider_id   TEXT,
    env_var       TEXT NOT NULL,
    created_at    TEXT NOT NULL,
    last_used_at  TEXT,
    last_test_at  TEXT,
    last_test_ok  INTEGER,
    notes         TEXT
);
CREATE UNIQUE INDEX keys_meta_env_var ON keys_meta(env_var);

-- Which projects may use which keys (checked by the launcher).
CREATE TABLE key_grants (
    key_id      TEXT NOT NULL REFERENCES keys_meta(id) ON DELETE CASCADE,
    project_id  TEXT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    created_at  TEXT NOT NULL,
    PRIMARY KEY (key_id, project_id)
);

-- Long-running, resumable work (research runs, kit generation).
CREATE TABLE jobs (
    id          TEXT PRIMARY KEY,
    project_id  TEXT REFERENCES projects(id) ON DELETE CASCADE,
    kind        TEXT NOT NULL,
    status      TEXT NOT NULL CHECK (status IN ('queued', 'running', 'done', 'failed', 'cancelled')),
    progress    REAL NOT NULL DEFAULT 0,
    state_json  TEXT,
    error       TEXT,
    created_at  TEXT NOT NULL,
    updated_at  TEXT NOT NULL
);
