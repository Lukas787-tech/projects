PRAGMA journal_mode = WAL;

CREATE TABLE IF NOT EXISTS sites (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    slug              TEXT    NOT NULL UNIQUE,
    title             TEXT    NOT NULL,
    summary           TEXT    NOT NULL DEFAULT '',
    doc               TEXT    NOT NULL,
    preset            TEXT    NOT NULL DEFAULT 'blank',
    is_public         INTEGER NOT NULL DEFAULT 1,
    status            TEXT    NOT NULL DEFAULT 'pending',
    contact           TEXT    NOT NULL DEFAULT '',
    manage_token_hash TEXT    NOT NULL,
    ip_hash           TEXT    NOT NULL DEFAULT '',
    template_source   TEXT    NOT NULL DEFAULT '',
    deploy_target     TEXT    NOT NULL DEFAULT '',
    deploy_url        TEXT    NOT NULL DEFAULT '',
    deploy_detail     TEXT    NOT NULL DEFAULT '',
    views             INTEGER NOT NULL DEFAULT 0,
    remixes           INTEGER NOT NULL DEFAULT 0,
    created_at        TEXT    NOT NULL,
    updated_at        TEXT    NOT NULL,
    published_at      TEXT
);

CREATE INDEX IF NOT EXISTS idx_sites_status  ON sites(status);
CREATE INDEX IF NOT EXISTS idx_sites_gallery ON sites(is_public, status, published_at DESC);

CREATE TABLE IF NOT EXISTS requests (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    site_id      INTEGER NOT NULL REFERENCES sites(id) ON DELETE CASCADE,
    kind         TEXT    NOT NULL DEFAULT 'publish',
    status       TEXT    NOT NULL DEFAULT 'pending',
    note         TEXT    NOT NULL DEFAULT '',
    decision_note TEXT   NOT NULL DEFAULT '',
    error        TEXT    NOT NULL DEFAULT '',
    ip_hash      TEXT    NOT NULL DEFAULT '',
    user_agent   TEXT    NOT NULL DEFAULT '',
    created_at   TEXT    NOT NULL,
    decided_at   TEXT
);

CREATE INDEX IF NOT EXISTS idx_requests_status ON requests(status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_requests_site   ON requests(site_id, created_at DESC);

CREATE TABLE IF NOT EXISTS rate_events (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    ip_hash    TEXT    NOT NULL,
    action     TEXT    NOT NULL,
    created_at REAL    NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_rate_lookup ON rate_events(action, ip_hash, created_at);

CREATE TABLE IF NOT EXISTS blocklist (
    ip_hash    TEXT PRIMARY KEY,
    reason     TEXT NOT NULL DEFAULT '',
    created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS audit_log (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    actor      TEXT NOT NULL,
    action     TEXT NOT NULL,
    target     TEXT NOT NULL DEFAULT '',
    detail     TEXT NOT NULL DEFAULT '',
    created_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_audit_time ON audit_log(created_at DESC);

CREATE TABLE IF NOT EXISTS settings (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
