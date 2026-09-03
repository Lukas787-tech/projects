"""The P-R-D pipeline: personalise -> request -> deploy.

Both the public API and the owner dashboard call into here, so the rules about
what may be published and how a site reaches the internet live in one place.
"""
from __future__ import annotations

from dataclasses import dataclass

from flask import current_app

from . import models, ratelimit
from .deploy import DeployResult, get_deployer
from .document import DocumentError, normalize_document, slug_error, slugify
from .presets import PRESET_MAP
from .render import render_site
from .security import new_manage_token


class PublishError(Exception):
    """A user-facing problem: the message is safe to show as-is."""

    def __init__(self, message: str, *, status: int = 400, field: str = "", retry_after: int = 0) -> None:
        super().__init__(message)
        self.message = message
        self.status = status
        self.field = field
        self.retry_after = retry_after


@dataclass
class PublishOutcome:
    site: dict
    request_id: int
    manage_token: str
    auto_approved: bool
    deploy: DeployResult | None = None


def _config():
    return current_app.config["PRD_CONFIG"]


def validated_document(raw) -> dict:
    config = _config()
    try:
        return normalize_document(raw, max_blocks=config.max_blocks, max_bytes=config.max_document_bytes)
    except DocumentError as exc:
        raise PublishError(str(exc), field="doc") from exc


def public_url(slug: str) -> str:
    base = _config().base_url
    return f"{base}/{slug}" if base else f"/{slug}"


def canonical_url(site: dict) -> str:
    """A verified custom domain is the site's real address; prefer it."""
    if site.get("custom_domain") and site.get("domain_verified"):
        return f"https://{site['custom_domain']}/"
    return public_url(site["slug"])


def badge_url() -> str:
    return _config().base_url or "/"


def render_for_site(site: dict, *, preview: bool = False) -> str:
    doc = models.load_doc(site)
    return render_site(
        doc,
        preview=preview,
        badge_url=badge_url(),
        canonical=canonical_url(site),
        noindex=not site["is_public"],
    )


# ---------------------------------------------------------------------------
# Requesting
# ---------------------------------------------------------------------------

def check_slug(slug: str) -> tuple[str, str]:
    """Return (clean_slug, error_message)."""
    clean = slugify(slug)
    error = slug_error(clean)
    if error:
        return clean, error
    if models.slug_taken(clean):
        return clean, "That address is already taken."
    return clean, ""


def submit_publish(
    *,
    raw_doc,
    slug: str,
    is_public: bool,
    contact: str,
    note: str,
    preset: str,
    template_source: str,
    ip_hash: str,
    user_agent: str,
) -> PublishOutcome:
    doc = validated_document(raw_doc)

    limit = ratelimit.check("publish", ip_hash)
    if not limit.allowed:
        raise PublishError(limit.message, status=429, retry_after=limit.retry_after)

    clean_slug, error = check_slug(slug or doc["meta"]["title"])
    if error:
        suggestion = models.suggest_slug(slugify(slug or doc["meta"]["title"]) or "site")
        raise PublishError(f"{error} How about '{suggestion}'?", field="slug")

    preset = preset if preset in PRESET_MAP else "custom"
    manage_token = new_manage_token()

    site_id = models.create_site(
        slug=clean_slug,
        doc=doc,
        manage_token=manage_token,
        is_public=is_public,
        preset=preset,
        contact=contact,
        ip_hash=ip_hash,
        template_source=template_source,
    )
    request_id = models.create_request(
        site_id=site_id, kind="publish", note=note, ip_hash=ip_hash, user_agent=user_agent
    )
    ratelimit.record("publish", ip_hash)
    models.audit("visitor", "request.publish", clean_slug, note[:120])

    site = models.get_site(site_id)
    outcome = PublishOutcome(site=site, request_id=request_id, manage_token=manage_token,
                             auto_approved=False)

    if _config().auto_approve:
        result = approve_request(request_id, actor="auto")
        outcome.auto_approved = True
        outcome.deploy = result
        outcome.site = models.get_site(site_id)
    return outcome


def submit_update(*, site: dict, raw_doc, is_public: bool | None, note: str, ip_hash: str,
                  user_agent: str) -> PublishOutcome:
    doc = validated_document(raw_doc)

    limit = ratelimit.check("update", ip_hash)
    if not limit.allowed:
        raise PublishError(limit.message, status=429, retry_after=limit.retry_after)

    models.update_site_doc(site["id"], doc, is_public=is_public)
    ratelimit.record("update", ip_hash)

    was_live = site["status"] == models.STATUS_LIVE
    request_id = models.create_request(
        site_id=site["id"], kind="update", note=note, ip_hash=ip_hash, user_agent=user_agent
    )
    models.audit("owner", "request.update", site["slug"], note[:120])

    outcome = PublishOutcome(site=models.get_site(site["id"]), request_id=request_id,
                             manage_token="", auto_approved=False)
    # An already-live site redeploys straight away: the owner proved ownership
    # with their manage token, and the content was reviewed once already.
    if was_live or _config().auto_approve:
        outcome.deploy = approve_request(request_id, actor="auto")
        outcome.auto_approved = True
        outcome.site = models.get_site(site["id"])
    return outcome


# ---------------------------------------------------------------------------
# Deploying
# ---------------------------------------------------------------------------

def deploy_site(site: dict) -> DeployResult:
    html = render_for_site(site)
    deployer = get_deployer()
    result = deployer.deploy(site["slug"], html)
    detail = result.detail
    if result.extra.get("static_url"):
        detail = f"{detail} Static mirror: {result.extra['static_url']}"
    models.set_deploy(site["id"], target=result.target,
                      url=result.url or public_url(site["slug"]), detail=detail)
    if result.ok:
        models.set_status(site["id"], models.STATUS_LIVE)
    else:
        models.set_status(site["id"], models.STATUS_FAILED)
    return result


def approve_request(request_id: int, *, actor: str, note: str = "") -> DeployResult:
    request_row = models.get_request(request_id)
    if not request_row:
        raise PublishError("That request no longer exists.", status=404)
    site = models.get_site(request_row["site_id"])
    if not site:
        raise PublishError("That site no longer exists.", status=404)

    result = deploy_site(site)
    if result.ok:
        models.decide_request(request_id, status=models.REQUEST_DEPLOYED, decision_note=note)
        models.audit(actor, "deploy.ok", site["slug"], result.detail[:200])
    else:
        models.decide_request(request_id, status=models.REQUEST_FAILED, decision_note=note,
                              error=result.detail)
        models.audit(actor, "deploy.failed", site["slug"], result.detail[:200])
    return result


def reject_request(request_id: int, *, actor: str, note: str) -> None:
    request_row = models.get_request(request_id)
    if not request_row:
        raise PublishError("That request no longer exists.", status=404)
    models.decide_request(request_id, status=models.REQUEST_REJECTED, decision_note=note)
    models.set_status(request_row["site_id"], models.STATUS_REJECTED)
    site = models.get_site(request_row["site_id"])
    models.audit(actor, "request.rejected", site["slug"] if site else str(request_row["site_id"]), note)


def take_down(site: dict, *, actor: str, reason: str = "") -> DeployResult:
    result = get_deployer().remove(site["slug"])
    models.set_status(site["id"], models.STATUS_OFFLINE)
    models.audit(actor, "site.offline", site["slug"], reason)
    return result


def delete_site(site: dict, *, actor: str, reason: str = "") -> None:
    get_deployer().remove(site["slug"])
    models.delete_site(site["id"])
    models.audit(actor, "site.deleted", site["slug"], reason)


def redeploy_all() -> dict:
    """Re-render every live site (used after a renderer change)."""
    done, failed = 0, 0
    for row in models.recent_sites(limit=500):
        if row["status"] != models.STATUS_LIVE:
            continue
        site = models.get_site(row["id"])
        if site and deploy_site(site).ok:
            done += 1
        else:
            failed += 1
    return {"redeployed": done, "failed": failed}
