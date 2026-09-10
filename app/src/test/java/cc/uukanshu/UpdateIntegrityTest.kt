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
    fun `uppercase algorithm prefix parses`() {
        val hex = "a".repeat(64)
        val info = UpdateApi.parse(payload("SHA256:$hex"))
        assertEquals(hex, info!!.sha256)
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

/** sha256Hex: real digests or nothing — never a made-up match. */
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
}

/** The single decision table, now truly pure (literals in, state out). */
class ApkStateIntegrityTest {
    private val sha = "a".repeat(64)

    private fun with(bytes: ByteArray) = File.createTempFile("uukanshu-apk", ".apk").apply { writeBytes(bytes) }

    @Test
    fun `size and digest match is ready`() {
        val f = with(ByteArray(10))
        try {
            val actual = UpdateDownloader.sha256Hex(f)!!
            assertEquals(
                UpdateDownloader.ApkState.Ready,
                UpdateDownloader.apkState(true, 10L, 10L, actual, actual, dmSuccess = false),
            )
        } finally {
            f.delete()
        }
    }

    @Test
    fun `digest mismatch is partial even when size matches`() {
        // The old size-only check passed exactly this shape into the installer.
        val f = with(ByteArray(10))
        try {
            val actual = UpdateDownloader.sha256Hex(f)!!
            assertTrue(actual != sha)
            assertEquals(UpdateDownloader.ApkState.Partial, UpdateDownloader.apkState(true, 10L, 10L, sha, actual, dmSuccess = false))
            assertEquals(UpdateDownloader.ApkState.Partial, UpdateDownloader.apkState(true, 10L, 10L, sha, actual, dmSuccess = true))
        } finally {
            f.delete()
        }
    }

    @Test
    fun `expected digest with uncomputed hash fails closed`() {
        assertEquals(UpdateDownloader.ApkState.Partial, UpdateDownloader.apkState(true, 10L, 10L, sha, computedSha256 = null))
    }

    @Test
    fun `size mismatch stays partial even with digest match`() {
        val f = with(ByteArray(9))
        try {
            val actual = UpdateDownloader.sha256Hex(f)!!
            assertEquals(UpdateDownloader.ApkState.Partial, UpdateDownloader.apkState(true, 9L, 10L, actual, actual, dmSuccess = false))
        } finally {
            f.delete()
        }
    }

    @Test
    fun `legacy no-digest release keeps old behavior`() {
        // No temp files: pure literals prove the legacy path.
        assertEquals(UpdateDownloader.ApkState.Ready, UpdateDownloader.apkState(true, 10L, 10L, dmSuccess = false))
        assertEquals(UpdateDownloader.ApkState.Ready, UpdateDownloader.apkState(true, 10L, 10L, null, null, dmSuccess = false))
        assertEquals(UpdateDownloader.ApkState.Partial, UpdateDownloader.apkState(true, 10L, 11L, null, null, dmSuccess = false))
    }

    @Test
    fun `sizeless receipt path verifies digest too`() {
        val f = with(ByteArray(10))
        try {
            val actual = UpdateDownloader.sha256Hex(f)!!
            assertEquals(UpdateDownloader.ApkState.Ready, UpdateDownloader.apkState(true, 10L, null, actual, actual, dmSuccess = true))
            assertEquals(UpdateDownloader.ApkState.Partial, UpdateDownloader.apkState(true, 10L, null, actual, "b".repeat(64), dmSuccess = true))
            assertEquals(UpdateDownloader.ApkState.Partial, UpdateDownloader.apkState(true, 10L, null, actual, actual, dmSuccess = false))
        } finally {
            f.delete()
        }
    }

    @Test
    fun `digest compare is case-insensitive`() {
        val f = with(ByteArray(10))
        try {
            val actual = UpdateDownloader.sha256Hex(f)!! // lowercase
            assertEquals(UpdateDownloader.ApkState.Ready, UpdateDownloader.apkState(true, 10L, 10L, actual.uppercase(), actual, dmSuccess = false))
        } finally {
            f.delete()
        }
    }

    @Test
    fun `missing file stays missing under digest`() {
        assertEquals(UpdateDownloader.ApkState.Missing, UpdateDownloader.apkState(false, 0L, 10L, sha, sha, dmSuccess = false))
        assertEquals(UpdateDownloader.ApkState.Missing, UpdateDownloader.apkState(true, 0L, 10L, sha, null))
    }

    @Test
    fun `pure table needs no filesystem`() {
        // The Linus pin: pure means literals. If this test needs a File,
        // the function is lying about purity.
        assertEquals(UpdateDownloader.ApkState.Ready, UpdateDownloader.apkState(true, 5L, 5L, sha, sha))
        assertEquals(UpdateDownloader.ApkState.Partial, UpdateDownloader.apkState(true, 5L, 5L, sha, "b".repeat(64)))
        assertEquals(UpdateDownloader.ApkState.Partial, UpdateDownloader.apkState(true, 5L, 6L, sha, sha))
        assertEquals(UpdateDownloader.ApkState.Missing, UpdateDownloader.apkState(false, 0L, 5L, sha, sha))
    }

    @Test
    fun `io wrappers hash lazily and skip read on size mismatch`() {
        val f = with(ByteArray(10))
        try {
            val actual = UpdateDownloader.sha256Hex(f)!!
            assertTrue(UpdateDownloader.isCompleteIO(f, 10L, actual))
            assertFalse(UpdateDownloader.isCompleteIO(f, 11L, actual))
            assertFalse(UpdateDownloader.isCompleteIO(f, 11L, sha))
            assertEquals(UpdateDownloader.ApkState.Ready, UpdateDownloader.apkStateIO(f, 10L, actual, dmSuccess = false))
            assertEquals(UpdateDownloader.ApkState.Partial, UpdateDownloader.apkStateIO(f, 10L, sha, dmSuccess = true))
        } finally {
            f.delete()
        }
    }

    @Test
    fun `io wrapper sizeless path needs receipt`() {
        val f = with(ByteArray(10))
        try {
            val actual = UpdateDownloader.sha256Hex(f)!!
            assertEquals(UpdateDownloader.ApkState.Ready, UpdateDownloader.apkStateIO(f, null, actual, dmSuccess = true))
            assertEquals(UpdateDownloader.ApkState.Partial, UpdateDownloader.apkStateIO(f, null, actual, dmSuccess = false))
            assertEquals(UpdateDownloader.ApkState.Partial, UpdateDownloader.apkStateIO(f, null, sha, dmSuccess = true))
        } finally {
            f.delete()
        }
    }

    @Test
    fun `io wrapper missing stays missing`() {
        val f = File("/tmp/uukanshu-test-missing-${System.nanoTime()}.apk")
        assertFalse(f.exists())
        assertFalse(UpdateDownloader.isCompleteIO(f, 10L, sha))
        assertEquals(UpdateDownloader.ApkState.Missing, UpdateDownloader.apkStateIO(f, 10L, sha))
    }
}

/** apkGateFailure: checksum only for digest-on-record + sane length. */
class ApkGateFailureTest {
    private val sha = "a".repeat(64)

    @Test fun `partial with digest and matching size is checksum failure`() {
        assertEquals(
            UpdateDownloader.ApkFailure.CHECKSUM_MISMATCH,
            UpdateDownloader.apkGateFailure(UpdateDownloader.ApkState.Partial, sha, 5L, 5L),
        )
    }

    @Test fun `partial with digest on sizeless release is checksum failure`() {
        assertEquals(
            UpdateDownloader.ApkFailure.CHECKSUM_MISMATCH,
            UpdateDownloader.apkGateFailure(UpdateDownloader.ApkState.Partial, sha, null, 5L),
        )
    }

    @Test fun `partial with digest but wrong length is incomplete`() {
        assertEquals(
            UpdateDownloader.ApkFailure.INCOMPLETE,
            UpdateDownloader.apkGateFailure(UpdateDownloader.ApkState.Partial, sha, 5L, 9L),
        )
    }

    @Test fun `partial without digest is incomplete`() {
        assertEquals(
            UpdateDownloader.ApkFailure.INCOMPLETE,
            UpdateDownloader.apkGateFailure(UpdateDownloader.ApkState.Partial, null, 5L, 5L),
        )
    }

    @Test fun `missing is incomplete`() {
        assertEquals(
            UpdateDownloader.ApkFailure.INCOMPLETE,
            UpdateDownloader.apkGateFailure(UpdateDownloader.ApkState.Missing, sha, 5L, 0L),
        )
    }
}
