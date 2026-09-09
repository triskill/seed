package com.seed.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class RootfsVersionTest {

    @Test
    fun parsesValidJson() {
        val v = RootfsVersion.parse("""{"seed_version":"0.1.0","build_id":"20260703T113314Z-a1b2c3d4"}""")
        assertEquals(RootfsVersion(seedVersion = "0.1.0", buildId = "20260703T113314Z-a1b2c3d4"), v)
    }

    @Test
    fun defaultsMissingGuestArchitectureToArm64ForOldMarkers() {
        val version = RootfsVersion.parse("""{"seed_version":"0.1.0","build_id":"X"}""")
        assertEquals(RootfsArchitecture.ARM64, version.guestArchitecture)
    }

    @Test
    fun parsesX86_64GuestArchitecture() {
        val version = RootfsVersion.parse(
            """{"seed_version":"0.1.0","build_id":"X","guest_arch":"x86_64"}""",
        )
        assertEquals(RootfsArchitecture.X86_64, version.guestArchitecture)
    }

    @Test
    fun x86GuestNeedsQemuOnlyOnArm64Host() {
        assertEquals(
            true,
            RootfsArchitecture.X86_64.requiresQemuX86_64(arrayOf("arm64-v8a", "armeabi-v7a")),
        )
        assertEquals(
            false,
            RootfsArchitecture.X86_64.requiresQemuX86_64(arrayOf("x86_64", "x86")),
        )
        assertEquals(
            false,
            RootfsArchitecture.ARM64.requiresQemuX86_64(arrayOf("arm64-v8a")),
        )
    }

    @Test
    fun rejectsUnsupportedGuestArchitecture() {
        try {
            RootfsVersion.parse("""{"seed_version":"0.1.0","build_id":"X","guest_arch":"mips"}""")
            throw AssertionError("expected unsupported architecture to fail")
        } catch (failure: IllegalArgumentException) {
            assertEquals("unsupported guest_arch: mips", failure.message)
        }
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
        // Forward-compat: if we add a field to seed_version.json later,
        // older app builds should still be able to parse it.
        val v = RootfsVersion.parse("""{"seed_version":"0.1.0","build_id":"X","future":"ignore me"}""")
        assertEquals(RootfsVersion("0.1.0", "X"), v)
    }
}
