// Seed webapp — tiny client-side helpers.
//
// `seed.fetch(path, opts)` is a thin wrapper over the standard `fetch` API
// that prefixes the path with the current origin and assumes JSON. Worker
// agent's generated code is encouraged to use it instead of calling
// `fetch("/api/...")` directly, so we can later swap in a base URL or
// add auth headers in one place.

(function (global) {
  "use strict";

  async function seedFetch(path, options) {
    const url = new URL(path, window.location.origin).toString();
    const opts = Object.assign(
      { headers: { Accept: "application/json" } },
      options || {},
    );
    // If a body is provided and looks like a plain object, JSON-encode it.
    if (
      opts.body &&
      typeof opts.body === "object" &&
      !(opts.body instanceof FormData) &&
      !(opts.body instanceof Blob) &&
      !(opts.body instanceof ArrayBuffer)
    ) {
      opts.headers["Content-Type"] = "application/json";
      opts.body = JSON.stringify(opts.body);
    }
    const res = await fetch(url, opts);
    const contentType = res.headers.get("Content-Type") || "";
    if (contentType.includes("application/json")) {
      return { ok: res.ok, status: res.status, data: await res.json() };
    }
    return { ok: res.ok, status: res.status, data: await res.text() };
  }

  global.seed = Object.assign(global.seed || {}, { fetch: seedFetch });

  // On load, ping the backend to confirm wiring. Updates the hint in the
  // placeholder card so the user can see the loop is alive.
  document.addEventListener("DOMContentLoaded", function () {
    const status = document.getElementById("status");
    if (!status) return;
    seedFetch("/api/ping")
      .then(function (r) {
        status.textContent = r.ok ? "ready (ping ok)" : "ping failed";
      })
      .catch(function () {
        status.textContent = "ping unreachable";
      });
  });
})(window);
