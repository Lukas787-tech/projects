"""End-to-end behaviour of the account-free request pipeline."""
import json

import pytest

from conftest import publish


def test_schema_endpoint_describes_every_block(client):
    data = client.get("/api/schema").get_json()
    assert len(data["blocks"]) >= 20
    for block in data["blocks"]:
        assert {"type", "label", "icon", "category", "fields"} <= set(block)


def test_preset_documents_are_served(client):
    data = client.get("/api/presets/discord").get_json()
    assert data["ok"] and data["doc"]["blocks"]
    assert client.get("/api/presets/nope").status_code == 404


def test_preview_renders_without_publishing(client, sample_doc):
    data = client.post("/api/preview", json={"doc": sample_doc}).get_json()
    assert data["ok"] and "<!doctype html>" in data["html"].lower()
    assert "data-prd-id" in data["html"]


def test_preview_rejects_a_broken_document(client):
    response = client.post("/api/preview", json={"doc": {"blocks": []}})
    assert response.status_code == 400
    assert "at least one section" in response.get_json()["error"]


def test_slug_endpoint_reports_availability(client, sample_doc):
    assert client.get("/api/slug?slug=Free Name").get_json()["slug"] == "free-name"
    assert client.get("/api/slug?slug=api").get_json()["ok"] is False
    publish(client, sample_doc, slug="taken-name")
    result = client.get("/api/slug?slug=taken-name").get_json()
    assert result["ok"] is False and result["suggestion"] == "taken-name-2"


def test_publish_queues_a_request_by_default(client, sample_doc):
    data = publish(client, sample_doc).get_json()
    assert data["ok"] and data["queued"] is True and data["live"] is False
    assert data["status"] == "pending"
    assert len(data["manage_token"]) > 20
    assert data["manage_url"].startswith("/manage/test-site?t=")
    # A queued site is not served yet.
    assert client.get("/s/test-site").status_code == 202


def test_publish_auto_approves_when_configured(app, client, sample_doc):
    app.config["PRD_CONFIG"].auto_approve = True
    data = publish(client, sample_doc, slug="auto-site").get_json()
    assert data["live"] is True and data["queued"] is False
    page = client.get("/s/auto-site")
    assert page.status_code == 200 and b"Hello" in page.data


def test_publish_rejects_a_taken_slug(client, sample_doc):
    publish(client, sample_doc, slug="dupe")
    response = publish(client, sample_doc, slug="dupe")
    assert response.status_code == 400
    assert "already taken" in response.get_json()["error"]


def test_publish_rejects_reserved_slugs(client, sample_doc):
    assert publish(client, sample_doc, slug="admin").status_code == 400


def test_honeypot_and_timing_block_bots(client, sample_doc):
    assert publish(client, sample_doc, slug="bot-1", website="http://spam").status_code == 400
    assert publish(client, sample_doc, slug="bot-2", elapsed_ms=200).status_code == 400


def test_rate_limit_blocks_the_fourth_publish_in_an_hour(client, sample_doc):
    for index in range(3):
        assert publish(client, sample_doc, slug=f"site-{index}").status_code == 200
    response = publish(client, sample_doc, slug="site-4")
    assert response.status_code == 429
    body = response.get_json()
    assert "limit of 3 per hour" in body["error"]
    assert body["retry_after"] > 0


def test_usage_endpoint_reports_the_remaining_allowance(client, sample_doc):
    publish(client, sample_doc, slug="one")
    usage = client.get("/api/usage").get_json()["usage"]
    assert usage["publish_day"]["used"] == 1
    assert usage["publish_day"]["limit"] == 8


def test_manage_token_is_required_to_read_or_change_a_site(client, sample_doc):
    data = publish(client, sample_doc, slug="mine").get_json()
    token = data["manage_token"]
    assert client.get("/api/sites/mine?t=wrong").status_code == 403
    assert client.get(f"/api/sites/mine?t={token}").get_json()["title"] == "Test Site"
    assert client.post("/api/sites/mine/update", json={"token": "wrong", "doc": sample_doc}).status_code == 403


def test_updating_a_live_site_redeploys_it(app, client, sample_doc):
    app.config["PRD_CONFIG"].auto_approve = True
    token = publish(client, sample_doc, slug="livesite").get_json()["manage_token"]
    sample_doc["blocks"][0]["props"]["title"] = "Second version"
    result = client.post("/api/sites/livesite/update",
                         json={"token": token, "doc": sample_doc}).get_json()
    assert result["ok"] and result["queued"] is False
    assert b"Second version" in client.get("/s/livesite").data


def test_owner_can_take_a_site_offline_and_delete_it(app, client, sample_doc):
    app.config["PRD_CONFIG"].auto_approve = True
    token = publish(client, sample_doc, slug="gone-soon").get_json()["manage_token"]
    client.post("/api/sites/gone-soon/offline", json={"token": token})
    assert client.get("/s/gone-soon").status_code == 410
    client.post("/api/sites/gone-soon/delete", json={"token": token})
    assert client.get("/s/gone-soon").status_code == 404


def test_gallery_only_lists_public_live_sites(app, client, sample_doc):
    app.config["PRD_CONFIG"].auto_approve = True
    publish(client, sample_doc, slug="public-one", public=True)
    publish(client, sample_doc, slug="private-one", public=False)
    slugs = [item["slug"] for item in client.get("/api/gallery").get_json()["items"]]
    assert "public-one" in slugs and "private-one" not in slugs


def test_templates_endpoint_serves_public_sites_only(app, client, sample_doc):
    app.config["PRD_CONFIG"].auto_approve = True
    publish(client, sample_doc, slug="tpl", public=True)
    publish(client, sample_doc, slug="secret", public=False)
    data = client.get("/api/templates/tpl").get_json()
    assert data["ok"] and data["doc"]["meta"]["title"].endswith("(remix)")
    assert client.get("/api/templates/secret").status_code == 404


def test_remix_count_increases(app, client, sample_doc):
    app.config["PRD_CONFIG"].auto_approve = True
    token = publish(client, sample_doc, slug="hot", public=True).get_json()["manage_token"]
    client.get("/api/templates/hot")
    client.get("/api/templates/hot")
    assert client.get(f"/api/sites/hot?t={token}").get_json()["remixes"] == 2


def test_private_sites_are_served_but_not_indexed(app, client, sample_doc):
    app.config["PRD_CONFIG"].auto_approve = True
    publish(client, sample_doc, slug="unlisted", public=False)
    response = client.get("/s/unlisted")
    assert response.status_code == 200
    assert response.headers["X-Robots-Tag"] == "noindex"


def test_served_sites_carry_a_restrictive_csp(app, client, sample_doc):
    app.config["PRD_CONFIG"].auto_approve = True
    publish(client, sample_doc, slug="csp-site")
    csp = client.get("/s/csp-site").headers["Content-Security-Policy"]
    assert "default-src 'none'" in csp and "form-action 'none'" in csp


def test_views_are_counted(app, client, sample_doc):
    app.config["PRD_CONFIG"].auto_approve = True
    token = publish(client, sample_doc, slug="counted").get_json()["manage_token"]
    for _ in range(3):
        client.get("/s/counted")
    assert client.get(f"/api/sites/counted?t={token}").get_json()["views"] == 3


def test_publish_rejects_an_invalid_document(client):
    response = client.post("/api/publish", json={"doc": {"nope": True}, "slug": "x-site",
                                                 "elapsed_ms": 9000})
    assert response.status_code == 400
