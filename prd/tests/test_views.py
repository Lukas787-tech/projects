"""Public pages render and behave."""
from conftest import publish


def test_landing_lists_presets(client):
    page = client.get("/")
    assert page.status_code == 200
    assert b"Discord server" in page.data and b"Personal bio page" in page.data


def test_editor_boots_with_a_preset(client):
    page = client.get("/editor?preset=discord")
    assert b'preset: "discord"' in page.data
    assert b"editor.js" in page.data


def test_gallery_page_renders(client):
    assert client.get("/gallery").status_code == 200


def test_unknown_site_is_a_404_page(client):
    page = client.get("/does-not-exist")
    assert page.status_code == 404
    assert b"Nothing here" in page.data


def test_manage_page_needs_the_token(client, sample_doc):
    data = publish(client, sample_doc, slug="managed").get_json()
    assert client.get("/manage/managed").status_code == 403
    page = client.get(f"/manage/managed?t={data['manage_token']}")
    assert page.status_code == 200 and b"private management page" in page.data


def test_site_detail_only_exists_for_public_live_sites(app, client, sample_doc):
    publish(client, sample_doc, slug="hidden-one", public=False)
    assert client.get("/site/hidden-one").status_code == 404
    app.config["PRD_CONFIG"].auto_approve = True
    publish(client, sample_doc, slug="shown-one", public=True)
    assert client.get("/site/shown-one").status_code == 200


def test_robots_and_sitemap(app, client, sample_doc):
    robots = client.get("/robots.txt").data.decode()
    assert "Disallow: /admin" in robots and "Sitemap:" in robots
    app.config["PRD_CONFIG"].auto_approve = True
    publish(client, sample_doc, slug="in-sitemap", public=True)
    assert b"/in-sitemap" in client.get("/sitemap.xml").data


def test_health_check(client):
    assert client.get("/healthz").get_json()["ok"] is True


def test_app_pages_are_not_framable(client):
    assert client.get("/").headers["X-Frame-Options"] == "SAMEORIGIN"


def test_published_sites_are_framable_for_gallery_thumbnails(app, client, sample_doc):
    app.config["PRD_CONFIG"].auto_approve = True
    publish(client, sample_doc, slug="framed")
    assert "X-Frame-Options" not in client.get("/framed").headers


def test_sites_live_at_the_root(app, client, sample_doc):
    app.config["PRD_CONFIG"].auto_approve = True
    publish(client, sample_doc, slug="cooldiscordserver")
    page = client.get("/cooldiscordserver")
    assert page.status_code == 200 and b"Hello" in page.data


def test_old_slash_s_addresses_still_work(app, client, sample_doc):
    app.config["PRD_CONFIG"].auto_approve = True
    publish(client, sample_doc, slug="moved")
    response = client.get("/s/moved")
    assert response.status_code == 301
    assert response.headers["Location"].endswith("/moved")


def test_app_pages_win_over_a_site_of_the_same_name(client):
    assert client.get("/gallery").status_code == 200
    assert client.get("/editor").status_code == 200


def test_reserved_names_cannot_be_claimed(client, sample_doc):
    for name in ("gallery", "editor", "admin", "healthz", "download"):
        response = publish(client, sample_doc, slug=name)
        assert response.status_code == 400, name
