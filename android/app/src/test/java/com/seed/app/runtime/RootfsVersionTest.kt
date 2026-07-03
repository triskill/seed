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
