# TechLight KDS — Hybrid + Voice Implementation Plan

This branch implements the KDS work in phases without removing existing functionality.

## Phase 1 (started)
- Audit current kitchen architecture and data flow.
- Preserve the existing Hybrid data model (API + local TechPro connection).
- Add a normalization contract for API/local payload merging.
- Add bilingual Arabic/English voice command parsing for read-only queries first.
- Keep status-changing voice commands gated behind confirmation in later phase.

## Non-negotiable rules
- Do not remove or disable existing features unless technically required to fix a defect.
- Do not invent missing API data.
- Prefer actual API/local payload fields; use explicit fallback mappings only when configured.
- Keep deterministic sorting/timers/history behavior.
- Production stability takes priority over demo behavior.
