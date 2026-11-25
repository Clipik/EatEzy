package app.eatezy.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.LocalTime

val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false; encodeDefaults = true }

@Entity(tableName = "entries", indices = [Index("date")])
data class EntryRow(
    @PrimaryKey val id: String,
    val date: String,
    val time: String,
    val meal: String,
    val name: String,
    val emoji: String,
    val photo: String?,
    val grams: Double,
    val kcal: Double,
    val protein: Double,
    val carbs: Double,
    val fat: Double,
    val nutrients: String,
    val ingredients: String,
    val source: String,
    val note: String,
    val favorite: Boolean,
    val status: String,
    val error: String?,
    val createdAt: Long,
)

@Entity(tableName = "weights", indices = [Index("date")])
data class WeightRow(@PrimaryKey val id: String, val date: String, val kg: Double)

@Entity(tableName = "chat")
data class ChatRow(@PrimaryKey(autoGenerate = true) val id: Long, val fromUser: Boolean, val text: String, val at: Long)

fun FoodEntry.toRow() = EntryRow(
    id, date.toString(), time.toString(), meal.key, name, emoji, photo, grams, kcal, protein, carbs, fat,
    json.encodeToString(nutrients), json.encodeToString(ingredients), source, note, favorite, status.name, error, createdAt,
)

fun EntryRow.toEntry() = FoodEntry(
    id = id, date = LocalDate.parse(date), time = LocalTime.parse(time), meal = Meal.of(meal), name = name, emoji = emoji,
    photo = photo, grams = grams, kcal = kcal, protein = protein, carbs = carbs, fat = fat,
    nutrients = runCatching { json.decodeFromString<Map<String, Double>>(nutrients) }.getOrDefault(emptyMap()),
    ingredients = runCatching { json.decodeFromString<List<Ingredient>>(ingredients) }.getOrDefault(emptyList()),
    source = source, note = note, favorite = favorite,
    status = runCatching { EntryStatus.valueOf(status) }.getOrDefault(EntryStatus.OK), error = error, createdAt = createdAt,
)

@Dao
interface EntryDao {
    @Query("SELECT * FROM entries WHERE date = :date ORDER BY time")
    fun day(date: String): Flow<List<EntryRow>>

    @Query("SELECT * FROM entries WHERE date BETWEEN :from AND :to ORDER BY date, time")
    fun range(from: String, to: String): Flow<List<EntryRow>>

    @Query("SELECT * FROM entries WHERE date BETWEEN :from AND :to ORDER BY date, time")
    suspend fun rangeOnce(from: String, to: String): List<EntryRow>

    @Query("SELECT * FROM entries ORDER BY date, time")
    suspend fun all(): List<EntryRow>

    @Query("SELECT * FROM entries WHERE id = :id")
    suspend fun get(id: String): EntryRow?

    @Query("SELECT * FROM entries WHERE id = :id")
    fun watch(id: String): Flow<EntryRow?>

    @Query("SELECT * FROM entries WHERE status = 'OK' ORDER BY createdAt DESC LIMIT 400")
    fun recent(): Flow<List<EntryRow>>

    @Query("SELECT * FROM entries WHERE favorite = 1 ORDER BY name")
    fun favorites(): Flow<List<EntryRow>>

    @Query("SELECT MIN(date) FROM entries")
    suspend fun firstDate(): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(row: EntryRow)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putAll(rows: List<EntryRow>)

    @Query("DELETE FROM entries WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM entries")
    suspend fun clear()
}

@Dao
interface WeightDao {
    @Query("SELECT * FROM weights ORDER BY date")
    fun all(): Flow<List<WeightRow>>

    @Query("SELECT * FROM weights ORDER BY date")
    suspend fun allOnce(): List<WeightRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(row: WeightRow)

    @Query("DELETE FROM weights WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM weights")
    suspend fun clear()
}

@Dao
interface ChatDao {
    @Query("SELECT * FROM chat ORDER BY id")
    fun all(): Flow<List<ChatRow>>

    @Query("SELECT * FROM chat ORDER BY id DESC LIMIT :n")
    suspend fun last(n: Int): List<ChatRow>

    @Insert
    suspend fun put(row: ChatRow): Long

    @Query("DELETE FROM chat")
    suspend fun clear()
}

@Database(entities = [EntryRow::class, WeightRow::class, ChatRow::class], version = 1, exportSchema = false)
abstract class Db : RoomDatabase() {
    abstract fun entries(): EntryDao
    abstract fun weights(): WeightDao
    abstract fun chat(): ChatDao

    companion object {
        fun open(context: Context): Db = Room.databaseBuilder(context, Db::class.java, "eatezy.db").build()
    }
}
