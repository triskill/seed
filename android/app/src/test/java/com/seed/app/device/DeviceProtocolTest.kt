package com.seed.app.device

import org.junit.Assert.*
import org.junit.Test

class DeviceProtocolTest {
    @Test fun originIsExactAndMainFrameOnly() {
        val url = "http://127.0.0.1:7778/"
        assertEquals("http://127.0.0.1:7778", DeviceProtocol.origin(url))
        assertTrue(DeviceProtocol.isTrusted(url, url, url, true))
        for (bad in listOf("http://127.0.0.1:7777/", "http://evil@127.0.0.1:7778/", "http://127.0.0.1:7778.evil/")) {
            assertFalse(DeviceProtocol.isTrusted(url, bad, url, true))
            assertFalse(DeviceProtocol.isTrusted(url, url, bad, true))
        }
        assertFalse(DeviceProtocol.isTrusted(url, url, url, false))
    }
    @Test fun strictRequestsAndDefaults() {
        val r = DeviceProtocol.parse("""{"v":1,"id":"1","method":"sensor.read","params":{"type":5}}""")
        assertEquals(3000, r.params["timeoutMs"])
        assertEquals(5, r.params["type"])
        for (params in listOf("{\"type\":0}", "{\"type\":1.5}", "{\"type\":2147483648}", "{\"type\":1,\"timeoutMs\":99}", "{\"type\":1,\"other\":0}", "[]")) {
            try { DeviceProtocol.parse("""{"v":1,"id":"1","method":"sensor.read","params":$params}"""); fail(params) } catch (_: DeviceCapabilityError) { }
        }
    }
    @Test fun registryAndEnvelopeTypes() {
        fun request(method: String, params: String = "{}", id: String = "abc_1") = """{"v":1,"id":"$id","method":"$method","params":$params}"""
        for (method in listOf("capabilities.list", "camera.capture", "sensor.list")) {
            assertTrue(DeviceProtocol.parse(request(method)).params.isEmpty())
            for (params in listOf("null", "true", "[]", "{\"unexpected\":1}")) {
                try { DeviceProtocol.parse(request(method, params)); fail(params) } catch (e: DeviceCapabilityError) { assertEquals("INVALID_REQUEST", e.code) }
            }
        }
        try { DeviceProtocol.parse(request("sensor.subscribe")); fail() } catch (e: DeviceCapabilityError) { assertEquals("UNKNOWN_METHOD", e.code) }
        for (id in listOf("a".repeat(65), "has space", "../", "")) {
            try { DeviceProtocol.parse(request("sensor.list", id = id)); fail(id) } catch (_: DeviceCapabilityError) { }
        }
        assertNull(DeviceProtocol.requestId("[]"))
        assertEquals("abc_1", DeviceProtocol.requestId(request("sensor.list")))
        assertNull(DeviceProtocol.origin("file:///tmp/app"))
        assertNull(DeviceProtocol.origin("http://user:pass@127.0.0.1:7778/"))
        assertEquals("https://example.com:443", DeviceProtocol.origin("https://example.com/path"))
    }
    @Test fun invalidEnvelopesAndMetadata() {
        for (input in listOf("[]", "{}", "x".repeat(8193), """{"v":2,"id":"1","method":"sensor.list","params":{}}""", """{"v":1,"id":"","method":"sensor.list","params":{}}""", """{"v":1,"id":"1","method":"camera.capture","params":{"x":1}}""")) {
            try { DeviceProtocol.parse(input); fail(input.take(100)) } catch (_: DeviceCapabilityError) { }
        }
        val manifest = DeviceProtocol.capabilities()
        val errorCodes = (manifest["reply"] as Map<*, *>)["errorCodes"] as List<*>
        assertTrue(errorCodes.contains("PERMISSION_DENIED"))
        assertFalse(errorCodes.contains("DENIED"))
        assertEquals(1, manifest["protocolVersion"])
        assertEquals(8192, (manifest["limits"] as Map<*, *>)["requestMaxBytes"])
        val capabilities = manifest["capabilities"] as List<*>
        assertEquals(4, capabilities.size)
        capabilities.forEach { assertTrue((it as Map<*, *>)["resultSchema"] is Map<*, *>) }
    }
}
