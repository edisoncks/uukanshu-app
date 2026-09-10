package cc.uukanshu

import cc.uukanshu.data.update.UpdateApi
import cc.uukanshu.data.update.UpdateDownloader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The default update client bounds the whole call, not just connect/read. */
class UpdateApiCallTimeoutTest {
    @Test
    fun `default client bounds whole call`() {
        assertEquals(
            UpdateApi.UPDATE_CALL_TIMEOUT_S * 1000L,
            UpdateApi.defaultClient().callTimeoutMillis.toLong(),
        )
    }
}

/** `/releases/latest` payload → UpdateInfo.sha256 (asset `digest` field). */
class UpdateDigestParseTest {
    private fun payload(digest: String?): String {
        val d = if (digest != null) ",\"digest\":\"$digest\"" else ""
        return "{\"tag_name\":\"v1.0.18\",\"assets\":" +
            "[{\"name\":\"uukanshu-1.0.18.apk\",\"browser_download_url\":" +
            "\"https://example.com/u.apk\",\"size\":12345678$d}]}"
    }

    @Test
    fun `digest parsed to lowercase hex`() {
        val hex = "a".repeat(31) + "B".repeat(33) // mixed case input
        val info = UpdateApi.parse(payload("sha256:$hex"))
        assertEquals(hex.lowercase(), info!!.sha256)
    }

    @Test
    fun `digest absent keeps size-only path`() {
        val info = UpdateApi.parse(payload(null))
        assertEquals(12345678L, info!!.size)
        assertNull(info.sha256)
    }

    @Test
    fun `malformed digests fail lenient to null`() {
        // Wrong algorithm, wrong length, trailing junk, non-hex: never a parse
        // failure (old payloads keep updating via size-only), never a truthy
        // value that could masquerade as a verified hash.
        for (bad in listOf(
            "sha256:abc",
            "sha256:${"a".repeat(63)}",
            "sha256:${"a".repeat(65)}",
            "sha256:${"g".repeat(64)}",
            "sha256:${"a".repeat(64)} ",
            "md5:${"a".repeat(64)}",
            "sha512:${"a".repeat(128)}",
            "",
        )) {
            assertNull("digest=$bad", UpdateApi.parseDigest(bad))
            val info = UpdateApi.parse(payload(bad))
            assertNotNull(info)
            assertNull("digest=$bad", info!!.sha256)
        }
        assertNull(UpdateApi.parseDigest(null))
    }
}

/** sha256Hex/actualSha256: real digests or nothing — never a made-up match. */
class UpdateSha256Test {
    private fun tempWith(bytes: ByteArray): File =
        File.createTempFile("uukanshu-sha", ".bin").apply { writeBytes(bytes) }

    @Test
    fun `known vectors`() {
        // NIST: sha256("") and sha256("abc").
        val empty = tempWith(ByteArray(0))
        try {
            assertEquals(
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                UpdateDownloader.sha256Hex(empty),
            )
        } finally {
            empty.delete()
        }
        val abc = tempWith("abc".toByteArray())
        try {
            assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                UpdateDownloader.sha256Hex(abc),
            )
        } finally {
            abc.delete()
        }
    }

    @Test
    fun `unreadable file is null`() {
        assertNull(UpdateDownloader.sha256Hex(File("/tmp/uukanshu-test-missing-${System.nanoTime()}")))
    }

    @Test
    fun `actualSha256 skips read when no expectation`() {
        // Legacy path: no digest in the release, no file read — the helper
        // must return null without touching disk (verified by not existing).
        assertNull(
            UpdateDownloader.actualSha256(
                File("/tmp/uukanshu-test-missing-${System.nanoTime()}"),
                expectedSha256 = null,
            ),
        )
    }

    @Test
    fun `actualSha256 computes when expectation present`() {
        val f = tempWith("abc".toByteArray())
        try {
            assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                UpdateDownloader.actualSha256(f, "ba7816bf"),
            )
        } finally {
            f.delete()
        }
    }
}

/** The single decision table, now with the digest AND-condition. */
class ApkStateIntegrityTest {
    private val sha = "a".repeat(64)

    private fun with(bytes: ByteArray) = File.createTempFile("uukanshu-apk", ".apk").apply { writeBytes(bytes) }
    private val ten = ByteArray(10)

    @Test
    fun `size and digest match is ready`() {
        val f = with(ten)
        try {
            // Expected = the file's real hash (sha256Hex correctness itself is
            // pinned by UpdateSha256Test's NIST vectors).
            val actual = UpdateDownloader.sha256Hex(f)!!
            assertTrue(UpdateDownloader.isComplete(f, 10L, actual, actual))
            assertTrue(UpdateDownloader.isInstallable(f, 10L, actual, actual, dmSuccess = false))
        } finally {
            f.delete()
        }
    }

    @Test
    fun `digest mismatch is partial even when size matches`() {
        // The old size-only check passed exactly this file into the installer.
        val f = with(ten)
        try {
            val actual = UpdateDownloader.sha256Hex(f)!!
            assertTrue(actual != sha)
            assertEquals(UpdateDownloader.ApkState.Partial, UpdateDownloader.apkState(f, 10L, sha, actual))
            assertFalse(UpdateDownloader.isComplete(f, 10L, sha, actual))
            assertFalse(UpdateDownloader.isInstallable(f, 10L, sha, actual, dmSuccess = true))
        } finally {
            f.delete()
        }
    }

    @Test
    fun `expected digest with uncomputed hash fails closed`() {
        // null actual = not verified, never "verified".
        val f = with(ten)
        try {
            assertEquals(UpdateDownloader.ApkState.Partial, UpdateDownloader.apkState(f, 10L, sha, actualSha256 = null))
        } finally {
            f.delete()
        }
    }

    @Test
    fun `size mismatch stays partial even with digest match`() {
        // Digest right, length wrong: truncated-then-padded weirdness must
        // never pass either gate while a size is on record.
        val f = with(ByteArray(9))
        try {
            val actual = UpdateDownloader.sha256Hex(f)!!
            assertFalse(UpdateDownloader.isComplete(f, 10L, actual, actual))
        } finally {
            f.delete()
        }
    }

    @Test
    fun `legacy no-digest release keeps old behavior`() {
        val f = with(ten)
        try {
            assertTrue(UpdateDownloader.isComplete(f, 10L))
            assertTrue(UpdateDownloader.isComplete(f, 10L, null, null))
            assertFalse(UpdateDownloader.isComplete(f, 11L, null, null))
        } finally {
            f.delete()
        }
    }

    @Test
    fun `sizeless receipt path verifies digest too`() {
        val f = with(ten)
        try {
            val actual = UpdateDownloader.sha256Hex(f)!!
            assertTrue(UpdateDownloader.isInstallable(f, null, actual, actual, dmSuccess = true))
            assertFalse(UpdateDownloader.isInstallable(f, null, actual, "b".repeat(64), dmSuccess = true))
            // Without the receipt the sizeless path stays strict.
            assertFalse(UpdateDownloader.isInstallable(f, null, actual, actual, dmSuccess = false))
        } finally {
            f.delete()
        }
    }

    @Test
    fun `digest compare is case-insensitive`() {
        val f = with(ten)
        try {
            val actual = UpdateDownloader.sha256Hex(f)!! // lowercase
            assertTrue(UpdateDownloader.isComplete(f, 10L, actual.uppercase(), actual))
        } finally {
            f.delete()
        }
    }

    @Test
    fun `missing file stays missing under digest`() {
        val f = File("/tmp/uukanshu-test-missing-${System.nanoTime()}.apk")
        assertFalse(f.exists())
        assertFalse(UpdateDownloader.isComplete(f, 10L, sha, sha))
        assertEquals(UpdateDownloader.ApkState.Missing, UpdateDownloader.apkState(f, 10L, sha, null))
    }
}
