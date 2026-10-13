package cz.trety.seed.release

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element

class BackupPolicyTest {
    private fun xml(path: String): Element = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
    }.newDocumentBuilder().parse(File(path)).documentElement

    @Test fun manifestDisablesBackupAndSelectsBothRuleFormats() {
        val application = xml("src/main/AndroidManifest.xml")
            .getElementsByTagName("application").item(0) as Element
        val ns = "http://schemas.android.com/apk/res/android"
        assertEquals("false", application.getAttributeNS(ns, "allowBackup"))
        assertEquals("@xml/backup_rules", application.getAttributeNS(ns, "fullBackupContent"))
        assertEquals("@xml/data_extraction_rules", application.getAttributeNS(ns, "dataExtractionRules"))
    }

    @Test fun everyStorageDomainIsExcludedFromBackupAndTransfer() {
        val domains = setOf("root", "file", "database", "sharedpref", "external",
            "device_root", "device_file", "device_database", "device_sharedpref")
        val legacy = xml("src/main/res/xml/backup_rules.xml")
        val modern = xml("src/main/res/xml/data_extraction_rules.xml")
        val rules = listOf(legacy,
            modern.getElementsByTagName("cloud-backup").item(0) as Element,
            modern.getElementsByTagName("device-transfer").item(0) as Element)
        for (rule in rules) {
            assertEquals(0, rule.getElementsByTagName("include").length)
            val excluded = rule.getElementsByTagName("exclude")
            val actual = (0 until excluded.length).map { index ->
                val entry = excluded.item(index) as Element
                assertEquals(".", entry.getAttribute("path"))
                entry.getAttribute("domain")
            }.toSet()
            assertEquals(domains, actual)
        }
    }
}
