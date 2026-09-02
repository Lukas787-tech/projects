"""The owner dashboard: the queue, moderation and access control."""
import pytest

from conftest import publish


def login(client, password="hunter2"):
    client.get("/admin/login")  # seeds the CSRF token in the session
    with client.session_transaction() as session:
        token = session["prd_csrf"]
    return client.post("/admin/login", data={"password": password, "csrf": token},
                       follow_redirects=True), token


def csrf(client):
    with client.session_transaction() as session:
        return session.get("prd_csrf", "")


def test_dashboard_requires_a_password(client):
    response = client.get("/admin/", follow_redirects=False)
    assert response.status_code == 302 and "/admin/login" in response.headers["Location"]


def test_wrong_password_is_refused(client):
    response, _ = login(client, "not-it")
    assert b"Wrong password" in response.data
    assert client.get("/admin/", follow_redirects=False).status_code == 302


def test_dashboard_is_disabled_without_a_configured_password(app, client):
    app.config["PRD_CONFIG"].admin_password = ""
    assert client.get("/admin/login").status_code == 503


def test_owner_can_approve_a_request_and_the_site_goes_live(client, sample_doc):
    data = publish(client, sample_doc, slug="queued-site").get_json()
    request_id = data["request_id"]
    login(client)
    response = client.post(f"/admin/request/{request_id}/approve",
                           data={"csrf": csrf(client)}, follow_redirects=True)
    assert response.status_code == 200
    page = client.get("/s/queued-site")
    assert page.status_code == 200 and b"Hello" in page.data


def test_owner_can_reject_a_request_with_a_reason(client, sample_doc):
    request_id = publish(client, sample_doc, slug="bad-site").get_json()["request_id"]
    token = publish(client, sample_doc, slug="other-site").get_json()["manage_token"]
    login(client)
    client.post(f"/admin/request/{request_id}/reject",
                data={"csrf": csrf(client), "note": "Not appropriate"}, follow_redirects=True)
    assert client.get("/s/bad-site").status_code == 410
    client.get("/admin/logout")


def test_rejected_reason_reaches_the_creator(client, sample_doc):
    data = publish(client, sample_doc, slug="nope-site").get_json()
    login(client)
    client.post(f"/admin/request/{data['request_id']}/reject",
                data={"csrf": csrf(client), "note": "Spam"}, follow_redirects=True)
    status = client.get(f"/api/sites/nope-site?t={data['manage_token']}").get_json()
    assert status["status"] == "rejected"
    assert status["request"]["note"] == "Spam"


def test_csrf_token_is_required_for_actions(client, sample_doc):
    request_id = publish(client, sample_doc, slug="csrf-site").get_json()["request_id"]
    login(client)
    response = client.post(f"/admin/request/{request_id}/approve", data={})
    assert response.status_code == 400


def test_blocking_a_visitor_stops_further_publishing(client, sample_doc):
    publish(client, sample_doc, slug="first-one")
    login(client)
    client.post("/admin/site/1/block", data={"csrf": csrf(client)}, follow_redirects=True)
    response = publish(client, sample_doc, slug="second-one")
    assert response.status_code == 429
    assert "blocked" in response.get_json()["error"]


def test_owner_can_delete_a_site(client, sample_doc):
    publish(client, sample_doc, slug="delete-me")
    login(client)
    client.post("/admin/site/1/delete", data={"csrf": csrf(client)}, follow_redirects=True)
    assert client.get("/s/delete-me").status_code == 404


def test_activity_log_records_decisions(client, sample_doc):
    request_id = publish(client, sample_doc, slug="logged").get_json()["request_id"]
    login(client)
    client.post(f"/admin/request/{request_id}/approve", data={"csrf": csrf(client)}, follow_redirects=True)
    page = client.get("/admin/audit")
    assert b"deploy.ok" in page.data
    assert b"request.publish" in page.data


def test_sites_list_renders(client, sample_doc):
    publish(client, sample_doc, slug="listed")
    login(client)
    assert b"listed" in client.get("/admin/sites").data


def test_admin_preview_shows_a_pending_site(client, sample_doc):
    publish(client, sample_doc, slug="peek")
    login(client)
    response = client.get("/admin/preview/1")
    assert response.status_code == 200 and b"Hello" in response.data


def test_ip_hashes_are_never_raw_addresses(client, sample_doc):
    publish(client, sample_doc, slug="hashed")
    login(client)
    page = client.get("/admin/sites").data.decode()
    assert "127.0.0.1" not in page
