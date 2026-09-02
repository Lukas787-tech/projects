"""Validation is the only thing standing between the renderer and the internet."""
import pytest

from prd_app.blocks import BLOCKS
from prd_app.document import (
    DocumentError, coerce_props, normalize_document, normalize_theme,
    sanitize_url, slug_error, slugify,
)


@pytest.mark.parametrize("value", [
    "javascript:alert(1)",
    "JaVaScRiPt:alert(1)",
    "java\tscript:alert(1)",
    "  javascript:alert(1)",
    "data:text/html;base64,PHNjcmlwdD4=",
    "vbscript:msgbox(1)",
    "file:///etc/passwd",
    "blob:https://evil.test/x",
    "about:blank",
    "ftp://example.com/x",
])
def test_dangerous_urls_are_dropped(value):
    assert sanitize_url(value) == ""


@pytest.mark.parametrize("value,expected", [
    ("https://example.com/a?b=c", "https://example.com/a?b=c"),
    ("http://example.com", "http://example.com"),
    ("#about", "#about"),
    ("/s/other", "/s/other"),
    ("mailto:hi@example.com", "mailto:hi@example.com"),
    ("example.com/path", "https://example.com/path"),
    ("", ""),
])
def test_safe_urls_survive(value, expected):
    assert sanitize_url(value) == expected


def test_unknown_block_types_are_dropped(sample_doc):
    sample_doc["blocks"].append({"type": "evil", "props": {}})
    doc = normalize_document(sample_doc)
    assert [block["type"] for block in doc["blocks"]] == ["hero", "footer"]


def test_unknown_props_are_dropped():
    props = coerce_props("hero", {"title": "ok", "onclick": "alert(1)", "__proto__": {"x": 1}})
    assert "onclick" not in props and "__proto__" not in props
    assert props["title"] == "ok"


def test_select_values_fall_back_to_the_default():
    assert coerce_props("hero", {"layout": "../../etc"})["layout"] == "center"


def test_list_items_are_capped_and_cleaned():
    items = [{"label": f"l{i}", "url": "javascript:1", "style": "nope"} for i in range(40)]
    props = coerce_props("hero", {"buttons": items})
    assert len(props["buttons"]) == 4          # hero buttons max
    assert props["buttons"][0]["url"] == ""    # dangerous link stripped
    assert props["buttons"][0]["style"] == "primary"


def test_nested_lists_are_not_allowed():
    props = coerce_props("features", {"items": [{"title": "x", "text": [{"nested": True}]}]})
    assert props["items"][0]["text"] == ""


def test_empty_document_is_rejected():
    with pytest.raises(DocumentError):
        normalize_document({"blocks": []})


def test_oversized_document_is_rejected(sample_doc):
    sample_doc["meta"]["description"] = "x" * 400_000
    with pytest.raises(DocumentError):
        normalize_document(sample_doc)


def test_too_many_blocks_is_rejected(sample_doc):
    sample_doc["blocks"] = sample_doc["blocks"] * 60
    with pytest.raises(DocumentError):
        normalize_document(sample_doc, max_blocks=10)


def test_duplicate_block_ids_are_replaced():
    doc = normalize_document({
        "meta": {"title": "t"},
        "blocks": [{"id": "same", "type": "hero", "props": {}},
                   {"id": "same", "type": "hero", "props": {}}],
    })
    assert doc["blocks"][0]["id"] != doc["blocks"][1]["id"]


def test_control_characters_are_stripped():
    doc = normalize_document({"meta": {"title": "a\x00b\x07c"}, "blocks": [{"type": "hero", "props": {}}]})
    assert doc["meta"]["title"] == "abc"


def test_theme_values_are_clamped():
    theme = normalize_theme({"radius": 9999, "width": 10, "palette": "nope",
                             "accent": "not-a-colour", "effect": "explode"})
    assert theme["radius"] == 40
    assert theme["width"] == 640
    assert theme["palette"] == "midnight"
    assert theme["accent"] == ""
    assert theme["effect"] == "none"


def test_short_hex_colours_expand():
    assert normalize_theme({"accent": "#ABC"})["accent"] == "#aabbcc"


@pytest.mark.parametrize("slug,ok", [
    ("my-site", True), ("a", False), ("api", False), ("prd-thing", False),
    ("UPPER", False), ("with space", False), ("x" * 41, False), ("ok-123", True),
])
def test_slug_rules(slug, ok):
    assert (slug_error(slug) == "") is ok


def test_slugify():
    assert slugify("My Cöol Site!! 2026") == "my-cool-site-2026"


def test_every_block_has_defaults_that_validate():
    for btype in BLOCKS:
        doc = normalize_document({"meta": {"title": "t"}, "blocks": [{"type": btype, "props": {}}]})
        assert doc["blocks"][0]["type"] == btype
