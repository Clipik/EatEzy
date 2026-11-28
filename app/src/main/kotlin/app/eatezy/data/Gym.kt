package app.eatezy.data

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.util.UUID
import kotlin.math.floor
import kotlin.math.roundToLong

/** How an exercise is loaded: weight on the bar, body weight plus extra, or nothing (reps / "done"). */
enum class Load { WEIGHT, BODY, NONE }

data class Exercise(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    /** How to do it: "суммарный вес", "до 0–1 rir", "без учета веса платформы". */
    val note: String = "",
    /** Smallest weight step, kg (2.5 for a plate; 5 for a pair of dumbbells logged as a sum). */
    val step: Double = 2.5,
    /** Top of the rep range: reaching it in every set suggests the next weight. */
    val top: Int = 10,
    val load: Load = Load.WEIGHT,
)

/** One set. [kg] is the whole weight (two dumbbells summed); for [Load.BODY] it is the extra weight. */
data class GymSet(
    val id: String = UUID.randomUUID().toString(),
    val workoutId: String,
    val exerciseId: String,
    /** Order inside the workout. */
    val idx: Int,
    val kg: Double? = null,
    val body: Boolean = false,
    /** Can be fractional ("6,5" = a half rep); null when unknown or not counted ("готово"). */
    val reps: Double? = null,
    val note: String = "",
    /** When it was logged (epoch ms); null for imported sets. */
    val at: Long? = null,
)

/** A planned slot of the workout: one exercise (or a choice between [options]) for [sets] sets. */
@Serializable
data class PlanItem(
    val options: List<String>,
    val sets: Int = 3,
    /** The exercise done today; one of [options] or a replacement. */
    val chosen: String? = null,
    val skipped: Boolean = false,
    /** Text kept from the notes ("4/3", "по одной (начиная с правой)"). */
    val label: String = "",
) {
    val exerciseId: String? get() = chosen ?: options.firstOrNull()
}

data class Program(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val pos: Int = 0,
    val items: List<PlanItem> = emptyList(),
)

data class Workout(
    val id: String = UUID.randomUUID().toString(),
    val date: LocalDate,
    val programId: String? = null,
    /** "Фулбади А"; for imported days without a program it may be empty. */
    val title: String = "",
    val start: Long? = null,
    /** Null while the workout is going on. */
    val end: Long? = null,
    val note: String = "",
    val plan: List<PlanItem> = emptyList(),
    /** Notes about one exercise on this day ("после 1 подхода на брусья с весом"), by exercise id. */
    val notes: Map<String, String> = emptyMap(),
    val source: String = "app",
)

/** A dated line that is not a set: body measurements, life notes ("Болезнь"), lines kept as they were. */
data class GymNote(
    val id: String = UUID.randomUUID().toString(),
    val date: LocalDate?,
    /** "life", "measure:<name>" or an exercise id. */
    val topic: String,
    val text: String,
)

/** Defaults from the name: dumbbells logged as a sum step by 5, calves/neck/abs count to 20. */
fun guessExercise(name: String, note: String = ""): Exercise {
    val s = (name + " " + note).lowercase()
    val high = listOf("икр", "шея", "шеи", "пресс", "скруч", "шраг", "предплеч", "запяст", "жук", "планк", "растяж")
    val dumbbells = "гантел" in s && "по очереди" !in s && "на каждую" !in s
    val load = when {
        listOf("планк", "растяж", "жук").any { it in s } -> Load.NONE
        listOf("подтягиван", "брусья", "в висе", "отжиман").any { it in s } -> Load.BODY
        else -> Load.WEIGHT
    }
    return Exercise(name = name, note = note, step = if (dumbbells) 5.0 else 2.5, top = if (high.any { it in s }) 20 else 10, load = load)
}

fun Double.kg(): String = fmt(2).replace('.', ',')

/** "80: 7 и 6 и 6; 60: 12" (the user's own notation), sets in order. */
fun formatSets(sets: List<GymSet>): String {
    if (sets.isEmpty()) return ""
    val groups = mutableListOf<MutableList<GymSet>>()
    for (s in sets.sortedBy { it.idx }) {
        val last = groups.lastOrNull()?.lastOrNull()
        if (last != null && last.kg == s.kg && last.body == s.body) groups.last() += s else groups += mutableListOf(s)
    }
    return groups.joinToString("; ") { g ->
        val head = g.first()
        val w = when {
            head.body && (head.kg ?: 0.0) > 0 -> "св+${head.kg!!.kg()}: "
            head.body -> "св: "
            head.kg != null -> "${head.kg.kg()}: "
            else -> ""
        }
        w + g.joinToString(" и ") { s -> (s.reps?.kg() ?: "✓") + if (s.note.isNotBlank()) " (${s.note})" else "" }
    }
}

/** Short form for summaries: "120 × 8·7·6". */
fun formatSetsShort(sets: List<GymSet>): String = formatSets(sets).replace(": ", " × ").replace(" и ", "·")

/** Estimated one-rep max (Epley); null past 12 reps, where it stops being honest. */
fun e1rm(kg: Double, reps: Double): Double? = if (reps <= 0 || reps > 12 || kg <= 0) null else if (reps <= 1) kg else kg * (1 + reps / 30)

/** The weight a set moves: extra weight plus body weight for pull-ups and dips. */
fun moved(s: GymSet, bodyKg: Double?): Double? = when {
    s.body -> bodyKg?.let { it + (s.kg ?: 0.0) }
    else -> s.kg
}

fun bestE1rm(sets: List<GymSet>, bodyKg: Double?): Double? =
    sets.mapNotNull { s -> s.reps?.let { r -> moved(s, bodyKg)?.let { e1rm(it, r) } } }.maxOrNull()

/** Weight × reps of a session, in kg. */
fun tonnage(sets: List<GymSet>, bodyKg: Double?): Double = sets.sumOf { s -> (moved(s, bodyKg) ?: 0.0) * (s.reps ?: 0.0) }

/** Body weight on a day: the last weigh-in on or before it, else the first after. */
fun bodyWeightOn(date: LocalDate, weights: List<WeightEntry>): Double? {
    if (weights.isEmpty()) return null
    val sorted = weights.sortedBy { it.date }
    return sorted.lastOrNull { !it.date.isAfter(date) }?.kg ?: sorted.first().kg
}

/** What the set screen offers: the weight in the field and the reps as a hint. */
data class Suggestion(val kg: Double?, val body: Boolean, val reps: Double?)

/**
 * Double progression, each exercise on its own. Compared with the last time this exercise was done:
 * the same weight and one more rep per set, until every set reaches [Exercise.top]; then the next
 * weight step, with the reps of the first time at the old weight. Nothing is remembered: if the user
 * does fewer reps, the next hint is again "last + 1".
 */
fun suggest(ex: Exercise, history: List<List<GymSet>>, setIndex: Int): Suggestion {
    val last = history.lastOrNull()?.sortedBy { it.idx }.orEmpty()
    if (last.isEmpty()) return Suggestion(null, ex.load == Load.BODY, null)
    val prev = last.getOrNull(setIndex) ?: last.last()
    val topKg = last.mapNotNull { it.kg }.maxOrNull()
    val atTop = last.filter { it.kg == topKg }
    val allTop = ex.load != Load.NONE && atTop.isNotEmpty() && last.size >= 2 &&
        last.all { (it.reps ?: 0.0) >= ex.top } && (topKg != null || ex.load == Load.BODY)
    if (allTop && ex.load == Load.WEIGHT && topKg != null) {
        val firstAtWeight = history.firstOrNull { s -> s.any { it.kg == topKg } }?.sortedBy { it.idx }
        val reps = firstAtWeight?.getOrNull(setIndex)?.reps ?: (ex.top - 4).toDouble()
        return Suggestion(roundToStep(topKg + ex.step, ex.step), false, reps.coerceAtMost(ex.top - 1.0))
    }
    val reps = prev.reps?.let { floor(it) + 1 }
    return Suggestion(prev.kg, prev.body, reps)
}

fun roundToStep(kg: Double, step: Double): Double = if (step <= 0) kg else (kg / step).roundToLong() * step

/** Next workout in the rotation: the one after the last finished workout's program. */
fun nextProgram(programs: List<Program>, workouts: List<Workout>): Program? {
    val sorted = programs.sortedBy { it.pos }
    if (sorted.isEmpty()) return null
    val last = workouts.filter { it.programId != null && (it.end != null || it.source == "import") }
        .maxWithOrNull(compareBy({ it.date }, { it.start ?: 0L }))
    val i = sorted.indexOfFirst { it.id == last?.programId }
    return if (i < 0) sorted.first() else sorted[(i + 1) % sorted.size]
}

/** Average rest between sets: gaps between log times inside one exercise, longer than 15 min ignored. */
fun averageRestSec(sets: List<GymSet>): Long? {
    val gaps = sets.filter { it.at != null }.sortedBy { it.at }.zipWithNext()
        .map { (a, b) -> (b.at!! - a.at!!) / 1000 }
        .filter { it in 10..900 }
    return if (gaps.isEmpty()) null else gaps.average().toLong()
}

/** 754 → "12:34", 4000 → "1:06:40". */
fun Long.clock(): String {
    val h = this / 3600; val m = (this % 3600) / 60; val s = this % 60
    return if (h > 0) "$h:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}" else "$m:${s.toString().padStart(2, '0')}"
}
