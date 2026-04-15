package com.voiceledger.lite.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        NoteEntity::class,
        RollupEntity::class,
        SemanticEntryEntity::class,
        AggregationCheckpointEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class LedgerDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao
    abstract fun rollupDao(): RollupDao
    abstract fun semanticEntryDao(): SemanticEntryDao
    abstract fun aggregationCheckpointDao(): AggregationCheckpointDao
}
