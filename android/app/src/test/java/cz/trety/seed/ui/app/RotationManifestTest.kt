package cz.trety.seed.ui.app

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test

/** Source contract: fixture exercises the same live rotation policy as the launcher. */
class RotationManifestTest {
    @Test fun androidIdentityAndSourceSetsUseApprovedNamespace() {
        val identity = "cz.trety.seed"
        val gradle = File("build.gradle.kts").readText()
        org.junit.Assert.assertTrue(gradle.contains("namespace = \"$identity\""))
        org.junit.Assert.assertTrue(gradle.contains("applicationId = \"$identity\""))
        assertEquals(identity, cz.trety.seed.BuildConfig.APPLICATION_ID)
        assertEquals("$identity.R", cz.trety.seed.R::class.java.name)
        for (sourceSet in listOf("main", "debug", "test", "androidTest")) {
            val root = File("src/$sourceSet/java/cz/trety/seed")
            org.junit.Assert.assertTrue("Missing $sourceSet package tree", root.isDirectory)
            org.junit.Assert.assertFalse(File("src/$sourceSet/java/" + "com/seed/app").exists())
            root.walkTopDown().filter { it.extension in listOf("kt", "java") }.forEach {
                org.junit.Assert.assertTrue("Wrong package: $it", it.readText().startsWith("package $identity"))
            }
        }
        assertEquals(setOf("orientation", "screenSize", "screenLayout"),
            changes("src/debug/AndroidManifest.xml", "$identity.ui.app.RotationFixtureActivity"))
    }

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
        assertEquals(expected, changes("src/debug/AndroidManifest.xml", "cz.trety.seed.ui.app.RotationFixtureActivity"))
    }
}
