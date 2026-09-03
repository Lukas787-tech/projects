"""Upgrading a database that already holds someone's sites."""
import sqlite3

from prd_app import db

# The sites table exactly as it shipped before custom domains existed.
OLD_SITES = """
CREATE TABLE sites (
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
"""


def old_database(tmp_path):
    path = tmp_path / "prd.sqlite3"
    conn = sqlite3.connect(path)
    conn.executescript(OLD_SITES)
    conn.execute(
        "INSERT INTO sites(slug, title, doc, manage_token_hash, created_at, updated_at, status)"
        " VALUES('kept', 'Kept', '{}', 'hash', '2026-01-01', '2026-01-01', 'live')")
    conn.commit()
    conn.close()
    return path


def columns(path, table="sites"):
    conn = sqlite3.connect(path)
    try:
        return {row[1] for row in conn.execute(f"PRAGMA table_info({table})")}
    finally:
        conn.close()


def test_an_older_database_gains_the_new_columns(tmp_path):
    path = old_database(tmp_path)
    assert "custom_domain" not in columns(path)
    db.init_db(path)                      # this is what runs on every boot
    assert {"custom_domain", "domain_verified"} <= columns(path)


def test_upgrading_keeps_the_sites_that_are_already_there(tmp_path):
    path = old_database(tmp_path)
    db.init_db(path)
    conn = sqlite3.connect(path)
    try:
        row = conn.execute("SELECT slug, title, custom_domain FROM sites").fetchone()
    finally:
        conn.close()
    assert row == ("kept", "Kept", "")


def test_booting_twice_is_harmless(tmp_path):
    path = old_database(tmp_path)
    db.init_db(path)
    db.init_db(path)
    assert {"custom_domain", "domain_verified"} <= columns(path)


def test_a_fresh_database_is_created_complete(tmp_path):
    path = tmp_path / "fresh.sqlite3"
    db.init_db(path)
    assert {"custom_domain", "domain_verified"} <= columns(path)
