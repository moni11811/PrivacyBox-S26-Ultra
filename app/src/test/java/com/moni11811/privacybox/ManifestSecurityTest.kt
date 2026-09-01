package com.moni11811.privacybox

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class ManifestSecurityTest {
  @Test
  fun componentAndBackupGuardsRemainExplicit() {
    val document = parse(File("src/main/AndroidManifest.xml"))
    val application = document.getElementsByTagName("application").item(0) as Element
    assertEquals("false", application.androidAttribute("allowBackup"))
    assertEquals("@xml/backup_rules", application.androidAttribute("fullBackupContent"))
    assertEquals("@xml/data_extraction_rules", application.androidAttribute("dataExtractionRules"))

    val activity = component(document.documentElement, "activity", ".MainActivity")
    assertEquals("true", activity.androidAttribute("exported"))

    val overlayService = component(document.documentElement, "service", ".PrivacyOverlayService")
    assertEquals("false", overlayService.androidAttribute("exported"))

    val tileService = component(document.documentElement, "service", ".PrivacyTileService")
    assertEquals("true", tileService.androidAttribute("exported"))
    assertEquals("android.permission.BIND_QUICK_SETTINGS_TILE", tileService.androidAttribute("permission"))
  }

  @Test
  fun bothBackupRuleFormatsExcludePrivateState() {
    val legacy = File("src/main/res/xml/backup_rules.xml").readText()
    val modern = File("src/main/res/xml/data_extraction_rules.xml").readText()

    listOf("privacy_overlay.xml", "overlay_start_authorization.xml").forEach { fileName ->
      assertTrue(legacy.contains("path=\"$fileName\""))
      assertEquals(2, modern.split("path=\"$fileName\"").size - 1)
    }
  }

  @Test
  fun exportedActivityHasNoAuthorizationExtraInSource() {
    val source = File("src/main/java/com/moni11811/privacybox/MainActivity.kt").readText()

    assertFalse(source.contains("start_after_permission"))
    assertFalse(source.contains("getBooleanExtra"))
    assertTrue(source.contains("OverlayStartAuthorization.onResume"))
  }

  private fun parse(file: File) = DocumentBuilderFactory.newInstance().apply {
    isNamespaceAware = true
    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    setFeature("http://xml.org/sax/features/external-general-entities", false)
    setFeature("http://xml.org/sax/features/external-parameter-entities", false)
  }.newDocumentBuilder().parse(file)

  private fun component(root: Element, tag: String, name: String): Element {
    val nodes = root.getElementsByTagName(tag)
    return (0 until nodes.length)
      .map { nodes.item(it) as Element }
      .single { it.androidAttribute("name") == name }
  }

  private fun Element.androidAttribute(name: String) =
    getAttributeNS("http://schemas.android.com/apk/res/android", name)
}
