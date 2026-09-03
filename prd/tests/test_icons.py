"""Every icon the interface asks for has to exist.

A missing name renders as nothing at all — no error, no fallback — so the
button just loses its icon. That happened once; this catches the next one.
"""
import re
from pathlib import Path

from prd_app import icons

ROOT = Path(__file__).resolve().parent.parent / "prd_app"

# icon("name", …) in Jinja, PRD.ico('name', …) in the editor, and the names
# blocks.py and presets.py store.
TEMPLATE_CALL = re.compile(r'icon\(\s*["\']([a-z-]+)["\']')
JS_CALL = re.compile(r"PRD\.ico\(\s*['\"]([a-z-]+)['\"]")


def referenced():
    for path in (ROOT / "templates").rglob("*.html"):
        for name in TEMPLATE_CALL.findall(path.read_text()):
            yield path.name, name
    for path in (ROOT / "static" / "js").glob("*.js"):
        for name in JS_CALL.findall(path.read_text()):
            yield path.name, name


def test_every_icon_the_interface_asks_for_exists():
    missing = sorted({(where, name) for where, name in referenced() if name not in icons.ALL})
    assert not missing, f"referenced but not drawn: {missing}"


def test_every_block_names_an_icon_that_exists():
    from prd_app.blocks import BLOCKS

    missing = [b["type"] for b in BLOCKS.values() if b["icon"] not in icons.ALL]
    assert not missing, f"blocks with no icon: {missing}"


def test_every_preset_names_an_icon_that_exists():
    from prd_app.presets import PRESETS

    missing = [p["id"] for p in PRESETS if p["icon"] not in icons.ALL]
    assert not missing, f"presets with no icon: {missing}"


def test_the_sprite_carries_every_glyph():
    sprite = icons.sprite()
    for name in icons.ALL:
        assert f'id="i-{name}"' in sprite, name


def test_an_unknown_name_is_refused_rather_than_rendered_empty():
    assert icons.use("no-such-icon") == ""
    assert icons.svg("no-such-icon") == ""
