# Seed agentic flow: current state and refactor research

**Baseline:** commit `eba90a0` (Pi 0.80.3 bundled on Android). Research only; recommendations below are not implemented. Backend suite: 188 passing tests; Android debug build and unit tests passed. On-device end-to-end task completion is not verified. The observed OpenRouter `nvidia/nemotron-3-super-120b-a12b:free` failure was HTTP 400: requested output (262,144 tokens) plus prompt exceeded its 262,144-token context. This is a selected-model configuration/provider rejection, not evidence that the agent routing itself failed. Middleman's saved session confirmed two failed prompts; the subsequent build added safe error feedback but it was not confirmed on-device.

## Actual flow

```text
Android ChatViewModel → ChatWebSocket → authenticated /chat
  → backend Orchestrator → Pi RPC Middleman (separate persistent session)
       ├─ ordinary answer → translated text → subscriber queues → chat
       └─ fenced JSON dispatch → task ID + persisted status → Pi RPC Worker
              ├─ progress marker → orchestrator → Middleman → user-friendly update
              ├─ done marker + end event → status + Middleman follow-up → final reply
              └─ failure/stop → status + safe error or Middleman report
```

Evidence: [`backend/seed_backend/chat.py`](../../backend/seed_backend/chat.py) routes `user_message` and `stop_task`; [`orchestrator.py`](../../backend/seed_backend/orchestrator.py) `send_to_middleman`, `_read_middleman_loop`, `_send_dispatch_to_worker`, `_read_worker_loop`, `_report_to_middleman`, and `_cancel_fallback`; [`events.py`](../../backend/seed_backend/events.py) filters Pi RPC output. [`pi_runner.py`](../../backend/seed_backend/pi_runner.py) correlates RPC command acknowledgments separately from streaming events. [`task_store.py`](../../backend/seed_backend/task_store.py) persists the latest task state atomically; on restart active work is marked interrupted, not resumed. Middleman and Worker have separate stable Pi session IDs and directories (`orchestrator.py` `pi_cmd_for_role`); the control process is ephemeral. Chat reconnect receives only the last task-status snapshot, not previous messages (`orchestrator.py` `subscribe`). Android hides Worker lines and dispatch JSON from normal chat but offers a bounded debug view (`android/app/src/main/java/com/seed/app/ui/chat/ChatViewModel.kt`, `ChatScreen.kt`).

**Version-specific Pi constraint:** The packaged Pi 0.80.3 `dist/core/agent-session.js` emits `agent_end` with `willRetry` (`lines 294–343`), but the inspected bundle has no `agent_settled` emission. The installed newer [Pi RPC documentation](https://github.com/earendil-works/pi-mono/blob/main/packages/coding-agent/docs/rpc.md) describes `agent_settled`; Seed therefore supports non-retrying `agent_end` as its bundled-version completion boundary. An RPC `prompt` response only confirms acceptance, not success; subsequent errors appear in the message/event stream (bundled `dist/modes/rpc/rpc-mode.js`, lines 294–315). `follow_up` and `steer` acknowledge queueing, not delivery. A later Pi upgrade requires contract tests against the *packaged* binary, not just host documentation.

## What works, and where the boundaries are

- The backend owns task IDs and `pending → running → completed | failed | cancelled | interrupted` transitions. Stop takes the direct backend path; after an unconfirmed abort, the backend force-stops and replaces the Worker before releasing the slot. Cancellation does not undo file edits.
- Shared Pi provider settings/auth are not duplicated in Android. Role-specific session histories are distinct; neither role automatically shares the other's context. Middleman receives only selected Worker reports, not all Worker tool output.
- Middleman has a read-only *tool-name* allowlist. Worker path restrictions are currently prompt instructions, **not enforced filesystem isolation**. Both roles run under the same Android app UID within PRoot; guest permissions planned later are organizational, not a sandbox.

## Ranked refactor opportunities

1. **Make the outcome visible even if Middleman reporting fails.** Worker completion is hidden in normal Android chat (`ChatViewModel.kt:128–141`), while `_report_to_middleman` only emits a generic error on failure (`orchestrator.py:540–545`). Keep Middleman as the preferred voice, but render an authoritative, plain-language backend fallback completion/failure card and tie it to task ID. Test a successful Worker with a dead Middleman.
2. **Deliver/replay chat events reliably.** Orchestrator drops events for slow subscribers (`orchestrator.py:436–452`); Android's bounded event flow also drops old items (`ChatWebSocket.kt`). Persist sequenced user-facing events or report gaps and replay after reconnect; task status alone cannot reconstruct a conversation. Avoid persisting provider diagnostics, API keys, or raw tool output.
3. **Treat model selection as a single user-visible operation.** `SettingsViewModel.kt:257–270` saves Android preferences before backend apply. If agent replacement fails, the UI can show settings that are not running. Apply first (or restore on failure), then refresh UI from Pi's authoritative selection; test rollback and concurrent shell edits.
4. **Make agent health part of chat status.** `_read_middleman_loop` logs a crash and exits (`orchestrator.py:667–670`), leaving a live WebSocket with no output. Publish a degraded/failed status, stop accepting silent prompts, and allow controlled role restart. Use a bounded timeout for no-response turns.
5. **Enforce Worker capabilities separately from its prompt.** `backend/prompts/worker.md` says to edit only the app, but Worker has general editing/shell tools (`service.py` `_new_orchestrator`). If actual confinement is required, use workspace-scoped tools or an OS-level boundary; tmux and PRoot guest groups do not supply it. This is larger than the planned best-effort guest-permissions commit.

## Recommended sequence

Keep **two agents and backend coordination** for now: this matches the product goal of one non-technical conversational voice and a dedicated builder. First add explicit, replayable task/user-facing events and fallback outcome cards; next fix settings/apply consistency and agent-health reporting; then decide whether to invest in enforced Worker containment. Preserve versioned Pi RPC contract fixtures across upgrades. One agent would simplify handoffs but remove the role separation; a configurable agent network, tmux as coordinator, or MCP would add complexity without addressing the above reliability gaps.

**Evidence still needed:** on-device test with a model that permits a realistic output budget: user prompt → dispatch → Worker edit/check → Middleman progress and final summary; Stop during a tool action; reconnect and restart; compare backend task record and UI. Do not conclude that this flow works solely from fake-Pi tests or from the failing free Nemotron model.
