# CraftMate Backend + Mod (Codex‑ready Monorepo)

## Structure
- /backend – Cloudflare Worker code
- /mod – Minecraft client mod source
- /docs – Architecture, API contracts, phases, environment config

## System Overview
CraftMate AI Companion:
Vision (VLM) → DO Memory → GPT Narrator → TTS → Mod Playback

## Codex Notes
Backend and Mod are separate systems.
DO must store structured data only.
Vision may speak only under lock conditions.
