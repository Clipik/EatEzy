package app.eatezy.data

import android.content.Context
import app.eatezy.ai.AiClient
import app.eatezy.ai.nameGym
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import androidx.room.withTransaction
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

/** Where the import of notes is. */
sealed interface ImportState {
    data object Idle : ImportState
    /** [done] of [total] AI batches named. */
    data class Working(val done: Int, val total: Int) : ImportState
    data class Review(val result: GymImportResult, val aiFailed: Boolean) : ImportState
    data class Failed(val message: String) : ImportState
}

/** Everything the gym tab shows, in one snapshot. */
data class GymData(
    val exercises: List<Exercise> = emptyList(),
    val workouts: List<Workout> = emptyList(),
    val sets: List<GymSet> = emptyList(),
    val programs: List<Program> = emptyList(),
    val notes: List<GymNote> = emptyList(),
) {
    val exercise: Map<String, Exercise> by lazy { exercises.associateBy { it.id } }
    val active: Workout? get() = workouts.lastOrNull { it.end == null && it.source == "app" }
    val setsByWorkout: Map<String, List<GymSet>> by lazy { sets.groupBy { it.workoutId } }
    private val dateOf by lazy { workouts.associate { it.id to it.date } }

    /** Sessions of an exercise, oldest first: each one the sets of one workout. */
    fun history(exerciseId: String, exceptWorkout: String? = null): List<Pair<Workout, List<GymSet>>> {
        val byId = workouts.associateBy { it.id }
        return sets.filter { it.exerciseId == exerciseId && it.workoutId != exceptWorkout }
            .groupBy { it.workoutId }
            .mapNotNull { (wid, l) -> byId[wid]?.let { it to l.sortedBy { s -> s.idx } } }
            .sortedWith(compareBy({ it.first.date }, { it.first.start ?: 0L }))
    }

    fun dateOfSet(s: GymSet): LocalDate? = dateOf[s.workoutId]
}

class GymRepo(private val context: Context, private val db: Db, private val settings: Settings, private val ai: AiClient) {
    private val dao = db.gym()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val data: Flow<GymData> = kotlinx.coroutines.flow.combine(
        dao.exercises(), dao.workouts(), dao.sets(), dao.programs(), dao.notes(),
    ) { e, w, s, p, n ->
        GymData(e.map { it.toModel() }, w.map { it.toModel() }, s.map { it.toModel() }, p.map { it.toModel() }, n.map { it.toModel() })
    }

    private suspend fun snapshot() = GymData(
        dao.exercisesOnce().map { it.toModel() }, dao.workoutsOnce().map { it.toModel() }, dao.setsOnce().map { it.toModel() },
        dao.programsOnce().map { it.toModel() }, dao.notesOnce().map { it.toModel() },
    )

    // ---------- workout ----------

    /** Starts [program] today; each "или" slot starts with the option done last time. */
    suspend fun start(program: Program): Workout {
        val d = snapshot()
        val plan = program.items.map { item ->
            val last = item.options.maxByOrNull { id -> d.history(id).lastOrNull()?.first?.date ?: LocalDate.MIN }
            item.copy(chosen = if (item.options.size > 1) last else null, skipped = false)
        }
        val w = Workout(date = LocalDate.now(), programId = program.id, title = program.name, start = System.currentTimeMillis(), plan = plan)
        dao.putWorkouts(listOf(w.toRow()))
        return w
    }

    suspend fun save(w: Workout) = dao.putWorkouts(listOf(w.toRow()))

    suspend fun logSet(w: Workout, exerciseId: String, kg: Double?, body: Boolean, reps: Double?, note: String) {
        val idx = (dao.setsOnce().filter { it.workoutId == w.id }.maxOfOrNull { it.idx } ?: -1) + 1
        dao.putSets(listOf(GymSet(workoutId = w.id, exerciseId = exerciseId, idx = idx, kg = kg, body = body, reps = reps, note = note.trim(), at = System.currentTimeMillis()).toRow()))
    }

    suspend fun deleteSet(id: String) = dao.deleteSet(id)

    suspend fun finish(w: Workout, note: String) {
        val sets = dao.setsOnce().filter { it.workoutId == w.id }
        if (sets.isEmpty()) { dao.deleteWorkout(w.id); return }
        val end = sets.mapNotNull { it.at }.maxOrNull()?.coerceAtLeast(w.start ?: 0) ?: System.currentTimeMillis()
        save(w.copy(end = end, note = note.trim()))
    }

    suspend fun deleteWorkout(id: String) { dao.deleteSetsOf(id); dao.deleteWorkout(id) }

    /** Another exercise in slot [index]: today only, or in the program too. */
    suspend fun replace(w: Workout, index: Int, exerciseId: String, forever: Boolean) {
        val item = w.plan.getOrNull(index) ?: return
        val old = item.exerciseId
        save(w.copy(plan = w.plan.toMutableList().also { it[index] = item.copy(chosen = exerciseId) }))
        if (forever && w.programId != null) {
            val p = dao.programsOnce().map { it.toModel() }.firstOrNull { it.id == w.programId } ?: return
            val items = p.items.map { pi ->
                if (old != null && old in pi.options) pi.copy(options = pi.options.map { if (it == old) exerciseId else it }.distinct()) else pi
            }
            saveProgram(p.copy(items = items))
        }
    }

    suspend fun exerciseNamed(name: String): Exercise {
        val k = name.trim().lowercase()
        dao.exercisesOnce().firstOrNull { it.name.lowercase() == k }?.let { return it.toModel() }
        return guessExercise(name.trim().replaceFirstChar { it.uppercase() }).also { saveExercise(it) }
    }

    suspend fun saveExercise(e: Exercise) = dao.putExercises(listOf(e.toRow()))
    suspend fun saveProgram(p: Program) = dao.putPrograms(listOf(p.toRow()))
    suspend fun deleteProgram(id: String) = dao.deleteProgram(id)
    suspend fun saveNote(n: GymNote) = dao.putNotes(listOf(n.toRow()))

    suspend fun clear() {
        dao.clearSets(); dao.clearWorkouts(); dao.clearExercises(); dao.clearPrograms(); dao.clearNotes()
    }

    suspend fun all(): GymData = snapshot()

    /** Restores a backup: rows with the same ids are replaced, nothing is duplicated. */
    suspend fun restore(d: GymData) {
        dao.putExercises(d.exercises.map { it.toRow() }); dao.putPrograms(d.programs.map { it.toRow() })
        dao.putWorkouts(d.workouts.map { it.toRow() }); dao.putSets(d.sets.map { it.toRow() }); dao.putNotes(d.notes.map { it.toRow() })
    }

    // ---------- import of notes ----------

    private val _import = MutableStateFlow<ImportState>(ImportState.Idle)
    val import: StateFlow<ImportState> = _import
    private val _importing = MutableStateFlow(false)

    /** True while the import runs; keeps [app.eatezy.RecognizeService] (and the process) alive. */
    val importing: StateFlow<Boolean> = _importing

    private val dir get() = File(context.filesDir, "gym_import")
    private val namesFile get() = File(dir, "names.json")

    /** Saves the texts first, so the import goes on after the app is closed or killed. */
    fun startImport(texts: List<String>) {
        dir.deleteRecursively(); dir.mkdirs()
        texts.forEachIndexed { i, t -> File(dir, "$i.txt").writeText(t) }
        run()
    }

    /** An import cut short by the process dying: go on from the batches already named. */
    fun resume() { if (File(dir, "0.txt").exists() && _import.value is ImportState.Idle) run() }

    fun discardImport() { dir.deleteRecursively(); _import.value = ImportState.Idle }

    private fun run() {
        if (_importing.value) return
        _importing.value = true
        app.eatezy.RecognizeService.start(context)
        scope.launch {
            try {
                _import.value = prepare()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _import.value = ImportState.Failed(e.message ?: e.toString())
            } finally {
                _importing.value = false
            }
        }
    }

    @kotlinx.serialization.Serializable
    private data class NameCache(val headers: Map<String, HeaderName> = emptyMap(), val programs: Map<String, List<String>> = emptyMap())

    private suspend fun prepare(): ImportState {
        val texts = generateSequence(0) { it + 1 }.map { File(dir, "$it.txt") }.takeWhile { it.exists() }.map { it.readText() }.toList()
        val parsed = parseNotes(texts)
        var cache = runCatching { json.decodeFromString<NameCache>(namesFile.readText()) }.getOrDefault(NameCache())
        val existing = snapshot()
        val items = parsed.programs.values.flatten().flatMap { p -> p.items.map { it.second } }.distinct()
        val headers = parsed.headers.filter { parsed.kinds[it] == BlockKind.EXERCISE }
        val todoH = headers.filter { it !in cache.headers }
        val todoP = items.filter { it !in cache.programs }
        val batches = todoH.chunked(30).map { it to emptyList<String>() } + todoP.chunked(30).map { emptyList<String>() to it }
        val cfg = settings.now().ai.forCoach()
        var aiFailed = false
        if (cfg.chain.isNotEmpty()) {
            for ((i, b) in batches.withIndex()) {
                _import.value = ImportState.Working(i, batches.size)
                val known = (existing.exercises.map { it.name } + cache.headers.values.map { it.name } + cache.programs.values.flatten()).distinct()
                val got = runCatching { ai.nameGym(cfg, b.first, b.second, known) }.getOrNull()
                if (got == null) { aiFailed = true; continue }
                cache = NameCache(cache.headers + got.headers, cache.programs + got.programs)
                namesFile.writeText(json.encodeToString(cache))
            }
        } else if (batches.isNotEmpty()) aiFailed = true
        val weights = db.weights().allOnce().map { WeightEntry(it.id, LocalDate.parse(it.date), it.kg) }
        val result = buildGymImport(
            parsed, cache.headers, cache.programs, existing.exercises, existing.workouts, existing.sets, existing.notes, weights, existing.programs,
        )
        return ImportState.Review(result, aiFailed)
    }

    /** Writes the reviewed import into the app. */
    suspend fun confirmImport(): GymImportResult? {
        val r = (_import.value as? ImportState.Review)?.result ?: return null
        withContext(Dispatchers.IO) {
            db.runInTransactionSuspend {
                dao.putExercises(r.exercises.map { it.toRow() })
                dao.putPrograms(r.programs.map { it.toRow() })
                dao.putWorkouts(r.workouts.map { it.toRow() })
                dao.putSets(r.sets.map { it.toRow() })
                dao.putNotes(r.notes.map { it.toRow() })
                r.weights.forEach { db.weights().put(WeightRow(it.id, it.date.toString(), it.kg)) }
            }
        }
        discardImport()
        return r
    }
}

private suspend fun Db.runInTransactionSuspend(block: suspend () -> Unit) = withTransaction { block() }

/** Workouts of the gym tab's period. */
enum class GymPeriod(val days: Long?) { MONTH(30), HALF(182), YEAR(365), ALL(null) }

fun GymPeriod.from(today: LocalDate): LocalDate = days?.let { today.minusDays(it - 1) } ?: LocalDate.MIN
