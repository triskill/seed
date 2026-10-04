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
    @Test fun streamAcknowledgementsAreBoundedAndExact() {
        assertEquals("native" to 1L, DeviceProtocol.streamAck("""{"v":1,"type":"stream_ack","subscriptionId":"native","sequence":1}"""))
        for (sequence in listOf("0", "-1", "1.5", "9007199254740992", "null", "\"1\"")) {
            assertNull(DeviceProtocol.streamAck("""{"v":1,"type":"stream_ack","subscriptionId":"native","sequence":$sequence}"""))
        }
        assertNull(DeviceProtocol.streamAck("""{"v":1,"type":"stream_ack","subscriptionId":"native","sequence":1,"id":"rpc"}"""))
        assertNull(DeviceProtocol.streamAck("""{"v":1,"type":"stream_ack","subscriptionId":"${"a".repeat(65)}","sequence":1}"""))
    }
    @Test fun streamingParameters() {
        fun parse(params: String) = DeviceProtocol.parse("""{"v":1,"id":"s","method":"sensor.subscribe","params":$params}""")
        assertEquals(30, parse("""{"type":1}""").params["rateHz"])
        assertEquals(60, parse("""{"type":1,"rateHz":60}""").params["rateHz"])
        for (rate in listOf("0", "61", "1.5", "null", "\"30\"")) {
            try { parse("""{"type":1,"rateHz":$rate}"""); fail(rate) } catch (e: DeviceCapabilityError) { assertEquals("INVALID_REQUEST", e.code) }
        }
        assertEquals(4, (DeviceProtocol.capabilities()["limits"] as Map<*, *>)["maxSensorSubscriptions"])
        val uuid = "12345678-1234-1234-1234-123456789abc"
        assertEquals(uuid, DeviceProtocol.parse("""{"v":1,"id":"u","method":"sensor.unsubscribe","params":{"subscriptionId":"$uuid"}}""").params["subscriptionId"])
        for (params in listOf("{}", """{"subscriptionId":""}""", """{"subscriptionId":"${"a".repeat(65)}"}""", """{"subscriptionId":"$uuid","extra":1}""")) {
            try { DeviceProtocol.parse("""{"v":1,"id":"u","method":"sensor.unsubscribe","params":$params}"""); fail(params) } catch (e: DeviceCapabilityError) { assertEquals("INVALID_REQUEST", e.code) }
        }
    }
    @Test fun strictRequestsAndDefaults() {
        val r = DeviceProtocol.parse("""{"v":1,"id":"1","method":"sensor.read","params":{"type":5}}""")
        assertEquals(3000, r.params["timeoutMs"])
        assertEquals(5, r.params["type"])
        for (params in listOf("{\"type\":0}", "{\"type\":1.5}", "{\"type\":2147483648}", "{\"type\":1,\"timeoutMs\":99}", "{\"type\":1,\"other\":0}", "[]")) {
            try { DeviceProtocol.parse("""{"v":1,"id":"1","method":"sensor.read","params":$params}"""); fail(params) } catch (_: DeviceCapabilityError) { }
        }
    }
    @Test fun locationParametersAreStrictAndDiscoverable() {
        fun parse(params: String) = DeviceProtocol.parse("""{"v":1,"id":"gps","method":"location.current","params":$params}""")
        assertEquals(mapOf("accuracy" to "coarse", "timeoutMs" to 15000), parse("{}").params)
        assertEquals(mapOf("accuracy" to "fine", "timeoutMs" to 1000), parse("""{"accuracy":"fine","timeoutMs":1000}""").params)
        assertEquals(60000, parse("""{"timeoutMs":60000}""").params["timeoutMs"])
        for (params in listOf("""{"accuracy":"precise"}""", """{"accuracy":null}""", """{"timeoutMs":999}""", """{"timeoutMs":60001}""", """{"timeoutMs":1000.5}""", """{"timeoutMs":"15000"}""", """{"watch":true}""")) {
            try { parse(params); fail(params) } catch (e: DeviceCapabilityError) { assertEquals("INVALID_REQUEST", e.code) }
        }
        assertEquals("location", deviceConsentGroup("location.current")?.id)
        val entry = (DeviceProtocol.capabilities()["capabilities"] as List<*>).map { it as Map<*, *> }.single { it["method"] == "location.current" }
        assertEquals(listOf("latitude", "longitude", "accuracyMeters", "timestampMs", "precision", "ageMs"), (entry["resultSchema"] as Map<*, *>)["required"])
    }
    @Test fun registryAndEnvelopeTypes() {
        fun request(method: String, params: String = "{}", id: String = "abc_1") = """{"v":1,"id":"$id","method":"$method","params":$params}"""
        for (method in listOf("capabilities.list", "camera.capture", "sensor.list")) {
            assertTrue(DeviceProtocol.parse(request(method)).params.isEmpty())
            for (params in listOf("null", "true", "[]", "{\"unexpected\":1}")) {
                try { DeviceProtocol.parse(request(method, params)); fail(params) } catch (e: DeviceCapabilityError) { assertEquals("INVALID_REQUEST", e.code) }
            }
        }
        try { DeviceProtocol.parse(request("sensor.subscribe")); fail() } catch (e: DeviceCapabilityError) { assertEquals("INVALID_REQUEST", e.code) }
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
        assertTrue("Consent choices must be discoverable", manifest["consent"] is Map<*, *>)
        val consent = manifest["consent"] as Map<*, *>
        assertEquals(listOf("Allow once", "Allow", "Deny"), consent["choices"])
        assertEquals("origin+capability", consent["scope"])
        val groups = consent["groups"] as Map<*, *>
        assertEquals(listOf("sensor.list", "sensor.read", "sensor.subscribe"), groups["sensors"])
        assertEquals(listOf("camera.capture"), groups["camera"])
        assertEquals(8192, (manifest["limits"] as Map<*, *>)["requestMaxBytes"])
        val capabilities = manifest["capabilities"] as List<*>
        assertEquals(listOf("location.current"), groups["location"])
        assertEquals(7, capabilities.size)
        capabilities.forEach { assertTrue((it as Map<*, *>)["resultSchema"] is Map<*, *>) }
    }
}
