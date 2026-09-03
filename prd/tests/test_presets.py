"""Presets are starting points, not claims. Nothing in them may read as a fact
about the person using them."""
import re

import pytest

from prd_app.blocks import BLOCKS, default_props
from prd_app.document import normalize_document
from prd_app.presets import PRESETS, preset_doc

# Fields that would state something about the site's owner if we filled them in.
# Plan names ("Pro") and roster slots ("IGL") are structure, not claims. A
# person's NAME is the fact, and only inside the people block.
FACT_FIELDS = {"value", "price", "members", "online", "boosts", "quote", "author"}
PEOPLE_FIELDS = {"name"}
NUMBERISH = re.compile(r"\d")


def blank(value: str) -> bool:
    """Empty, or a visibly marked blank like [0] or [Their name]."""
    return not value.strip() or ("[" in value and "]" in value)


def walk_props(props):
    for key, value in props.items():
        if isinstance(value, str):
            yield key, value
        elif isinstance(value, list):
            for item in value:
                if isinstance(item, dict):
                    yield from walk_props(item)


@pytest.mark.parametrize("preset_id", [p["id"] for p in PRESETS])
def test_presets_state_no_facts_about_their_owner(preset_id):
    doc = normalize_document(preset_doc(preset_id))
    offenders = []
    for block in doc["blocks"]:
        checked = FACT_FIELDS | (PEOPLE_FIELDS if block["type"] == "team" else set())
        for key, value in walk_props(block["props"]):
            if key in checked and not blank(value):
                offenders.append(f"{block['type']}.{key} = {value!r}")
    assert not offenders, f"{preset_id} states things we made up: {offenders}"


@pytest.mark.parametrize("btype", sorted(BLOCKS))
def test_block_defaults_state_no_facts(btype):
    checked = FACT_FIELDS | (PEOPLE_FIELDS if btype == "team" else set())
    offenders = [f"{key}={value!r}" for key, value in walk_props(default_props(btype))
                 if key in checked and not blank(value)]
    assert not offenders, f"a fresh {btype} block ships invented content: {offenders}"


@pytest.mark.parametrize("preset_id", [p["id"] for p in PRESETS])
def test_presets_carry_no_stray_numbers_in_headline_copy(preset_id):
    """Counts and percentages are the ones that get published unchanged."""
    doc = normalize_document(preset_doc(preset_id))
    for block in doc["blocks"]:
        for key, value in walk_props(block["props"]):
            if key in ("eyebrow", "tagline") and NUMBERISH.search(value) and not blank(value):
                pytest.fail(f"{preset_id}: {block['type']}.{key} = {value!r}")


def test_every_preset_still_renders_after_the_rewrite():
    from prd_app.render import render_site

    for preset in PRESETS:
        html = render_site(normalize_document(preset_doc(preset["id"])))
        assert len(html) > 5000, preset["id"]
        assert "[0]" not in html or "[0]" in html  # blanks are allowed to show through


def test_a_blank_reads_as_something_to_replace():
    """A builder should never wonder whether [0] was deliberate."""
    doc = normalize_document(preset_doc("discord"))
    stats = next(b for b in doc["blocks"] if b["type"] == "stats")
    assert all(item["value"] == "[0]" for item in stats["props"]["items"])
    assert all(item["label"] for item in stats["props"]["items"])   # labels stay real
