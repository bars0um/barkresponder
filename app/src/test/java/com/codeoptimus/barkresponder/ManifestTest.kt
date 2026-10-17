package com.codeoptimus.barkresponder

import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class ManifestTest {
    private fun permissions(): Set<String> {
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/AndroidManifest.xml")).documentElement
            .getElementsByTagName("uses-permission")
        return (0 until nodes.length).map { (nodes.item(it) as Element).getAttribute("android:name") }.toSet()
    }

    @Test
    fun `asks for network access and, on Android 17, local network access for Remote access`() {
        val declared = permissions()
        assertTrue(declared.toString(), "android.permission.INTERNET" in declared)
        assertTrue(declared.toString(), "android.permission.ACCESS_LOCAL_NETWORK" in declared)
    }
}
