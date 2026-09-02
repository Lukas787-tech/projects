"""Owner dashboard.

Password-protected (PRD_ADMIN_PASSWORD); this is where publish requests are
reviewed, sites are deployed or taken down, and abuse is blocked.
"""
from __future__ import annotations

import secrets
import time
from functools import wraps

from flask import (
    Blueprint, abort, current_app, flash, redirect, render_template, request, session, url_for,
)

from . import models, ratelimit
from .deploy import get_deployer
from .presets import PRESET_MAP
from .security import check_admin_password, hash_ip
from .services import PublishError, approve_request, deploy_site, public_url, redeploy_all
from .services import delete_site as delete_site_service
from .services import reject_request, take_down

bp = Blueprint("admin", __name__, url_prefix="/admin")

LOGIN_WINDOW = 900
LOGIN_ATTEMPTS = 8


def _config():
    return current_app.config["PRD_CONFIG"]


def logged_in() -> bool:
    return bool(session.get("prd_admin"))


def csrf_token() -> str:
    token = session.get("prd_csrf")
    if not token:
        token = secrets.token_urlsafe(24)
        session["prd_csrf"] = token
    return token


def require_csrf() -> None:
    sent = request.form.get("csrf", "")
    if not sent or not secrets.compare_digest(sent, session.get("prd_csrf", "")):
        abort(400, "Session expired — please try again.")


def admin_required(view):
    @wraps(view)
    def wrapper(*args, **kwargs):
        if not _config().admin_enabled:
            return render_template("admin/disabled.html"), 503
        if not logged_in():
            return redirect(url_for("admin.login", next=request.path))
        return view(*args, **kwargs)

    return wrapper


@bp.app_context_processor
def inject_admin():
    return {"admin_logged_in": logged_in(), "csrf_token": csrf_token}


@bp.route("/login", methods=["GET", "POST"])
def login():
    config = _config()
    if not config.admin_enabled:
        return render_template("admin/disabled.html"), 503
    if request.method == "POST":
        require_csrf()
        ip_hash = hash_ip()
        attempts = ratelimit._count(ip_hash, "admin_login", time.time() - LOGIN_WINDOW)
        if attempts >= LOGIN_ATTEMPTS:
            flash("Too many attempts. Wait fifteen minutes.", "error")
            return render_template("admin/login.html"), 429
        ratelimit.record("admin_login", ip_hash)
        if check_admin_password(request.form.get("password", "")):
            session.clear()
            session["prd_admin"] = True
            session.permanent = False
            models.audit("owner", "admin.login")
            return redirect(request.args.get("next") or url_for("admin.dashboard"))
        flash("Wrong password.", "error")
    return render_template("admin/login.html")


@bp.post("/logout")
def logout():
    require_csrf()
    session.clear()
    return redirect(url_for("views.index"))


@bp.get("/")
@admin_required
def dashboard():
    status = request.args.get("status", "pending")
    return render_template(
        "admin/dashboard.html",
        requests=models.list_requests(status if status != "all" else "", limit=120),
        status=status,
        stats=models.stats(),
        deployer=get_deployer().status(),
        presets=PRESET_MAP,
    )


@bp.get("/sites")
@admin_required
def sites():
    return render_template("admin/sites.html", sites=models.recent_sites(limit=200))


@bp.get("/audit")
@admin_required
def audit():
    return render_template("admin/audit.html", entries=models.audit_log(120),
                           blocked=models.blocklist())


@bp.post("/request/<int:request_id>/<action>")
@admin_required
def request_action(request_id: int, action: str):
    require_csrf()
    note = request.form.get("note", "")[:400]
    try:
        if action == "approve":
            result = approve_request(request_id, actor="owner", note=note)
            flash("Deployed." if result.ok else f"Deploy failed: {result.detail}",
                  "success" if result.ok else "error")
        elif action == "reject":
            reject_request(request_id, actor="owner", note=note or "Rejected by the owner")
            flash("Request rejected.", "success")
        else:
            abort(404)
    except PublishError as exc:
        flash(exc.message, "error")
    return redirect(request.referrer or url_for("admin.dashboard"))


@bp.post("/site/<int:site_id>/<action>")
@admin_required
def site_action(site_id: int, action: str):
    require_csrf()
    site = models.get_site(site_id)
    if not site:
        abort(404)
    if action == "deploy":
        result = deploy_site(site)
        models.audit("owner", "deploy.manual", site["slug"], result.detail[:200])
        flash("Deployed." if result.ok else f"Deploy failed: {result.detail}",
              "success" if result.ok else "error")
    elif action == "offline":
        take_down(site, actor="owner", reason=request.form.get("note", "")[:200])
        flash("Taken offline.", "success")
    elif action == "public":
        models.update_site_doc(site_id, models.load_doc(site), is_public=not site["is_public"])
        flash("Gallery visibility toggled.", "success")
    elif action == "block":
        models.block_ip(site["ip_hash"], f"Blocked while reviewing {site['slug']}")
        flash("That network can no longer publish.", "success")
    elif action == "delete":
        delete_site_service(site, actor="owner", reason=request.form.get("note", "")[:200])
        flash("Site deleted.", "success")
        return redirect(url_for("admin.sites"))
    else:
        abort(404)
    return redirect(request.referrer or url_for("admin.sites"))


@bp.post("/unblock")
@admin_required
def unblock():
    require_csrf()
    models.unblock_ip(request.form.get("ip_hash", "")[:64])
    flash("Unblocked.", "success")
    return redirect(url_for("admin.audit"))


@bp.post("/redeploy-all")
@admin_required
def redeploy_everything():
    require_csrf()
    result = redeploy_all()
    models.audit("owner", "deploy.all", detail=str(result))
    flash(f"Redeployed {result['redeployed']} sites ({result['failed']} failed).", "success")
    return redirect(url_for("admin.dashboard"))


@bp.get("/preview/<int:site_id>")
@admin_required
def preview(site_id: int):
    site = models.get_site(site_id)
    if not site:
        abort(404)
    from flask import Response

    from .services import render_for_site

    response = Response(render_for_site(site), mimetype="text/html")
    response.headers["Content-Security-Policy"] = (
        "default-src 'none'; img-src * data:; style-src 'unsafe-inline' https://fonts.googleapis.com; "
        "font-src https://fonts.gstatic.com; script-src 'unsafe-inline'; frame-src https:"
    )
    return response
