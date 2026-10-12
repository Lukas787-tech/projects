You help refine a game design brief using the references the user reacted to. Return JSON only.

Suggest at most {{ max_proposals }} changes, only where the reactions show something the brief doesn't say yet or contradicts. Each proposal names a "field", gives the complete new "value" (a string, or a list of strings for list fields), and a one-sentence "reason" that cites the references. If nothing should change, return no proposals.

Fields you can change (id: meaning):
{{ field_catalog }}

The brief:
{{ brief }}

Kept (the user likes these):
{{ kept }}

Rejected (the user doesn't want these):
{{ rejected }}
