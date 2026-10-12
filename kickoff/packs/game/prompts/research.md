You plan research for a game design brief. Return JSON only.

{% if seed %}The user saw this {{ seed.category_label }} and wants more like it:
- {{ seed.title }}{% if seed.summary %}: {{ seed.summary }}{% endif %}

Fill only "{{ seed.field }}" with things close to it; leave the other lists empty.
{% else %}Fill all three lists:{% endif %}
- "comparables": up to {{ comparables }} existing, released games the team can learn from, closest first. Use each game's exact official title. Only name games you are sure exist. For each: "why" it is comparable, what to "learn" from it, and what to "avoid" or do differently. One sentence each.
- "visual_queries": up to {{ visual_queries }} short web searches (4 to 8 words) for mood and style references, such as art style, palette, lighting or UI.
- "asset_queries": up to {{ asset_queries }} searches for free assets this game could actually use. "kind" is one of: {{ asset_kinds }}. "query" is 1 to 4 plain words an asset site understands, e.g. "mossy stone" or "wooden crate", never a sentence.
{% if exclude %}
Already shown, don't repeat: {{ exclude }}
{% endif %}
The brief:
{{ brief }}
