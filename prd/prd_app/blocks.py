"""Block registry.

One definition per block feeds three consumers:

* :mod:`prd_app.document` validates and coerces incoming JSON against it,
* the editor fetches it as JSON and generates the whole inspector UI from it,
* :mod:`prd_app.render` turns the coerced props into HTML.

Adding a new element to the builder therefore means adding an entry here plus a
renderer function -- no editor changes required.
"""
from __future__ import annotations

from typing import Any

# ---------------------------------------------------------------------------
# Field helpers
# ---------------------------------------------------------------------------

TEXT_MAX = 240
LINE_MAX = 600
BODY_MAX = 4000
URL_MAX = 800
LIST_MAX = 24


def field(
    key: str,
    ftype: str,
    label: str,
    default: Any = None,
    **extra: Any,
) -> dict:
    spec: dict[str, Any] = {"key": key, "type": ftype, "label": label}
    if default is None:
        default = {
            "text": "",
            "textarea": "",
            "richtext": "",
            "url": "",
            "image": "",
            "color": "",
            "icon": "",
            "select": "",
            "number": 0,
            "toggle": False,
            "list": [],
            "date": "",
        }.get(ftype, "")
    spec["default"] = default
    spec.update({k: v for k, v in extra.items() if v is not None})
    return spec


def select(key: str, label: str, options: list[tuple[str, str]], default: str, **extra: Any) -> dict:
    return field(
        key,
        "select",
        label,
        default,
        options=[{"value": v, "label": l} for v, l in options],
        **extra,
    )


ALIGN_OPTIONS = [("left", "Left"), ("center", "Center")]
WIDTH_OPTIONS = [("normal", "Normal"), ("wide", "Wide"), ("full", "Full bleed")]
PAD_OPTIONS = [("s", "Compact"), ("m", "Normal"), ("l", "Roomy"), ("xl", "Huge")]
SURFACE_OPTIONS = [
    ("none", "Transparent"),
    ("soft", "Soft panel"),
    ("solid", "Solid panel"),
    ("accent", "Accent wash"),
]
COLUMN_OPTIONS = [("2", "2 columns"), ("3", "3 columns"), ("4", "4 columns")]

SOCIAL_PLATFORMS = [
    ("discord", "Discord"),
    ("github", "GitHub"),
    ("x", "X / Twitter"),
    ("youtube", "YouTube"),
    ("instagram", "Instagram"),
    ("tiktok", "TikTok"),
    ("twitch", "Twitch"),
    ("telegram", "Telegram"),
    ("reddit", "Reddit"),
    ("spotify", "Spotify"),
    ("steam", "Steam"),
    ("linkedin", "LinkedIn"),
    ("email", "Email"),
    ("website", "Website"),
]


def _section_fields(pad: str = "l", surface: str = "none") -> list[dict]:
    """Layout controls shared by most sections."""
    return [
        select("pad", "Spacing", PAD_OPTIONS, pad, group="Layout"),
        select("surface", "Background", SURFACE_OPTIONS, surface, group="Layout"),
    ]


# ---------------------------------------------------------------------------
# Reusable sub-item field sets (used by `list` fields)
# ---------------------------------------------------------------------------

BUTTON_FIELDS = [
    field("label", "text", "Label", "Get started"),
    field("url", "url", "Link", "#"),
    select("style", "Style", [("primary", "Primary"), ("ghost", "Outline"), ("soft", "Soft")], "primary"),
]

FEATURE_FIELDS = [
    field("icon", "icon", "Icon", "✨"),
    field("title", "text", "Title", "Feature"),
    field("text", "textarea", "Description", "Say what makes it good."),
]

LINK_FIELDS = [
    field("icon", "icon", "Icon", "🔗"),
    field("label", "text", "Label", "My link"),
    field("url", "url", "Link", "https://"),
    field("note", "text", "Small note", ""),
]

SOCIAL_FIELDS = [
    select("platform", "Platform", SOCIAL_PLATFORMS, "discord"),
    field("url", "url", "Link", "https://"),
]

STAT_FIELDS = [
    field("value", "text", "Value", "1,200+"),
    field("label", "text", "Label", "Members"),
]

GALLERY_FIELDS = [
    field("image", "image", "Image URL", ""),
    field("caption", "text", "Caption", ""),
    field("url", "url", "Link (optional)", ""),
]

PRICING_FIELDS = [
    field("name", "text", "Plan name", "Starter"),
    field("price", "text", "Price", "$0"),
    field("period", "text", "Period", "/month"),
    field("features", "textarea", "Features (one per line)", "Everything you need\nNo credit card"),
    field("cta", "text", "Button label", "Choose plan"),
    field("url", "url", "Button link", "#"),
    field("highlight", "toggle", "Highlight this plan", False),
]

TESTIMONIAL_FIELDS = [
    field("quote", "textarea", "Quote", "This is exactly what I needed."),
    field("author", "text", "Name", "Alex"),
    field("role", "text", "Role", "Community member"),
    field("avatar", "image", "Avatar URL", ""),
]

FAQ_FIELDS = [
    field("q", "text", "Question", "How do I join?"),
    field("a", "textarea", "Answer", "Click the invite button at the top."),
]

TIMELINE_FIELDS = [
    field("date", "text", "Date", "2024"),
    field("title", "text", "Title", "Something happened"),
    field("text", "textarea", "Description", ""),
]

TEAM_FIELDS = [
    field("name", "text", "Name", "Jamie"),
    field("role", "text", "Role", "Founder"),
    field("photo", "image", "Photo URL", ""),
    field("url", "url", "Link", ""),
]

NAV_LINK_FIELDS = [
    field("label", "text", "Label", "Home"),
    field("url", "url", "Link", "#"),
]


# ---------------------------------------------------------------------------
# Block definitions
# ---------------------------------------------------------------------------

def block(
    btype: str,
    label: str,
    icon: str,
    category: str,
    description: str,
    fields: list[dict],
    *,
    unique: bool = False,
    keywords: str = "",
) -> dict:
    return {
        "type": btype,
        "label": label,
        "icon": icon,
        "category": category,
        "description": description,
        "fields": fields,
        "unique": unique,
        "keywords": keywords,
    }


BLOCK_LIST: list[dict] = [
    block(
        "nav", "Navigation", "nav", "Structure",
        "Sticky top bar with your name and links.",
        [
            field("brand", "text", "Brand / name", "My Site"),
            field("logo", "image", "Logo URL", ""),
            field("links", "list", "Links", [
                {"label": "About", "url": "#about"},
                {"label": "Contact", "url": "#contact"},
            ], fields=NAV_LINK_FIELDS, item_label="Link", max=8),
            field("cta_label", "text", "Button label", ""),
            field("cta_url", "url", "Button link", ""),
            field("sticky", "toggle", "Stick to top while scrolling", True),
        ],
        unique=True, keywords="menu header bar",
    ),
    block(
        "hero", "Hero", "hero", "Structure",
        "The big opening statement people read first.",
        [
            field("eyebrow", "text", "Eyebrow", "Welcome"),
            field("title", "text", "Headline", "Your headline goes here"),
            field("subtitle", "textarea", "Subheadline", "One or two friendly sentences about what this is."),
            field("image", "image", "Image / avatar URL", ""),
            select("layout", "Layout", [
                ("center", "Centered"), ("split", "Text left, image right"), ("avatar", "Round avatar on top"),
            ], "center"),
            field("buttons", "list", "Buttons", [
                {"label": "Get started", "url": "#", "style": "primary"},
            ], fields=BUTTON_FIELDS, item_label="Button", max=4),
            select("glow", "Background glow", [
                ("aurora", "Aurora"), ("beam", "Beam"), ("grid", "Grid"), ("none", "None"),
            ], "aurora"),
            *_section_fields(pad="xl"),
        ],
        keywords="header banner intro",
    ),
    block(
        "discord", "Discord invite", "discord", "Community",
        "Server card with member counts and a big join button.",
        [
            field("name", "text", "Server name", "My Server"),
            field("tagline", "textarea", "Short pitch", "Chill community, active voice chats, daily events."),
            field("icon", "image", "Server icon URL", ""),
            field("banner", "image", "Banner URL", ""),
            field("invite", "url", "Invite link", "https://discord.gg/"),
            field("cta", "text", "Button label", "Join the server"),
            field("members", "text", "Members", "1,240"),
            field("online", "text", "Online", "312"),
            field("boosts", "text", "Boosts", "14"),
            field("widget_id", "text", "Widget server ID (optional)", "", help="Enable the server widget in Discord to show a live member list."),
            *_section_fields(pad="l", surface="soft"),
        ],
        keywords="server invite join community gaming",
    ),
    block(
        "links", "Link buttons", "links", "Content",
        "Stacked buttons — perfect for a bio page.",
        [
            field("heading", "text", "Heading", ""),
            field("items", "list", "Links", [
                {"icon": "💬", "label": "Discord", "url": "https://discord.gg/", "note": ""},
                {"icon": "🎥", "label": "YouTube", "url": "https://youtube.com/", "note": ""},
            ], fields=LINK_FIELDS, item_label="Link", max=LIST_MAX),
            select("style", "Button style", [("soft", "Soft"), ("outline", "Outline"), ("solid", "Solid")], "soft"),
            *_section_fields(pad="m"),
        ],
        keywords="linktree bio buttons",
    ),
    block(
        "socials", "Social icons", "socials", "Content",
        "A neat row of platform icons.",
        [
            field("items", "list", "Profiles", [
                {"platform": "discord", "url": "https://discord.gg/"},
                {"platform": "github", "url": "https://github.com/"},
            ], fields=SOCIAL_FIELDS, item_label="Profile", max=14),
            select("size", "Icon size", [("s", "Small"), ("m", "Medium"), ("l", "Large")], "m"),
            *_section_fields(pad="s"),
        ],
        keywords="icons social media",
    ),
    block(
        "about", "Text section", "text", "Content",
        "A heading plus a paragraph. Supports **bold**, *italic* and [links](url).",
        [
            field("heading", "text", "Heading", "About"),
            field("body", "richtext", "Body", "Write anything here. **Bold**, *italic* and [links](https://example.com) work.", rows=8),
            field("image", "image", "Image URL", ""),
            select("layout", "Layout", [("stack", "Stacked"), ("split", "Text + image")], "stack"),
            select("align", "Align", ALIGN_OPTIONS, "left"),
            *_section_fields(),
        ],
        keywords="paragraph about story text",
    ),
    block(
        "features", "Feature cards", "features", "Content",
        "Grid of cards for perks, services or rules.",
        [
            field("heading", "text", "Heading", "What you get"),
            field("subheading", "textarea", "Subheading", ""),
            field("items", "list", "Cards", [
                {"icon": "⚡", "title": "Fast", "text": "Loads instantly, everywhere."},
                {"icon": "🎨", "title": "Yours", "text": "Every colour and word is editable."},
                {"icon": "🛡️", "title": "Safe", "text": "No trackers, no nonsense."},
            ], fields=FEATURE_FIELDS, item_label="Card", max=LIST_MAX),
            select("columns", "Columns", COLUMN_OPTIONS, "3"),
            *_section_fields(),
        ],
        keywords="cards grid perks services rules",
    ),
    block(
        "stats", "Stats", "stats", "Content",
        "Big numbers that count up as people scroll.",
        [
            field("heading", "text", "Heading", ""),
            field("items", "list", "Stats", [
                {"value": "1,200+", "label": "Members"},
                {"value": "24/7", "label": "Active mods"},
                {"value": "99%", "label": "Vibes"},
            ], fields=STAT_FIELDS, item_label="Stat", max=8),
            *_section_fields(pad="m", surface="soft"),
        ],
        keywords="numbers counter metrics",
    ),
    block(
        "gallery", "Gallery", "gallery", "Media",
        "Image grid with hover zoom.",
        [
            field("heading", "text", "Heading", "Gallery"),
            field("items", "list", "Images", [], fields=GALLERY_FIELDS, item_label="Image", max=LIST_MAX),
            select("columns", "Columns", COLUMN_OPTIONS, "3"),
            select("shape", "Shape", [("wide", "Landscape"), ("square", "Square"), ("tall", "Portrait")], "wide"),
            *_section_fields(),
        ],
        keywords="images photos screenshots grid",
    ),
    block(
        "image", "Single image", "image", "Media",
        "One picture with an optional caption.",
        [
            field("url", "image", "Image URL", ""),
            field("caption", "text", "Caption", ""),
            field("link", "url", "Link (optional)", ""),
            select("width", "Width", WIDTH_OPTIONS, "normal"),
            *_section_fields(pad="m"),
        ],
        keywords="photo picture banner",
    ),
    block(
        "pricing", "Pricing", "pricing", "Business",
        "Side-by-side plans with a highlighted favourite.",
        [
            field("heading", "text", "Heading", "Pricing"),
            field("subheading", "textarea", "Subheading", ""),
            field("items", "list", "Plans", [
                {"name": "Free", "price": "$0", "period": "/forever", "features": "1 site\nCommunity support", "cta": "Start free", "url": "#", "highlight": False},
                {"name": "Pro", "price": "$9", "period": "/month", "features": "Unlimited sites\nCustom domain\nPriority support", "cta": "Go Pro", "url": "#", "highlight": True},
            ], fields=PRICING_FIELDS, item_label="Plan", max=4),
            *_section_fields(),
        ],
        keywords="plans price money tiers",
    ),
    block(
        "testimonials", "Testimonials", "testimonials", "Business",
        "Quotes from people who like you.",
        [
            field("heading", "text", "Heading", "What people say"),
            field("items", "list", "Quotes", [
                {"quote": "Genuinely the nicest community I'm in.", "author": "Sam", "role": "Member since 2023", "avatar": ""},
            ], fields=TESTIMONIAL_FIELDS, item_label="Quote", max=LIST_MAX),
            select("columns", "Columns", COLUMN_OPTIONS, "3"),
            *_section_fields(surface="soft"),
        ],
        keywords="reviews quotes praise",
    ),
    block(
        "faq", "FAQ", "faq", "Content",
        "Expandable questions and answers.",
        [
            field("heading", "text", "Heading", "FAQ"),
            field("items", "list", "Questions", [
                {"q": "Is it free?", "a": "Yes. Building and publishing costs nothing."},
            ], fields=FAQ_FIELDS, item_label="Question", max=LIST_MAX),
            *_section_fields(),
        ],
        keywords="questions answers help accordion",
    ),
    block(
        "timeline", "Timeline", "timeline", "Content",
        "A vertical story — history, roadmap or schedule.",
        [
            field("heading", "text", "Heading", "Timeline"),
            field("items", "list", "Entries", [
                {"date": "2023", "title": "Started", "text": "Two friends and a voice chat."},
                {"date": "Today", "title": "Growing", "text": "Over a thousand members."},
            ], fields=TIMELINE_FIELDS, item_label="Entry", max=LIST_MAX),
            *_section_fields(),
        ],
        keywords="history roadmap schedule agenda",
    ),
    block(
        "team", "People", "team", "Business",
        "Team, staff or speaker cards.",
        [
            field("heading", "text", "Heading", "The team"),
            field("items", "list", "People", [
                {"name": "Jamie", "role": "Founder", "photo": "", "url": ""},
            ], fields=TEAM_FIELDS, item_label="Person", max=LIST_MAX),
            select("columns", "Columns", COLUMN_OPTIONS, "4"),
            *_section_fields(),
        ],
        keywords="staff crew members speakers",
    ),
    block(
        "cta", "Call to action", "cta", "Structure",
        "A loud panel with one clear button.",
        [
            field("heading", "text", "Heading", "Ready when you are"),
            field("text", "textarea", "Text", "Join us — it takes ten seconds."),
            field("buttons", "list", "Buttons", [
                {"label": "Join now", "url": "#", "style": "primary"},
            ], fields=BUTTON_FIELDS, item_label="Button", max=3),
            *_section_fields(pad="l", surface="accent"),
        ],
        keywords="banner action join signup",
    ),
    block(
        "contact", "Contact", "contact", "Structure",
        "Ways to reach you, with copy-to-clipboard.",
        [
            field("heading", "text", "Heading", "Get in touch"),
            field("text", "textarea", "Text", ""),
            field("email", "text", "Email", ""),
            field("discord", "text", "Discord tag", ""),
            field("telegram", "text", "Telegram", ""),
            field("location", "text", "Location", ""),
            *_section_fields(surface="soft"),
        ],
        keywords="email reach out message",
    ),
    block(
        "embed", "Embed", "embed", "Media",
        "YouTube, Spotify, SoundCloud, Google Maps or a Discord widget.",
        [
            field("url", "url", "Paste a link", "", help="YouTube, Spotify, SoundCloud, Google Maps or discord.com/widget links."),
            field("caption", "text", "Caption", ""),
            select("ratio", "Shape", [("16:9", "Widescreen"), ("4:3", "Classic"), ("1:1", "Square"), ("tall", "Tall")], "16:9"),
            *_section_fields(pad="m"),
        ],
        keywords="video youtube spotify music map iframe",
    ),
    block(
        "marquee", "Scrolling text", "marquee", "Media",
        "An endless ticker of words or emoji.",
        [
            field("text", "text", "Text (separate with • or ,)", "welcome • have fun • be nice"),
            select("speed", "Speed", [("slow", "Slow"), ("normal", "Normal"), ("fast", "Fast")], "normal"),
            *_section_fields(pad="s", surface="soft"),
        ],
        keywords="ticker scroll banner",
    ),
    block(
        "countdown", "Countdown", "countdown", "Media",
        "Counts down to a date and time.",
        [
            field("heading", "text", "Heading", "Launching in"),
            field("target", "date", "Target date & time", ""),
            field("done_text", "text", "Text when it hits zero", "We're live!"),
            *_section_fields(pad="l", surface="soft"),
        ],
        keywords="timer launch event date",
    ),
    block(
        "divider", "Divider", "divider", "Structure",
        "A quiet line between sections.",
        [
            select("style", "Style", [("line", "Line"), ("dots", "Dots"), ("glow", "Glow")], "line"),
            *_section_fields(pad="s"),
        ],
        keywords="separator hr line",
    ),
    block(
        "spacer", "Spacer", "spacer", "Structure",
        "Empty breathing room.",
        [select("pad", "Height", PAD_OPTIONS, "m")],
        keywords="gap space margin",
    ),
    block(
        "footer", "Footer", "footer", "Structure",
        "Small print, links and socials at the bottom.",
        [
            field("text", "text", "Text", "© 2026 My Site"),
            field("links", "list", "Links", [], fields=NAV_LINK_FIELDS, item_label="Link", max=8),
            field("socials", "list", "Socials", [], fields=SOCIAL_FIELDS, item_label="Profile", max=10),
            field("show_badge", "toggle", "Show 'Built with PRD' badge", True),
        ],
        unique=True, keywords="bottom copyright",
    ),
]

BLOCKS: dict[str, dict] = {b["type"]: b for b in BLOCK_LIST}
BLOCK_TYPES: set[str] = set(BLOCKS)

CATEGORIES = ["Structure", "Content", "Media", "Community", "Business"]


def field_map(btype: str) -> dict[str, dict]:
    return {f["key"]: f for f in BLOCKS[btype]["fields"]}


def default_props(btype: str) -> dict:
    """Fresh props for a newly dropped block, deep-copied so callers can edit."""
    import copy

    return {f["key"]: copy.deepcopy(f["default"]) for f in BLOCKS[btype]["fields"]}


def new_block(btype: str, **overrides: Any) -> dict:
    props = default_props(btype)
    props.update(overrides)
    return {"type": btype, "props": props}
