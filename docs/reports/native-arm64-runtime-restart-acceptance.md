# Native ARM64 wedged-runtime restart acceptance

Baseline: source commit `03f38a7`, installed native ARM64 APK on Moto G32.
User authorized interrupting the idle runtime. No app data was cleared.

## Procedure and observations

1. Captured production settings and generated-app file checksums (excluding
   transient Python bytecode). Recorded the backend PRoot and uvicorn PIDs.
2. Sending SIGSTOP to the traced guest uvicorn did not stop HTTP readiness;
   `/health` still returned 200. Resumed that PID before changing the test.
3. Sent SIGSTOP to the backend PRoot tracer. Android process status reported
   `T`; the process remained alive, simulating a wedged runtime.
4. The existing health/error UI displayed **Restart runtime** after failed
   readiness probes. Detection and action visibility therefore passed.
5. Tapped Restart runtime. Readiness did not return within a 140-second
   observation window. The old PRoot was still stopped. **Restart recovery
   failed acceptance.** Do not treat the prior JVM/action tests as proof of
   actual process shutdown.
6. Restored the tracer with SIGCONT, force-stopped the Android package, and
   reopened Seed. `/health` returned 200 with `flask: "up"`; a new backend
   PRoot/uvicorn pair was running. This fallback is not acceptance of the
   Restart button.
7. Production settings and generated-app file checksums matched before/after.
   No rootfs, credentials or app data were deleted. Removed the temporary ADB
   port forward. Browser-local habit data and recreated terminal behavior still
   require user confirmation after recovery.

## Repeat with manual button presses

The user repeated the scenario with the phone unlocked: tapped **Restart
runtime**, saw startup progress followed by an error, then tapped **Retry**
and again saw progress followed by an error. Neither action recovered.
This was a sequence of manual retries, not evidence of automatic restarting.
ADB process inspection showed the original PRoot still stopped alongside a
second PRoot/uvicorn pair. That confirms overlapping runtime generations;
it does not yet establish why the termination call failed. The phone was
restored by resuming the original tracer, force-stopping Seed and reopening;
`/health` returned 200 with Flask up again. No app data was cleared.

## Acceptance after owned QUIT/CONT implementation

The user reconnected the phone and authorized testing. Moto G32 reports
Android **13 / API33**. Both APKs were installed with `adb install -r`; no app
storage was cleared. Results on the new implementation:

- **Isolated ownership/signal smoke: OK (1 test), 1.371 seconds.** A harmless
  app-domain host child establishes receipt/exec identity and handler readiness,
  is paused, and exits through verified QUIT/CONT.
- **Full connected suite: OK (11 tests), 130.224 seconds.**
- **Real stopped-PRoot restart passed twice**, using Restart runtime on the
  error screen (ADB taps), not Retry, force-stop or a manual CONT workaround.
  - First: old tracer `20576` and all six captured generation processes were
    absent after recovery. New tracer `21109` served healthy `/health` after
    approximately **13.2 seconds** from the post-tap readiness probe start.
  - Second: an active terminal was opened and ran
    `echo seed-before-restart-terminal-ok`. Tracer `21109` was paused; Restart
    recovered in approximately **16.7 seconds**. All eight captured old backend
    and terminal processes, including terminal tracer `21284`, were absent.
    New backend tracer `21527` reached health. Returning to Shell created a
    fresh terminal tracer `21640`, and `echo seed-after-restart-terminal-ok`
    printed the expected marker (screenshot inspected).
- Production settings and generated-app file checksums (excluding transient
  Python bytecode) matched before installation and after both restarts.
- Temporary ADB forward/UI dumps were removed and the App tab restored.
- Local runner logs: `/tmp/seed-owned-host-smoke-device.log`,
  `/tmp/seed-owned-full-device.log`, `/tmp/seed-owned-phone-build.log`.

This accepts the observed native ARM64 ownership and paused-runtime recovery
scenario, including basic terminal re-creation. It does not prove atomic PID
signaling, all possible descendant races, crash-at-every-startup-stage behavior,
x86_64 compatibility, or browser-local habit data contents. The generated app's
files were unchanged; user confirmation of browser-local habit data remains
separate. Source/PID-reuse and same-UID limitations are documented in the design.

## Remaining work

Repeat on x86_64 and add broader crash/supervision/UI coverage as required.
The earlier failed acceptance is retained above as history, not current status.
