package com.voiceledger.lite.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes ORDER BY created_at_epoch_ms DESC")
    fun observeAll(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE id = :noteId LIMIT 1")
    suspend fun getById(noteId: Long): NoteEntity?

    @Query(
        """
        SELECT * FROM notes
        WHERE created_at_epoch_ms >= :sinceEpochMs
        ORDER BY created_at_epoch_ms DESC
        LIMIT :limit
        """,
    )
    suspend fun recentSince(sinceEpochMs: Long, limit: Int): List<NoteEntity>

    @Insert
    suspend fun insert(note: NoteEntity): Long

    @Update
    suspend fun update(note: NoteEntity)

    @Query("DELETE FROM notes WHERE id = :noteId")
    suspend fun deleteById(noteId: Long)
}

@Dao
interface InsightDao {
    @Query("SELECT * FROM insights WHERE kind = :kind LIMIT 1")
    fun observe(kind: String): Flow<InsightEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replace(insight: InsightEntity)
}
