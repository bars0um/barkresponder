package com.codeoptimus.barkresponder

import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Saved clips can hold voices from the house, so no backup or transfer may carry them. */
class BackupRulesTest {
    // Gradle runs unit tests from the module folder.
    private fun xml(name: String): Element =
        DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/xml/$name")).documentElement

    private fun Element.excludesSamples(): Boolean {
        val excludes = getElementsByTagName("exclude")
        return (0 until excludes.length).map { excludes.item(it) as Element }
            .any { it.getAttribute("domain") == "file" && it.getAttribute("path") == SampleStore.DIR_NAME + "/" }
    }

    private fun Element.section(tag: String) = getElementsByTagName(tag).item(0) as Element?

    @Test
    fun `cloud backup and device transfer on Android 12 and later leave out the clips`() {
        val rules = xml("data_extraction_rules.xml")

        assertTrue("cloud-backup", rules.section("cloud-backup")?.excludesSamples() == true)
        assertTrue("device-transfer", rules.section("device-transfer")?.excludesSamples() == true)
    }

    @Test
    fun `the manifest uses both rule files`() {
        val app = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/AndroidManifest.xml")).documentElement
            .getElementsByTagName("application").item(0) as Element

        assertTrue(app.getAttribute("android:dataExtractionRules") == "@xml/data_extraction_rules")
        assertTrue(app.getAttribute("android:fullBackupContent") == "@xml/backup_rules")
    }

    @Test
    fun `backup on Android 11 and earlier leaves out the clips`() {
        assertTrue(xml("backup_rules.xml").excludesSamples())
    }
}
