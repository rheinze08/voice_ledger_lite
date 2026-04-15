package com.voiceledger.lite.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [NoteEntity::class, InsightEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class LedgerDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao
    abstract fun insightDao(): InsightDao
}
