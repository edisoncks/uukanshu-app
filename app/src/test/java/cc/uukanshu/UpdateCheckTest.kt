package cc.uukanshu

import cc.uukanshu.data.update.UpdateApi
import cc.uukanshu.data.update.VersionCompare
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionCompareTest {
    @Test
    fun `patch bump is newer`() {
        assertTrue(VersionCompare.isNewer("1.0.15", "1.0.14"))
        assertFalse(VersionCompare.isNewer("1.0.14", "1.0.14"))
        assertFalse(VersionCompare.isNewer("1.0.14", "1.0.15"))
    }

    @Test
    fun `numeric not lexicographic`() {
        // "9" < "15" numerically, but "9" > "1" lexicographically.
        assertTrue(VersionCompare.isNewer("1.0.15", "1.0.9"))
        assertTrue(VersionCompare.isNewer("1.10.0", "1.9.9"))
    }

    @Test
    fun `leading v and whitespace tolerated`() {
        assertTrue(VersionCompare.isNewer("v1.0.15", "1.0.14"))
        assertTrue(VersionCompare.isNewer("  v1.0.15  ", "v1.0.14"))
        assertFalse(VersionCompare.isNewer("v1.0.14", "1.0.14"))
    }

    @Test
    fun `release beats prerelease`() {
        assertTrue(VersionCompare.isNewer("1.0.15", "1.0.15-beta"))
        assertFalse(VersionCompare.isNewer("1.0.15-beta", "1.0.15"))
    }
}

class UpdateApiParseTest {
    private val payload = """
        {
          "tag_name": "v1.0.15",
          "body": "Fix reader crash",
          "html_url": "https://github.com/edisoncks/uukanshu-app/releases/tag/v1.0.15",
          "assets": [
            {"name": "output-metadata.json", "browser_download_url": "https://example.com/m.json"},
            {"name": "uukanshu-1.0.15.apk", "browser_download_url": "https://example.com/u.apk"}
          ]
        }
    """.trimIndent()

    @Test
    fun `picks uukanshu apk asset`() {
        val info = UpdateApi.parse(payload)
        assertNotNull(info)
        assertEquals("v1.0.15", info!!.tag)
        assertEquals("1.0.15", info.version)
        assertEquals("https://example.com/u.apk", info.apkUrl)
        assertEquals("uukanshu-1.0.15.apk", info.apkName)
        assertEquals("Fix reader crash", info.changelog)
    }

    @Test
    fun `null when no apk asset`() {
        assertNull(UpdateApi.parse("""{"tag_name":"v1.0.15","assets":[]}"""))
        assertNull(UpdateApi.parse("""{"tag_name":"","assets":[]}"""))
    }

    @Test
    fun `handles escapes and nested objects`() {
        // Raw string: backslashes reach the parser untouched, exactly like
        // a real GitHub body with \n newlines and escaped quotes, plus a
        // nested uploader object inside the asset entry.
        val nested = """{"tag_name":"v1.0.15","body":"line1\nline2 \"quoted\"","assets":[{"name":"uukanshu-1.0.15.apk","browser_download_url":"https://example.com/u.apk","uploader":{"login":"edisoncks","id":123}}]}"""
        val info = UpdateApi.parse(nested)
        assertNotNull(info)
        assertEquals("line1\nline2 \"quoted\"", info!!.changelog)
    }

    @Test
    fun `falls back to any apk`() {
        val info = UpdateApi.parse(
            """{"tag_name":"v1.0.15","assets":[{"name":"app.apk","browser_download_url":"https://example.com/a.apk"}]}""",
        )
        assertNotNull(info)
        assertEquals("app.apk", info!!.apkName)
    }
}
