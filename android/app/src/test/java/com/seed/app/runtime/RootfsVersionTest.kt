package com.seed.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RootfsVersionTest {

    private val marker = """{
        "seed_version":"0.1.0",
        "build_id":"20260703T113314Z-a1b2c3d4",
        "runtime_format":"native",
        "runtime_format_version":3,
        "native_arch":"arm64"
    }"""

    @Test
    fun parsesNativeMarker() {
        val v = RootfsVersion.parse(marker)
        assertEquals(RootfsVersion(
            seedVersion = "0.1.0",
            buildId = "20260703T113314Z-a1b2c3d4",
        ), v)
        assertEquals(RootfsVersion.NATIVE_RUNTIME_FORMAT, v.runtimeFormat)
        assertEquals(RootfsVersion.NATIVE_RUNTIME_FORMAT_VERSION, v.runtimeFormatVersion)
        assertEquals(RootfsVersion.NATIVE_ARCH, v.nativeArch)
    }

    @Test
    fun oldMarkerWithoutNativeFormatIsStale() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            RootfsVersion.parse("""{"seed_version":"0.1.0","build_id":"X"}""")
        }
        assertEquals("missing runtime_format", failure.message)
    }

    @Test
    fun oldQemuMarkerIsStale() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            RootfsVersion.parse(
                """{"seed_version":"0.1.0","build_id":"X","guest_arch":"x86_64"}""",
            )
        }
        assertEquals("unsupported marker field: guest_arch", failure.message)
    }

    @Test
    fun rejectsLegacyGuestArchitectureFieldEvenWithNativeMarker() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            RootfsVersion.parse(marker.replace("\"native_arch\":\"arm64", "\"guest_arch\":\"arm64\",\"native_arch\":\"arm64"))
        }
        assertEquals("unsupported marker field: guest_arch", failure.message)
    }

    @Test
    fun rejectsUnsupportedRuntimeFormat() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            RootfsVersion.parse(marker.replace("native", "qemu-x86"))
        }
        assertEquals("unsupported runtime_format: qemu-x86", failure.message)
    }

    @Test
    fun rejectsUnsupportedRuntimeFormatVersion() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            RootfsVersion.parse(marker.replace(":3", ":2"))
        }
        assertEquals("unsupported runtime_format_version: 2", failure.message)
    }

    @Test
    fun parsesX8664NativeMarker() {
        val version = RootfsVersion.parse(marker.replace("\"native_arch\":\"arm64", "\"native_arch\":\"x86_64"))
        assertEquals("x86_64", version.nativeArch)
    }

    @Test
    fun rejectsUnsupportedNativeArchitecture() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            RootfsVersion.parse(marker.replace("\"native_arch\":\"arm64", "\"native_arch\":\"riscv64"))
        }
        assertEquals("unsupported native_arch: riscv64", failure.message)
    }

    @Test
    fun equalityIsStructural() {
        val a = RootfsVersion("0.1.0", "X")
        val b = RootfsVersion("0.1.0", "X")
        val c = RootfsVersion("0.1.0", "Y")
        assertEquals(a, b)
        assertNotEquals(a, c)
    }

    @Test
    fun toleratesUnknownExtraFields() {
        val v = RootfsVersion.parse(marker.replace("\"native_arch\":\"arm64", "\"native_arch\":\"arm64\",\"future\":\"ignore me"))
        assertEquals("0.1.0", v.seedVersion)
    }

    @Test
    fun serializesNativeMarker() {
        val version = RootfsVersion("0.1.0", "X")
        assertEquals(
            """{"seed_version":"0.1.0","build_id":"X","runtime_format":"native","runtime_format_version":3,"native_arch":"arm64"}""",
            version.toMarkerJson(),
        )
    }
}
