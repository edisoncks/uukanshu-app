package cc.uukanshu

import cc.uukanshu.data.db.MIGRATION_1_2
import cc.uukanshu.data.db.MIGRATION_2_3
import cc.uukanshu.data.db.MIGRATION_3_4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * JVM-runnable DB contract: schema files stay checked in, version stays 4,
 * migrations cover every step. Full data-level migration runs live in
 * androidTest/MigrationTest (needs an emulator); this locks the wiring so a
 * missing schema or skipped step fails `mise run test`.
 */
class DbSchemaTest {
    @Test fun databaseVersionIs4() {
        val dir = schemaDir()
        val text = java.io.File(dir, "4.json").readText()
        assertTrue("4.json must declare version 4", text.contains("\"version\": 4"))
    }

    @Test fun migrationsCoverEveryStep() {
        assertEquals(1, MIGRATION_1_2.startVersion)
        assertEquals(2, MIGRATION_1_2.endVersion)
        assertEquals(2, MIGRATION_2_3.startVersion)
        assertEquals(3, MIGRATION_2_3.endVersion)
        assertEquals(3, MIGRATION_3_4.startVersion)
        assertEquals(4, MIGRATION_3_4.endVersion)
    }

    private fun schemaDir(): File {
        val candidates = listOf(
            File("schemas/cc.uukanshu.data.db.AppDb"),
            File("app/schemas/cc.uukanshu.data.db.AppDb"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("schema dir missing; checked ${candidates.map { it.path }}")
    }

    @Test fun schemasAreCheckedIn() {
        val dir = schemaDir()
        for (v in 1..4) {
            val f = File(dir, "$v.json")
            assertTrue("missing schema $v.json", f.isFile)
            val text = f.readText()
            assertTrue("schema $v missing books table", text.contains("\"tableName\": \"books\""))
        }
        val v4 = File(dir, "4.json").readText()
        assertTrue("v4 must carry progress.pageId", v4.contains("pageId"))
    }
}
