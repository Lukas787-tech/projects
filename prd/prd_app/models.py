"""Data access for sites, publish requests, the gallery and the audit log."""
from __future__ import annotations

import json
import sqlite3
from datetime import datetime, timezone
from typing import Any, Iterable

from . import db
from .document import document_summary, normalize_document
from .security import hash_token, token_matches

STATUS_PENDING = "pending"
STATUS_LIVE = "live"
STATUS_REJECTED = "rejected"
STATUS_OFFLINE = "offline"
STATUS_FAILED = "failed"

REQUEST_PENDING = "pending"
REQUEST_APPROVED = "approved"
REQUEST_REJECTED = "rejected"
REQUEST_DEPLOYED = "deployed"
REQUEST_FAILED = "failed"


def now_iso() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat()


def _row_to_site(row: sqlite3.Row | None) -> dict | None:
    if row is None:
        return None
    site = dict(row)
    try:
        site["doc"] = json.loads(site["doc"])
    except (ValueError, TypeError):
        site["doc"] = None
    site["is_public"] = bool(site["is_public"])
    return site


# ---------------------------------------------------------------------------
# Sites
# ---------------------------------------------------------------------------

def slug_taken(slug: str) -> bool:
    return db.query_one("SELECT 1 FROM sites WHERE slug = ?", (slug,)) is not None


def suggest_slug(base: str) -> str:
    """First free variation of `base` (base, base-2, base-3 ...)."""
    base = base[:36] or "site"
    if not slug_taken(base):
        return base
    for suffix in range(2, 60):
        candidate = f"{base}-{suffix}"
        if not slug_taken(candidate):
            return candidate
    import secrets

    return f"{base}-{secrets.token_hex(3)}"


def create_site(
    *,
    slug: str,
    doc: dict,
    manage_token: str,
    is_public: bool,
    preset: str,
    contact: str,
    ip_hash: str,
    template_source: str = "",
    status: str = STATUS_PENDING,
) -> int:
    stamp = now_iso()
    return db.insert(
        """INSERT INTO sites(slug, title, summary, doc, preset, is_public, status, contact,
                             manage_token_hash, ip_hash, template_source, created_at, updated_at)
           VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)""",
        (slug, doc["meta"]["title"], document_summary(doc), json.dumps(doc, ensure_ascii=False),
         preset, int(is_public), status, contact, hash_token(manage_token), ip_hash,
         template_source, stamp, stamp),
    )


def get_site(site_id: int) -> dict | None:
    return _row_to_site(db.query_one("SELECT * FROM sites WHERE id = ?", (site_id,)))


def get_site_by_slug(slug: str) -> dict | None:
    return _row_to_site(db.query_one("SELECT * FROM sites WHERE slug = ?", (slug,)))


def get_site_by_domain(domain: str) -> dict | None:
    domain = (domain or "").strip().lower().removeprefix("www.")
    if not domain:
        return None
    return _row_to_site(db.query_one(
        "SELECT * FROM sites WHERE custom_domain = ? AND status = 'live'", (domain,)))


def set_custom_domain(site_id: int, domain: str) -> None:
    db.execute("UPDATE sites SET custom_domain = ?, domain_verified = 0, updated_at = ? WHERE id = ?",
               (domain, now_iso(), site_id))


def mark_domain_verified(site_id: int) -> None:
    """The first request that actually arrives on the domain proves the DNS."""
    db.execute("UPDATE sites SET domain_verified = 1 WHERE id = ? AND domain_verified = 0", (site_id,))


def domain_taken(domain: str, exclude_site: int = 0) -> bool:
    row = db.query_one("SELECT id FROM sites WHERE custom_domain = ? AND id <> ?",
                       (domain, exclude_site))
    return row is not None


def authorize_site(slug: str, token: str) -> dict | None:
    """Return the site if `token` is its manage token."""
    site = get_site_by_slug(slug)
    if site and token_matches(token, site["manage_token_hash"]):
        return site
    return None


def update_site_doc(site_id: int, doc: dict, *, is_public: bool | None = None) -> None:
    fields = ["title = ?", "summary = ?", "doc = ?", "updated_at = ?"]
    params: list[Any] = [doc["meta"]["title"], document_summary(doc),
                         json.dumps(doc, ensure_ascii=False), now_iso()]
    if is_public is not None:
        fields.append("is_public = ?")
        params.append(int(is_public))
    params.append(site_id)
    db.execute(f"UPDATE sites SET {', '.join(fields)} WHERE id = ?", params)


def set_status(site_id: int, status: str) -> None:
    published = ", published_at = COALESCE(published_at, ?)" if status == STATUS_LIVE else ""
    params: list[Any] = [status, now_iso()]
    if published:
        params.append(now_iso())
    params.append(site_id)
    db.execute(
        f"UPDATE sites SET status = ?, updated_at = ?{published} WHERE id = ?",
        params,
    )


def set_deploy(site_id: int, *, target: str, url: str, detail: str) -> None:
    db.execute(
        "UPDATE sites SET deploy_target = ?, deploy_url = ?, deploy_detail = ?, updated_at = ? WHERE id = ?",
        (target, url, detail[:500], now_iso(), site_id),
    )


def bump_views(site_id: int) -> None:
    db.execute("UPDATE sites SET views = views + 1 WHERE id = ?", (site_id,))


def bump_remixes(site_id: int) -> None:
    db.execute("UPDATE sites SET remixes = remixes + 1 WHERE id = ?", (site_id,))


def delete_site(site_id: int) -> None:
    db.execute("DELETE FROM sites WHERE id = ?", (site_id,))


GALLERY_SORTS = {
    "new": "published_at DESC, id DESC",
    "popular": "views DESC, id DESC",
    "remixed": "remixes DESC, id DESC",
}


def gallery(*, sort: str = "new", preset: str = "", search: str = "",
            limit: int = 24, offset: int = 0) -> list[dict]:
    where = ["is_public = 1", "status = ?"]
    params: list[Any] = [STATUS_LIVE]
    if preset:
        where.append("preset = ?")
        params.append(preset)
    if search:
        where.append("(title LIKE ? OR summary LIKE ? OR slug LIKE ?)")
        needle = f"%{search[:60]}%"
        params += [needle, needle, needle]
    order = GALLERY_SORTS.get(sort, GALLERY_SORTS["new"])
    params += [limit, offset]
    rows = db.query(
        f"""SELECT id, slug, title, summary, preset, views, remixes, published_at, doc
            FROM sites WHERE {' AND '.join(where)} ORDER BY {order} LIMIT ? OFFSET ?""",
        params,
    )
    out = []
    for row in rows:
        item = dict(row)
        try:
            doc = json.loads(item.pop("doc"))
            item["theme"] = doc.get("theme", {})
            item["favicon"] = doc.get("meta", {}).get("favicon", "🌐")
        except (ValueError, TypeError):
            item["theme"], item["favicon"] = {}, "🌐"
        out.append(item)
    return out


def gallery_count(**kwargs: Any) -> int:
    return len(gallery(limit=1000, **kwargs))


# ---------------------------------------------------------------------------
# Requests
# ---------------------------------------------------------------------------

def create_request(*, site_id: int, kind: str, note: str, ip_hash: str, user_agent: str) -> int:
    return db.insert(
        """INSERT INTO requests(site_id, kind, status, note, ip_hash, user_agent, created_at)
           VALUES(?,?,?,?,?,?,?)""",
        (site_id, kind, REQUEST_PENDING, note[:500], ip_hash, user_agent[:200], now_iso()),
    )


def get_request(request_id: int) -> dict | None:
    row = db.query_one("SELECT * FROM requests WHERE id = ?", (request_id,))
    return dict(row) if row else None


def latest_request(site_id: int) -> dict | None:
    row = db.query_one(
        "SELECT * FROM requests WHERE site_id = ? ORDER BY id DESC LIMIT 1", (site_id,)
    )
    return dict(row) if row else None


def list_requests(status: str = "", limit: int = 100, offset: int = 0) -> list[dict]:
    where, params = "", []
    if status:
        where = "WHERE r.status = ?"
        params.append(status)
    params += [limit, offset]
    rows = db.query(
        f"""SELECT r.*, s.slug, s.title, s.is_public, s.preset, s.status AS site_status,
                   s.deploy_url, s.views
            FROM requests r JOIN sites s ON s.id = r.site_id
            {where} ORDER BY r.id DESC LIMIT ? OFFSET ?""",
        params,
    )
    return [dict(row) for row in rows]


def decide_request(request_id: int, *, status: str, decision_note: str = "", error: str = "") -> None:
    db.execute(
        "UPDATE requests SET status = ?, decision_note = ?, error = ?, decided_at = ? WHERE id = ?",
        (status, decision_note[:400], error[:500], now_iso(), request_id),
    )


def pending_count() -> int:
    return int(db.scalar("SELECT COUNT(*) FROM requests WHERE status = 'pending'"))


# ---------------------------------------------------------------------------
# Moderation + audit
# ---------------------------------------------------------------------------

def block_ip(ip_hash: str, reason: str) -> None:
    db.execute(
        "INSERT OR REPLACE INTO blocklist(ip_hash, reason, created_at) VALUES(?,?,?)",
        (ip_hash, reason[:200], now_iso()),
    )


def unblock_ip(ip_hash: str) -> None:
    db.execute("DELETE FROM blocklist WHERE ip_hash = ?", (ip_hash,))


def blocklist() -> list[dict]:
    return [dict(row) for row in db.query("SELECT * FROM blocklist ORDER BY created_at DESC LIMIT 200")]


def audit(actor: str, action: str, target: str = "", detail: str = "") -> None:
    db.execute(
        "INSERT INTO audit_log(actor, action, target, detail, created_at) VALUES(?,?,?,?,?)",
        (actor[:60], action[:60], target[:120], detail[:400], now_iso()),
    )


def audit_log(limit: int = 60) -> list[dict]:
    return [dict(row) for row in db.query(
        "SELECT * FROM audit_log ORDER BY id DESC LIMIT ?", (limit,))]


def stats() -> dict:
    return {
        "sites_total": int(db.scalar("SELECT COUNT(*) FROM sites")),
        "sites_live": int(db.scalar("SELECT COUNT(*) FROM sites WHERE status = 'live'")),
        "sites_public": int(db.scalar("SELECT COUNT(*) FROM sites WHERE status = 'live' AND is_public = 1")),
        "pending": pending_count(),
        "views": int(db.scalar("SELECT COALESCE(SUM(views), 0) FROM sites")),
        "today": int(db.scalar(
            "SELECT COUNT(*) FROM sites WHERE created_at > ?",
            (datetime.now(timezone.utc).replace(hour=0, minute=0, second=0, microsecond=0).isoformat(),),
        )),
    }


def recent_sites(limit: int = 30) -> list[dict]:
    rows = db.query(
        """SELECT id, slug, title, status, is_public, preset, views, remixes, deploy_url,
                  custom_domain, domain_verified, created_at, published_at, ip_hash
           FROM sites ORDER BY id DESC LIMIT ?""", (limit,))
    return [dict(row) for row in rows]


def load_doc(site: dict) -> dict:
    """Re-validate a stored document before it is rendered or edited."""
    return normalize_document(site["doc"])
