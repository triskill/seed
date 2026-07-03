package com.seed.app.data

import com.seed.app.BuildConfig
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Builds and caches the singleton [BackendApi] the
 * app uses to talk to the FastAPI orchestrator.
 *
 * **Phase 6.1** wires this up so the Shell and
 * Settings ViewModels (Phase 6.4, 6.5) can call
 * `ApiModule.get()` and get a ready-to-use
 * [BackendApi] bound to `BuildConfig.BACKEND_DEV_URL`
 * (compile-time, defaults to `http://10.0.2.2:7777/`
 * on the emulator; flip to `127.0.0.1:7777` for a
 * physical device using `adb reverse`).
 *
 * **Design notes:**
 *
 *   - **Manual DI, not Hilt.** The app is small
 *     enough that a hand-rolled factory is clearer
 *     than a Hilt graph, and adding Hilt would
 *     require an annotation processor + the
 *     `hilt-android-gradle-plugin` + the
 *     `androidx.hilt:hilt-navigation-compose`
 *     artifact for Compose ViewModel injection.
 *     Phase 6 keeps the surface small: one
 *     [BackendApi] interface, one [BackendApi.ChatWebSocket],
 *     one [SettingsRepo]. The factory pattern here
 *     is the seam a future Hilt module would wrap.
 *
 *   - **One process-wide instance.** Building a
 *     Retrofit is non-trivial (Moshi + OkHttp +
 *     converters) and the connection pool is
 *     process-scoped anyway. The lazy delegate
 *     ensures the build only happens once, on first
 *     access.
 *
 *   - **Debug-only logging.** [HttpLoggingInterceptor]
 *     dumps full request/response bodies to logcat
 *     — useful for development, but a privacy leak
 *     in production (the API key is in the body of
 *     `PUT /config`). The interceptor is only
 *     installed when [BuildConfig.DEBUG] is true;
 *     release builds get a silent OkHttp stack.
 *
 *   - **`@Volatile` + double-checked locking.** The
 *     lazy delegate is the idiomatic Kotlin pattern
 *     for thread-safe one-time init. The Kotlin
 *     `lazy` defaults to `SYNCHRONIZED` mode, which
 *     is what we want for the rare race between
 *     Chat's WebSocket connect and Shell's first
 *     `shellExec` call.
 *
 *   - **No auth header yet.** Phase 7+ may add a
 *     `RuntimeService`-issued bearer token the
 *     backend validates. For v0.1 the loopback
 *     connection is the auth boundary: nothing on
 *     the device except this app can talk to the
 *     orchestrator.
 */
object ApiModule {

    /**
     * The process-wide [BackendApi] instance. Lazy
     * because building the Retrofit graph is
     * non-trivial and we want first-use cost, not
     * app-startup cost.
     */
    val default: BackendApi by lazy { build(BuildConfig.BACKEND_DEV_URL, debugLogging = BuildConfig.DEBUG) }

    /**
     * Build a [BackendApi] pointed at an arbitrary
     * base URL, with logging forced on. Used by the
     * BackendApiTest contract tests (Phase 6.1) with
     * MockWebServer. Also useful for "test
     * connection" UI affordances in a future task
     * (the Settings screen could let the user point
     * at a different backend on the LAN).
     *
     * The returned instance is a fresh Retrofit —
     * the caller owns it and is free to discard it
     * after the test. The MockWebServer in
     * BackendApiTest calls `server.shutdown()` in
     * `@After` to release the underlying socket;
     * the OkHttp client inside this Retrofit is
     * short-lived for the test's duration and
     * doesn't need an explicit teardown.
     */
    fun forTesting(baseUrl: String): BackendApi = build(baseUrl, debugLogging = true)

    /**
     * Build the Retrofit + OkHttp + Moshi stack.
     *
     * @param baseUrl   The backend's base URL. Must
     *                  end with `/` (Retrofit's
     *                  contract); a missing trailing
     *                  slash throws at the first
     *                  request.
     * @param debugLogging  If true, install the
     *                      [HttpLoggingInterceptor]
     *                      with `BODY` level (full
     *                      request + response
     *                      bodies to logcat). Forced
     *                      on for tests via
     *                      [forTesting]; forced off
     *                      in release builds.
     */
    private fun build(baseUrl: String, debugLogging: Boolean): BackendApi {
        val moshi = Moshi.Builder()
            // KotlinJsonAdapterFactory reads Kotlin
            // metadata (constructors, default
            // values, nullability) so Moshi can
            // build adapters for the DTOs in
            // [BackendApi] without per-class
            // boilerplate. Requires `kotlin-reflect`
            // at runtime (see build.gradle.kts).
            .add(KotlinJsonAdapterFactory())
            .build()

        val clientBuilder = OkHttpClient.Builder()
            // 10s connect, 30s read, 30s write.
            // The backend's shell endpoint has a
            // 60s server-side timeout (Task 1.4)
            // and the orchestrator is local — if
            // a request takes longer than 30s to
            // read, something is wrong on the host.
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)

        if (debugLogging) {
            clientBuilder.addInterceptor(
                HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BODY
                },
            )
        }

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(clientBuilder.build())
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(BackendApi::class.java)
    }
}
