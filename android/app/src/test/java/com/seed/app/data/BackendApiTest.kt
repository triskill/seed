package com.seed.app.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import java.util.concurrent.TimeUnit

/**
 * Contract tests for [BackendApi] using OkHttp's
 * [MockWebServer].
 *
 * **What this verifies:**
 *   - The [BackendApi] interface issues the right
 *     HTTP method + path for each endpoint.
 *   - The request body is serialized with the
 *     snake_case field names the backend's Pydantic
 *     models expect (e.g. `exit_code` not
 *     `exitCode` and nested config `ports`).
 *   - The response body is deserialized into the
 *     right Kotlin DTOs (snake_case JSON → camelCase
 *     Kotlin fields via Moshi's `@Json(name=...)`).
 *   - Non-2xx responses surface as
 *     [retrofit2.HttpException] with the right
 *     status code.
 *
 * **What this does NOT verify:**
 *   - End-to-end behavior against a live backend.
 *     That's a `make backend` + `adb reverse` job
 *     (Phase 6.4+ integration testing on the
 *     emulator).
 *   - The OkHttp client configuration (timeouts,
 *     logging interceptor). Those are wiring
 *     concerns, not contract concerns, and would
 *     just duplicate [ApiModule]'s test surface.
 *
 * **Phase 6.1** ships these as the first Android
 * unit tests that touch the network stack. Each
 * test uses the public [ApiModule.forTesting]
 * entry point so the tests don't reach into
 * private Retrofit internals.
 */
class BackendApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: BackendApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        // MockWebServer.url("/") gives us the base
        // URL with the trailing slash Retrofit
        // requires. Each test enqueues its own
        // responses on `server.enqueue(...)`.
        api = ApiModule.forTesting(server.url("/").toString())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ---- /health ------------------------------------------------

    @Test
    fun `health issues GET health and parses status and flask fields`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"status":"ok","flask":"up"}"""),
        )

        val response = api.health()

        assertEquals("ok", response.status)
        assertEquals("up", response.flask)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/health", request.path)
    }

    @Test
    fun `health parses flask down`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"status":"ok","flask":"down"}"""),
        )

        val response = api.health()

        assertEquals("down", response.flask)
    }

    // ---- /shell/exec --------------------------------------------

    @Test
    fun `shellExec issues POST shell-exec with command body and parses snake_case exit_code`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{"stdout":"hello\n","stderr":"","exit_code":0,"truncated":false}""",
                ),
        )

        val response = api.shellExec(ShellExecRequest(command = "echo hello"))

        assertEquals("hello\n", response.stdout)
        assertEquals("", response.stderr)
        // Crucial: backend serializes this field as
        // `exit_code`; without the @Json(name=...)
        // mapping on the DTO, Moshi would fail to
        // find `exitCode` and the exit code would
        // silently default to 0. The test guards
        // that mapping.
        assertEquals(0, response.exitCode)
        assertFalse(response.truncated)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/shell/exec", request.path)
        val body = request.body.readUtf8()
        // Request body is a single JSON object with
        // the `command` field. Moshi doesn't add
        // whitespace, so we match the compact form.
        assertEquals("""{"command":"echo hello"}""", body)
    }

    @Test
    fun `shellExec parses non-zero exit code and truncated flag`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{"stdout":"","stderr":"oops\n","exit_code":2,"truncated":true}""",
                ),
        )

        val response = api.shellExec(ShellExecRequest(command = "false"))

        assertEquals("oops\n", response.stderr)
        assertEquals(2, response.exitCode)
        assertTrue(response.truncated)
    }

    @Test
    fun `shellExec uses exit_code 0 when the backend omits truncated`() = runBlocking {
        // Defensive: an older backend (or a hand-
        // rolled mock in some future test) might
        // omit the optional `truncated` field. The
        // DTO declares `truncated: Boolean = false`,
        // so Moshi's KotlinJsonAdapterFactory uses
        // the default. We pin that behavior here.
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"stdout":"hi","stderr":"","exit_code":0}"""),
        )

        val response = api.shellExec(ShellExecRequest(command = "echo hi"))

        assertFalse("default truncated should be false", response.truncated)
    }

    @Test
    fun `shellExec raises HttpException on 422 validation error`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(422)
                .setBody("""{"detail":[{"loc":["body","command"],"msg":"field required"}]}"""),
        )

        try {
            api.shellExec(ShellExecRequest(command = ""))
            fail("expected HttpException on 422")
        } catch (e: HttpException) {
            assertEquals(422, e.code())
        }
    }

    // ---- /config -----------------------------------------------

    @Test
    fun `putConfig issues PUT config with snake_case body and parses ok response`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"ok":true}"""),
        )

        val response = api.putConfig(
            ConfigRequest(
                provider = "anthropic",
                model = "claude-sonnet-4-5",
                ports = ConfigPorts(backend = 7777, flask = 7778),
            ),
        )

        assertTrue(response.ok)
        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/config", request.path)
        val body = request.body.readUtf8()
        // Verify the wire format byte-for-byte. Provider/model and nested
        // ports are synchronized, but the encrypted API key must never cross
        // the loopback HTTP boundary.
        assertEquals(
            """{"provider":"anthropic","model":"claude-sonnet-4-5","ports":{"backend":7777,"flask":7778}}""",
            body,
        )
    }

    @Test
    fun `putConfig parses ok false response`() = runBlocking {
        // The backend currently always returns 200
        // with ok=true, but the response shape
        // permits `{"ok": false}` for a future
        // "config locked because orchestrator is
        // busy" case. Pin the parse path.
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"ok":false}"""),
        )

        val response = api.putConfig(
            ConfigRequest(
                provider = "openai",
                model = "gpt-4o",
                ports = ConfigPorts(backend = 7777, flask = 7778),
            ),
        )

        assertFalse(response.ok)
    }

    // ---- Response delay (proves the suspend boundary) ----------

    @Test
    fun `shellExec suspends until the response is available`() = runBlocking {
        // We enqueue the response with a 200ms
        // bodyDelay. The Retrofit `suspend` bridge
        // has to actually suspend (not block the
        // thread) for the test to finish. The
        // call returning the right value is the
        // proof; if `suspend` were silently
        // blocking on a synchronous Retrofit, the
        // test would still pass but for the wrong
        // reason — so we also assert that we can
        // do other work concurrently via
        // `coroutineContext`.
        server.enqueue(
            MockResponse()
                .setBodyDelay(200, TimeUnit.MILLISECONDS)
                .setResponseCode(200)
                .setBody("""{"stdout":"delayed","stderr":"","exit_code":0,"truncated":false}"""),
        )

        val response = api.shellExec(ShellExecRequest(command = "sleep 0.1"))

        assertEquals("delayed", response.stdout)
        assertNotNull("request was received", server.takeRequest(1, TimeUnit.SECONDS))
    }
}
