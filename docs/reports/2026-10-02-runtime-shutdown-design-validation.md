# Runtime shutdown design validation

Read-only source validation; no new runtime implementation or phone experiment.
The phone was disconnected. The purpose is to validate the proposed owned-PID
adapter against actual pinned PRoot and Android process behavior.

## Verdict

**Conditional go for owned PID/start-time capture; no-go for the original generic
TERM-then-tracer-KILL policy.** Implement a smaller PRoot-specific cleanup path
with verified PID ownership and retain Java direct-child exit observation unless
an app-domain experiment disproves it. Avoid a second cached proc-based liveness
state machine without evidence that it is necessary.

## Established findings

### 1. PRoot ignores TERM; QUIT is its cleanup request

The project's `scripts/runtime-target.sh` pins Termux PRoot 5.1.107.92. Its
[packaging source](https://github.com/termux/termux-packages/blob/08b49b3ce00b1e14a3a0365200f30e50f8dfafe1/packages/proot/build.sh#L6-L22)
selects the Termux fork tag resolving to `7266fb3e8516535682f5a9c8f3a7e70f6506eddb`.

The pinned [signal table](https://github.com/termux/proot/blob/7266fb3e8516535682f5a9c8f3a7e70f6506eddb/src/tracee/event.c#L305-L360)
ignores SIGTERM after handlers are installed. SIGQUIT calls
[`kill_all_tracees2`](https://github.com/termux/proot/blob/7266fb3e8516535682f5a9c8f3a7e70f6506eddb/src/tracee/event.c#L150-L162),
which invokes [`kill_all_tracees`](https://github.com/termux/proot/blob/7266fb3e8516535682f5a9c8f3a7e70f6506eddb/src/tracee/tracee.c#L694-L701)
(SIGKILL for every tracked tracee) and lets the tracer event loop process exits.
A stopped tracer needs SIGCONT to execute its pending QUIT handler. Send QUIT
before CONT so cleanup is pending before resuming the tracer.

This terminates guest work; it is not graceful application-level uvicorn
shutdown. That distinction must be reflected in UI/test expectations.

### 2. Java forced-destroy assumptions are incorrect on inspected AOSP

In the inspected Android 13 AOSP/libcore revision, native
[`destroyProcess`](https://android.googlesource.com/platform/libcore/+/7267082c39a25fb5afbc4eeb5752c1b276d29344/ojluni/src/main/native/UNIXProcess_md.c#1055)
sends SIGTERM. UNIXProcess has no force-destroy override and inherited
[`destroyForcibly`](https://android.googlesource.com/platform/libcore/+/7267082c39a25fb5afbc4eeb5752c1b276d29344/ojluni/src/main/java/java/lang/Process.java#244)
simply calls destroy. Therefore the existing five-second escalation need not
send SIGKILL at all. The exact Motorola firmware implementation still requires
confirmation, but this is a concrete source-backed mechanism consistent with
the stopped tracer surviving.

Java [`processExited`](https://android.googlesource.com/platform/libcore/+/7267082c39a25fb5afbc4eeb5752c1b276d29344/ojluni/src/main/java/java/lang/UNIXProcess.java#170)
records the direct child's exit before pipe cleanup. The observed stopped child
remaining alive does **not** establish broken `isAlive`/waitFor semantics.
Previous project comments attributing failure to unreliable liveness/process
groups are hypotheses, not validated causes.

### 3. Tracer KILL is not proof of guest cleanup

`--kill-on-exit` marks the initial guest tracee; its exit causes
[tracked-tracee cleanup](https://github.com/termux/proot/blob/7266fb3e8516535682f5a9c8f3a7e70f6506eddb/src/tracee/tracee.c#L345-L358).
It does not promise cleanup after arbitrary tracer death. The pinned
[ptrace option list](https://github.com/termux/proot/blob/7266fb3e8516535682f5a9c8f3a7e70f6506eddb/src/tracee/event.c#L502-L533)
lacks PTRACE_O_EXITKILL. Tracer SIGKILL bypasses the handler/atexit cleanup, so it
must not be treated as successful full-runtime teardown. New generation launch
must remain blocked when cleanup is uncertain.

### 4. Receipt/exec ownership is technically plausible

The pinned [launch path](https://github.com/termux/proot/blob/7266fb3e8516535682f5a9c8f3a7e70f6506eddb/src/tracee/event.c#L80-L146)
forks the guest while retaining the parent as tracer. A host shell that reports
its own identity and then execs PRoot retains PID/starttime. A private bounded
receipt/acknowledgment therefore establishes ownership without hidden Java PID
reflection or unreliable name-based process discovery.

[`Os.kill`](https://developer.android.com/reference/android/system/Os#kill(int,%20int))
is available since API21. App-domain child stat/status access and signal delivery
remain firmware-dependent and need a smoke test. PID/starttime revalidation
reduces reuse mistakes but is not an atomic pidfd guarantee. Same-UID generated
code is not sandboxed by private receipt files.

## Revised implementation boundary

- Keep existing spawn/stream plumbing and Java exit observation where possible.
- Add bounded private identity receipt/acknowledgment and injectable positive-PID
  signal delivery; validate nonce, parent, UID and starttime before execution.
- Send verified tracer QUIT, then CONT, on a non-UI cleanup worker. Signal delivery
  must precede potentially blocking pipe closure. Await exit with a deadline.
- Timeout/identity uncertainty must preserve the replacement gate; no automatic
  tracer-only KILL fallback advertised as complete cleanup.
- Do not introduce process-group kills, reflection, a native launcher or a second
  liveness monitor unless a targeted experiment establishes the need.

## Reconnect go/no-go experiment

First launch an isolated harmless app-domain receipt/exec child and test identity
inspection plus direct signaling/waitFor. Then test isolated PRoot QUIT+CONT
while stopped, confirming tracer and guest descendants disappear and ports are
released. Only afterward repeat the user's real Restart action, check terminal
re-creation and verify generated app/browser data/settings. If proc inspection or
cleanup cannot be established, reconsider a native launcher or patched EXITKILL
PRoot build instead of broadening unsafe PID discovery.
