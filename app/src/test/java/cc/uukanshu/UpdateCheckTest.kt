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

    @Test
    fun `prerelease suffix compares numbers numerically`() {
        // Lexicographic order says beta2 > beta10 ("2" > "1"); wrong.
        assertTrue(VersionCompare.isNewer("1.0.15-beta10", "1.0.15-beta2"))
        assertFalse(VersionCompare.isNewer("1.0.15-beta2", "1.0.15-beta10"))
        assertTrue(VersionCompare.isNewer("1.0.15-rc2", "1.0.15-beta10"))
        assertFalse(VersionCompare.isNewer("1.0.15-beta", "1.0.15-beta"))
    }

    @Test
    fun `prerelease detection`() {
        // The updater's stable-only gate keys on this (see UpdateApi.parse).
        assertTrue(VersionCompare.isPrerelease("v1.2.19-beta"))
        assertTrue(VersionCompare.isPrerelease("1.2.19-rc.1"))
        assertFalse(VersionCompare.isPrerelease("1.2.19"))
        // `+build` metadata is cut by normalize, so it is not a prerelease:
        // only a `-suffix` marks a build as not-for-stable-users.
        assertFalse(VersionCompare.isPrerelease("1.2.19+build7"))
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
    fun `rejects stray apk asset`() {
        // Fail closed: a non-uukanshu .apk must never be offered for install.
        assertNull(
            UpdateApi.parse(
                """{"tag_name":"v1.0.15","assets":[{"name":"app.apk","browser_download_url":"https://example.com/a.apk"}]}""",
            ),
        )
    }

    @Test
    fun `parses asset size when present`() {
        val info = UpdateApi.parse(
            """{"tag_name":"v1.0.18","assets":[{"name":"uukanshu-1.0.18.apk","browser_download_url":"https://example.com/u.apk","size":12345678}]}""",
        )
        assertNotNull(info)
        assertEquals(12345678L, info!!.size)
    }

    @Test
    fun `size null when absent`() {
        val info = UpdateApi.parse(payload)
        assertNotNull(info)
        assertNull(info!!.size)
    }

    @Test
    fun `rejects version-mismatched apk asset`() {
        // Tag v1.0.15 must ship exactly uukanshu-1.0.15.apk: a stale/second
        // APK fails closed instead of offering the wrong binary.
        assertNull(
            UpdateApi.parse(
                """{"tag_name":"v1.0.15","assets":[{"name":"uukanshu-1.0.14.apk","browser_download_url":"https://example.com/old.apk"}]}""",
            ),
        )
    }
    @Test
    fun `rejects second uukanshu apk even when first matches`() {
        // Exactly-one-asset contract: an extra uukanshu-*.apk alongside the
        // exact match fails closed instead of offering the first of two.
        assertNull(
            UpdateApi.parse(
                """{"tag_name":"v1.0.15","assets":[{"name":"uukanshu-1.0.15.apk","browser_download_url":"https://example.com/u.apk"},{"name":"uukanshu-1.0.15-debug.apk","browser_download_url":"https://example.com/d.apk"}]}""",
            ),
        )
    }

    @Test
    fun `never offers a prerelease tag`() {
        // Stable-only channel. Both payloads below are otherwise perfect (tag,
        // asset name and parsed version all agree), which is exactly the shape
        // of a beta published without GitHub's prerelease flag: it must be
        // refused, not offered to users of the previous release.
        assertNull(
            UpdateApi.parse(
                """{"tag_name":"v1.2.19-beta","assets":[{"name":"uukanshu-1.2.19-beta.apk","browser_download_url":"https://example.com/b.apk"}]}""",
            ),
        )
        assertNull(
            UpdateApi.parse(
                """{"tag_name":"v1.2.19-rc.1","assets":[{"name":"uukanshu-1.2.19-rc.1.apk","browser_download_url":"https://example.com/b.apk"}]}""",
            ),
        )
    }

    @Test
    fun `prerelease flag refuses an otherwise usable payload`() {
        assertNull(
            UpdateApi.parse(
                """{"tag_name":"v1.0.15","prerelease":true,"assets":[{"name":"uukanshu-1.0.15.apk","browser_download_url":"https://example.com/u.apk"}]}""",
            ),
        )
        // Field absent (old payloads) and explicit false both stay usable.
        assertNotNull(
            UpdateApi.parse(
                """{"tag_name":"v1.0.15","prerelease":false,"assets":[{"name":"uukanshu-1.0.15.apk","browser_download_url":"https://example.com/u.apk"}]}""",
            ),
        )
    }
}
