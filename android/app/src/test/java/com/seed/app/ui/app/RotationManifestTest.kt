package com.seed.app.ui.app

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test

/** Source contract: fixture exercises the same live rotation policy as the launcher. */
class RotationManifestTest {
    private fun changes(path: String, name: String): Set<String> {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val activities = factory.newDocumentBuilder().parse(File(path)).getElementsByTagName("activity")
        val android = "http://schemas.android.com/apk/res/android"
        for (index in 0 until activities.length) {
            val activity = activities.item(index) as org.w3c.dom.Element
            if (activity.getAttributeNS(android, "name") == name) {
                return activity.getAttributeNS(android, "configChanges").split('|').filter { it.isNotEmpty() }.toSet()
            }
        }
        error("Missing activity $name")
    }

    @Test fun launcherAndFixtureHandleOnlyRotationRelatedChanges() {
        val expected = setOf("orientation", "screenSize", "screenLayout")
        assertEquals(expected, changes("src/main/AndroidManifest.xml", ".MainActivity"))
        assertEquals(expected, changes("src/debug/AndroidManifest.xml", "com.seed.app.ui.app.RotationFixtureActivity"))
    }
}
