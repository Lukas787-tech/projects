"""Site document: validation, normalisation and theming.

A document is plain JSON:

    {"version": 1,
     "meta":   {"title": ..., "description": ..., "favicon": ...},
     "theme":  {...},
     "blocks": [{"id": "b1", "type": "hero", "props": {...}}]}

Everything that reaches the database goes through :func:`normalize_document`
first, so the renderer can trust the shape of what it is given. Untrusted text
is still escaped at render time -- this layer is about shape and size, not
about HTML safety.
"""
from __future__ import annotations

import json
import re
import secrets
import unicodedata
from typing import Any

from . import blocks as blocklib

VERSION = 1

MAX_BLOCKS_DEFAULT = 80
MAX_DOC_BYTES_DEFAULT = 300_000

SLUG_RE = re.compile(r"^[a-z0-9][a-z0-9-]{1,38}[a-z0-9]$")

RESERVED_SLUGS = {
    "api", "admin", "static", "editor", "gallery", "site", "sites", "s", "manage",
    "login", "logout", "about", "help", "docs", "new", "create", "preset", "presets",
    "template", "templates", "prd", "www", "assets", "favicon", "robots", "sitemap",
    "health", "healthz", "status", "terms", "privacy", "me", "user", "users", "null",
    "undefined", "download", "downloads", "domain", "domains", "settings", "account",
    "signin", "signup", "register", "dashboard", "index", "home", "search", "explore",
}

SAFE_SCHEMES = ("http://", "https://", "mailto:", "tel:")

CONTROL_CHARS = re.compile(r"[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]")


class DocumentError(ValueError):
    """Raised when a submitted document cannot be repaired into a valid one."""


# ---------------------------------------------------------------------------
# Theme
# ---------------------------------------------------------------------------

FONTS: dict[str, dict[str, str]] = {
    "inter": {"label": "Inter", "stack": "'Inter', system-ui, -apple-system, 'Segoe UI', sans-serif", "google": "Inter:wght@400;500;600;800"},
    "outfit": {"label": "Outfit", "stack": "'Outfit', system-ui, sans-serif", "google": "Outfit:wght@400;600;800"},
    "poppins": {"label": "Poppins", "stack": "'Poppins', system-ui, sans-serif", "google": "Poppins:wght@400;600;800"},
    "space": {"label": "Space Grotesk", "stack": "'Space Grotesk', system-ui, sans-serif", "google": "Space+Grotesk:wght@400;600;700"},
    "sora": {"label": "Sora", "stack": "'Sora', system-ui, sans-serif", "google": "Sora:wght@400;600;800"},
    "dm": {"label": "DM Sans", "stack": "'DM Sans', system-ui, sans-serif", "google": "DM+Sans:wght@400;500;700"},
    "playfair": {"label": "Playfair Display", "stack": "'Playfair Display', Georgia, serif", "google": "Playfair+Display:wght@500;700;800"},
    "mono": {"label": "JetBrains Mono", "stack": "'JetBrains Mono', ui-monospace, monospace", "google": "JetBrains+Mono:wght@400;600;800"},
    "system": {"label": "System", "stack": "system-ui, -apple-system, 'Segoe UI', Roboto, sans-serif", "google": ""},
}

PALETTES: dict[str, dict[str, Any]] = {
    "midnight": {"label": "Midnight", "mode": "dark", "bg": "#0a0b10", "surface": "#14161f",
                 "text": "#eef1f8", "muted": "#98a0b4", "accent": "#7c5cff", "accent2": "#22d3ee"},
    "ember":    {"label": "Ember", "mode": "dark", "bg": "#120b0b", "surface": "#1e1414",
                 "text": "#fdeee6", "muted": "#c0a094", "accent": "#ff6b3d", "accent2": "#ffb703"},
    "forest":   {"label": "Forest", "mode": "dark", "bg": "#08120e", "surface": "#111e18",
                 "text": "#e8f5ee", "muted": "#8fae9f", "accent": "#34d399", "accent2": "#a3e635"},
    "cyber":    {"label": "Cyber", "mode": "dark", "bg": "#05060f", "surface": "#0e1225",
                 "text": "#e6ecff", "muted": "#8b95c9", "accent": "#ff2e88", "accent2": "#00e5ff"},
    "royal":    {"label": "Royal", "mode": "dark", "bg": "#0b0f1e", "surface": "#141a30",
                 "text": "#eaf0ff", "muted": "#9aa6cc", "accent": "#5865f2", "accent2": "#c084fc"},
    "paper":    {"label": "Paper", "mode": "light", "bg": "#fbfaf7", "surface": "#ffffff",
                 "text": "#171613", "muted": "#6b6862", "accent": "#1f6feb", "accent2": "#f97316"},
    "mint":     {"label": "Mint", "mode": "light", "bg": "#f3fbf7", "surface": "#ffffff",
                 "text": "#0f1f19", "muted": "#5c7a6d", "accent": "#0d9488", "accent2": "#65a30d"},
    "candy":    {"label": "Candy", "mode": "light", "bg": "#fff7fb", "surface": "#ffffff",
                 "text": "#25101c", "muted": "#7d5f6e", "accent": "#e11d8f", "accent2": "#8b5cf6"},
    "sand":     {"label": "Sand", "mode": "light", "bg": "#faf6ef", "surface": "#ffffff",
                 "text": "#231d13", "muted": "#7a6d59", "accent": "#b45309", "accent2": "#0f766e"},
}

EFFECTS = ("aurora", "grid", "dots", "noise", "none")
SPACINGS = ("tight", "normal", "airy")

HEX_RE = re.compile(r"^#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{6})$")

THEME_DEFAULTS: dict[str, Any] = {
    "palette": "midnight",
    "font": "inter",
    "heading_font": "inter",
    "radius": 18,
    "width": 1080,
    "spacing": "normal",
    "effect": "none",
    "animations": True,
    "bg": "", "surface": "", "text": "", "muted": "", "accent": "", "accent2": "",
    "mode": "",
}


def clean_color(value: Any, fallback: str = "") -> str:
    if isinstance(value, str) and HEX_RE.match(value.strip()):
        v = value.strip().lower()
        if len(v) == 4:  # #abc -> #aabbcc
            v = "#" + "".join(c * 2 for c in v[1:])
        return v
    return fallback


def normalize_theme(raw: Any) -> dict:
    raw = raw if isinstance(raw, dict) else {}
    theme = dict(THEME_DEFAULTS)

    palette = str(raw.get("palette", "")).strip().lower()
    theme["palette"] = palette if palette in PALETTES else THEME_DEFAULTS["palette"]

    for key in ("font", "heading_font"):
        value = str(raw.get(key, "")).strip().lower()
        theme[key] = value if value in FONTS else THEME_DEFAULTS[key]

    try:
        theme["radius"] = max(0, min(40, int(raw.get("radius", THEME_DEFAULTS["radius"]))))
    except (TypeError, ValueError):
        theme["radius"] = THEME_DEFAULTS["radius"]
    try:
        theme["width"] = max(640, min(1600, int(raw.get("width", THEME_DEFAULTS["width"]))))
    except (TypeError, ValueError):
        theme["width"] = THEME_DEFAULTS["width"]

    spacing = str(raw.get("spacing", "")).strip().lower()
    theme["spacing"] = spacing if spacing in SPACINGS else "normal"

    effect = str(raw.get("effect", "")).strip().lower()
    theme["effect"] = effect if effect in EFFECTS else "none"

    theme["animations"] = bool(raw.get("animations", True))

    # Optional per-colour overrides on top of the chosen palette.
    for key in ("bg", "surface", "text", "muted", "accent", "accent2"):
        theme[key] = clean_color(raw.get(key), "")

    mode = str(raw.get("mode", "")).strip().lower()
    theme["mode"] = mode if mode in ("dark", "light") else ""
    return theme


def resolved_palette(theme: dict) -> dict[str, str]:
    """Palette preset with any per-site colour overrides applied."""
    base = dict(PALETTES.get(theme.get("palette", "midnight"), PALETTES["midnight"]))
    for key in ("bg", "surface", "text", "muted", "accent", "accent2"):
        override = theme.get(key)
        if override:
            base[key] = override
    if theme.get("mode"):
        base["mode"] = theme["mode"]
    return base


# ---------------------------------------------------------------------------
# Value cleaning
# ---------------------------------------------------------------------------

def clean_text(value: Any, max_len: int = blocklib.TEXT_MAX, allow_newlines: bool = False) -> str:
    if value is None or isinstance(value, (dict, list)):
        return ""
    if isinstance(value, bool):
        return "yes" if value else ""
    text = str(value)
    text = unicodedata.normalize("NFC", text)
    text = CONTROL_CHARS.sub("", text)
    if not allow_newlines:
        text = text.replace("\r", " ").replace("\n", " ")
    else:
        text = text.replace("\r\n", "\n").replace("\r", "\n")
    return text[:max_len].strip()


def sanitize_url(value: Any, max_len: int = blocklib.URL_MAX) -> str:
    """Return a safe href, or '' if the value is not one.

    Anchors and site-relative paths are kept; everything else must use an
    explicit safe scheme. Bare domains ("example.com") get https:// added so
    non-technical users don't have to think about it.
    """
    url = clean_text(value, max_len)
    if not url:
        return ""
    stripped = url.strip()
    lowered = stripped.lower()
    # Reject anything that tries to smuggle a scheme past us.
    compact = re.sub(r"[\s\x00-\x20]", "", lowered)
    for bad in ("javascript:", "data:", "vbscript:", "file:", "blob:", "about:"):
        if compact.startswith(bad):
            return ""
    if stripped.startswith(("#", "/")):
        return stripped
    if lowered.startswith(SAFE_SCHEMES):
        return stripped
    if ":" in compact.split("/")[0]:
        return ""  # unknown scheme
    if "." in stripped.split("/")[0] and " " not in stripped:
        return "https://" + stripped
    return ""


def clean_icon(value: Any) -> str:
    return clean_text(value, 8)


def _coerce_value(spec: dict, value: Any, depth: int = 0) -> Any:
    ftype = spec["type"]
    if ftype in ("text", "select") and ftype == "select":
        allowed = {o["value"] for o in spec.get("options", [])}
        raw = clean_text(value, 40)
        return raw if raw in allowed else spec["default"]
    if ftype == "text":
        return clean_text(value, spec.get("max", blocklib.TEXT_MAX))
    if ftype == "textarea":
        return clean_text(value, spec.get("max", blocklib.BODY_MAX), allow_newlines=True)
    if ftype == "richtext":
        return clean_text(value, spec.get("max", blocklib.BODY_MAX), allow_newlines=True)
    if ftype in ("url", "image"):
        return sanitize_url(value)
    if ftype == "color":
        return clean_color(value, spec["default"])
    if ftype == "icon":
        return clean_icon(value)
    if ftype == "toggle":
        return bool(value)
    if ftype == "date":
        return clean_text(value, 32)
    if ftype == "number":
        try:
            number = float(value)
        except (TypeError, ValueError):
            return spec["default"]
        number = max(spec.get("min", -10_000), min(spec.get("max", 10_000), number))
        return int(number) if float(number).is_integer() else round(number, 2)
    if ftype == "list":
        if depth > 1 or not isinstance(value, list):
            return []
        sub_specs = spec.get("fields", [])
        limit = int(spec.get("max", blocklib.LIST_MAX))
        items = []
        for raw_item in value[:limit]:
            if not isinstance(raw_item, dict):
                continue
            items.append({s["key"]: _coerce_value(s, raw_item.get(s["key"], s["default"]), depth + 1) for s in sub_specs})
        return items
    return clean_text(value, blocklib.TEXT_MAX)


def coerce_props(btype: str, raw: Any) -> dict:
    """Drop unknown keys, coerce known ones, fill in missing defaults."""
    raw = raw if isinstance(raw, dict) else {}
    specs = blocklib.BLOCKS[btype]["fields"]
    return {spec["key"]: _coerce_value(spec, raw.get(spec["key"], spec["default"])) for spec in specs}


# ---------------------------------------------------------------------------
# Documents
# ---------------------------------------------------------------------------

def new_block_id() -> str:
    return "b" + secrets.token_hex(4)


def normalize_document(
    raw: Any,
    *,
    max_blocks: int = MAX_BLOCKS_DEFAULT,
    max_bytes: int = MAX_DOC_BYTES_DEFAULT,
) -> dict:
    if isinstance(raw, (str, bytes)):
        try:
            raw = json.loads(raw)
        except (ValueError, TypeError) as exc:
            raise DocumentError("That design could not be read (invalid JSON).") from exc
    if not isinstance(raw, dict):
        raise DocumentError("A site design must be an object.")

    encoded = json.dumps(raw, ensure_ascii=False)
    if len(encoded.encode("utf-8")) > max_bytes:
        raise DocumentError(
            f"That design is too big ({len(encoded) // 1024} KB). "
            "Try using image links instead of very long text."
        )

    meta_raw = raw.get("meta") if isinstance(raw.get("meta"), dict) else {}
    meta = {
        "title": clean_text(meta_raw.get("title"), 90) or "Untitled site",
        "description": clean_text(meta_raw.get("description"), 200),
        "favicon": clean_icon(meta_raw.get("favicon")) or "🌐",
        "og_image": sanitize_url(meta_raw.get("og_image")),
    }

    raw_blocks = raw.get("blocks")
    if not isinstance(raw_blocks, list):
        raise DocumentError("A site design needs a list of blocks.")
    if not raw_blocks:
        raise DocumentError("Add at least one section before publishing.")
    if len(raw_blocks) > max_blocks:
        raise DocumentError(f"Too many sections — the limit is {max_blocks}.")

    seen_ids: set[str] = set()
    blocks: list[dict] = []
    for raw_block in raw_blocks:
        if not isinstance(raw_block, dict):
            continue
        btype = clean_text(raw_block.get("type"), 40)
        if btype not in blocklib.BLOCK_TYPES:
            continue
        bid = clean_text(raw_block.get("id"), 24)
        if not re.fullmatch(r"[A-Za-z0-9_-]{1,24}", bid or "") or bid in seen_ids:
            bid = new_block_id()
        seen_ids.add(bid)
        blocks.append({"id": bid, "type": btype, "props": coerce_props(btype, raw_block.get("props"))})

    if not blocks:
        raise DocumentError("None of those sections are recognised — try adding one from the sidebar.")

    return {
        "version": VERSION,
        "meta": meta,
        "theme": normalize_theme(raw.get("theme")),
        "blocks": blocks,
    }


def document_summary(doc: dict) -> str:
    """Short human description used on gallery cards."""
    if doc["meta"].get("description"):
        return doc["meta"]["description"]
    for block in doc["blocks"]:
        for key in ("subtitle", "tagline", "text", "body", "subheading"):
            value = block["props"].get(key)
            if isinstance(value, str) and value.strip():
                flat = " ".join(value.split())
                return flat[:160]
    return ""


# ---------------------------------------------------------------------------
# Slugs
# ---------------------------------------------------------------------------

def slugify(value: str) -> str:
    text = unicodedata.normalize("NFKD", str(value or "")).encode("ascii", "ignore").decode()
    text = re.sub(r"[^a-zA-Z0-9]+", "-", text).strip("-").lower()
    text = re.sub(r"-{2,}", "-", text)
    return text[:40].strip("-")


DOMAIN_RE = re.compile(r"^(?=.{4,253}$)(?!-)[a-z0-9-]{1,63}(?<!-)(\.(?!-)[a-z0-9-]{1,63}(?<!-))+$")


def clean_domain(value: str) -> str:
    """Strip anything that is not the hostname itself."""
    text = clean_text(value, 300).strip().lower()
    text = re.sub(r"^[a-z]+://", "", text)
    text = text.split("/")[0].split("?")[0].split("#")[0].split(":")[0]
    return text.removeprefix("www.").strip(".")


def domain_error(domain: str) -> str:
    """Return a human message describing why `domain` is unusable, else ''."""
    if not domain:
        return ""
    if not DOMAIN_RE.match(domain):
        return "That does not look like a domain. Use something like fanclub.example."
    if domain.endswith((".local", ".localhost", ".test", ".invalid", ".example")):
        return "That domain cannot be reached from the internet."
    if domain.endswith(".pythonanywhere.com"):
        return "PythonAnywhere addresses are handed out by PythonAnywhere, not here."
    return ""


def slug_error(slug: str) -> str:
    """Return a human message describing why `slug` is unusable, else ''."""
    if not slug:
        return "Pick an address for your site."
    if len(slug) < 3:
        return "That address is too short (3 characters minimum)."
    if len(slug) > 40:
        return "That address is too long (40 characters maximum)."
    if not SLUG_RE.match(slug):
        return "Use lowercase letters, numbers and dashes only."
    if slug in RESERVED_SLUGS:
        return "That address is reserved — try another."
    if slug.startswith("prd-"):
        return "Addresses cannot start with 'prd-'."
    return ""
