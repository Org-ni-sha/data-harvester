package com.capstone.dataharvester

import com.capstone.dataharvester.data.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every schema version a phone can have must migrate to the current one, one step at a
 * time. Without this, raising AppDatabase.VERSION and forgetting the migration only
 * shows up on participants' phones, as a database the app can no longer open.
 * publishUpdate runs this test, so such a build is never published.
 */
class MigrationCoverageTest {

    private val schemaDir = File("schemas/com.capstone.dataharvester.data.AppDatabase")

    @Test
    fun everyVersionHasAMigrationToTheNext() {
        val steps = AppDatabase.ALL_MIGRATIONS.map { it.startVersion to it.endVersion }
        assertEquals("one migration per step, oldest first", (1 until AppDatabase.VERSION).map { it to it + 1 }, steps)
    }

    @Test
    fun currentVersionHasAnExportedSchema() {
        // Room writes <VERSION>.json at build time; MigrationTest validates migrations against it
        assertTrue("missing ${schemaDir}/${AppDatabase.VERSION}.json", File(schemaDir, "${AppDatabase.VERSION}.json").exists())
        val newest = schemaDir.listFiles()!!.mapNotNull { it.nameWithoutExtension.toIntOrNull() }.max()
        assertEquals("a schema newer than AppDatabase.VERSION exists", AppDatabase.VERSION, newest)
    }
}
