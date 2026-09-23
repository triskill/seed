# Middleman–Worker Coordination Design

**Status:** Agreed design; implementation not started.

## Goal

Seed users chat with one non-technical voice: Middleman. Middleman clarifies requests, delegates app work to Worker, communicates useful progress, accepts corrections, and automatically summarizes the completed work. Worker does not speak directly to users in the normal chat view.

## Chosen architecture

Keep two long-lived Pi RPC processes, separately prompted and separately sessioned. The backend orchestrator is the authority for task IDs, routing, state, cancellation, and user-visible work status. Pi's session history preserves each agent's context; it is *not* the task-state database. No tmux or MCP server is required for agent-to-agent delivery: the backend already owns both RPC pipes. tmux could be added later as an optional debugging surface; a configurable agent graph is out of scope.

Alternative considered: one agent eliminates handoff costs but loses the conversational/editor role distinction. A configurable network of agents adds routing and recovery complexity without a current use case.

## Task lifecycle and communication

1. Every user message goes to Middleman except an explicit **Stop** control action, which the backend handles directly. Middleman answers questions itself or emits a structured delegation request. The backend validates it, creates a task ID and durable `pending` record, and sends the task specification to Worker through Pi RPC.
2. On accepted Worker work, the backend transitions to `running` and emits a machine-readable chat task-status event. Android renders **Work in progress** from task state, not from a model's prose. Normal chat hides dispatch JSON and raw Worker/tool events; a debug view can retain them.
3. Worker progress reaches the backend as selected observable events (start, tool activity, settled/error) and optional concise, explicitly marked milestones. The backend deduplicates/throttles milestone delivery and routes it, tagged with task ID, to Middleman. Middleman translates meaningful changes into short user-friendly updates; tool calls are not automatically presented as progress.
4. A user reply during work still goes to Middleman. Middleman may answer directly, steer the active Worker via the backend's Pi RPC steering command, or arrange a follow-up; the backend checks task IDs and serializes these decisions. Do not let stale reports from an old task steer a new one. If Middleman is busy, use Pi's supported queueing behavior rather than sending an unqualified `prompt` command during streaming.
5. A Worker final report includes what changed, checks performed, outstanding failures, and task ID. The backend waits for the Worker run to fully settle and checks its actual outcome; a model-written "done" alone does not mark completion. The backend records `completed` or `failed`, clears the indicator, forwards the result to Middleman, and prompts an **automatic final summary** to the user. If Middleman fails, send a plain fallback status rather than leave an endless indicator.
6. **Stop** requests immediately send Pi's `abort` to Worker and mark cancellation pending. Clear the indicator once termination/settlement is observed or a bounded failure path is reached. Cancellation is best effort and does not undo edits already written. Never automatically retry or resume cancelled work.

Task state transitions: `pending → running → completed | failed | cancelled | interrupted`. Persist enough task metadata for restart reconciliation without persisting API keys or raw tool output in task records. If Seed restarts with `pending` or `running` work, mark it `interrupted`, do not resume work automatically, and inform the user that existing edits may remain. A WebSocket reconnect should retrieve the latest task status instead of inferring status from transient chat events.

## Sessions and filesystem

Remove `--no-session` for Middleman and Worker only; retain a short-lived, non-persistent control Pi. Store role-specific session files under Seed's persistent, app-private Pi directory, outside the replaceable rootfs. Save explicit session paths/IDs, and resume by explicit `--session <path>` rather than by `-c` or by selecting the most recent session (which could be a user's terminal Pi session). Never point both roles at one session file. Session persistence does not imply cross-agent memory: only backend-routed progress and reports enter Middleman's context, and only backend-routed tasks/steering enter Worker's context. Handle missing/corrupt session files with a surfaced recovery status, not silent context loss.

The existing shared Pi `auth.json` and `settings.json` remain the single provider source of truth. The role-specific *conversation sessions* do not duplicate credentials. On model changes, agent replacement should preserve the correct role sessions while respecting the existing task-interruption rule.

## Failure handling and verification

Treat invalid dispatch, Worker unavailability, failed RPC acceptance, runtime errors, and Middleman unavailability as distinct task errors. Show a safe, non-secret message and a truthful terminal task state. Bound progress volume and queued steering so repeated user messages cannot silently disappear or overwhelm either agent. Do not claim that Pi sessions make active tasks restart-safe.

Tests with fake Pi RPC processes: delegation and role session selection; Worker progress routing and suppression of raw debug events; steering while busy; automatic final summary only after settled success; model-reported completion followed by actual failure; immediate stop; Worker/Middleman crash; WebSocket reconnect; restart marks active work interrupted; malformed/stale task IDs; session file recovery and credential redaction. Android tests: indicator driven by task-status events, debug visibility, cancellation feedback. On-device: capable model completes a small app edit, accepts a mid-task correction, reports progress and final summary, and responds to Stop; verify restart does not resume interrupted work.

## Sequencing

Implement this coordination design as a separate feature before finalizing the planned guest identities/permissions commit, because role lifecycle, session paths, and Worker reporting affect the processes those permissions must cover. Guest permissions remain best-effort organization under PRoot, not a security sandbox.
