package com.cacaosd.wowtelemetry.data.database

import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertNull

class RoomMetadataRepositoryTest {
    @Test
    fun metadataLookupsReturnNullWhenRowsAreAbsent() = runBlocking {
        val databaseFile = File.createTempFile("wow-telemetry", ".db")
        val database = createAppDatabase(databaseFile.absolutePath)
        try {
            assertNull(RoomSpellMetadataRepository(database.spellNameDao()).findById("1234"))
            assertNull(RoomNpcMetadataRepository(database.creatureDao()).findById("5678"))
            assertNull(RoomSpellMetadataRepository(database.spellNameDao()).findById("missing"))
        } finally {
            database.close()
            databaseFile.delete()
        }
    }
}
