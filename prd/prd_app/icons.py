"""PRD icon set.

Originally drawn for the monochrome redesign — every glyph on a 20x20 grid, 16px live area, 1.5px stroke,
square caps and mitre joins. Block icons are schematic wireframes of the
section they insert, not metaphors."""


def f(x, y, w, h, op=1.0):
    o = '' if op == 1.0 else f' opacity="{op}"'
    return f'<rect x="{x}" y="{y}" width="{w}" height="{h}" fill="currentColor" stroke="none"{o}/>'


def c(cx, cy, r, op=1.0):
    o = '' if op == 1.0 else f' opacity="{op}"'
    return f'<circle cx="{cx}" cy="{cy}" r="{r}" fill="currentColor" stroke="none"{o}/>'


# ---------------------------------------------------------------- interface --
UI = {
    "search": '<circle cx="9" cy="9" r="5.25"/><path d="M12.9 12.9 16.8 16.8"/>',
    "plus": '<path d="M10 3.5V16.5M3.5 10H16.5"/>',
    "undo": '<path d="M3.5 8.5h9a4 4 0 0 1 0 8H8"/><path d="M7 4.5 3 8.5l4 4"/>',
    "redo": '<path d="M16.5 8.5h-9a4 4 0 0 0 0 8H12"/><path d="M13 4.5 17 8.5l-4 4"/>',
    "desktop": '<rect x="2.5" y="3.5" width="15" height="10"/><path d="M7 16.5h6M10 13.5v3"/>',
    "tablet": '<rect x="5" y="2.5" width="10" height="15"/><path d="M8.5 15.2h3"/>',
    "phone": '<rect x="6.5" y="2.5" width="7" height="15"/><path d="M8.8 15.3h2.4"/>',
    "layers": '<path d="M10 3 3 6.5 10 10 17 6.5Z"/><path d="M3 10 10 13.5 17 10"/><path d="M3 13.5 10 17 17 13.5"/>',
    "duplicate": '<rect x="2.5" y="6.5" width="11" height="11"/><path d="M6.5 6.5v-4h11v11h-4"/>',
    "trash": '<path d="M3 5.5h14"/><path d="M5.5 5.5v11h9v-11"/><path d="M8 5.5V3h4v2.5"/><path d="M8.5 8.5v5M11.5 8.5v5"/>',
    "drag": (f(6.5, 5, 2, 2) + f(11.5, 5, 2, 2) + f(6.5, 9, 2, 2)
             + f(11.5, 9, 2, 2) + f(6.5, 13, 2, 2) + f(11.5, 13, 2, 2)),
    "chevron-down": '<path d="M5 7.5 10 12.5 15 7.5"/>',
    "chevron-right": '<path d="M7.5 5 12.5 10 7.5 15"/>',
    "check": '<path d="M3.5 10.5 7.5 14.5 16.5 5.5"/>',
    "close": '<path d="M5 5 15 15M15 5 5 15"/>',
    "eye": '<path d="M2 10c3-4.5 13-4.5 16 0-3 4.5-13 4.5-16 0Z"/><circle cx="10" cy="10" r="2.3"/>',
    "external": '<path d="M10.5 3.5h6v6"/><path d="M16.5 3.5 9 11"/><path d="M14 11.5v5h-11v-11h5"/>',
    "arrow-right": '<path d="M3 10h13.5"/><path d="M11.5 5 16.5 10 11.5 15"/>',
    "arrow-up": '<path d="M10 17V3.5"/><path d="M5 8.5 10 3.5 15 8.5"/>',
    "arrow-down": '<path d="M10 3v13.5"/><path d="M5 11.5 10 16.5 15 11.5"/>',
    "sliders": ('<path d="M3 6.5h14M3 13.5h14"/>' + f(5.5, 4.5, 4, 4) + f(11, 11.5, 4, 4)),
    "page": '<path d="M5 2.5h7l3.5 3.5v11.5H5Z"/><path d="M12 2.5V6h3.5"/>',
    "lock": '<rect x="4.5" y="8.5" width="11" height="9"/><path d="M7.5 8.5V6a2.5 2.5 0 0 1 5 0v2.5"/>',
    "upload": '<path d="M10 2.5v10"/><path d="M6 6.5 10 2.5l4 4"/><path d="M3.5 13v4.5h13V13"/>',
    "link": ('<path d="M8.6 11.4a3.2 3.2 0 0 0 4.5 0l2.6-2.6a3.2 3.2 0 0 0-4.5-4.5l-1.3 1.3"/>'
             '<path d="M11.4 8.6a3.2 3.2 0 0 0-4.5 0l-2.6 2.6a3.2 3.2 0 0 0 4.5 4.5l1.3-1.3"/>'),
}

# ------------------------------------------------------------------- blocks --
BLOCKS = {
    "nav": (f(2, 11.5, 16, 1.2, 0.18) + f(2, 14.5, 11, 1.2, 0.18)
            + '<rect x="2.75" y="3.75" width="14.5" height="4.75"/>'
            + f(3.8, 5.2, 3, 2) + f(11.2, 5.5, 2, 1.4, 0.45) + f(14.2, 5.5, 2, 1.4, 0.45)),
    "hero": (f(2.5, 4, 11, 2.8) + f(2.5, 9, 15, 1.2, 0.35) + f(2.5, 11.8, 10.5, 1.2, 0.35)
             + '<rect x="2.5" y="15" width="6" height="3"/>'),
    "text": (f(2.5, 3.5, 7, 2.2) + f(2.5, 8, 15, 1.2, 0.35)
             + f(2.5, 11, 15, 1.2, 0.35) + f(2.5, 14, 9, 1.2, 0.35)),
    "features": ('<rect x="2.75" y="5" width="4.5" height="10"/><rect x="7.75" y="5" width="4.5" height="10"/>'
                 '<rect x="12.75" y="5" width="4.5" height="10"/>'
                 + f(3.2, 6.4, 2, 2) + f(8.8, 6.4, 2, 2) + f(14.4, 6.4, 2, 2)),
    "stats": (f(2.5, 5.5, 4.5, 5) + f(2.5, 12, 4.5, 1.7, 0.4)
              + f(7.75, 5.5, 4.5, 5) + f(7.75, 12, 4.5, 1.7, 0.4)
              + f(13, 5.5, 4.5, 5) + f(13, 12, 4.5, 1.7, 0.4)),
    "gallery": (f(2.5, 2.5, 7, 7) + '<rect x="10.5" y="2.5" width="7" height="7"/>'
                '<rect x="2.5" y="10.5" width="7" height="7"/><rect x="10.5" y="10.5" width="7" height="7"/>'),
    "image": ('<rect x="2.5" y="4" width="15" height="12"/>' + c(6.5, 7.8, 1.3)
              + '<path d="M3.5 14.5 8 9.5l3.2 3.6 2.2-2.2 3.6 3.6"/>'),
    "links": ('<rect x="2.5" y="3.5" width="15" height="3.6"/><rect x="2.5" y="8.2" width="15" height="3.6"/>'
              '<rect x="2.5" y="12.9" width="15" height="3.6"/>'
              + f(4.2, 4.7, 2.2, 1.2) + f(4.2, 9.4, 2.2, 1.2) + f(4.2, 14.1, 2.2, 1.2)),
    "socials": '<circle cx="5" cy="10" r="2.6"/><circle cx="10" cy="10" r="2.6"/><circle cx="15" cy="10" r="2.6"/>',
    "discord": ('<rect x="2.5" y="3" width="15" height="14"/>' + c(6.4, 7.8, 2.1)
                + f(10, 6.9, 6, 1.4, 0.35) + f(10, 9.4, 4, 1.4, 0.35) + f(4.6, 12.7, 10.8, 2.6)),
    "pricing": ('<rect x="2.2" y="7" width="4.6" height="10"/>' + f(7.7, 4.5, 4.6, 12.5)
                + '<rect x="13.2" y="7" width="4.6" height="10"/>'),
    "testimonials": (f(2.8, 4, 3.2, 3.6) + f(7.4, 4, 3.2, 3.6) + f(2.8, 11, 14.4, 1.5, 0.4)
                     + f(2.8, 14, 8.4, 1.5, 0.4) + c(15.4, 15.2, 1.8)),
    "faq": ('<rect x="2.5" y="3.5" width="15" height="5.5"/><path d="M13.4 5.8 14.9 7.3 16.4 5.8"/>'
            '<rect x="2.5" y="11" width="15" height="5.5"/><path d="M13.4 13.3 14.9 14.8 16.4 13.3"/>'),
    "timeline": ('<path d="M5 3.5v13"/>' + c(5, 5, 1.7) + c(5, 10, 1.7) + c(5, 15, 1.7)
                 + f(8.6, 4.2, 8.4, 1.6, 0.35) + f(8.6, 9.2, 6.4, 1.6, 0.35) + f(8.6, 14.2, 7.4, 1.6, 0.35)),
    "team": ('<circle cx="6" cy="7.6" r="2.5"/><circle cx="14" cy="7.6" r="2.5"/>'
             + f(3, 12.4, 6, 1.5, 0.35) + f(11, 12.4, 6, 1.5, 0.35)),
    "cta": '<rect x="2.75" y="4.75" width="14.5" height="10.5"/>' + f(5, 7.2, 10, 1.7) + f(6.6, 11.2, 6.8, 2.8),
    "contact": '<rect x="2.5" y="5" width="15" height="10"/><path d="M2.5 5 10 11 17.5 5"/>',
    "embed": ('<rect x="2.5" y="4" width="15" height="12"/>'
              '<path d="M8.2 7.2 13 10l-4.8 2.8Z" fill="currentColor" stroke="none"/>'),
    "marquee": ('<rect x="2.75" y="6.25" width="14.5" height="7.5"/>'
                + f(4.1, 8.6, 3.4, 2.8) + f(8.5, 8.6, 2.4, 2.8) + f(11.9, 8.6, 4.2, 2.8)),
    "countdown": ('<rect x="2.75" y="6.5" width="4" height="7"/><rect x="8" y="6.5" width="4" height="7"/>'
                  '<rect x="13.25" y="6.5" width="4" height="7"/>'
                  + c(7.4, 8.6, .55) + c(7.4, 11.4, .55) + c(12.65, 8.6, .55) + c(12.65, 11.4, .55)),
    "divider": f(2, 9.4, 16, 1.6) + f(5, 4, 10, 1, 0.2) + f(5, 15, 10, 1, 0.2),
    "spacer": (f(2, 3.5, 16, 1.2, 0.3) + f(2, 15.3, 16, 1.2, 0.3)
               + '<path d="M10 7v6"/><path d="M8 8.6 10 6.6l2 2"/><path d="M8 11.4 10 13.4l2-2"/>'),
    "footer": (f(2, 4, 16, 1.2, 0.18) + f(2, 7, 11, 1.2, 0.18)
               + '<rect x="2.75" y="11.5" width="14.5" height="4.75"/>'
               + f(3.8, 13.2, 3, 2) + f(11.2, 13.5, 2, 1.4, 0.45) + f(14.2, 13.5, 2, 1.4, 0.45)),
}

BLOCK_LABELS = {
    "nav": "Navigation", "hero": "Hero", "text": "Text section", "features": "Feature cards",
    "stats": "Stats", "gallery": "Gallery", "image": "Image", "links": "Link buttons",
    "socials": "Social icons", "discord": "Discord invite", "pricing": "Pricing",
    "testimonials": "Testimonials", "faq": "FAQ", "timeline": "Timeline", "team": "People",
    "cta": "Call to action", "contact": "Contact", "embed": "Embed", "marquee": "Marquee",
    "countdown": "Countdown", "divider": "Divider", "spacer": "Spacer", "footer": "Footer",
}

UI_LABELS = {
    "search": "Search", "plus": "Add", "undo": "Undo", "redo": "Redo", "desktop": "Desktop",
    "tablet": "Tablet", "phone": "Phone", "layers": "Layers", "duplicate": "Duplicate",
    "trash": "Delete", "drag": "Drag handle", "chevron-down": "Expand", "chevron-right": "Next",
    "check": "Confirm", "close": "Close", "eye": "Views", "external": "Open externally",
    "arrow-right": "Continue", "arrow-up": "Move up", "arrow-down": "Move down", "sliders": "Design", "page": "Page", "lock": "Private",
    "upload": "Upload", "link": "Copy link",
}

ALL: dict[str, str] = dict(UI)
ALL.update(BLOCKS)

NAMES = sorted(ALL)


def svg(name: str, size: int = 20, cls: str = "i") -> str:
    """A standalone glyph. Used where a sprite reference would be awkward."""
    body = ALL.get(name)
    if body is None:
        return ""
    return (f'<svg class="{cls}" width="{size}" height="{size}" viewBox="0 0 20 20" fill="none" '
            f'stroke="currentColor" stroke-width="1.5" stroke-linecap="square" '
            f'stroke-linejoin="miter" aria-hidden="true">{body}</svg>')


def use(name: str, size: int = 20, cls: str = "i") -> str:
    """A reference into the page sprite — one definition, many uses."""
    if name not in ALL:
        return ""
    return (f'<svg class="{cls}" width="{size}" height="{size}" aria-hidden="true">'
            f'<use href="#i-{name}"></use></svg>')


def sprite() -> str:
    """Every glyph once, hidden, for the rest of the page to reference."""
    symbols = "".join(
        f'<symbol id="i-{name}" viewBox="0 0 20 20" fill="none" stroke="currentColor" '
        f'stroke-width="1.5" stroke-linecap="square" stroke-linejoin="miter">{body}</symbol>'
        for name, body in ALL.items()
    )
    return f'<svg xmlns="http://www.w3.org/2000/svg" style="display:none" aria-hidden="true">{symbols}</svg>'
