"""Public pages: landing, editor, gallery, served sites and the manage screen."""
from __future__ import annotations

from flask import Blueprint, Response, abort, current_app, redirect, render_template, request, url_for

from . import models
from .presets import PRESET_MAP, preset_index
from .services import public_url, render_for_site

bp = Blueprint("views", __name__)

SITE_CSP = (
    "default-src 'none'; img-src * data: blob:; media-src *; "
    "style-src 'unsafe-inline' https://fonts.googleapis.com; "
    "font-src https://fonts.gstatic.com data:; script-src 'unsafe-inline'; "
    "frame-src https:; base-uri 'none'; form-action 'none'"
)


def _config():
    return current_app.config["PRD_CONFIG"]


@bp.app_context_processor
def inject_globals():
    config = _config()
    return {
        "base_url": config.base_url,
        "admin_enabled": config.admin_enabled,
        "limits": config.limits,
        "auto_approve": config.auto_approve,
    }


@bp.get("/")
def index():
    return render_template(
        "index.html",
        presets=preset_index(),
        showcase=models.gallery(sort="popular", limit=6),
        stats=models.stats(),
    )


@bp.get("/editor")
def editor():
    return render_template(
        "editor.html",
        presets=preset_index(),
        start_preset=request.args.get("preset", ""),
        start_template=request.args.get("template", ""),
        edit_slug=request.args.get("edit", ""),
        edit_token=request.args.get("t", ""),
    )


@bp.get("/gallery")
def gallery():
    return render_template("gallery.html", presets=preset_index())


@bp.get("/site/<slug>")
def site_detail(slug: str):
    site = models.get_site_by_slug(slug)
    if not site or site["status"] != models.STATUS_LIVE or not site["is_public"]:
        abort(404)
    preset = PRESET_MAP.get(site["preset"])
    return render_template("site_detail.html", site=site, preset=preset,
                           live_url=site["deploy_url"] or public_url(slug))


@bp.get("/s/<slug>")
@bp.get("/s/<slug>/")
def serve_site(slug: str):
    site = models.get_site_by_slug(slug)
    if not site:
        abort(404)
    if site["status"] in (models.STATUS_OFFLINE, models.STATUS_REJECTED):
        return render_template("site_gone.html", site=site), 410
    if site["status"] != models.STATUS_LIVE:
        return render_template("site_pending.html", site=site), 202

    path = _config().sites_root / slug / "index.html"
    try:
        html = path.read_text(encoding="utf-8")
    except OSError:
        html = render_for_site(site)

    models.bump_views(site["id"])
    response = Response(html, mimetype="text/html")
    response.headers["Content-Security-Policy"] = SITE_CSP
    response.headers["X-Content-Type-Options"] = "nosniff"
    response.headers["Cache-Control"] = "public, max-age=120"
    if not site["is_public"]:
        response.headers["X-Robots-Tag"] = "noindex"
    return response


@bp.get("/manage/<slug>")
def manage(slug: str):
    token = request.args.get("t", "")
    site = models.authorize_site(slug, token)
    if not site:
        return render_template("manage_denied.html", slug=slug), 403
    latest = models.latest_request(site["id"]) or {}
    return render_template("manage.html", site=site, token=token, latest=latest,
                           live_url=site["deploy_url"] or public_url(slug))


@bp.get("/healthz")
def healthz():
    return {"ok": True, "sites": models.stats()["sites_total"]}


@bp.get("/robots.txt")
def robots():
    lines = ["User-agent: *", "Allow: /", "Disallow: /admin", "Disallow: /manage", "Disallow: /api"]
    if _config().base_url:
        lines.append(f"Sitemap: {_config().base_url}/sitemap.xml")
    return Response("\n".join(lines) + "\n", mimetype="text/plain")


@bp.get("/sitemap.xml")
def sitemap():
    base = _config().base_url
    urls = [f"{base}/", f"{base}/gallery", f"{base}/editor"] if base else []
    urls += [f"{base}/s/{item['slug']}" for item in models.gallery(limit=500)] if base else []
    body = "".join(f"<url><loc>{u}</loc></url>" for u in urls)
    return Response(
        f'<?xml version="1.0" encoding="UTF-8"?><urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">{body}</urlset>',
        mimetype="application/xml",
    )


@bp.get("/new")
def new_site():
    return redirect(url_for("views.editor", preset=request.args.get("preset", "blank")))
