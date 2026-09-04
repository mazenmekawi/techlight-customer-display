# Voice + Hybrid integration notes

Initial implementation target:

1. Read-only bilingual voice intents:
   - Arabic: delayed orders, ready orders, invoice lookup/status.
   - English: delayed orders, ready orders, invoice lookup/status.
2. Responses must be generated from live normalized KDS state only.
3. No fake answers when an invoice/order is missing.
4. Mutating voice intents (Preparing/Ready/Completed) remain disabled until explicit confirmation UX is added.
5. Hybrid merge priority will be field-specific: API remains authoritative for server-backed order lifecycle/status; local TechPro data enriches groups/items/modifiers where available.
