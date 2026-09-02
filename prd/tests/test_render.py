"""The renderer must never emit anything a visitor's document did not put there."""
import re

import pytest

from prd_app.blocks import BLOCKS
from prd_app.document import normalize_document
from prd_app.presets import PRESETS, preset_doc
from prd_app.render import embed_src, inline_text, render_site, rich_text


def render(blocks, meta=None, theme=None):
    doc = normalize_document({"meta": meta or {"title": "t"}, "theme": theme or {}, "blocks": blocks})
    return render_site(doc)


def test_script_tags_in_text_are_escaped():
    html = render([{"type": "hero", "props": {"title": "<script>alert(1)</script>"}}])
    assert "<script>alert(1)</script>" not in html
    assert "&lt;script&gt;" in html


def test_attribute_injection_is_escaped():
    html = render([{"type": "hero", "props": {"title": '" onload="alert(1)'}}])
    assert 'onload="alert(1)' not in html


def test_image_urls_are_sanitised():
    html = render([{"type": "image", "props": {"url": "javascript:alert(1)", "caption": "x"}}])
    assert "javascript:" not in html


def test_markdown_links_cannot_smuggle_scripts():
    out = inline_text("[click](javascript:alert(1))")
    assert "javascript:" not in out
    assert "<a" not in out


def test_markdown_emphasis_works():
    out = rich_text("hello **bold** and *slanted*\n\n- one\n- two")
    assert "<strong>bold</strong>" in out and "<em>slanted</em>" in out
    assert out.count("<li>") == 2


def test_external_links_get_noopener():
    html = render([{"type": "links", "props": {"items": [
        {"icon": "", "label": "x", "url": "https://example.com", "note": ""}]}}])
    assert 'rel="noopener noreferrer"' in html


@pytest.mark.parametrize("url,expected_fragment", [
    ("https://youtu.be/dQw4w9WgXcQ", "youtube-nocookie.com/embed/dQw4w9WgXcQ"),
    ("https://www.youtube.com/watch?v=abc123XYZ", "youtube-nocookie.com/embed/abc123XYZ"),
    ("https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT", "open.spotify.com/embed/track/"),
    ("https://vimeo.com/123456789", "player.vimeo.com/video/123456789"),
    ("https://discord.com/widget?id=123456789012", "discord.com/widget?id=123456789012"),
])
def test_supported_embeds(url, expected_fragment):
    assert expected_fragment in embed_src(url)


@pytest.mark.parametrize("url", [
    "https://evil.test/x", "javascript:alert(1)", "http://youtube.com/watch?v=x",
    "https://youtube.com.evil.test/watch?v=abcdef",
])
def test_unsupported_embeds_are_refused(url):
    assert embed_src(url) == "" or "evil.test" not in embed_src(url)


def test_embed_block_shows_help_instead_of_a_raw_iframe():
    html = render([{"type": "embed", "props": {"url": "https://evil.test/page"}}])
    assert "evil.test" not in html
    assert "emb-empty" in html


def test_every_block_renders_with_defaults():
    for btype in BLOCKS:
        html = render([{"type": btype, "props": {}}])
        assert "<body" in html and len(html) > 800, btype


def test_all_presets_render():
    for preset in PRESETS:
        html = render_site(normalize_document(preset_doc(preset["id"])))
        assert "<!doctype html>" in html.lower()
        assert len(html) > 5000, preset["id"]


def test_only_used_block_css_is_shipped():
    html = render([{"type": "hero", "props": {}}])
    assert ".dc-banner" not in html          # discord CSS not needed
    assert ".hero-inner" in html


def test_preview_mode_adds_hooks_and_publish_mode_does_not():
    doc = normalize_document({"meta": {"title": "t"}, "blocks": [{"type": "hero", "props": {}}]})
    assert "data-prd-id" in render_site(doc, preview=True)
    assert "data-prd-id" not in render_site(doc, preview=False)


def test_noindex_flag():
    doc = normalize_document({"meta": {"title": "t"}, "blocks": [{"type": "hero", "props": {}}]})
    assert 'name="robots" content="noindex"' in render_site(doc, noindex=True)


def test_countdown_target_becomes_epoch_seconds():
    html = render([{"type": "countdown", "props": {"target": "2030-01-01T00:00"}}])
    match = re.search(r'data-until="(\d+)"', html)
    assert match and int(match.group(1)) == 1893456000


def test_broken_countdown_date_does_not_crash():
    html = render([{"type": "countdown", "props": {"target": "not a date"}}])
    assert "set a date" in html


def test_dark_and_light_palettes_pick_readable_button_text():
    dark = render([{"type": "hero", "props": {}}], theme={"palette": "midnight"})
    light = render([{"type": "hero", "props": {}}], theme={"palette": "paper"})
    assert "--on-accent:#ffffff" in dark
    assert "--on-accent:" in light


def test_favicon_emoji_is_inlined():
    html = render([{"type": "hero", "props": {}}], meta={"title": "t", "favicon": "🎮"})
    assert 'rel="icon" href="data:image/svg+xml,' in html
