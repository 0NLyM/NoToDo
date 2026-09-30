package app.notodo.data

import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@androidx.room.Dao
interface Dao {
    @Query("SELECT * FROM capture WHERE id = :id") suspend fun capture(id: String): Capture?
    @Query("SELECT * FROM capture WHERE id = :id") fun captureFlow(id: String): Flow<Capture?>
    @Query("SELECT * FROM capture WHERE processed = 0 ORDER BY updatedAt DESC") fun inbox(): Flow<List<Capture>>
    @Upsert suspend fun upsert(c: Capture)
    @Query("DELETE FROM capture WHERE id = :id AND processed = 0") suspend fun deleteDraft(id: String)

    @Query("SELECT * FROM item") fun items(): Flow<List<Item>>
    @Query("SELECT * FROM item WHERE id = :id") suspend fun item(id: String): Item?
    @Query("SELECT * FROM item WHERE id = :id") fun itemFlow(id: String): Flow<Item?>
    @Upsert suspend fun upsert(i: Item)
    @Query("DELETE FROM item WHERE id = :id") suspend fun deleteItem(id: String)
    @Query("SELECT * FROM item WHERE nextAlertAt <= :now") suspend fun due(now: Long): List<Item>
    @Query("SELECT MIN(nextAlertAt) FROM item") suspend fun nextAlert(): Long?

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(a: Audit)
    @Query("SELECT * FROM audit WHERE itemId = :itemId ORDER BY at DESC, rowid DESC") fun audit(itemId: String): Flow<List<Audit>>
    @Query("DELETE FROM audit WHERE itemId = :itemId") suspend fun deleteAudit(itemId: String)

    @Query("SELECT * FROM capture") suspend fun allCaptures(): List<Capture>
    @Query("SELECT * FROM item") suspend fun allItems(): List<Item>
    @Query("SELECT * FROM audit") suspend fun allAudit(): List<Audit>
}

@Database(entities = [Capture::class, Item::class, Audit::class], version = 1, exportSchema = true)
@TypeConverters(Converters::class)
abstract class Db : RoomDatabase() {
    abstract fun dao(): Dao
}
