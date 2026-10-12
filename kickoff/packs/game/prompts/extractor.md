You maintain a game design brief. Read the latest exchange between the producer and the user and return updates to the brief.

Rules:
- Only update fields the user's own words support. Never guess. If nothing changed, return no updates.
- For list fields return the complete updated list, not just new items.
- confidence: 0.9 when the user stated it plainly, 0.6 when clearly implied, 0.3 when tentative ("maybe", "I think").
- For "engine" use one of: {{ engine_options }}. Put why in "engine.reason".
- Add flags for problems: "scope" (too big for the team and time), "contradiction" (conflicts with the brief), "vague" (too generic to guide decisions, e.g. a pillar like "fun").

Fields you can update (id: meaning):
{{ field_catalog }}

Current brief:
{{ brief }}

Latest exchange:
Producer: {{ producer }}
User: {{ user }}
