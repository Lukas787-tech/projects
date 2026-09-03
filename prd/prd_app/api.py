"""JSON API used by the editor, the gallery and the manage page."""
from __future__ import annotations

from flask import Blueprint, current_app, jsonify, request

from . import models, ratelimit
from .blocks import BLOCK_LIST, CATEGORIES
from .document import EFFECTS, FONTS, PALETTES, SPACINGS, clean_domain, domain_error
from .presets import PRESET_MAP, preset_doc, preset_index
from .render import render_site
from .security import hash_ip
from .document import slugify
from .services import (
    PublishError,
    badge_url,
    check_slug,
    public_url,
    render_for_site,
    submit_publish,
    submit_update,
    take_down,
    validated_document,
)
from .services import delete_site as delete_site_service

bp = Blueprint("api", __name__, url_prefix="/api")


def fail(message: str, status: int = 400, **extra):
    payload = {"ok": False, "error": message}
    payload.update(extra)
    return jsonify(payload), status


@bp.errorhandler(PublishError)
def _publish_error(exc: PublishError):
    return fail(exc.message, exc.status, field=exc.field, retry_after=exc.retry_after)


def body() -> dict:
    data = request.get_json(silent=True)
    return data if isinstance(data, dict) else {}


def looks_automated(data: dict) -> bool:
    """Two quiet bot checks that never bother a real person."""
    if (data.get("website") or "").strip():
        return True
    try:
        elapsed = int(data.get("elapsed_ms") or 0)
    except (TypeError, ValueError):
        return True
    return 0 < elapsed < 1200


# ---------------------------------------------------------------------------
# Schema + presets
# ---------------------------------------------------------------------------

@bp.get("/schema")
def schema():
    return jsonify({
        "blocks": BLOCK_LIST,
        "categories": CATEGORIES,
        "palettes": [{"id": key, **value} for key, value in PALETTES.items()],
        "fonts": [{"id": key, **value} for key, value in FONTS.items()],
        "effects": list(EFFECTS),
        "spacings": list(SPACINGS),
        "presets": preset_index(),
    })


@bp.get("/presets")
def presets():
    return jsonify({"presets": preset_index()})


@bp.get("/presets/<preset_id>")
def preset(preset_id: str):
    if preset_id not in PRESET_MAP:
        return fail("Unknown preset.", 404)
    return jsonify({"ok": True, "id": preset_id, "doc": preset_doc(preset_id)})


# ---------------------------------------------------------------------------
# Live preview
# ---------------------------------------------------------------------------

@bp.post("/preview")
def preview():
    ip_hash = hash_ip()
    limit = ratelimit.consume("preview", ip_hash)
    if not limit.allowed:
        return fail(limit.message, 429, retry_after=limit.retry_after)
    data = body()
    doc = validated_document(data.get("doc"))
    html = render_site(doc, preview=bool(data.get("editing", True)), badge_url=badge_url(), noindex=True)
    return jsonify({"ok": True, "html": html})


def html_attachment(html: str, filename: str):
    """One self-contained file — no assets, no build step, opens anywhere."""
    safe = "".join(c for c in filename if c.isalnum() or c in "-_") or "site"
    response = current_app.response_class(html, mimetype="text/html")
    response.headers["Content-Disposition"] = f'attachment; filename="{safe}.html"'
    response.headers["Content-Length"] = str(len(html.encode("utf-8")))
    return response


@bp.post("/download")
def download_draft():
    """Take the site as a file without publishing anything."""
    ip_hash = hash_ip()
    limit = ratelimit.consume("preview", ip_hash)
    if not limit.allowed:
        return fail(limit.message, 429, retry_after=limit.retry_after)
    data = body()
    doc = validated_document(data.get("doc"))
    html = render_site(doc, preview=False, badge_url=badge_url())
    return html_attachment(html, slugify(doc["meta"]["title"]) or "site")


# ---------------------------------------------------------------------------
# Addresses
# ---------------------------------------------------------------------------

@bp.get("/slug")
def slug_check():
    raw = request.args.get("slug", "")
    clean, error = check_slug(raw)
    suggestion = "" if not error else models.suggest_slug(clean or "site")
    return jsonify({
        "ok": not error, "slug": clean, "error": error,
        "suggestion": suggestion, "url": public_url(clean or "your-site"),
    })


@bp.get("/usage")
def usage():
    return jsonify({"ok": True, "usage": ratelimit.usage(hash_ip())})


# ---------------------------------------------------------------------------
# Publish / update
# ---------------------------------------------------------------------------

@bp.post("/publish")
def publish():
    data = body()
    if looks_automated(data):
        return fail("That request looked automated. Please try again from the editor.", 400)

    outcome = submit_publish(
        raw_doc=data.get("doc"),
        slug=str(data.get("slug", ""))[:60],
        is_public=bool(data.get("public", True)),
        contact=str(data.get("contact", ""))[:120],
        note=str(data.get("note", ""))[:400],
        preset=str(data.get("preset", ""))[:40],
        template_source=str(data.get("template", ""))[:60],
        ip_hash=hash_ip(),
        user_agent=request.headers.get("User-Agent", ""),
    )
    site = outcome.site
    return jsonify({
        "ok": True,
        "slug": site["slug"],
        "status": site["status"],
        "url": site["deploy_url"] or public_url(site["slug"]),
        "manage_token": outcome.manage_token,
        "manage_url": f"/manage/{site['slug']}?t={outcome.manage_token}",
        "request_id": outcome.request_id,
        "live": site["status"] == models.STATUS_LIVE,
        "deploy_error": "" if not outcome.deploy or outcome.deploy.ok else outcome.deploy.detail,
        "queued": not outcome.auto_approved,
    })


def _authorized_site(slug: str, token: str):
    site = models.authorize_site(slug, token)
    if not site:
        raise PublishError("That manage link is not valid for this site.", status=403)
    return site


@bp.get("/sites/<slug>")
def site_status(slug: str):
    site = _authorized_site(slug, request.args.get("t", ""))
    latest = models.latest_request(site["id"]) or {}
    return jsonify({
        "ok": True,
        "slug": site["slug"],
        "title": site["title"],
        "status": site["status"],
        "public": site["is_public"],
        "url": site["deploy_url"] or public_url(site["slug"]),
        "views": site["views"],
        "remixes": site["remixes"],
        "created_at": site["created_at"],
        "published_at": site["published_at"],
        "domain": site["custom_domain"],
        "domain_verified": bool(site["domain_verified"]),
        "doc": site["doc"],
        "request": {"id": latest.get("id"), "status": latest.get("status"),
                    "note": latest.get("decision_note", ""), "error": latest.get("error", "")},
    })


@bp.post("/sites/<slug>/update")
def site_update(slug: str):
    data = body()
    site = _authorized_site(slug, str(data.get("token", "")))
    is_public = data.get("public")
    outcome = submit_update(
        site=site,
        raw_doc=data.get("doc"),
        is_public=None if is_public is None else bool(is_public),
        note=str(data.get("note", ""))[:400],
        ip_hash=hash_ip(),
        user_agent=request.headers.get("User-Agent", ""),
    )
    updated = outcome.site
    return jsonify({
        "ok": True,
        "status": updated["status"],
        "url": updated["deploy_url"] or public_url(slug),
        "queued": not outcome.auto_approved,
        "deploy_error": "" if not outcome.deploy or outcome.deploy.ok else outcome.deploy.detail,
    })


@bp.post("/sites/<slug>/offline")
def site_offline(slug: str):
    data = body()
    site = _authorized_site(slug, str(data.get("token", "")))
    take_down(site, actor="owner", reason="Taken down by its creator")
    return jsonify({"ok": True, "status": models.STATUS_OFFLINE})


@bp.post("/sites/<slug>/delete")
def site_delete(slug: str):
    data = body()
    site = _authorized_site(slug, str(data.get("token", "")))
    delete_site_service(site, actor="owner", reason="Deleted by its creator")
    return jsonify({"ok": True})


# ---------------------------------------------------------------------------
# Gallery
# ---------------------------------------------------------------------------

@bp.get("/gallery")
def gallery():
    try:
        page = max(0, int(request.args.get("page", 0)))
    except ValueError:
        page = 0
    per_page = 24
    items = models.gallery(
        sort=request.args.get("sort", "new"),
        preset=request.args.get("preset", "")[:40],
        search=request.args.get("q", "")[:60],
        limit=per_page + 1,
        offset=page * per_page,
    )
    has_more = len(items) > per_page
    return jsonify({"ok": True, "items": items[:per_page], "page": page, "has_more": has_more})


@bp.get("/templates/<slug>")
def template(slug: str):
    site = models.get_site_by_slug(slug)
    if not site or not site["is_public"] or site["status"] != models.STATUS_LIVE:
        return fail("That site is not available as a template.", 404)
    models.bump_remixes(site["id"])
    doc = models.load_doc(site)
    doc["meta"]["title"] = f"{doc['meta']['title']} (remix)"
    return jsonify({"ok": True, "doc": doc, "source": site["slug"]})


@bp.get("/sites/<slug>/download")
def site_download(slug: str):
    site = _authorized_site(slug, request.args.get("t", ""))
    return html_attachment(render_for_site(site), site["slug"])


@bp.post("/sites/<slug>/domain")
def site_domain(slug: str):
    data = body()
    site = _authorized_site(slug, str(data.get("token", "")))
    domain = clean_domain(str(data.get("domain", "")))
    error = domain_error(domain)
    if error:
        return fail(error, 400, field="domain")
    if domain and models.domain_taken(domain, site["id"]):
        return fail("Another site here already uses that domain.", 400, field="domain")
    models.set_custom_domain(site["id"], domain)
    models.audit("owner", "domain.set" if domain else "domain.clear", site["slug"], domain)
    return jsonify({
        "ok": True,
        "domain": domain,
        "verified": False,
        "cname_target": _config_host(),
        "help": ("Point the domain at this host with a CNAME record, then open it. "
                 "It verifies itself on the first visit that arrives.") if domain else "",
    })


def _config_host():
    from urllib.parse import urlparse

    return urlparse(current_app.config["PRD_CONFIG"].base_url).hostname or ""


@bp.get("/sites/<slug>/html")
def site_html(slug: str):
    """Rendered HTML for a site the caller owns (used by the manage page)."""
    site = _authorized_site(slug, request.args.get("t", ""))
    return jsonify({"ok": True, "html": render_for_site(site, preview=False)})
