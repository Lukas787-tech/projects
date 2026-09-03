"""Starting points.

Each preset is a complete document, so "pick a preset" means "here is a
finished site you can now change", which is much friendlier than a blank page.
"""
from __future__ import annotations

import copy
from typing import Any

from .blocks import new_block


def _doc(title: str, favicon: str, description: str, theme: dict, blocks: list[dict]) -> dict:
    return {
        "version": 1,
        "meta": {"title": title, "description": description, "favicon": favicon, "og_image": ""},
        "theme": theme,
        "blocks": blocks,
    }


def _theme(palette: str, **kwargs: Any) -> dict:
    base = {"palette": palette, "font": "inter", "heading_font": "inter",
            "radius": 18, "width": 1080, "spacing": "normal", "effect": "none", "animations": True}
    base.update(kwargs)
    return base


# ---------------------------------------------------------------------------

DISCORD = _doc(
    "Nebula — Discord Server", "💬",
    "A friendly Discord community for gamers, artists and night owls.",
    _theme("royal", font="outfit", heading_font="outfit", effect="none"),
    [
        new_block("nav", brand="Nebula", links=[{"label": "About", "url": "#about"},
                                                {"label": "Perks", "url": "#perks"},
                                                {"label": "FAQ", "url": "#faq"}],
                  cta_label="Join", cta_url="https://discord.gg/"),
        new_block("hero", eyebrow="[0] members and counting", title="A Discord that actually feels like home",
                  subtitle="Daily events, active voice chats, zero drama. Come say hi — we don't bite.",
                  layout="center", glow="aurora",
                  buttons=[{"label": "Join the server", "url": "https://discord.gg/", "style": "primary"},
                           {"label": "See the rules", "url": "#faq", "style": "ghost"}]),
        new_block("discord", name="Nebula", tagline="Gaming • Art • Music • Late night chaos",
                  invite="https://discord.gg/", cta="Join the server",
                  members="[0]", online="[0]", boosts="[0]", surface="soft"),
        new_block("features", heading="Why people stay", subheading="",
                  items=[{"icon": "🎮", "title": "Game nights", "text": "Minecraft, Valorant, Among Us — every Friday, no skill required."},
                         {"icon": "🎨", "title": "Creative corner", "text": "Share art, music and builds. Feedback is kind here."},
                         {"icon": "🛡️", "title": "Real moderation", "text": "[Who moderates, and how reports are handled.]"},
                         {"icon": "🎁", "title": "Giveaways", "text": "[What you give away, and how often.]"},
                         {"icon": "🎧", "title": "Chill VCs", "text": "Study rooms, music rooms, 3am talks."},
                         {"icon": "🤝", "title": "No gatekeeping", "text": "New members get a welcome, not a quiz."}],
                  columns="3"),
        new_block("stats", items=[{"value": "[0]", "label": "Members"},
                                  {"value": "[0]", "label": "Online now"},
                                  {"value": "[0]", "label": "Channels"},
                                  {"value": "[0]", "label": "Staff"}], surface="soft"),
        new_block("gallery", heading="Inside the server", columns="3", shape="wide", items=[]),
        new_block("faq", heading="Before you join",
                  items=[{"q": "Is there an age requirement?", "a": "13+, per Discord's own terms. Be sensible."},
                         {"q": "Do I have to talk in VC?", "a": "Never. Lurking is a valid lifestyle."},
                         {"q": "What gets you banned?", "a": "Slurs, harassment, spam, NSFW outside the marked channels. That's it."},
                         {"q": "Can I advertise my server?", "a": "Only in #self-promo, and only if you're actually part of the community."}]),
        new_block("cta", heading="See you in there", text="One click and you're in. No forms, no verification maze.",
                  buttons=[{"label": "Join Nebula", "url": "https://discord.gg/", "style": "primary"}], surface="accent"),
        new_block("footer", text="© 2026 Nebula", show_badge=True,
                  socials=[{"platform": "discord", "url": "https://discord.gg/"},
                           {"platform": "youtube", "url": "https://youtube.com/"}]),
    ],
)

BIO = _doc(
    "your name — links", "✨",
    "Everything I make, in one place.",
    _theme("candy", font="dm", heading_font="poppins", effect="dots", radius=22, width=760),
    [
        new_block("hero", layout="avatar", eyebrow="", title="hey, i'm alex",
                  subtitle="designer by day, streamer by night. links below 👇",
                  image="", glow="beam", buttons=[], pad="l"),
        new_block("socials", size="l", pad="s",
                  items=[{"platform": "discord", "url": "https://discord.gg/"},
                         {"platform": "youtube", "url": "https://youtube.com/"},
                         {"platform": "instagram", "url": "https://instagram.com/"},
                         {"platform": "tiktok", "url": "https://tiktok.com/"}]),
        new_block("links", style="soft", pad="m",
                  items=[{"icon": "🎥", "label": "Latest video", "url": "https://youtube.com/", "note": "new"},
                         {"icon": "🛍️", "label": "My shop", "url": "https://example.com/", "note": ""},
                         {"icon": "💬", "label": "Discord server", "url": "https://discord.gg/", "note": "1.2k"},
                         {"icon": "☕", "label": "Buy me a coffee", "url": "https://example.com/", "note": ""}]),
        new_block("about", heading="about me", layout="stack", align="center",
                  body="[Two lines about you. What you make, where you are, what you are into.]\n\nbusiness enquiries below."),
        new_block("contact", heading="say hi", email="you@example.com", discord="yourtag", surface="soft"),
        new_block("footer", text="made with love", show_badge=True),
    ],
)

COMPANY = _doc(
    "Northlight — Studio", "🏢",
    "We design and build digital products for ambitious teams.",
    _theme("paper", font="inter", heading_font="playfair", effect="none", radius=14, width=1140),
    [
        new_block("nav", brand="Northlight", sticky=True,
                  links=[{"label": "Services", "url": "#services"}, {"label": "Work", "url": "#work"},
                         {"label": "Pricing", "url": "#pricing"}, {"label": "Contact", "url": "#contact"}],
                  cta_label="Book a call", cta_url="#contact"),
        new_block("hero", layout="split", eyebrow="Digital studio", title="Software that earns its keep",
                  subtitle="We help small teams ship products people actually use — strategy, design and engineering under one roof.",
                  glow="none", image="",
                  buttons=[{"label": "Start a project", "url": "#contact", "style": "primary"},
                           {"label": "See our work", "url": "#work", "style": "ghost"}]),
        new_block("stats", items=[{"value": "[0]", "label": "Projects shipped"},
                                  {"value": "[0]", "label": "Years doing this"},
                                  {"value": "[0]", "label": "People"}], surface="soft"),
        new_block("features", heading="What we do", subheading="Three things, done properly.",
                  items=[{"icon": "🧭", "title": "Product strategy", "text": "Research, positioning and a roadmap you can defend to a board."},
                         {"icon": "🎨", "title": "Design", "text": "Interfaces that feel obvious. Design systems that survive contact with reality."},
                         {"icon": "⚙️", "title": "Engineering", "text": "Web and mobile builds, sensible architecture, documented handover."}],
                  columns="3"),
        new_block("gallery", heading="Selected work", columns="3", shape="square", items=[]),
        new_block("testimonials", heading="Clients", columns="3",
                  items=[{"quote": "[Paste something a client actually said. A real sentence beats a polished one.]", "author": "[Their name]", "role": "[Their role, their company]", "avatar": ""},
                         {"quote": "[A second one, if you have it. Delete this card if you don\u2019t.]", "author": "[Their name]", "role": "[Their role]", "avatar": ""}],
                  surface="soft"),
        new_block("pricing", heading="Engagements", subheading="Fixed scope or ongoing — your call.",
                  items=[{"name": "Sprint", "price": "[PRICE]", "period": "/ 2 weeks", "features": "Discovery workshop\nClickable prototype\nBuild-ready spec", "cta": "Book a sprint", "url": "#contact", "highlight": False},
                         {"name": "Build", "price": "[PRICE]", "period": "/ month", "features": "Full design + engineering\nWeekly releases\nDedicated Slack channel\nHandover docs", "cta": "Talk to us", "url": "#contact", "highlight": True},
                         {"name": "Retainer", "price": "[PRICE]", "period": "/ month", "features": "Ongoing improvements\nMonitoring & fixes\nQuarterly roadmap", "cta": "Enquire", "url": "#contact", "highlight": False}]),
        new_block("faq", heading="Questions",
                  items=[{"q": "How fast can you start?", "a": "Usually within two weeks. Discovery can start sooner."},
                         {"q": "Do you work with early-stage startups?", "a": "Yes — the Sprint package exists for exactly that."},
                         {"q": "Who owns the code?", "a": "You do, from day one."}]),
        new_block("contact", heading="Let's talk", text="Tell us what you're building. We reply within a day.",
                  email="hello@example.com", location="Berlin · Remote", surface="soft"),
        new_block("footer", text="© 2026 Northlight Studio",
                  links=[{"label": "Privacy", "url": "#"}, {"label": "Imprint", "url": "#"}],
                  socials=[{"platform": "linkedin", "url": "https://linkedin.com/"},
                           {"platform": "github", "url": "https://github.com/"}]),
    ],
)

PORTFOLIO = _doc(
    "Mara Ruiz — Portfolio", "🎨",
    "Selected design and photography work.",
    _theme("midnight", font="space", heading_font="space", effect="noise", radius=10, width=1180),
    [
        new_block("nav", brand="Mara Ruiz", links=[{"label": "Work", "url": "#work"}, {"label": "About", "url": "#about"}],
                  cta_label="Hire me", cta_url="#contact"),
        new_block("hero", layout="center", eyebrow="[What you do] · [Where you are]",
                  title="I make brands look like they mean it",
                  subtitle="[What you do, and who for.]",
                  glow="beam", buttons=[{"label": "View work", "url": "#work", "style": "primary"}]),
        new_block("marquee", text="branding • packaging • art direction • photography • type", speed="normal", surface="soft"),
        new_block("gallery", heading="Work", columns="2", shape="wide", items=[]),
        new_block("about", heading="About", layout="split", align="left",
                  body="[How long you have been doing this, and how you like to work.]\n\nPreviously: **[Studio]**, *[Studio]*, and a lot of late nights."),
        new_block("timeline", heading="Path",
                  items=[{"date": "[year]", "title": "[Job title]", "text": "[Company, city]"},
                         {"date": "[year]", "title": "[Job title]", "text": "[Company, city]"},
                         {"date": "[year]", "title": "[Job title]", "text": "[What changed]"}]),
        new_block("contact", heading="Work with me", email="hola@example.com",
                  text="[When you are free, and what you are looking for.]", surface="soft"),
        new_block("footer", text="© 2026 Mara Ruiz",
                  socials=[{"platform": "instagram", "url": "https://instagram.com/"},
                           {"platform": "email", "url": "hola@example.com"}]),
    ],
)

PRODUCT = _doc(
    "Trailhead — Launch", "🚀",
    "The simplest way to plan your next hike.",
    _theme("forest", font="outfit", heading_font="outfit", effect="grid", radius=20),
    [
        new_block("nav", brand="Trailhead", links=[{"label": "Features", "url": "#features"}, {"label": "Pricing", "url": "#pricing"}],
                  cta_label="Get early access", cta_url="#cta"),
        new_block("hero", layout="split", eyebrow="[Where you are — beta, waitlist, launched]", title="Plan your hike in 30 seconds",
                  subtitle="Routes, weather, water sources and a share link your friends can actually read. No account needed.",
                  glow="aurora", buttons=[{"label": "Get early access", "url": "#cta", "style": "primary"},
                                          {"label": "How it works", "url": "#features", "style": "ghost"}]),
        new_block("features", heading="Everything in one place",
                  items=[{"icon": "🗺️", "title": "Smart routes", "text": "Elevation, surface and bail-out points, drawn automatically."},
                         {"icon": "🌦️", "title": "Real weather", "text": "Hour-by-hour along your route, not just at the trailhead."},
                         {"icon": "🔗", "title": "Share links", "text": "One link, works on any phone, no app install."},
                         {"icon": "📶", "title": "Offline packs", "text": "Download before you lose signal."}],
                  columns="4"),
        new_block("gallery", heading="", columns="3", shape="tall", items=[]),
        new_block("pricing", heading="Simple pricing",
                  items=[{"name": "Free", "price": "[PRICE]", "period": "forever", "features": "Unlimited routes\n3 offline packs\nShare links", "cta": "Start free", "url": "#cta", "highlight": False},
                         {"name": "Pro", "price": "[PRICE]", "period": "/month", "features": "Unlimited offline packs\nLive tracking\nGroup planning\nPriority support", "cta": "Go Pro", "url": "#cta", "highlight": True}]),
        new_block("testimonials", heading="Early testers",
                  items=[{"quote": "[What an early tester told you, in their words.]", "author": "[Their name]", "role": "[How they used it]", "avatar": ""},
                         {"quote": "[A second quote, or delete this card.]", "author": "[Their name]", "role": "[How they used it]", "avatar": ""}],
                  columns="2", surface="soft"),
        new_block("cta", heading="Get early access", text="[How many you let in, and how often.]",
                  buttons=[{"label": "Join the waitlist", "url": "#", "style": "primary"}], surface="accent"),
        new_block("footer", text="© 2026 Trailhead"),
    ],
)

EVENT = _doc(
    "[Event name]", "🎪",
    "One day, four stages, a lot of noise.",
    _theme("ember", font="sora", heading_font="sora", effect="beam", radius=16),
    [
        new_block("nav", brand="Signal Fest", links=[{"label": "Line-up", "url": "#lineup"}, {"label": "Schedule", "url": "#schedule"}],
                  cta_label="Tickets", cta_url="#tickets"),
        new_block("hero", layout="center", eyebrow="[Date] · [City]",
                  title="[Event name]", subtitle="[One line: what it is, and why someone should come.]",
                  glow="beam", buttons=[{"label": "Get tickets", "url": "#tickets", "style": "primary"}]),
        new_block("countdown", heading="Doors open in", target="", done_text="We're open — come in!", surface="soft"),
        new_block("team", heading="Line-up", columns="4",
                  items=[{"name": "[Act]", "role": "Main stage · [time]", "photo": "", "url": ""},
                         {"name": "[Act]", "role": "Main stage · [time]", "photo": "", "url": ""},
                         {"name": "[Act]", "role": "Tent · [time]", "photo": "", "url": ""},
                         {"name": "[Act]", "role": "Garden · [time]", "photo": "", "url": ""}]),
        new_block("timeline", heading="Schedule",
                  items=[{"date": "10:00", "title": "Doors", "text": "Market stalls and coffee."},
                         {"date": "13:00", "title": "First sets", "text": "Garden and tent stages open."},
                         {"date": "20:00", "title": "Main stage", "text": "Headliners until late."},
                         {"date": "02:00", "title": "Close", "text": "[How people get home.]"}]),
        new_block("faq", heading="Practical stuff",
                  items=[{"q": "Is it cashless?", "a": "[Say whether you take cash, card, or both.]"},
                         {"q": "Can I bring a bag?", "a": "[Your bag policy.]"},
                         {"q": "Under 18?", "a": "[Your age policy.]"}]),
        new_block("cta", heading="Tickets", text="[Price, and the date it goes up.]",
                  buttons=[{"label": "Buy tickets", "url": "#", "style": "primary"}], surface="accent"),
        new_block("footer", text="© 2026 Signal Fest"),
    ],
)

CLAN = _doc(
    "IRONCLAD — Community", "🛡️",
    "Competitive clan. Casual people.",
    _theme("cyber", font="space", heading_font="space", effect="grid", radius=8),
    [
        new_block("nav", brand="IRONCLAD", links=[{"label": "Roster", "url": "#roster"}, {"label": "Apply", "url": "#apply"}],
                  cta_label="Discord", cta_url="https://discord.gg/"),
        new_block("hero", layout="center", eyebrow="[Region] · Founded [year]", title="IRONCLAD",
                  subtitle="Weekly scrims, honest VOD reviews, and a roster that does not flame you for missing a shot.",
                  glow="grid", buttons=[{"label": "Apply to join", "url": "#apply", "style": "primary"},
                                        {"label": "Watch us play", "url": "https://twitch.tv/", "style": "ghost"}]),
        new_block("stats", items=[{"value": "[0]", "label": "Active rosters"},
                                  {"value": "[0]", "label": "Tournaments played"},
                                  {"value": "[0]", "label": "Podium finishes"}], surface="soft"),
        new_block("team", heading="Roster", columns="4",
                  items=[{"name": "[Player]", "role": "IGL", "photo": "", "url": ""},
                         {"name": "[Player]", "role": "Entry", "photo": "", "url": ""},
                         {"name": "[Player]", "role": "Support", "photo": "", "url": ""},
                         {"name": "[Player]", "role": "Sniper", "photo": "", "url": ""}]),
        new_block("features", heading="What we offer",
                  items=[{"icon": "🎯", "title": "Coaching", "text": "[Who coaches, how often, and what a session looks like.]"},
                         {"icon": "📅", "title": "Scrims", "text": "[How often, and against whom.]"},
                         {"icon": "🏆", "title": "Tournaments", "text": "[Which cups you enter, and how prize money is split.]"}],
                  columns="3"),
        new_block("about", heading="Apply", layout="stack",
                  body="Requirements: **[age]+**, **[rank]** or above, a microphone, and the ability to lose without going nuclear.\n\n[When applications open, and where.]"),
        new_block("cta", heading="Join the server", text="Applications, scrim pings and memes all live in Discord.",
                  buttons=[{"label": "Open Discord", "url": "https://discord.gg/", "style": "primary"}], surface="accent"),
        new_block("footer", text="© 2026 IRONCLAD",
                  socials=[{"platform": "discord", "url": "https://discord.gg/"},
                           {"platform": "twitch", "url": "https://twitch.tv/"},
                           {"platform": "steam", "url": "https://steamcommunity.com/"}]),
    ],
)

RESUME = _doc(
    "Jonas Weber — CV", "📄",
    "Backend engineer, ten years in.",
    _theme("sand", font="dm", heading_font="dm", effect="none", radius=12, width=880),
    [
        new_block("hero", layout="avatar", title="[Your name]", eyebrow="[Your role] · [Your city]",
                  subtitle="[The one line you would put at the top of your CV.]",
                  glow="none", image="",
                  buttons=[{"label": "Download CV", "url": "#", "style": "primary"},
                           {"label": "Email me", "url": "mailto:you@example.com", "style": "ghost"}], pad="l"),
        new_block("timeline", heading="Experience",
                  items=[{"date": "[year] — now", "title": "[Title] · [Company]", "text": "[What you owned, and one thing it changed.]"},
                         {"date": "[year] — [year]", "title": "[Title] · [Company]", "text": "[What you worked on.]"},
                         {"date": "[year] — [year]", "title": "[Title] · [Company]", "text": "[Where you started.]"}]),
        new_block("features", heading="Skills", columns="4",
                  items=[{"icon": "🐍", "title": "Python", "text": "Django, FastAPI, asyncio"},
                         {"icon": "🐹", "title": "Go", "text": "Services and CLIs"},
                         {"icon": "🗄️", "title": "Postgres", "text": "Modelling, tuning, migrations"},
                         {"icon": "☁️", "title": "Infra", "text": "Terraform, Docker, CI"}]),
        new_block("contact", heading="Contact", email="you@example.com", location="Hamburg, DE", surface="soft"),
        new_block("footer", text="© 2026 Jonas Weber", show_badge=True,
                  socials=[{"platform": "github", "url": "https://github.com/"},
                           {"platform": "linkedin", "url": "https://linkedin.com/"}]),
    ],
)

SOON = _doc(
    "Something is coming", "🌙",
    "A new thing, soon.",
    _theme("midnight", font="sora", heading_font="sora", effect="aurora", radius=20, width=820),
    [
        new_block("hero", layout="center", eyebrow="Coming soon", title="Something is coming",
                  subtitle="We're building it. Leave your email and we'll tell you the moment it's live.",
                  glow="aurora", buttons=[{"label": "Notify me", "url": "mailto:you@example.com", "style": "primary"}], pad="xl"),
        new_block("countdown", heading="", target="", done_text="It's live.", surface="none"),
        new_block("socials", size="m", items=[{"platform": "x", "url": "https://x.com/"},
                                              {"platform": "discord", "url": "https://discord.gg/"}]),
        new_block("footer", text="© 2026", show_badge=True),
    ],
)

BLANK = _doc(
    "My new site", "🌐", "",
    _theme("midnight"),
    [
        new_block("hero", title="My new site", eyebrow="", subtitle="Click anything to edit it. Drag blocks in from the left.",
                  buttons=[{"label": "A button", "url": "#", "style": "primary"}]),
        new_block("footer", text="© 2026"),
    ],
)


PRESETS: list[dict] = [
    {"id": "discord", "name": "Discord server", "icon": "discord", "accent": "#5865f2",
     "tagline": "Advertise a server and grow it", "tags": ["community", "gaming"], "doc": DISCORD},
    {"id": "bio", "name": "Personal bio page", "icon": "links", "accent": "#e11d8f",
     "tagline": "All your links on one pretty page", "tags": ["personal", "creator"], "doc": BIO},
    {"id": "company", "name": "Company website", "icon": "features", "accent": "#1f6feb",
     "tagline": "Services, proof and a way to get hired", "tags": ["business"], "doc": COMPANY},
    {"id": "portfolio", "name": "Portfolio", "icon": "gallery", "accent": "#7c5cff",
     "tagline": "Show the work, get the work", "tags": ["creative"], "doc": PORTFOLIO},
    {"id": "product", "name": "Product launch", "icon": "stats", "accent": "#34d399",
     "tagline": "Features, pricing, waitlist", "tags": ["business", "startup"], "doc": PRODUCT},
    {"id": "event", "name": "Event page", "icon": "timeline", "accent": "#ff6b3d",
     "tagline": "Line-up, schedule, countdown, tickets", "tags": ["event"], "doc": EVENT},
    {"id": "clan", "name": "Gaming clan", "icon": "team", "accent": "#ff2e88",
     "tagline": "Roster, scrims and recruitment", "tags": ["gaming", "community"], "doc": CLAN},
    {"id": "resume", "name": "CV / résumé", "icon": "page", "accent": "#b45309",
     "tagline": "One page that gets you the interview", "tags": ["personal"], "doc": RESUME},
    {"id": "soon", "name": "Coming soon", "icon": "countdown", "accent": "#22d3ee",
     "tagline": "A teaser with a countdown", "tags": ["minimal"], "doc": SOON},
    {"id": "blank", "name": "Blank canvas", "icon": "spacer", "accent": "#98a0b4",
     "tagline": "Start from almost nothing", "tags": ["minimal"], "doc": BLANK},
]

PRESET_MAP: dict[str, dict] = {preset["id"]: preset for preset in PRESETS}


def preset_doc(preset_id: str) -> dict:
    preset = PRESET_MAP.get(preset_id) or PRESET_MAP["blank"]
    return copy.deepcopy(preset["doc"])


def preset_index() -> list[dict]:
    """Preset metadata for the gallery / picker (without the full documents)."""
    return [
        {k: v for k, v in preset.items() if k != "doc"} | {"blocks": len(preset["doc"]["blocks"])}
        for preset in PRESETS
    ]
