"""Document -> standalone HTML.

The same renderer powers the live editor preview, the served site and the files
pushed to PythonAnywhere, so what you see while building is exactly what gets
deployed. Everything user-supplied is escaped here; nothing in a document can
inject markup or script.
"""
from __future__ import annotations

import html
import re
from datetime import datetime, timezone
from typing import Any

from .blocks import BLOCKS
from .document import FONTS, resolved_palette, sanitize_url

# ---------------------------------------------------------------------------
# Small helpers
# ---------------------------------------------------------------------------


def esc(value: Any) -> str:
    return html.escape(str(value if value is not None else ""), quote=True)


def attr(name: str, value: Any) -> str:
    if value in (None, "", False):
        return ""
    return f' {name}="{esc(value)}"'


def _hex_to_rgb(color: str) -> tuple[int, int, int]:
    color = (color or "#000000").lstrip("#")
    if len(color) == 3:
        color = "".join(c * 2 for c in color)
    try:
        return int(color[0:2], 16), int(color[2:4], 16), int(color[4:6], 16)
    except ValueError:
        return 0, 0, 0


def _luminance(color: str) -> float:
    r, g, b = (c / 255 for c in _hex_to_rgb(color))
    channels = [c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4 for c in (r, g, b)]
    return 0.2126 * channels[0] + 0.7152 * channels[1] + 0.0722 * channels[2]


def on_color(color: str) -> str:
    """Readable foreground for a solid background."""
    return "#0b0b0f" if _luminance(color) > 0.45 else "#ffffff"


def rgba(color: str, alpha: float) -> str:
    r, g, b = _hex_to_rgb(color)
    return f"rgba({r},{g},{b},{alpha})"


LINK_RE = re.compile(r"\[([^\]\n]{1,140})\]\(([^)\s]{1,600})\)")
BOLD_RE = re.compile(r"\*\*(.+?)\*\*", re.S)
ITALIC_RE = re.compile(r"(?<![\w*])\*([^*\n]+?)\*(?![\w*])")
CODE_RE = re.compile(r"`([^`\n]{1,200})`")


def _emphasis(escaped: str) -> str:
    out = CODE_RE.sub(r"<code>\1</code>", escaped)
    out = BOLD_RE.sub(r"<strong>\1</strong>", out)
    out = ITALIC_RE.sub(r"<em>\1</em>", out)
    return out


def inline_text(raw: str) -> str:
    """Escape text, then allow a tiny markdown subset: **b** *i* `c` [x](url)."""
    parts: list[str] = []
    pos = 0
    for match in LINK_RE.finditer(raw or ""):
        parts.append(_emphasis(esc(raw[pos:match.start()])))
        url = sanitize_url(match.group(2))
        label = _emphasis(esc(match.group(1)))
        parts.append(link_html(url, label) if url else label)
        pos = match.end()
    parts.append(_emphasis(esc(raw[pos:] if raw else "")))
    return "".join(parts)


def link_html(url: str, inner_html: str, cls: str = "") -> str:
    external = url.startswith(("http://", "https://"))
    rel = ' target="_blank" rel="noopener noreferrer"' if external else ""
    return f'<a class="{esc(cls)}" href="{esc(url)}"{rel}>{inner_html}</a>'


def rich_text(raw: str) -> str:
    """Paragraphs, line breaks and simple bullet lists."""
    if not raw:
        return ""
    blocks_out: list[str] = []
    for chunk in re.split(r"\n{2,}", raw.strip()):
        lines = [line for line in chunk.split("\n") if line.strip()]
        if not lines:
            continue
        if all(line.strip()[:2] in ("- ", "* ") for line in lines):
            items = "".join(f"<li>{inline_text(line.strip()[2:])}</li>" for line in lines)
            blocks_out.append(f"<ul>{items}</ul>")
        else:
            blocks_out.append("<p>" + "<br>".join(inline_text(line) for line in lines) + "</p>")
    return "".join(blocks_out)


def plain_paragraphs(raw: str) -> str:
    if not raw:
        return ""
    return "".join(
        "<p>" + "<br>".join(esc(line) for line in chunk.split("\n")) + "</p>"
        for chunk in re.split(r"\n{2,}", raw.strip())
        if chunk.strip()
    )


def muted_paragraphs(raw: str) -> str:
    return plain_paragraphs(raw).replace("<p>", '<p class="muted">')


def img_tag(url: str, alt: str = "", cls: str = "", loading: str = "lazy") -> str:
    if not url:
        return ""
    return (
        f'<img class="{esc(cls)}" src="{esc(url)}" alt="{esc(alt)}" '
        f'loading="{esc(loading)}" decoding="async" referrerpolicy="no-referrer">'
    )


def initials(name: str) -> str:
    words = [w for w in re.split(r"\s+", (name or "").strip()) if w]
    return esc("".join(w[0] for w in words[:2]).upper() or "•")


# ---------------------------------------------------------------------------
# Social icons (inline SVG, 24x24, currentColor)
# ---------------------------------------------------------------------------

_ICON_PATHS = {
    "discord": "M20.3 4.5A19 19 0 0 0 15.7 3l-.3.5a14 14 0 0 1 4 2 17 17 0 0 0-13 0 14 14 0 0 1 4-2L10.2 3a19 19 0 0 0-4.6 1.5C2.7 8.8 2 13 2.3 17.1A19 19 0 0 0 8 20l1.2-1.7a12 12 0 0 1-2-1l.5-.4a13.5 13.5 0 0 0 11.6 0l.5.4a12 12 0 0 1-2 1L19 20a19 19 0 0 0 5.7-2.9c.4-4.8-.6-9-2.4-12.6zM9.5 14.7c-1.1 0-2-1-2-2.3s.9-2.3 2-2.3 2 1 2 2.3-.9 2.3-2 2.3zm5 0c-1.1 0-2-1-2-2.3s.9-2.3 2-2.3 2 1 2 2.3-.9 2.3-2 2.3z",
    "github": "M12 2a10 10 0 0 0-3.2 19.5c.5.1.7-.2.7-.5v-1.7C6.7 19.9 6.1 18 6.1 18c-.4-1.2-1.1-1.5-1.1-1.5-.9-.6.1-.6.1-.6 1 .1 1.5 1 1.5 1 .9 1.6 2.4 1.1 3 .9.1-.7.4-1.1.6-1.4-2.2-.3-4.6-1.1-4.6-5 0-1.1.4-2 1-2.8-.1-.3-.4-1.3.1-2.7 0 0 .8-.3 2.7 1a9.4 9.4 0 0 1 5 0c1.9-1.3 2.7-1 2.7-1 .5 1.4.2 2.4.1 2.7.6.7 1 1.7 1 2.8 0 3.9-2.4 4.7-4.6 5 .4.3.7.9.7 1.9v2.8c0 .3.2.6.7.5A10 10 0 0 0 12 2z",
    "x": "M17.5 3h3l-6.6 7.5L21.7 21h-6l-4.7-6.1L5.6 21h-3l7-8L2.6 3h6.1l4.3 5.6L17.5 3zm-1.1 16.2h1.7L7.7 4.7H5.9l10.5 14.5z",
    "youtube": "M23 12s0-3.2-.4-4.7a3 3 0 0 0-2.1-2.1C18.9 4.7 12 4.7 12 4.7s-6.9 0-8.5.5A3 3 0 0 0 1.4 7.3C1 8.8 1 12 1 12s0 3.2.4 4.7a3 3 0 0 0 2.1 2.1c1.6.5 8.5.5 8.5.5s6.9 0 8.5-.5a3 3 0 0 0 2.1-2.1C23 15.2 23 12 23 12zM9.8 15.3V8.7l5.7 3.3-5.7 3.3z",
    "instagram": "M12 2.2c3.2 0 3.6 0 4.9.1 1.2.1 1.8.3 2.2.4.6.2 1 .5 1.4.9.4.4.7.8.9 1.4.2.4.4 1 .4 2.2.1 1.3.1 1.7.1 4.9s0 3.6-.1 4.9c-.1 1.2-.3 1.8-.4 2.2-.2.6-.5 1-.9 1.4-.4.4-.8.7-1.4.9-.4.2-1 .4-2.2.4-1.3.1-1.7.1-4.9.1s-3.6 0-4.9-.1c-1.2-.1-1.8-.3-2.2-.4-.6-.2-1-.5-1.4-.9-.4-.4-.7-.8-.9-1.4-.2-.4-.4-1-.4-2.2C2.2 15.6 2.2 15.2 2.2 12s0-3.6.1-4.9c.1-1.2.3-1.8.4-2.2.2-.6.5-1 .9-1.4.4-.4.8-.7 1.4-.9.4-.2 1-.4 2.2-.4C8.4 2.2 8.8 2.2 12 2.2zm0 3.1a6.7 6.7 0 1 0 0 13.4 6.7 6.7 0 0 0 0-13.4zm0 11a4.3 4.3 0 1 1 0-8.6 4.3 4.3 0 0 1 0 8.6zm6.9-11.3a1.6 1.6 0 1 1-3.1 0 1.6 1.6 0 0 1 3.1 0z",
    "tiktok": "M16.6 2h-3v13.4a2.6 2.6 0 1 1-2.3-2.6V9.7a5.9 5.9 0 1 0 5.3 5.8V9a7 7 0 0 0 4.1 1.3V7.2a4.1 4.1 0 0 1-4.1-4.1V2z",
    "twitch": "M4.3 2 2.5 6.4v13.1h4.6V22h2.6l2.5-2.5h3.7l5-5V2H4.3zm14.8 11.4-2.9 2.9h-4.6l-2.5 2.5v-2.5H5.5V3.9h13.6v9.5zM15.7 7v5.3h-1.9V7h1.9zm-5 0v5.3H8.8V7h1.9z",
    "telegram": "M21.9 4.3 18.8 19c-.2 1-.9 1.3-1.7.8l-4.7-3.5-2.3 2.2c-.3.3-.5.5-1 .5l.4-4.9 8.9-8c.4-.3-.1-.5-.6-.2L6.9 12.8 2.3 11.3c-1-.3-1-1 .2-1.5l18-6.9c.8-.3 1.6.2 1.4 1.4z",
    "reddit": "M22 12a2.1 2.1 0 0 0-3.6-1.5 10.4 10.4 0 0 0-5.5-1.7l1-4.4 3.1.7a1.7 1.7 0 1 0 .2-1.2l-3.7-.8a.6.6 0 0 0-.7.5l-1.1 5.2A10.4 10.4 0 0 0 6 10.5 2.1 2.1 0 1 0 3.4 14a4 4 0 0 0 0 .6c0 3.3 3.8 6 8.6 6s8.6-2.7 8.6-6a4 4 0 0 0 0-.6A2.1 2.1 0 0 0 22 12zM7 13.6a1.7 1.7 0 1 1 3.4 0 1.7 1.7 0 0 1-3.4 0zm9.5 4.6a5.9 5.9 0 0 1-4.5 1.4 5.9 5.9 0 0 1-4.5-1.4.6.6 0 0 1 .8-.9 4.7 4.7 0 0 0 3.7 1.1 4.7 4.7 0 0 0 3.7-1.1.6.6 0 1 1 .8.9zm-.6-2.9a1.7 1.7 0 1 1 0-3.4 1.7 1.7 0 0 1 0 3.4z",
    "spotify": "M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zm4.6 14.4a.8.8 0 0 1-1 .3 12 12 0 0 0-6.2-1.3.8.8 0 1 1-.1-1.5 13.6 13.6 0 0 1 7 1.5c.4.2.5.7.3 1zm1.2-2.8a.9.9 0 0 1-1.3.3 14.6 14.6 0 0 0-7.5-1.6.9.9 0 1 1-.2-1.8 16.4 16.4 0 0 1 8.6 1.8c.5.3.6.8.4 1.3zm.1-2.9a17.5 17.5 0 0 0-9-1.8 1.1 1.1 0 1 1-.3-2.2 19.7 19.7 0 0 1 10.2 2 1.1 1.1 0 1 1-1 2z",
    "steam": "M12 2a10 10 0 0 0-10 9.4l5.4 2.2a2.8 2.8 0 0 1 1.6-.5h.2l2.4-3.4v-.1a3.8 3.8 0 1 1 3.8 3.8h-.1l-3.4 2.4v.2a2.8 2.8 0 0 1-5.6.2L2.3 14.6A10 10 0 1 0 12 2zm-3.2 13.2-1.2-.5a2.1 2.1 0 0 0 3.9-.6 2.1 2.1 0 0 0-2.8-2l1.2.5a1.6 1.6 0 1 1-1.1 2.6zm9.7-6.7a2.5 2.5 0 1 0-5 0 2.5 2.5 0 0 0 5 0zm-4.4 0a1.9 1.9 0 1 1 3.8 0 1.9 1.9 0 0 1-3.8 0z",
    "linkedin": "M4.9 3.5a2.4 2.4 0 1 0 0 4.8 2.4 2.4 0 0 0 0-4.8zM3 21h3.9V9.5H3V21zm7.1-11.5V21H14v-6.3c0-1.7.3-3.3 2.4-3.3 2 0 2.1 1.9 2.1 3.4V21H22v-6.9c0-3.4-.7-6-4.7-6a4.1 4.1 0 0 0-3.7 2h-.1V9.5h-3.4z",
    "email": "M20 4H4a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V6a2 2 0 0 0-2-2zm0 4.2-8 5-8-5V6l8 5 8-5v2.2z",
    "website": "M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zm7 6h-3a15 15 0 0 0-1.4-3.7A8 8 0 0 1 19 8zM12 4.2c.7 1 1.3 2.3 1.7 3.8h-3.4c.4-1.5 1-2.8 1.7-3.8zM4.3 14a8 8 0 0 1 0-4h3.4a17 17 0 0 0 0 4H4.3zm.7 2h3a15 15 0 0 0 1.4 3.7A8 8 0 0 1 5 16zm3-8H5a8 8 0 0 1 4.4-3.7A15 15 0 0 0 8 8zm4 11.8c-.7-1-1.3-2.3-1.7-3.8h3.4c-.4 1.5-1 2.8-1.7 3.8zM14.1 14H9.9a15 15 0 0 1 0-4h4.2a15 15 0 0 1 0 4zm.5 5.7a15 15 0 0 0 1.4-3.7h3a8 8 0 0 1-4.4 3.7zm1.7-5.7a17 17 0 0 0 0-4h3.4a8 8 0 0 1 0 4h-3.4z",
}

_PLATFORM_LABELS = {
    "x": "X", "email": "Email", "website": "Website", "tiktok": "TikTok",
    "github": "GitHub", "linkedin": "LinkedIn",
}


def social_icon(platform: str) -> str:
    path = _ICON_PATHS.get(platform, _ICON_PATHS["website"])
    return (
        '<svg viewBox="0 0 24 24" width="20" height="20" fill="currentColor" '
        f'aria-hidden="true"><path d="{path}"></path></svg>'
    )


def platform_label(platform: str) -> str:
    return _PLATFORM_LABELS.get(platform, platform.capitalize())


def social_href(platform: str, url: str) -> str:
    if platform == "email" and url and "@" in url and not url.startswith("mailto:"):
        return sanitize_url("mailto:" + url)
    return url


# ---------------------------------------------------------------------------
# Stylesheet
# ---------------------------------------------------------------------------

PAD_SCALE = {"s": (28, 40), "m": (48, 64), "l": (72, 96), "xl": (104, 140)}
SPACING_FACTOR = {"tight": 0.75, "normal": 1.0, "airy": 1.3}


def stylesheet(theme: dict) -> str:
    pal = resolved_palette(theme)
    light = pal.get("mode") == "light"
    accent, accent2 = pal["accent"], pal["accent2"]
    factor = SPACING_FACTOR.get(theme.get("spacing", "normal"), 1.0)
    body_font = FONTS.get(theme.get("font", "inter"), FONTS["inter"])["stack"]
    head_font = FONTS.get(theme.get("heading_font", "inter"), FONTS["inter"])["stack"]
    line = rgba(pal["text"], 0.12 if light else 0.10)
    line_soft = rgba(pal["text"], 0.07 if light else 0.06)
    shadow = rgba("#000000", 0.10 if light else 0.45)

    pads = "\n".join(
        f".pad-{key}{{padding-block:{round(mob * factor)}px}}"
        f"@media(min-width:760px){{.pad-{key}{{padding-block:{round(desk * factor)}px}}}}"
        for key, (mob, desk) in PAD_SCALE.items()
    )

    return f""":root{{
  --bg:{pal['bg']}; --surface:{pal['surface']}; --text:{pal['text']}; --muted:{pal['muted']};
  --accent:{accent}; --accent2:{accent2}; --on-accent:{on_color(accent)};
  --line:{line}; --line-soft:{line_soft}; --shadow:0 18px 50px {shadow};
  --r:{theme.get('radius', 18)}px; --maxw:{theme.get('width', 1080)}px;
  --font:{body_font}; --head:{head_font};
  --glass:{rgba(pal['surface'], 0.72)};
}}
*,*::before,*::after{{box-sizing:border-box}}
html{{scroll-behavior:smooth;-webkit-text-size-adjust:100%}}
body{{margin:0;background:var(--bg);color:var(--text);font-family:var(--font);
  font-size:17px;line-height:1.65;overflow-x:hidden;
  -webkit-font-smoothing:antialiased;text-rendering:optimizeLegibility}}
img{{max-width:100%;height:auto;display:block}}
a{{color:var(--accent);text-decoration:none}}
a:hover{{text-decoration:underline}}
h1,h2,h3,h4{{font-family:var(--head);line-height:1.15;letter-spacing:-.022em;margin:0 0 .5em}}
h1{{font-size:clamp(2.1rem,6.2vw,3.9rem);font-weight:800}}
h2{{font-size:clamp(1.6rem,3.6vw,2.5rem);font-weight:700}}
h3{{font-size:1.18rem;font-weight:700}}
p{{margin:0 0 1em}}
p:last-child{{margin-bottom:0}}
ul{{margin:0 0 1em;padding-left:1.15em}}
code{{font-family:ui-monospace,'JetBrains Mono',monospace;font-size:.9em;
  background:var(--line-soft);padding:.15em .4em;border-radius:6px}}
.wrap{{width:min(100% - 40px,var(--maxw));margin-inline:auto}}
.wrap-narrow{{width:min(100% - 40px,760px);margin-inline:auto}}
section{{position:relative}}
{pads}
.sf-soft{{background:{rgba(pal['surface'], 0.55)}}}
.sf-solid{{background:var(--surface)}}
.sf-accent{{background:linear-gradient(135deg,var(--accent),var(--accent2));color:var(--on-accent)}}
.sf-accent h1,.sf-accent h2,.sf-accent h3,.sf-accent p{{color:var(--on-accent)}}
.sf-accent .muted{{color:{rgba('#ffffff', 0.85) if _luminance(accent) <= 0.45 else rgba('#000000', 0.7)}}}
.muted{{color:var(--muted)}}
.eyebrow{{display:inline-block;font-size:.78rem;font-weight:700;letter-spacing:.14em;
  text-transform:uppercase;color:var(--accent);margin-bottom:14px;
  padding:6px 12px;border-radius:999px;background:{rgba(accent, 0.12)};border:1px solid {rgba(accent, 0.25)}}}
.center{{text-align:center}}
.center .btns{{justify-content:center}}
.head{{max-width:640px;margin-bottom:36px}}
.center .head{{margin-inline:auto}}
.head p{{color:var(--muted);font-size:1.05rem}}
/* buttons */
.btns{{display:flex;flex-wrap:wrap;gap:12px;margin-top:26px}}
.btn{{display:inline-flex;align-items:center;justify-content:center;gap:8px;
  padding:13px 24px;border-radius:calc(var(--r) * .7);font-weight:650;font-size:.98rem;
  border:1px solid transparent;cursor:pointer;transition:transform .18s cubic-bezier(.2,.7,.3,1),box-shadow .18s,background .18s;
  text-decoration:none;line-height:1.2}}
.btn:hover{{transform:translateY(-2px);text-decoration:none}}
.btn:active{{transform:translateY(0)}}
.btn-primary{{background:linear-gradient(135deg,var(--accent),var(--accent2));color:var(--on-accent);
  box-shadow:0 10px 26px {rgba(accent, 0.32)}}}
.btn-primary:hover{{box-shadow:0 16px 34px {rgba(accent, 0.42)}}}
.btn-ghost{{border-color:var(--line);color:var(--text);background:transparent}}
.btn-ghost:hover{{border-color:var(--accent);background:{rgba(accent, 0.08)}}}
.btn-soft{{background:{rgba(accent, 0.14)};color:var(--accent)}}
.btn-soft:hover{{background:{rgba(accent, 0.22)}}}
/* cards */
.card{{background:var(--surface);border:1px solid var(--line);border-radius:var(--r);
  padding:26px;transition:transform .25s cubic-bezier(.2,.7,.3,1),border-color .25s,box-shadow .25s}}
.card:hover{{transform:translateY(-4px);border-color:{rgba(accent, 0.45)};box-shadow:var(--shadow)}}
.grid{{display:grid;gap:18px}}
.cols-2{{grid-template-columns:repeat(2,minmax(0,1fr))}}
.cols-3{{grid-template-columns:repeat(3,minmax(0,1fr))}}
.cols-4{{grid-template-columns:repeat(4,minmax(0,1fr))}}
@media(max-width:1000px){{.cols-4{{grid-template-columns:repeat(3,minmax(0,1fr))}}}}
@media(max-width:840px){{.cols-3,.cols-4{{grid-template-columns:repeat(2,minmax(0,1fr))}}}}
@media(max-width:560px){{.grid{{grid-template-columns:minmax(0,1fr)}}}}
.ic{{font-size:1.7rem;line-height:1;display:inline-block;margin-bottom:14px}}
/* reveal animation */
[data-r]{{opacity:0;transform:translateY(18px);transition:opacity .7s ease,transform .7s cubic-bezier(.2,.7,.3,1)}}
[data-r].in{{opacity:1;transform:none}}
@media(prefers-reduced-motion:reduce){{
  [data-r]{{opacity:1;transform:none;transition:none}}
  html{{scroll-behavior:auto}}
  .btn:hover,.card:hover{{transform:none}}
}}
"""


BACKDROP_CSS = {
    "aurora": """.bd{position:absolute;inset:0;overflow:hidden;pointer-events:none;z-index:0}
.bd{-webkit-mask-image:linear-gradient(#000 62%,transparent);mask-image:linear-gradient(#000 62%,transparent)}
.bd::before,.bd::after{content:'';position:absolute;width:52vw;height:52vw;border-radius:50%;
  filter:blur(90px);opacity:.45;animation:float 22s ease-in-out infinite alternate}
.bd::before{background:var(--accent);top:-18%;left:-12%}
.bd::after{background:var(--accent2);bottom:-24%;right:-14%;animation-delay:-8s}
@keyframes float{from{transform:translate3d(0,0,0) scale(1)}to{transform:translate3d(4%,6%,0) scale(1.18)}}""",
    "beam": """.bd{position:absolute;inset:0;overflow:hidden;pointer-events:none;z-index:0}
.bd::before{content:'';position:absolute;top:-60%;left:50%;width:120%;height:150%;transform:translateX(-50%);
  background:radial-gradient(ellipse at 50% 0%,var(--accent) 0%,transparent 62%);opacity:.28}""",
    "grid": """.bd{position:absolute;inset:0;pointer-events:none;z-index:0;
  background-image:linear-gradient(var(--line) 1px,transparent 1px),linear-gradient(90deg,var(--line) 1px,transparent 1px);
  background-size:46px 46px;
  -webkit-mask-image:radial-gradient(ellipse 70% 60% at 50% 0%,#000 20%,transparent 75%);
  mask-image:radial-gradient(ellipse 70% 60% at 50% 0%,#000 20%,transparent 75%)}""",
    "dots": """.bd{position:absolute;inset:0;pointer-events:none;z-index:0;
  background-image:radial-gradient(var(--line) 1.4px,transparent 1.4px);background-size:24px 24px;
  -webkit-mask-image:radial-gradient(ellipse 75% 70% at 50% 10%,#000 10%,transparent 80%);
  mask-image:radial-gradient(ellipse 75% 70% at 50% 10%,#000 10%,transparent 80%)}""",
    "noise": """.bd{position:absolute;inset:0;pointer-events:none;z-index:0;opacity:.06;
  background-image:url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='120' height='120'%3E%3Cfilter id='n'%3E%3CfeTurbulence baseFrequency='.85' numOctaves='3'/%3E%3C/filter%3E%3Crect width='120' height='120' filter='url(%23n)'/%3E%3C/svg%3E")}""",
    "none": ".bd{display:none}",
}

RUNTIME_JS = """(function(){
  var io = 'IntersectionObserver' in window ? new IntersectionObserver(function(es){
    es.forEach(function(e){ if(e.isIntersecting){ e.target.classList.add('in'); io.unobserve(e.target);
      if(e.target.hasAttribute('data-count')) countUp(e.target); } });
  }, {rootMargin:'0px 0px -8% 0px', threshold:0.06}) : null;
  function watch(){ document.querySelectorAll('[data-r]:not(.in)').forEach(function(el){
    if(io){ io.observe(el); } else { el.classList.add('in'); } }); }
  function countUp(el){
    var raw = el.getAttribute('data-count') || '', m = raw.match(/-?[\\d.,]+/);
    if(!m || window.matchMedia('(prefers-reduced-motion: reduce)').matches){ return; }
    var target = parseFloat(m[0].replace(/,/g,'')); if(!isFinite(target) || target > 1e9){ return; }
    var pre = raw.slice(0, m.index), post = raw.slice(m.index + m[0].length);
    var dec = (m[0].split('.')[1] || '').length, group = m[0].indexOf(',') > -1;
    var t0 = performance.now(), dur = 1100;
    (function step(now){
      var p = Math.min(1, (now - t0) / dur), v = target * (1 - Math.pow(1 - p, 3));
      var s = dec ? v.toFixed(dec) : String(Math.round(v));
      if(group){ s = s.replace(/\\B(?=(\\d{3})+(?!\\d))/g, ','); }
      el.textContent = pre + s + post;
      if(p < 1){ requestAnimationFrame(step); }
    })(t0);
  }
  function nav(){
    var t = document.querySelector('[data-navtoggle]'); if(!t){ return; }
    t.addEventListener('click', function(){
      var open = document.body.classList.toggle('nav-open');
      t.setAttribute('aria-expanded', open ? 'true' : 'false');
    });
    document.querySelectorAll('.nav-links a').forEach(function(a){
      a.addEventListener('click', function(){ document.body.classList.remove('nav-open'); });
    });
  }
  function countdowns(){
    var els = document.querySelectorAll('[data-until]'); if(!els.length){ return; }
    function pad(n){ return (n < 10 ? '0' : '') + n; }
    function tick(){
      els.forEach(function(el){
        var end = parseInt(el.getAttribute('data-until'), 10) * 1000, left = end - Date.now();
        if(left <= 0){ el.innerHTML = '<div class="cd-done">' + (el.getAttribute('data-done') || '') + '</div>'; return; }
        var d = Math.floor(left / 864e5), h = Math.floor(left / 36e5) % 24,
            m = Math.floor(left / 6e4) % 60, s = Math.floor(left / 1e3) % 60;
        var parts = [[d,'days'],[h,'hours'],[m,'minutes'],[s,'seconds']];
        el.innerHTML = parts.map(function(p){
          return '<div class="cd-cell"><span class="cd-num">' + pad(p[0]) + '</span><span class="cd-lab">' + p[1] + '</span></div>';
        }).join('');
      });
    }
    tick(); setInterval(tick, 1000);
  }
  function copies(){
    document.querySelectorAll('[data-copy]').forEach(function(el){
      el.addEventListener('click', function(){
        var text = el.getAttribute('data-copy');
        var done = function(){ var old = el.getAttribute('data-label') || el.textContent;
          el.setAttribute('data-label', old); el.textContent = 'Copied!';
          setTimeout(function(){ el.textContent = old; }, 1400); };
        if(navigator.clipboard){ navigator.clipboard.writeText(text).then(done, function(){}); }
      });
    });
  }
  function boot(){ watch(); nav(); countdowns(); copies(); }
  if(document.readyState === 'loading'){ document.addEventListener('DOMContentLoaded', boot); } else { boot(); }
})();"""


def backdrop_css(kind: str) -> str:
    """Namespace a backdrop so heroes and the page can use different ones."""
    css = BACKDROP_CSS.get(kind, BACKDROP_CSS["none"])
    return css.replace(".bd{", f".bd-{kind}{{").replace(".bd::", f".bd-{kind}::")


# ---------------------------------------------------------------------------
# Per-block CSS (only the blocks a page actually uses get shipped)
# ---------------------------------------------------------------------------

BLOCK_CSS: dict[str, str] = {
    "nav": """.nav{position:relative;z-index:60;border-bottom:1px solid var(--line-soft)}
.nav.sticky{position:sticky;top:0;backdrop-filter:blur(14px);-webkit-backdrop-filter:blur(14px);background:var(--glass)}
.nav-inner{display:flex;align-items:center;justify-content:space-between;gap:16px;padding:14px 0}
.nav-brand{display:flex;align-items:center;gap:10px;font-weight:800;color:var(--text);font-size:1.05rem}
.nav-brand:hover{text-decoration:none;opacity:.85}
.nav-brand img{width:32px;height:32px;border-radius:9px;object-fit:cover}
.nav-links{display:flex;align-items:center;gap:8px}
.nav-links a{color:var(--muted);padding:8px 12px;border-radius:10px;font-size:.95rem;font-weight:550;transition:color .2s,background .2s}
.nav-links a:hover{color:var(--text);background:var(--line-soft);text-decoration:none}
.nav-links .btn{color:var(--on-accent)}
.nav-burger{display:none;width:42px;height:42px;border-radius:12px;border:1px solid var(--line);
  background:transparent;color:var(--text);cursor:pointer;align-items:center;justify-content:center;flex-direction:column;gap:4px}
.nav-burger span{display:block;width:18px;height:2px;background:currentColor;border-radius:2px;transition:transform .25s,opacity .25s}
body.nav-open .nav-burger span:nth-child(1){transform:translateY(6px) rotate(45deg)}
body.nav-open .nav-burger span:nth-child(2){opacity:0}
body.nav-open .nav-burger span:nth-child(3){transform:translateY(-6px) rotate(-45deg)}
@media(max-width:760px){
  .nav-burger{display:flex}
  .nav-links{position:absolute;top:100%;left:0;right:0;flex-direction:column;align-items:stretch;
    gap:4px;padding:14px 20px 20px;background:var(--surface);border-bottom:1px solid var(--line);
    opacity:0;visibility:hidden;transform:translateY(-10px);transition:.22s ease}
  .nav-links a{padding:12px}
  body.nav-open .nav-links{opacity:1;visibility:visible;transform:none}
}""",
    "hero": """.s-hero{overflow:hidden}
.hero-inner{display:grid;gap:44px;align-items:center}
.hero-inner.split{grid-template-columns:1fr}
@media(min-width:900px){.hero-inner.split{grid-template-columns:1.05fr .95fr}}
.hero-sub{font-size:clamp(1.02rem,2vw,1.22rem);max-width:56ch;color:var(--muted)}
.hero-inner.center{text-align:center;justify-items:center}
.hero-inner.center .hero-sub{margin-inline:auto}
.hero-media img{border-radius:var(--r);box-shadow:var(--shadow);width:100%}
.hero-avatar{width:132px;height:132px;border-radius:50%;object-fit:cover;margin:0 auto 26px;
  border:3px solid var(--accent);box-shadow:0 0 0 8px rgba(255,255,255,.04),var(--shadow)}
.hero-avatar-ring{animation:pulse 4s ease-in-out infinite}
@keyframes pulse{0%,100%{box-shadow:0 0 0 8px rgba(255,255,255,.04)}50%{box-shadow:0 0 0 16px rgba(255,255,255,.02)}}""",
    "discord": """.dc{border:1px solid var(--line);border-radius:calc(var(--r) + 6px);overflow:hidden;
  background:var(--surface);box-shadow:var(--shadow);max-width:720px;margin-inline:auto}
.dc-banner{height:150px;background:linear-gradient(135deg,var(--accent),var(--accent2));background-size:cover;background-position:center}
.dc-body{padding:26px;position:relative}
.dc-icon{width:82px;height:82px;border-radius:26px;object-fit:cover;border:5px solid var(--surface);
  margin-top:-64px;background:var(--surface);display:grid;place-items:center;font-weight:800;font-size:1.5rem}
.dc-name{margin:16px 0 6px;font-size:1.6rem}
.dc-meta{display:flex;flex-wrap:wrap;gap:18px;margin:18px 0 22px;padding:16px 0;
  border-top:1px solid var(--line-soft);border-bottom:1px solid var(--line-soft)}
.dc-meta div{display:flex;align-items:center;gap:8px;font-size:.94rem;color:var(--muted)}
.dc-meta b{color:var(--text);font-size:1.05rem}
.dc-dot{width:9px;height:9px;border-radius:50%;background:#3ba55d;box-shadow:0 0 0 4px rgba(59,165,93,.18)}
.dc-dot.idle{background:var(--muted);box-shadow:none}
.dc-widget{width:100%;height:340px;border:0;border-radius:var(--r);margin-top:20px;background:var(--line-soft)}""",
    "links": """.lk{display:flex;flex-direction:column;gap:12px;max-width:560px;margin-inline:auto}
.lk a{display:flex;align-items:center;gap:14px;padding:16px 20px;border-radius:calc(var(--r) * .8);
  font-weight:600;transition:transform .18s cubic-bezier(.2,.7,.3,1),background .2s,border-color .2s;color:var(--text)}
.lk a:hover{transform:translateY(-2px) scale(1.012);text-decoration:none}
.lk-soft a{background:var(--surface);border:1px solid var(--line)}
.lk-soft a:hover{border-color:var(--accent)}
.lk-outline a{border:1.5px solid var(--line)}
.lk-outline a:hover{border-color:var(--accent);background:var(--line-soft)}
.lk-solid a{background:linear-gradient(135deg,var(--accent),var(--accent2));color:var(--on-accent);border:1px solid transparent}
.lk-ic{font-size:1.25rem;width:26px;text-align:center;flex:none}
.lk-note{margin-left:auto;font-size:.82rem;opacity:.7;font-weight:500}
.lk-arrow{margin-left:auto;opacity:.35;transition:transform .2s,opacity .2s}
.lk a:hover .lk-arrow{transform:translateX(3px);opacity:.9}""",
    "socials": """.soc{display:flex;flex-wrap:wrap;gap:12px;justify-content:center}
.soc a{display:grid;place-items:center;border-radius:50%;background:var(--surface);
  border:1px solid var(--line);color:var(--muted);transition:transform .2s cubic-bezier(.2,.7,.3,1),color .2s,border-color .2s,background .2s}
.soc a:hover{transform:translateY(-3px) scale(1.06);color:var(--accent);border-color:var(--accent);background:var(--line-soft)}
.soc-s a{width:38px;height:38px}.soc-m a{width:46px;height:46px}.soc-l a{width:56px;height:56px}""",
    "about": """.ab{display:grid;gap:36px;align-items:center}
@media(min-width:880px){.ab.split{grid-template-columns:1fr 1fr}}
.ab-body{font-size:1.05rem;color:var(--muted)}
.ab-body strong{color:var(--text)}
.ab img{border-radius:var(--r);box-shadow:var(--shadow)}""",
    "features": """.ft h3{margin-bottom:6px}
.ft p{color:var(--muted);font-size:.97rem;margin:0}""",
    "stats": """.st{display:grid;gap:14px;text-align:center}
.st .card{padding:28px 18px}
.st-num{font-family:var(--head);font-size:clamp(1.9rem,4.4vw,2.9rem);font-weight:800;
  background:linear-gradient(135deg,var(--accent),var(--accent2));-webkit-background-clip:text;
  background-clip:text;color:transparent;line-height:1.1}
.st-lab{color:var(--muted);font-size:.9rem;font-weight:600;letter-spacing:.04em;text-transform:uppercase;margin-top:6px}""",
    "gallery": """.gal figure{margin:0;border-radius:var(--r);overflow:hidden;background:var(--surface);border:1px solid var(--line);position:relative}
.gal img{width:100%;height:100%;object-fit:cover;transition:transform .5s cubic-bezier(.2,.7,.3,1)}
.gal figure:hover img{transform:scale(1.06)}
.gal .ratio-wide{aspect-ratio:16/10}.gal .ratio-square{aspect-ratio:1}.gal .ratio-tall{aspect-ratio:3/4}
.gal figcaption{position:absolute;left:0;right:0;bottom:0;padding:26px 16px 14px;font-size:.9rem;
  color:#fff;background:linear-gradient(transparent,rgba(0,0,0,.75));opacity:0;transform:translateY(6px);transition:.25s}
.gal figure:hover figcaption{opacity:1;transform:none}
.gal-empty{display:flex;flex-direction:column;align-items:center;justify-content:center;gap:8px;
  color:var(--muted);background:var(--line-soft);font-size:1.6rem}
.gal-empty span{font-size:.82rem}
.gal-empty.ratio-wide{aspect-ratio:16/10}.gal-empty.ratio-square{aspect-ratio:1}.gal-empty.ratio-tall{aspect-ratio:3/4}""",
    "image": """.im figure{margin:0}
.im img{border-radius:var(--r);box-shadow:var(--shadow);margin-inline:auto}
.im figcaption{text-align:center;color:var(--muted);font-size:.9rem;margin-top:12px}""",
    "pricing": """.pr .card{display:flex;flex-direction:column;position:relative}
.pr .hot{border-color:var(--accent);box-shadow:0 0 0 1px var(--accent),var(--shadow)}
.pr .tag{position:absolute;top:-11px;left:50%;transform:translateX(-50%);background:var(--accent);
  color:var(--on-accent);font-size:.7rem;font-weight:800;letter-spacing:.1em;text-transform:uppercase;padding:5px 12px;border-radius:999px}
.pr-price{font-family:var(--head);font-size:2.5rem;font-weight:800;line-height:1;margin:10px 0 2px}
.pr-per{color:var(--muted);font-size:.9rem}
.pr ul{list-style:none;padding:0;margin:20px 0;flex:1}
.pr li{padding:7px 0 7px 26px;position:relative;color:var(--muted);font-size:.95rem}
.pr li::before{content:'✓';position:absolute;left:0;color:var(--accent);font-weight:800}""",
    "testimonials": """.ts blockquote{margin:0 0 18px;font-size:1.02rem;line-height:1.6}
.ts blockquote::before{content:'“';font-family:var(--head);font-size:2.6rem;line-height:0;
  color:var(--accent);display:block;height:22px;opacity:.7}
.ts-who{display:flex;align-items:center;gap:12px}
.ts-who img,.ts-av{width:42px;height:42px;border-radius:50%;object-fit:cover;flex:none;
  display:grid;place-items:center;background:linear-gradient(135deg,var(--accent),var(--accent2));
  color:var(--on-accent);font-weight:800;font-size:.85rem}
.ts-name{font-weight:700;font-size:.95rem}
.ts-role{color:var(--muted);font-size:.83rem}""",
    "faq": """.faq{max-width:760px;margin-inline:auto;display:flex;flex-direction:column;gap:10px}
.faq details{background:var(--surface);border:1px solid var(--line);border-radius:calc(var(--r) * .8);
  overflow:hidden;transition:border-color .2s}
.faq details[open]{border-color:var(--accent)}
.faq summary{cursor:pointer;padding:18px 22px;font-weight:650;list-style:none;display:flex;
  align-items:center;justify-content:space-between;gap:14px}
.faq summary::-webkit-details-marker{display:none}
.faq summary::after{content:'';width:9px;height:9px;border-right:2px solid var(--muted);
  border-bottom:2px solid var(--muted);transform:rotate(45deg);transition:transform .25s;flex:none;margin-bottom:4px}
.faq details[open] summary::after{transform:rotate(225deg);margin:4px 0 0}
.faq .faq-a{padding:0 22px 20px;color:var(--muted);animation:fade .3s ease}
@keyframes fade{from{opacity:0;transform:translateY(-6px)}to{opacity:1;transform:none}}""",
    "timeline": """.tl{position:relative;max-width:760px;margin-inline:auto;padding-left:32px}
.tl::before{content:'';position:absolute;left:7px;top:6px;bottom:6px;width:2px;
  background:linear-gradient(var(--accent),var(--accent2));opacity:.45;border-radius:2px}
.tl-item{position:relative;padding-bottom:30px}
.tl-item:last-child{padding-bottom:0}
.tl-item::before{content:'';position:absolute;left:-32px;top:7px;width:16px;height:16px;border-radius:50%;
  background:var(--bg);border:3px solid var(--accent)}
.tl-date{font-size:.78rem;font-weight:800;letter-spacing:.1em;text-transform:uppercase;color:var(--accent)}
.tl-item h3{margin:4px 0 6px}
.tl-item p{color:var(--muted);margin:0}""",
    "team": """.tm{text-align:center}
.tm .card{padding:24px 16px}
.tm img,.tm .tm-av{width:88px;height:88px;border-radius:50%;object-fit:cover;margin:0 auto 14px;
  display:grid;place-items:center;background:linear-gradient(135deg,var(--accent),var(--accent2));
  color:var(--on-accent);font-weight:800;font-size:1.4rem}
.tm h3{margin:0 0 2px;font-size:1.02rem}
.tm p{color:var(--muted);font-size:.87rem;margin:0}""",
    "cta": """.cta-box{text-align:center;max-width:680px;margin-inline:auto}
.cta-box h2{margin-bottom:12px}""",
    "contact": """.ct{display:grid;gap:12px;max-width:620px;margin-inline:auto}
.ct-row{display:flex;align-items:center;gap:14px;padding:16px 20px;background:var(--surface);
  border:1px solid var(--line);border-radius:calc(var(--r) * .8)}
.ct-key{color:var(--muted);font-size:.82rem;font-weight:700;letter-spacing:.08em;text-transform:uppercase;min-width:82px}
.ct-val{font-weight:600;word-break:break-word}
.ct-copy{margin-left:auto;font-size:.8rem;font-weight:700;color:var(--accent);background:transparent;
  border:1px solid var(--line);border-radius:8px;padding:6px 10px;cursor:pointer;flex:none}
.ct-copy:hover{border-color:var(--accent)}""",
    "embed": """.emb{border-radius:var(--r);overflow:hidden;border:1px solid var(--line);background:var(--surface)}
.emb iframe{width:100%;height:100%;border:0;display:block}
.emb.r-16-9{aspect-ratio:16/9}.emb.r-4-3{aspect-ratio:4/3}.emb.r-1-1{aspect-ratio:1}.emb.r-tall{aspect-ratio:9/14;max-width:420px;margin-inline:auto}
.emb-cap{text-align:center;color:var(--muted);font-size:.88rem;margin-top:12px}
.emb-empty{display:grid;place-items:center;padding:48px 20px;color:var(--muted);font-size:.92rem;text-align:center}""",
    "marquee": """.mq{overflow:hidden;-webkit-mask-image:linear-gradient(90deg,transparent,#000 8%,#000 92%,transparent);
  mask-image:linear-gradient(90deg,transparent,#000 8%,#000 92%,transparent)}
.mq-track{display:flex;width:max-content;animation:slide var(--mq,26s) linear infinite}
.mq-track span{padding:0 26px;font-family:var(--head);font-weight:700;font-size:clamp(1rem,2.2vw,1.4rem);
  color:var(--muted);white-space:nowrap;display:flex;align-items:center;gap:26px}
.mq-track span::after{content:'✦';color:var(--accent);font-size:.7em}
@keyframes slide{from{transform:translateX(0)}to{transform:translateX(-50%)}}
@media(prefers-reduced-motion:reduce){.mq-track{animation:none}}""",
    "countdown": """.cd{display:flex;flex-wrap:wrap;gap:14px;justify-content:center}
.cd-cell{min-width:92px;padding:18px 10px;background:var(--surface);border:1px solid var(--line);
  border-radius:var(--r);text-align:center}
.cd-num{display:block;font-family:var(--head);font-size:clamp(1.8rem,4vw,2.6rem);font-weight:800;
  line-height:1;font-variant-numeric:tabular-nums;
  background:linear-gradient(135deg,var(--accent),var(--accent2));-webkit-background-clip:text;background-clip:text;color:transparent}
.cd-lab{display:block;margin-top:8px;font-size:.74rem;letter-spacing:.14em;text-transform:uppercase;color:var(--muted)}
.cd-done{font-family:var(--head);font-size:1.6rem;font-weight:800;color:var(--accent)}""",
    "divider": """.dv{height:1px;background:var(--line)}
.dv-dots{height:6px;background:radial-gradient(circle,var(--muted) 1.6px,transparent 1.7px);
  background-size:16px 6px;opacity:.6}
.dv-glow{height:2px;background:linear-gradient(90deg,transparent,var(--accent),var(--accent2),transparent);
  border-radius:2px;opacity:.85}""",
    "footer": """.ft-wrap{border-top:1px solid var(--line-soft);padding:40px 0;margin-top:auto}
.ft-inner{display:flex;flex-wrap:wrap;gap:18px;align-items:center;justify-content:space-between}
.ft-links{display:flex;flex-wrap:wrap;gap:16px}
.ft-links a{color:var(--muted);font-size:.92rem}
.ft-links a:hover{color:var(--text)}
.ft-txt{color:var(--muted);font-size:.9rem}
.ft-badge{display:inline-flex;align-items:center;gap:6px;font-size:.78rem;color:var(--muted);
  border:1px solid var(--line);border-radius:999px;padding:5px 12px;transition:.2s}
.ft-badge:hover{color:var(--accent);border-color:var(--accent);text-decoration:none}""",
}


# ---------------------------------------------------------------------------
# Embeds (allowlist only -- arbitrary iframes are never rendered)
# ---------------------------------------------------------------------------

_YT = re.compile(r"(?:youtube\.com/(?:watch\?v=|embed/|shorts/|live/)|youtu\.be/)([A-Za-z0-9_-]{6,20})")
_SPOTIFY = re.compile(r"open\.spotify\.com/(?:intl-[a-z-]+/)?(track|album|playlist|artist|episode|show)/([A-Za-z0-9]{6,40})")
_VIMEO = re.compile(r"vimeo\.com/(?:video/)?(\d{5,12})")
_SOUNDCLOUD = re.compile(r"^https://soundcloud\.com/[A-Za-z0-9_/-]{3,120}$")
_MAPS = re.compile(r"^https://www\.google\.com/maps/embed\?pb=[A-Za-z0-9!_.\-]{5,900}$")
_DISCORD_WIDGET = re.compile(r"discord\.com/widget\?id=(\d{5,25})")


def embed_src(url: str) -> str:
    """Map a pasted link to a safe embeddable src, or '' if unsupported."""
    url = sanitize_url(url)
    if not url.startswith("https://"):
        return ""
    match = _YT.search(url)
    if match:
        return f"https://www.youtube-nocookie.com/embed/{match.group(1)}"
    match = _SPOTIFY.search(url)
    if match:
        return f"https://open.spotify.com/embed/{match.group(1)}/{match.group(2)}"
    match = _VIMEO.search(url)
    if match:
        return f"https://player.vimeo.com/video/{match.group(1)}"
    match = _DISCORD_WIDGET.search(url)
    if match:
        return f"https://discord.com/widget?id={match.group(1)}&theme=dark"
    if _MAPS.match(url):
        return url
    if _SOUNDCLOUD.match(url):
        from urllib.parse import quote

        return f"https://w.soundcloud.com/player/?url={quote(url, safe='')}&color=%23000000&visual=true"
    return ""


def _epoch(value: str) -> int:
    """Parse a datetime-local string as UTC and return epoch seconds."""
    raw = (value or "").strip().replace(" ", "T")
    if not raw:
        return 0
    for suffix in ("", ":00"):
        try:
            parsed = datetime.fromisoformat(raw.replace("Z", "+00:00") + suffix)
        except ValueError:
            continue
        if parsed.tzinfo is None:
            parsed = parsed.replace(tzinfo=timezone.utc)
        return int(parsed.timestamp())
    return 0


# ---------------------------------------------------------------------------
# Block renderers
# ---------------------------------------------------------------------------

def _buttons(items: list[dict], reveal: str = "") -> str:
    out = []
    for item in items:
        label, url = item.get("label", ""), item.get("url", "")
        if not label:
            continue
        style = item.get("style", "primary")
        out.append(link_html(url or "#", esc(label), f"btn btn-{style}"))
    return f'<div class="btns"{reveal}>{"".join(out)}</div>' if out else ""


def _heading(props: dict, sub_key: str = "subheading", center: bool = True) -> str:
    heading, sub = props.get("heading", ""), props.get(sub_key, "")
    if not heading and not sub:
        return ""
    parts = ['<div class="head" data-r>']
    if heading:
        parts.append(f"<h2>{esc(heading)}</h2>")
    if sub:
        parts.append(muted_paragraphs(sub))
    parts.append("</div>")
    return "".join(parts)


def _cols(props: dict, default: str = "3") -> str:
    return f'cols-{props.get("columns", default)}'


def r_nav(p: dict, ctx: dict) -> str:
    brand = esc(p.get("brand") or "")
    logo = img_tag(p.get("logo", ""), p.get("brand", ""), loading="eager")
    links = "".join(
        link_html(item.get("url") or "#", esc(item.get("label", "")))
        for item in p.get("links", []) if item.get("label")
    )
    if p.get("cta_label"):
        links += link_html(p.get("cta_url") or "#", esc(p["cta_label"]), "btn btn-primary")
    burger = (
        '<button class="nav-burger" data-navtoggle aria-label="Menu" aria-expanded="false">'
        "<span></span><span></span><span></span></button>"
    ) if links else ""
    sticky = " sticky" if p.get("sticky", True) else ""
    return (
        f'<header class="nav{sticky}"><div class="wrap nav-inner">'
        f'<a class="nav-brand" href="#top">{logo}<span>{brand}</span></a>'
        f'{burger}<nav class="nav-links">{links}</nav>'
        "</div></header>"
    )


def r_hero(p: dict, ctx: dict) -> str:
    layout = p.get("layout", "center")
    eyebrow = f'<span class="eyebrow">{esc(p["eyebrow"])}</span>' if p.get("eyebrow") else ""
    title = f"<h1>{inline_text(p.get('title', ''))}</h1>" if p.get("title") else ""
    sub = f'<p class="hero-sub">{inline_text(p["subtitle"])}</p>' if p.get("subtitle") else ""
    buttons = _buttons(p.get("buttons", []))
    avatar = ""
    media = ""
    if p.get("image"):
        if layout == "avatar":
            avatar = img_tag(p["image"], p.get("title", ""), "hero-avatar hero-avatar-ring", loading="eager")
        elif layout == "split":
            media = f'<div class="hero-media" data-r>{img_tag(p["image"], p.get("title", ""), loading="eager")}</div>'
    inner_class = "split" if (layout == "split" and media) else "center"
    copy = f'<div class="hero-copy" data-r>{avatar}{eyebrow}{title}{sub}{buttons}</div>'
    glow = p.get("glow", "aurora")
    ctx["backdrops"].add(glow)
    backdrop = f'<div class="bd-{glow}" aria-hidden="true"></div>' if glow != "none" else ""
    return f'{backdrop}<div class="wrap hero-inner {inner_class}">{copy}{media}</div>'


def r_discord(p: dict, ctx: dict) -> str:
    banner = ""
    if p.get("banner"):
        banner = f'<div class="dc-banner" style="background-image:url(\'{esc(p["banner"])}\')"></div>'
    else:
        banner = '<div class="dc-banner"></div>'
    icon = (
        img_tag(p["icon"], p.get("name", ""), "dc-icon")
        if p.get("icon")
        else f'<div class="dc-icon">{initials(p.get("name", ""))}</div>'
    )
    meta = []
    if p.get("members"):
        meta.append(f'<div><span class="dc-dot idle"></span><b data-count="{esc(p["members"])}">{esc(p["members"])}</b> members</div>')
    if p.get("online"):
        meta.append(f'<div><span class="dc-dot"></span><b data-count="{esc(p["online"])}">{esc(p["online"])}</b> online</div>')
    if p.get("boosts"):
        meta.append(f'<div>🚀 <b>{esc(p["boosts"])}</b> boosts</div>')
    meta_html = f'<div class="dc-meta">{"".join(meta)}</div>' if meta else ""
    cta = ""
    if p.get("invite"):
        cta = link_html(p["invite"], f'{social_icon("discord")}<span>{esc(p.get("cta") or "Join")}</span>', "btn btn-primary")
    widget = ""
    if p.get("widget_id", "").isdigit():
        widget = (
            f'<iframe class="dc-widget" src="https://discord.com/widget?id={esc(p["widget_id"])}&theme=dark" '
            'title="Discord server widget" loading="lazy" sandbox="allow-popups allow-popups-to-escape-sandbox allow-scripts allow-same-origin"></iframe>'
        )
    return (
        f'<div class="wrap"><div class="dc" data-r>{banner}<div class="dc-body">{icon}'
        f'<h2 class="dc-name">{esc(p.get("name", ""))}</h2>'
        f'{muted_paragraphs(p.get("tagline", ""))}'
        f'{meta_html}{cta}{widget}</div></div></div>'
    )


def r_links(p: dict, ctx: dict) -> str:
    style = p.get("style", "soft")
    rows = []
    for item in p.get("items", []):
        if not item.get("label"):
            continue
        icon = f'<span class="lk-ic">{esc(item["icon"])}</span>' if item.get("icon") else ""
        note = f'<span class="lk-note">{esc(item["note"])}</span>' if item.get("note") else '<span class="lk-arrow">→</span>'
        rows.append(link_html(item.get("url") or "#", f'{icon}<span>{esc(item["label"])}</span>{note}'))
    heading = f'<div class="head center" data-r><h2>{esc(p["heading"])}</h2></div>' if p.get("heading") else ""
    return f'<div class="wrap">{heading}<div class="lk lk-{style}" data-r>{"".join(rows)}</div></div>'


def r_socials(p: dict, ctx: dict) -> str:
    out = []
    for item in p.get("items", []):
        platform = item.get("platform", "website")
        href = social_href(platform, item.get("url", ""))
        if not href:
            continue
        out.append(link_html(href, social_icon(platform), "") .replace("<a ", f'<a title="{esc(platform_label(platform))}" aria-label="{esc(platform_label(platform))}" '))
    if not out:
        return ""
    return f'<div class="wrap"><div class="soc soc-{p.get("size", "m")}" data-r>{"".join(out)}</div></div>'


def r_about(p: dict, ctx: dict) -> str:
    align = " center" if p.get("align") == "center" else ""
    heading = f"<h2>{esc(p['heading'])}</h2>" if p.get("heading") else ""
    body = f'<div class="ab-body">{rich_text(p.get("body", ""))}</div>'
    image = f'<div data-r>{img_tag(p["image"], p.get("heading", ""))}</div>' if p.get("image") else ""
    split = "split" if (p.get("layout") == "split" and image) else ""
    inner = f'<div data-r class="{align.strip()}">{heading}{body}</div>{image}'
    width = "wrap" if split else "wrap-narrow"
    return f'<div class="{width}"><div class="ab {split}{align}">{inner}</div></div>'


def r_features(p: dict, ctx: dict) -> str:
    cards = []
    for index, item in enumerate(p.get("items", [])):
        if not (item.get("title") or item.get("text")):
            continue
        icon = f'<span class="ic">{esc(item["icon"])}</span>' if item.get("icon") else ""
        title = f"<h3>{esc(item['title'])}</h3>" if item.get("title") else ""
        text = plain_paragraphs(item.get("text", ""))
        delay = f' style="transition-delay:{min(index, 6) * 70}ms"'
        cards.append(f'<div class="card" data-r{delay}>{icon}{title}{text}</div>')
    return f'<div class="wrap">{_heading(p)}<div class="grid {_cols(p)} ft">{"".join(cards)}</div></div>'


def r_stats(p: dict, ctx: dict) -> str:
    cells = []
    for index, item in enumerate(p.get("items", [])):
        if not item.get("value"):
            continue
        delay = f' style="transition-delay:{min(index, 6) * 80}ms"'
        cells.append(
            f'<div class="card" data-r{delay}><div class="st-num" data-count="{esc(item["value"])}">{esc(item["value"])}</div>'
            f'<div class="st-lab">{esc(item.get("label", ""))}</div></div>'
        )
    heading = f'<div class="head center" data-r><h2>{esc(p["heading"])}</h2></div>' if p.get("heading") else ""
    columns = "cols-4" if len(cells) > 3 else "cols-3"
    return f'<div class="wrap">{heading}<div class="grid {columns} st">{"".join(cells)}</div></div>'


def r_gallery(p: dict, ctx: dict) -> str:
    shape = p.get("shape", "wide")
    figures = []
    for item in p.get("items", []):
        if not item.get("image"):
            continue
        caption = f"<figcaption>{esc(item['caption'])}</figcaption>" if item.get("caption") else ""
        inner = f'<div class="ratio-{shape}">{img_tag(item["image"], item.get("caption", ""))}</div>{caption}'
        if item.get("url"):
            inner = link_html(item["url"], inner)
        figures.append(f'<figure data-r>{inner}</figure>')
    if not figures:
        figures = [
            f'<figure><div class="gal-empty ratio-{shape}">🖼️<span>Add image links</span></div></figure>'
            for _ in range(int(p.get("columns", "3") or 3))
        ]
    return f'<div class="wrap">{_heading(p)}<div class="grid {_cols(p)} gal">{"".join(figures)}</div></div>'


def r_image(p: dict, ctx: dict) -> str:
    if not p.get("url"):
        return ""
    image = img_tag(p["url"], p.get("caption", ""))
    if p.get("link"):
        image = link_html(p["link"], image)
    caption = f"<figcaption>{esc(p['caption'])}</figcaption>" if p.get("caption") else ""
    width = {"normal": "wrap-narrow", "wide": "wrap", "full": "wrap-full"}.get(p.get("width", "normal"), "wrap-narrow")
    return f'<div class="{width}"><figure class="im" data-r>{image}{caption}</figure></div>'


def r_pricing(p: dict, ctx: dict) -> str:
    cards = []
    for item in p.get("items", []):
        if not item.get("name"):
            continue
        features = "".join(
            f"<li>{esc(line.strip())}</li>" for line in (item.get("features") or "").split("\n") if line.strip()
        )
        cta = link_html(item.get("url") or "#", esc(item.get("cta") or "Choose"),
                        "btn " + ("btn-primary" if item.get("highlight") else "btn-ghost")) if item.get("cta") else ""
        tag = '<span class="tag">Popular</span>' if item.get("highlight") else ""
        cards.append(
            f'<div class="card{" hot" if item.get("highlight") else ""}" data-r>{tag}'
            f'<h3>{esc(item["name"])}</h3>'
            f'<div class="pr-price">{esc(item.get("price", ""))}<span class="pr-per"> {esc(item.get("period", ""))}</span></div>'
            f"<ul>{features}</ul>{cta}</div>"
        )
    return f'<div class="wrap">{_heading(p)}<div class="grid cols-3 pr">{"".join(cards)}</div></div>'


def r_testimonials(p: dict, ctx: dict) -> str:
    cards = []
    for item in p.get("items", []):
        if not item.get("quote"):
            continue
        avatar = (
            img_tag(item["avatar"], item.get("author", ""))
            if item.get("avatar") else f'<div class="ts-av">{initials(item.get("author", ""))}</div>'
        )
        cards.append(
            f'<div class="card" data-r><blockquote>{esc(item["quote"])}</blockquote>'
            f'<div class="ts-who">{avatar}<div><div class="ts-name">{esc(item.get("author", ""))}</div>'
            f'<div class="ts-role">{esc(item.get("role", ""))}</div></div></div></div>'
        )
    return f'<div class="wrap">{_heading(p)}<div class="grid {_cols(p)} ts">{"".join(cards)}</div></div>'


def r_faq(p: dict, ctx: dict) -> str:
    items = "".join(
        f'<details data-r><summary>{esc(item["q"])}</summary>'
        f'<div class="faq-a">{plain_paragraphs(item.get("a", ""))}</div></details>'
        for item in p.get("items", []) if item.get("q")
    )
    return f'<div class="wrap">{_heading(p)}<div class="faq">{items}</div></div>'


def r_timeline(p: dict, ctx: dict) -> str:
    items = "".join(
        f'<div class="tl-item" data-r><div class="tl-date">{esc(item.get("date", ""))}</div>'
        f'<h3>{esc(item.get("title", ""))}</h3>{plain_paragraphs(item.get("text", ""))}</div>'
        for item in p.get("items", []) if item.get("title") or item.get("date")
    )
    return f'<div class="wrap">{_heading(p)}<div class="tl">{items}</div></div>'


def r_team(p: dict, ctx: dict) -> str:
    cards = []
    for item in p.get("items", []):
        if not item.get("name"):
            continue
        photo = img_tag(item["photo"], item["name"]) if item.get("photo") else f'<div class="tm-av">{initials(item["name"])}</div>'
        body = f'{photo}<h3>{esc(item["name"])}</h3><p>{esc(item.get("role", ""))}</p>'
        if item.get("url"):
            body = link_html(item["url"], body)
        cards.append(f'<div class="card" data-r>{body}</div>')
    return f'<div class="wrap">{_heading(p)}<div class="grid {_cols(p, "4")} tm">{"".join(cards)}</div></div>'


def r_cta(p: dict, ctx: dict) -> str:
    heading = f"<h2>{esc(p['heading'])}</h2>" if p.get("heading") else ""
    text = plain_paragraphs(p.get("text", ""))
    return f'<div class="wrap"><div class="cta-box center" data-r>{heading}{text}{_buttons(p.get("buttons", []))}</div></div>'


def r_contact(p: dict, ctx: dict) -> str:
    rows = []
    entries = [("Email", p.get("email", "")), ("Discord", p.get("discord", "")),
               ("Telegram", p.get("telegram", "")), ("Where", p.get("location", ""))]
    for key, value in entries:
        if not value:
            continue
        display = esc(value)
        if key == "Email":
            href = sanitize_url("mailto:" + value) if "@" in value else sanitize_url(value)
            if href:
                display = link_html(href, esc(value))
        copy = f'<button class="ct-copy" type="button" data-copy="{esc(value)}">Copy</button>' if key != "Where" else ""
        rows.append(f'<div class="ct-row" data-r><span class="ct-key">{key}</span><span class="ct-val">{display}</span>{copy}</div>')
    body = f'<div class="ct">{"".join(rows)}</div>' if rows else ""
    heading = _heading(p, "text")
    return f'<div class="wrap"><div class="center">{heading}</div>{body}</div>'


def r_embed(p: dict, ctx: dict) -> str:
    src = embed_src(p.get("url", ""))
    ratio = {"16:9": "r-16-9", "4:3": "r-4-3", "1:1": "r-1-1", "tall": "r-tall"}.get(p.get("ratio", "16:9"), "r-16-9")
    if not src:
        inner = '<div class="emb-empty">Paste a YouTube, Spotify, SoundCloud, Vimeo, Google&nbsp;Maps or Discord widget link.</div>'
        body = f'<div class="emb">{inner}</div>'
    else:
        body = (
            f'<div class="emb {ratio}"><iframe src="{esc(src)}" loading="lazy" title="Embedded content" '
            'referrerpolicy="strict-origin-when-cross-origin" '
            'allow="accelerometer; autoplay; clipboard-write; encrypted-media; picture-in-picture" '
            'allowfullscreen sandbox="allow-scripts allow-same-origin allow-presentation allow-popups allow-popups-to-escape-sandbox"></iframe></div>'
        )
    caption = f'<div class="emb-cap">{esc(p["caption"])}</div>' if p.get("caption") else ""
    return f'<div class="wrap" data-r>{body}{caption}</div>'


def r_marquee(p: dict, ctx: dict) -> str:
    words = [w.strip() for w in re.split(r"[•,]", p.get("text", "")) if w.strip()] or ["hello"]
    run = "".join(f"<span>{esc(word)}</span>" for word in words)
    speed = {"slow": "42s", "normal": "26s", "fast": "14s"}.get(p.get("speed", "normal"), "26s")
    return f'<div class="mq" style="--mq:{speed}"><div class="mq-track">{run}{run}</div></div>'


def r_countdown(p: dict, ctx: dict) -> str:
    target = _epoch(p.get("target", ""))
    heading = f'<div class="head center" data-r><h2>{esc(p["heading"])}</h2></div>' if p.get("heading") else ""
    if not target:
        body = '<div class="cd"><div class="cd-cell"><span class="cd-num">--</span><span class="cd-lab">set a date</span></div></div>'
    else:
        body = f'<div class="cd" data-until="{target}" data-done="{esc(p.get("done_text", ""))}"></div>'
    return f'<div class="wrap">{heading}{body}</div>'


def r_divider(p: dict, ctx: dict) -> str:
    style = p.get("style", "line")
    cls = {"line": "dv", "dots": "dv dv-dots", "glow": "dv dv-glow"}.get(style, "dv")
    return f'<div class="wrap"><div class="{cls}"></div></div>'


def r_spacer(p: dict, ctx: dict) -> str:
    return ""


def r_footer(p: dict, ctx: dict) -> str:
    links = "".join(
        link_html(item.get("url") or "#", esc(item.get("label", "")))
        for item in p.get("links", []) if item.get("label")
    )
    socials = "".join(
        link_html(social_href(item.get("platform", "website"), item.get("url", "")), social_icon(item.get("platform", "website")))
        for item in p.get("socials", []) if item.get("url")
    )
    social_html = f'<div class="soc soc-s">{socials}</div>' if socials else ""
    badge = ""
    if p.get("show_badge", True) and ctx.get("badge_url"):
        badge = link_html(ctx["badge_url"], "⚡ Built with PRD", "ft-badge")
    return (
        f'<footer class="ft-wrap"><div class="wrap ft-inner">'
        f'<span class="ft-txt">{esc(p.get("text", ""))}</span>'
        f'<div class="ft-links">{links}</div>{social_html}{badge}</div></footer>'
    )


RENDERERS = {
    "nav": r_nav, "hero": r_hero, "discord": r_discord, "links": r_links, "socials": r_socials,
    "about": r_about, "features": r_features, "stats": r_stats, "gallery": r_gallery,
    "image": r_image, "pricing": r_pricing, "testimonials": r_testimonials, "faq": r_faq,
    "timeline": r_timeline, "team": r_team, "cta": r_cta, "contact": r_contact, "embed": r_embed,
    "marquee": r_marquee, "countdown": r_countdown, "divider": r_divider, "spacer": r_spacer,
    "footer": r_footer,
}


# ---------------------------------------------------------------------------
# Page shell
# ---------------------------------------------------------------------------

WRAPPERLESS = {"nav", "footer", "marquee"}

EXTRA_CSS = """.wrap,.wrap-narrow{position:relative;z-index:1}
.wrap-full{width:100%;position:relative;z-index:1}
.page-bd{position:fixed;z-index:0}
body{display:flex;flex-direction:column;min-height:100vh}
main{flex:1 0 auto;position:relative;z-index:1}
.skip{position:absolute;left:-9999px;top:0;background:var(--accent);color:var(--on-accent);
  padding:10px 16px;border-radius:0 0 10px 0;z-index:200}
.skip:focus{left:0}"""

PREVIEW_CSS = """.prd-blk{position:relative}
.prd-blk::after{content:'';position:absolute;inset:0;pointer-events:none;z-index:90;
  border:2px solid transparent;border-radius:10px;transition:border-color .15s,box-shadow .15s}
.prd-blk.hov::after{border-color:rgba(124,92,255,.5)}
.prd-blk.sel::after{border-color:#7c5cff;box-shadow:0 0 0 4px rgba(124,92,255,.16)}
.prd-blk.hov>.prd-tag,.prd-blk.sel>.prd-tag{opacity:1;transform:none}
.prd-tag{position:absolute;top:0;left:0;z-index:91;background:#7c5cff;color:#fff;font:600 11px/1 ui-sans-serif,system-ui;
  padding:5px 9px;border-radius:0 0 8px 0;pointer-events:none;opacity:0;transform:translateY(-4px);transition:.15s;
  font-family:ui-sans-serif,system-ui,sans-serif;letter-spacing:.02em}
.prd-drop{position:absolute;left:0;right:0;height:3px;background:#7c5cff;z-index:95;pointer-events:none;
  box-shadow:0 0 12px rgba(124,92,255,.9);border-radius:3px}
.prd-drop::before{content:'';position:absolute;left:8px;top:-4px;width:11px;height:11px;border-radius:50%;background:#7c5cff}
body.prd-dragging *{cursor:grabbing !important}
html{scroll-behavior:auto}"""

PREVIEW_JS = """(function(){
  var parentWin = window.parent, drop = null;
  function blocks(){ return Array.prototype.slice.call(document.querySelectorAll('.prd-blk')); }
  function post(msg){ try{ parentWin.postMessage(msg, '*'); }catch(e){} }
  document.addEventListener('click', function(ev){
    var el = ev.target.closest ? ev.target.closest('.prd-blk') : null;
    var det = ev.target.closest && ev.target.closest('details > summary');
    if(!det){ ev.preventDefault(); }
    ev.stopPropagation();
    if(el){ post({source:'prd-preview', type:'select', id: el.getAttribute('data-prd-id')}); }
  }, true);
  document.addEventListener('mouseover', function(ev){
    var el = ev.target.closest ? ev.target.closest('.prd-blk') : null;
    blocks().forEach(function(b){ b.classList.toggle('hov', b === el); });
  });
  document.addEventListener('mouseleave', function(){ blocks().forEach(function(b){ b.classList.remove('hov'); }); });
  document.addEventListener('mousemove', function(ev){
    post({source:'prd-preview', type:'pointer', x: ev.clientX, y: ev.clientY});
  });
  function showDrop(y){
    if(!drop){ drop = document.createElement('div'); drop.className = 'prd-drop'; document.body.appendChild(drop); }
    var list = blocks(), index = list.length, top = document.body.scrollHeight;
    for(var i = 0; i < list.length; i++){
      var r = list[i].getBoundingClientRect();
      if(y < r.top + r.height / 2){ index = i; top = r.top + window.scrollY; break; }
    }
    if(index === list.length && list.length){
      var last = list[list.length - 1].getBoundingClientRect();
      top = last.bottom + window.scrollY;
    }
    drop.style.top = (top - 1) + 'px';
    return index;
  }
  function hideDrop(){ if(drop){ drop.remove(); drop = null; } }
  window.addEventListener('message', function(ev){
    var d = ev.data || {};
    if(d.source !== 'prd-editor'){ return; }
    if(d.type === 'select'){
      blocks().forEach(function(b){ b.classList.toggle('sel', b.getAttribute('data-prd-id') === d.id); });
      var el = document.querySelector('[data-prd-id="' + (d.id || '') + '"]');
      if(el && d.scroll){ el.scrollIntoView({behavior:'smooth', block:'center'}); }
    } else if(d.type === 'dragmove'){
      document.body.classList.add('prd-dragging');
      post({source:'prd-preview', type:'dropindex', index: showDrop(d.y)});
    } else if(d.type === 'dragend'){
      document.body.classList.remove('prd-dragging'); hideDrop();
    } else if(d.type === 'scrollTo'){
      var target = document.querySelector('[data-prd-id="' + (d.id || '') + '"]');
      if(target){ target.scrollIntoView({behavior:'smooth', block:'center'}); }
    }
  });
  post({source:'prd-preview', type:'ready'});
})();"""


def favicon_data_uri(emoji: str) -> str:
    from urllib.parse import quote

    svg = (
        "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'>"
        f"<text x='50' y='54' font-size='72' text-anchor='middle' dominant-baseline='central'>{html.escape(emoji or '🌐')}</text></svg>"
    )
    return "data:image/svg+xml," + quote(svg)


def font_link(theme: dict) -> str:
    families = []
    for key in {theme.get("font", "inter"), theme.get("heading_font", "inter")}:
        spec = FONTS.get(key, {})
        if spec.get("google"):
            families.append("family=" + spec["google"])
    if not families:
        return ""
    href = "https://fonts.googleapis.com/css2?" + "&".join(sorted(families)) + "&display=swap"
    # Loaded with media="print" and flipped on load: a slow or blocked font CDN
    # then costs nothing -- the page paints immediately in the fallback stack
    # instead of holding up rendering and scripts.
    return (
        '<link rel="preconnect" href="https://fonts.googleapis.com">'
        '<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>'
        f'<link rel="stylesheet" href="{esc(href)}" media="print" onload="this.media=\'all\'">'
        f'<noscript><link rel="stylesheet" href="{esc(href)}"></noscript>'
    )


def render_blocks(doc: dict, ctx: dict) -> str:
    parts: list[str] = []
    for block in doc["blocks"]:
        btype = block["type"]
        renderer = RENDERERS.get(btype)
        if renderer is None:
            continue
        props = block["props"]
        html_out = renderer(props, ctx)
        if btype not in WRAPPERLESS:
            classes = [f"s-{btype}", f"pad-{props.get('pad', 'l')}"]
            surface = props.get("surface", "none")
            if surface and surface != "none":
                classes.append(f"sf-{surface}")
            anchor = f' id="{esc(btype)}"' if btype in ("about", "contact", "pricing", "faq") else ""
            html_out = f'<section class="{" ".join(classes)}"{anchor}>{html_out}</section>'
        elif btype == "marquee":
            pad = props.get("pad", "s")
            surface = props.get("surface", "none")
            cls = f"s-marquee pad-{pad}" + (f" sf-{surface}" if surface != "none" else "")
            html_out = f'<section class="{cls}">{html_out}</section>'
        if ctx.get("preview"):
            label = BLOCKS[btype]["label"]
            html_out = (
                f'<div class="prd-blk" data-prd-id="{esc(block["id"])}" data-prd-type="{esc(btype)}">'
                f'<div class="prd-tag">{esc(BLOCKS[btype]["icon"])} {esc(label)}</div>{html_out}</div>'
            )
        parts.append(html_out)
    return "".join(parts)


def render_site(
    doc: dict,
    *,
    preview: bool = False,
    badge_url: str = "",
    canonical: str = "",
    noindex: bool = False,
) -> str:
    """Render a normalised document into a complete, standalone HTML page."""
    theme = doc["theme"]
    meta = doc["meta"]
    ctx: dict[str, Any] = {"preview": preview, "badge_url": badge_url, "backdrops": set()}

    body_html = render_blocks(doc, ctx)

    used_types = {block["type"] for block in doc["blocks"]}
    css_parts = [stylesheet(theme), EXTRA_CSS]
    page_effect = theme.get("effect", "none")
    if page_effect and page_effect != "none":
        ctx["backdrops"].add(page_effect)
    for kind in sorted(ctx["backdrops"]):
        css_parts.append(backdrop_css(kind))
    for btype in sorted(used_types):
        if btype in BLOCK_CSS:
            css_parts.append(BLOCK_CSS[btype])
    if not theme.get("animations", True):
        css_parts.append("[data-r]{opacity:1 !important;transform:none !important}")
    if preview:
        css_parts.append(PREVIEW_CSS)

    scripts = RUNTIME_JS
    if preview:
        scripts += "\n" + PREVIEW_JS

    page_backdrop = (
        f'<div class="page-bd bd-{page_effect}" aria-hidden="true"></div>'
        if page_effect and page_effect != "none" else ""
    )
    description = esc(meta.get("description", ""))
    og_image = meta.get("og_image", "")
    head_extra = ""
    if canonical:
        head_extra += f'<link rel="canonical" href="{esc(canonical)}">'
        head_extra += f'<meta property="og:url" content="{esc(canonical)}">'
    if og_image:
        head_extra += f'<meta property="og:image" content="{esc(og_image)}">'
        head_extra += '<meta name="twitter:card" content="summary_large_image">'
    if noindex:
        head_extra += '<meta name="robots" content="noindex">'

    return f"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>{esc(meta.get('title', 'Site'))}</title>
<meta name="description" content="{description}">
<meta property="og:title" content="{esc(meta.get('title', 'Site'))}">
<meta property="og:description" content="{description}">
<meta property="og:type" content="website">
<meta name="theme-color" content="{esc(resolved_palette(theme)['bg'])}">
<link rel="icon" href="{esc(favicon_data_uri(meta.get('favicon', '🌐')))}">
{head_extra}
{font_link(theme)}
<style>{"".join(css_parts)}</style>
</head>
<body id="top">
{page_backdrop}
<a class="skip" href="#main">Skip to content</a>
<main id="main">{body_html}</main>
<script>{scripts}</script>
</body>
</html>"""
