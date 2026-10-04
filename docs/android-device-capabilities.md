# Android capabilities for generated apps

The Android App tab injects a single Promise-based interface into the configured generated-app origin:

```js
if (!window.seed?.android?.call) {
  // Desktop or unsupported WebView: show an honest unavailable state.
} else {
  const capabilities = await seed.android.call({method: "capabilities.list", params: {}});
}
```

Preserve existing `window.seed` helpers (for example `seed.fetch`). No script import, Python package, per-tool JS wrapper, or arbitrary Android reflection is needed. This is a browser API, not an agent shell tool or Flask endpoint. Both agent system prompts document it.

## Initial methods

| Method | Parameters | Result / limitation |
|---|---|---|
| `capabilities.list` | `{}` | Registered methods and schemas, not a promise that every hardware type exists |
| `camera.capture` | `{}` | `{dataUrl,width,height,mimeType:"image/jpeg",preview:true}`; consent + external camera UI; JPEG preview, not full-resolution/video/lens control |
| `sensor.list` | `{}` | `{sensors:[...]}`; Android sensor types/names depend on hardware |
| `sensor.read` | `{type:<positive integer>,timeoutMs:3000}` | `{type,values,timestampNs,accuracy}`; consent + one measurement; timeoutMs optional integer 100..10000 |
| `sensor.subscribe` | `{type:<positive integer>,rateHz:30}` | `{subscriptionId,type,rateHz}` acknowledgment followed by samples; rateHz optional integer 1..60, maximum four active subscriptions |
| `sensor.unsubscribe` | `{subscriptionId}` | Idempotent stop for a subscription owned by this document; does not require fresh consent |
| `location.current` | `{accuracy:"coarse",timeoutMs:15000}` | `{latitude,longitude,accuracyMeters,timestampMs,precision,ageMs}`; foreground one-shot, Android location permission; optional accuracy coarse/fine and timeoutMs integer 1000..60000 |

Sensor types come from `sensor.list`; the same native reader covers different sensors. Units depend on the Android sensor type; timestamps are monotonic nanoseconds, not dates. Restricted sensors can be denied. For responsive displays, use sensor.subscribe rather than polling sensor.read.
Only continuous/on-change sensors support streaming; one-shot/special-trigger
sensors are rejected. Location/GPS uses location.current, not SensorManager.

Native consent offers **Allow once / Allow / Deny**. Allow once covers one call;
Allow remembers the exact origin and capability until revoked in **Settings >
Device access**. Camera and Location are separate from Sensors; a Sensors grant covers both
sensor.list, sensor.read and sensor.subscribe across sub-app pages. Grants survive host/activity
recreation and are stored separately from provider settings/credentials. Android
permissions remain independent; remembered native consent cannot bypass them.
Denial, dismissal, canceled requests and stale confirmations do not grant access.
Revocation applies to future requests, not data already returned.

Location is foreground-only, not background tracking. The Location grant does
not replace Android runtime coarse/fine permission or turn on disabled location
services. Prefer approximate (coarse) access; a fine request can return coarse
when the user grants only approximate access. Do not repeatedly ask for a precise
upgrade. Coarse requests do not expose fine coordinates even if Android has fine
permission. timestampMs is wall-clock milliseconds; ageMs is monotonic fix age
(<=10000). Indoors/cold-start GPS may time out; show real permission/disabled/
unavailable/timeout states, never fabricate a location. Calls started while the
app is not visible are denied, and active location reads cancel when it stops.
Coordinates are not written to diagnostic logs.

Camera data is not automatically persisted. Display its data URL in an `<img>`; if the user requests saving, use a validated app-owned upload endpoint and storage inside the generated-app workspace. No app keys, Android file paths or control-plane capability are returned. Run reads/capture from a clear user action and handle refusals without repeatedly requesting consent.

```js
async function takePhoto() {
  if (!window.seed?.android?.call) return showStatus("Requires the Seed Android App tab");
  try {
    const photo = await seed.android.call({method: "camera.capture", params: {}});
    document.querySelector("#photo").src = photo.dataUrl;
  } catch (error) {
    showStatus(error.code === "CANCELLED" ? "Cancelled" : "Photo unavailable");
  }
}
```

## Sensor streams

```js
const stream = await seed.android.subscribe({
  method: "sensor.subscribe", params: {type: 1, rateHz: 30}
}, sample => updateDisplay(sample.values));
// Cleanup when the component closes, even if another native request is pending:
await stream.stop();
stream.closed.then(reason => showStreamStopped(reason));
```

The SDK returns an id, an idempotent stop Promise and a closed Promise resolving
the termination reason. Hardware may run slower than the requested rate. Native
listeners remain registered between samples; no repeated permission prompt or
one-shot setup overhead. Allow once covers one subscription lifetime; Allow
remembers the Sensors grant.

End-to-end backpressure permits one unacknowledged sample and the latest pending
sample per stream, not an unbounded native/renderer queue. The SDK acknowledges
after the callback settles (including async callbacks); callbacks must finish
promptly. No acknowledgment within 10 seconds ends the stream and unregisters
its listener. Thrown/rejected callbacks stop their stream. Sample timestamps and
accuracy have the same semantics as sensor.read.

Streams end on explicit stop, page navigation/disposal, backgrounding, revoked
permission/grant or errors. Returning to the app/cached page does not automatically
resubscribe. App code should stop when replacing UI components and use closed to
reset controls; a route change inside a same-document SPA is not a new document.
Stopping works while another normal camera/location call is pending. Subscription
IDs/acks are document-owned; stale/foreign IDs cannot control another stream.

## Protocol and safety

The SDK adds a version and correlation ID to `{method,params}`. The native envelope is `{v:1,id,method,params}`; replies contain `ok` and `result`, or `error:{code,message}`. Parameters are schema-checked, not forwarded to arbitrary classes/methods. Requests are bounded, operations serialized, and waits bounded. Handle `UNAVAILABLE`, `PERMISSION_DENIED`, `CANCELLED`, `TIMEOUT`, `BUSY`, and invalid requests without breaking the page.

The bridge requires WebView `WEB_MESSAGE_LISTENER` and `DOCUMENT_START_SCRIPT` support; there is no insecure `addJavascriptInterface` fallback. Injection and requests are restricted to the exact app origin including port, with native source-origin/current-page/top-frame checks. Backend ports and other origins do not get native privileges. Direct subframe calls are rejected; same-origin frames are not an isolation boundary against their parent under browser rules. Generated apps remain untrusted code; this limited bridge is not a complete Linux/browser sandbox.

Navigation or disposal cancels pending work. A canceled camera request's late activity result must not fulfill a later request. Sensor listeners are removed on success, timeout, denial and cancellation. Consent dialogs (when no remembered grant exists) and system camera operations
remain user-controlled.

## Prompt delivery and verification

The Gradle build bundles only `backend/prompts/{middleman,worker}.md` into APK `agent-prompts/` assets. Runtime startup installs those role instructions atomically per file, without replacing the rootfs, generated app, auth store or browser data. Existing already-running agent processes load the new prompts on their next runtime startup; changing a prompt file does not rewrite a live Pi conversation.

Local tests cover protocol/schema/origin behavior, SDK promises and native lifecycle seams. HTTP/curl proves generated routes, not camera or sensor operation. Real photo capture needs user-operated camera acceptance; do not present a mocked camera test as proof of real capture. Never clear data to test recovery or update this API.
