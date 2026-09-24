# Transparent Agent Flow Upgrade (working plan)

**Status:** Implemented in the working tree; not committed. Backend/Android tests and arm64 build pass. Device runtime, backend health, chat connection, prompt acknowledgement, and user-visible provider failure were verified; Worker completion remains blocked by the selected model's upstream rate limit.

## Contract

Backend owns task IDs, status, history and safe outcomes. Every user-facing server event carries `generationId` and monotonically increasing `eventId`; `task_status` and `task_outcome` also carry `taskId`. A task has exactly one terminal `task_outcome` with `status` (`completed`, `failed`, `cancelled`, `interrupted`), a bounded plain-language summary, and `source` (`middleman` or `backend`). Middleman remains the preferred voice; backend publishes a fallback when a final agent reply is unavailable. Credentials, raw provider errors, dispatch JSON, tool inputs/results and reasoning are never journaled. A client sends `{"type":"resume","generationId":"...","eventId":N}` after WebSocket connection; backend replies with missing events or `history_gap` and current task snapshot when continuity is impossible. Existing clients without a cursor receive current snapshot. Android deduplicates by generation/event ID. Distinguish `role_health` for Middleman/Worker readiness from `/health` Flask liveness. Stop is direct; it does not revert edits.

## Work items

1. Backend tests and bounded private sanitized event journal, atomic subscribe/replay, explicit gaps on overrun/restart, retention policy (256 entries minimum; never silently lose task status/outcome).
2. Centralized task transition and exactly-once outcome, Worker terminal fallback, Middleman errors, role health, timeouts, retries/EOF, model replacement with subscribers preserved. Pi 0.80.3 uses non-retrying `agent_end` rather than assuming `agent_settled`; command RPC success means accepted only.
3. Android cursor-aware reconnection and bounded safe debug; visible status/outcome and health; failure to send keeps draft and reports error. Tests for replay, dedup, gap, stop, no duplicate terminal cards.
4. Settings apply consistency and failure rollback; integration tests.
5. Full test/build and optional connected-device smoke; report unresolved items without commit.

## Implemented behavior and remaining limits

- Backend publishes sequenced agent events, bounded in-memory replay, private metadata-only task journal, `history_gap` with a current task/outcome snapshot, role health, and an authoritative terminal outcome. Middleman final replies are preferred; after timeout/failure the backend publishes a fallback card. Stop still does not undo edits.
- Android displays one task card, agent availability and safe debug, restores task snapshots after gaps, merges streamed Middleman text, retains drafts until backend acceptance, and warns when delivery is unconfirmed. The backend deduplicates matching request IDs within the current orchestrator generation.
- Settings applies Pi selection before recording local preferences. An applied-but-unsaved local preference shows a warning rather than claiming local persistence.
- **Limits:** in-memory prompt deduplication is lost on backend restart, so delivery can remain uncertain across a restart. Full chat text is not persisted to disk (to avoid storing private agent output); reconnect across a process restart produces an explicit history gap and latest task snapshot rather than full chat replay. The generated rootfs archive is ignored by git and must be rebuilt for a new checkout. On-device task completion with a capable model and cancellation of active work are not yet verified. On 2026-09-24, a moto g32 accepted the updated APK, extracted the rebuilt rootfs, served `/health` with Flask up, and accepted a chat prompt. The selected OpenRouter `google/gemma-4-26b-a4b-it:free` model returned HTTP 429 upstream rate-limit responses; the UI showed a safe failure, so no Worker dispatch/edit/completion could be verified. Navigating away and back preserved the displayed prompt/error, but network-loss replay was not tested. Guest user/group confinement and Git rollback are separate work.
