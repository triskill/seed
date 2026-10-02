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

## Next task

Investigate shutdown and replacement ordering, including whether synchronous
pipe closure blocks before termination and whether Android's Process kill
reaches Termux PRoot. Establish the actual failure point before implementing
termination changes. A stopped old runtime must be terminated or otherwise
safely released before declaring successful replacement; no history/data wipe
is an acceptable workaround. Repeat this real-device scenario after the fix.
