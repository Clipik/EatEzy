package app.eatezy.data

import kotlinx.serialization.Serializable
import java.time.LocalDate

/*
 * Import of the user's own workout notes (plain text). The numbers are read here, by strict rules,
 * never by the AI: "28.11.26 80кг: 6 и 6; 60кг: 6". The AI only gives the headers clean names, so the
 * same exercise written differently in two files becomes one ([HeaderName]). Without the AI the
 * headers are cleaned up locally. Nothing is dropped: every line that is not a set is kept as a note.
 */

enum class BlockKind { EXERCISE, BODYWEIGHT, MEASURE }

/** Clean name of a header, from the AI or the local fallback. Only names: the kind of block and the numbers are never left to the AI. */
@Serializable
data class HeaderName(val name: String, val note: String = "")

data class ParsedSet(val kg: Double?, val body: Boolean, val reps: Double?, val note: String)

/** One exercise on one day, as written in a file. */
data class ParsedSession(
    val file: Int,
    val line: Int,
    val header: String,
    val section: String,
    val date: LocalDate,
    val sets: List<ParsedSet>,
    val note: String,
)

data class ParsedProgram(val name: String, val items: List<Pair<Int, String>>, val labels: List<String>)

data class ParsedNote(val date: LocalDate?, val topic: String, val text: String)

data class ParsedNotes(
    val sessions: List<ParsedSession>,
    val weights: List<Pair<LocalDate, Double>>,
    val notes: List<ParsedNote>,
    /** Programs of each file, by file index. */
    val programs: Map<Int, List<ParsedProgram>>,
    val headers: List<String>,
    /** Headers whose block is about body weight or measurements, not sets. */
    val kinds: Map<String, BlockKind>,
    /** Newest date in each file: the newest file wins when two files disagree. */
    val newest: Map<Int, LocalDate>,
)

private val DATE = Regex("""^(\d{1,2})[.\s](\d{1,2})[.\s](\d{4}|\d{2})(?!\d)\s*:?\s*(.*)$""")
private val PROGRAM = Regex("""^\d+(/\d+)?\s+\S.*""")
private val KG = Regex("""(\d+(?:[.,]\d+)?)\s*кг""", RegexOption.IGNORE_CASE)
private val REP = Regex("""^\s*(\d+(?:[.,]\d+)?|\?)\s*(.*)$""")
private val CM = Regex("""\d\s*см""", RegexOption.IGNORE_CASE)
private val MEASURE_LINE = Regex("""^([^\d:]{2,}):\s*(.+)$""")

fun num(s: String) = s.replace(',', '.').toDoubleOrNull()

fun parseDate(d: String, m: String, y: String): LocalDate? = runCatching {
    LocalDate.of(if (y.length == 2) 2000 + y.toInt() else y.toInt(), m.toInt(), d.toInt())
}.getOrNull()

private fun clean(s: String) = s.trim().trim(':', ';', ',', '.', ' ').replace(Regex("""^\((.*)\)$"""), "$1").replace(Regex("""\s+"""), " ").trim()

/** "80кг (после...)", "собственный вес", "45кг(по 22,5кг)", "теперь по одной ноге 60кг". */
fun parseWeight(w: String): Triple<Double?, Boolean, String> {
    val low = w.lowercase()
    val body = "собственный вес" in low || "свой вес" in low
    val m = KG.find(w)
    val kg = m?.let { num(it.groupValues[1]) }
    var rest = if (m != null) w.removeRange(m.range) else w
    if (body) rest = rest.replace(Regex("собственный вес|свой вес", RegexOption.IGNORE_CASE), "")
    if ("без веса" in low) rest = rest.replace(Regex("без веса", RegexOption.IGNORE_CASE), "")
    return Triple(kg, body, clean(rest))
}

/** "6 и 5 пиздец устают и 4,5" → three sets; text after a number is that set's note. */
fun parseReps(r: String, kg: Double?, body: Boolean, groupNote: String): List<ParsedSet> {
    val tokens = splitOutside(r, Regex("""\s+и\s+""")).map { it.trim() }.filter { it.isNotEmpty() }
    return tokens.mapIndexed { i, tok ->
        val m = REP.find(tok)
        val reps = m?.groupValues?.get(1)?.let { if (it == "?") null else num(it) }
        val note = if (m != null) clean(m.groupValues[2]) else clean(tok)
        val full = listOf(if (i == 0) groupNote else "", note).filter { it.isNotBlank() }.joinToString("; ")
        ParsedSet(kg, body, reps, full)
    }
}

/** Splits by [sep], but not inside parentheses: "7 (рука устала и не сгибается) и 6". */
fun splitOutside(s: String, sep: Regex): List<String> {
    val out = mutableListOf<String>()
    var depth = 0; var startAt = 0; var i = 0
    while (i < s.length) {
        when (s[i]) { '(' -> depth++; ')' -> depth = maxOf(0, depth - 1) }
        if (depth == 0) {
            val m = sep.matchAt(s, i)
            if (m != null && m.range.first == i && m.value.isNotEmpty()) { out += s.substring(startAt, i); i = m.range.last + 1; startAt = i; continue }
        }
        i++
    }
    out += s.substring(startAt)
    return out
}

/** The part of a dated line after the date: "80кг: 6 и 6; 60кг: 6". */
fun parseSets(rest: String): List<ParsedSet> = splitOutside(rest, Regex(";")).map { it.trim() }.filter { it.isNotEmpty() }.flatMap { g ->
    val colon = splitOutside(g, Regex(":")).first().length.takeIf { it < g.length } ?: -1
    if (colon < 0) {
        // "готово", or reps without a weight.
        val (kg, body, note) = parseWeight(g)
        if (kg != null) listOf(ParsedSet(kg, body, null, note)) else parseReps(g, null, false, "")
    } else {
        val (kg, body, note) = parseWeight(g.substring(0, colon))
        parseReps(g.substring(colon + 1), kg, body, note)
    }
}

private fun isSection(line: String): Boolean {
    val letters = line.filter { it.isLetter() }
    return letters.length >= 4 && letters.all { it.isUpperCase() }
}

private fun sectionName(line: String): String =
    line.trim().lowercase().replace(Regex("^тренировка\\s+"), "").replaceFirstChar { it.uppercase() }.let { s ->
        // "фулбади а" → "Фулбади А": a one-letter last word is a letter name.
        s.split(' ').joinToString(" ") { w -> if (w.length == 1) w.uppercase() else w }
    }

/** Local guess when there is no AI: what the block is about. */
fun guessKind(header: String, lines: List<String>): BlockKind {
    val h = header.lowercase()
    if ("вес" in h && listOf("утром", "перед трен", "голодн", "тела").any { it in h }) return BlockKind.BODYWEIGHT
    if (lines.isNotEmpty() && lines.count { CM.containsMatchIn(it) } * 2 >= lines.size && lines.none { "кг:" in it.lowercase() }) return BlockKind.MEASURE
    return BlockKind.EXERCISE
}

private val GROUPS = Regex("""^(грудь|спина|ноги|плечи|пресс)\s+""", RegexOption.IGNORE_CASE)

/** "Ноги присед" → "присед": the muscle group word the program lines start with. */
fun stripGroup(name: String): String = name.trim().replace(GROUPS, "")

/** "Спина подтягивания или тяга вертикальная" → two options, without the AI. */
fun localOptions(item: String): List<String> {
    val base = localName(item).name
    val parts = base.split(Regex("""\s+или\s+|/""")).map { it.trim() }.filter { it.isNotEmpty() }
    if (parts.size < 2) return listOf(stripGroup(base).replaceFirstChar { it.uppercase() })
    val group = GROUPS.find(base)?.value.orEmpty()
    return parts.mapIndexed { i, p -> (if (i == 0) p else group + p).let(::stripGroup).replaceFirstChar { it.uppercase() } }
}

/** "Жим лежа горизонтальный (Смит/тренажер ...):" → name and note, without the AI. */
fun localName(header: String): HeaderName {
    val h = header.trim().trimEnd(':').trim()
    val paren = Regex("""\(([^)]*)\)""").findAll(h).map { it.groupValues[1].trim() }.toList()
    val name = h.replace(Regex("""\([^)]*\)"""), "").replace(Regex("""\s+"""), " ").trim()
    return HeaderName(name.replaceFirstChar { it.uppercase() }, paren.joinToString("; "))
}

private class Block(val file: Int, val section: String, var header: String, var kind: BlockKind? = null) {
    val dated = mutableListOf<Triple<Int, LocalDate, String>>()
    /** Text lines with the index of the dated line they come before. */
    val texts = mutableListOf<Pair<Int, String>>()
}

/**
 * Splits the files into blocks (a header and its dated lines), reads programs ("3 Ноги ... + 4 Икры")
 * and sections ("ТРЕНИРОВКА ФУЛБАДИ А"). Text lines inside a block are notes for the next date,
 * or for the last one at the end of a block.
 */
fun parseNotes(files: List<String>): ParsedNotes {
    val blocks = mutableListOf<Block>()
    val programs = mutableMapOf<Int, MutableList<ParsedProgram>>()
    val newest = mutableMapOf<Int, LocalDate>()
    for ((fi, text) in files.withIndex()) {
        val lines = text.replace("\r", "").split('\n')
        var section = ""
        var cur: Block? = null
        var last: Block? = null
        var pendingTitle: String? = null
        for ((li, raw) in lines.withIndex()) {
            val line = raw.trim()
            if (line.isEmpty()) { if (cur != null) last = cur; cur = null; continue }
            if (line.all { it.isDigit() }) continue
            if (line.startsWith("Порядок", ignoreCase = true)) continue
            if (PROGRAM.matches(line) && line.contains('+')) {
                val parts = line.split('+').map { it.trim() }.filter { it.isNotEmpty() }
                val items = parts.map { p ->
                    val m = Regex("""^(\d+)(/\d+)?\s+(.*)$""").find(p)
                    (m?.groupValues?.get(1)?.toIntOrNull() ?: 1) to (m?.groupValues?.get(3) ?: p)
                }
                val labels = parts.map { p -> Regex("""^\d+(/\d+)""").find(p)?.value ?: "" }
                val list = programs.getOrPut(fi) { mutableListOf() }
                list += ParsedProgram(pendingTitle?.let(::sectionName) ?: "Тренировка ${list.size + 1}", items, labels)
                pendingTitle = null; cur = null
                continue
            }
            val d = DATE.find(line)
            val date = d?.let { parseDate(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }
            if (d != null && date != null) {
                val rest = d.groupValues[4].trim()
                if (newest[fi]?.isBefore(date) != false) newest[fi] = date
                val mm = MEASURE_LINE.find(rest)
                // "10.02.26 Бицепс в напряжении: 36см" opens a measurement block of its own.
                if (mm != null && CM.containsMatchIn(mm.groupValues[2]) && KG.find(mm.groupValues[1]) == null) {
                    cur = Block(fi, section, mm.groupValues[1].trim(), BlockKind.MEASURE).also { blocks += it }
                    cur.dated += Triple(li, date, mm.groupValues[2].trim())
                    continue
                }
                if (cur == null) {
                    // Dates after a blank line continue the block above.
                    cur = last?.takeIf { it.file == fi } ?: Block(fi, section, "").also { blocks += it }
                }
                cur.dated += Triple(li, date, rest)
                continue
            }
            if (isSection(line)) { section = sectionName(line); cur = null; last = null; continue }
            if (line.startsWith("Тренировка", ignoreCase = true) && lines.drop(li + 1).firstOrNull { it.isNotBlank() }?.trim()?.let { PROGRAM.matches(it) && it.contains('+') } == true) {
                pendingTitle = line; continue
            }
            val c = cur
            when {
                c == null -> {
                    // A text line after a blank line: a new header, or a note for the block above
                    // ("Заменяем"), decided once we see whether dates follow it.
                    cur = Block(fi, section, line).also { blocks += it }
                }
                c.dated.isEmpty() && c.texts.isEmpty() && !c.header.endsWith(":") && line.endsWith(":") -> {
                    // "Рычажной гак..." then "Другой тип гака:" — still the header.
                    c.header = c.header + " " + line
                }
                line.endsWith(":") && c.dated.isNotEmpty() -> {
                    cur = Block(fi, section, line).also { blocks += it }
                }
                else -> c.texts += c.dated.size to line
            }
        }
    }

    // Blocks without dates are notes for the block before them (or the file title: dropped).
    val merged = mutableListOf<Block>()
    for (b in blocks) {
        if (b.dated.isEmpty()) {
            val prev = merged.lastOrNull { it.file == b.file }
            if (prev != null) {
                prev.texts += prev.dated.size to b.header
                b.texts.forEach { prev.texts += prev.dated.size to it.second }
            }
            continue
        }
        merged += b
    }

    val sessions = mutableListOf<ParsedSession>()
    val weights = mutableListOf<Pair<LocalDate, Double>>()
    val notes = mutableListOf<ParsedNote>()
    val kinds = mutableMapOf<String, BlockKind>()
    for (b in merged) {
        val kind = b.kind ?: guessKind(b.header, b.dated.map { it.third })
        if (b.header.isNotBlank()) kinds[b.header] = kind
        fun noteFor(i: Int): String = b.texts.filter { (at, _) -> at == i || (i == b.dated.lastIndex && at > i) }.joinToString("; ") { it.second }
        for ((i, dl) in b.dated.withIndex()) {
            val (li, date, rest) = dl
            val pre = noteFor(i)
            when (kind) {
                BlockKind.BODYWEIGHT -> {
                    val m = KG.find(rest)
                    val kg = m?.let { num(it.groupValues[1]) }
                    if (kg != null) weights += date to kg
                    val extra = clean(if (m != null) rest.removeRange(m.range) else rest)
                    listOf(pre, extra).filter { it.isNotBlank() }.forEach { notes += ParsedNote(date, "life", it) }
                }
                BlockKind.MEASURE -> {
                    notes += ParsedNote(date, "measure:" + localName(b.header).name, rest)
                    if (pre.isNotBlank()) notes += ParsedNote(date, "measure:" + localName(b.header).name, pre)
                }
                BlockKind.EXERCISE -> {
                    val sets = parseSets(rest)
                    if (b.header.isBlank()) {
                        notes += ParsedNote(date, "life", listOf(pre, rest).filter { it.isNotBlank() }.joinToString("; "))
                    } else {
                        sessions += ParsedSession(b.file, li, b.header, b.section, date, sets, pre)
                    }
                }
            }
        }
    }
    return ParsedNotes(sessions, weights, notes, programs, merged.map { it.header }.filter { it.isNotBlank() }.distinct(), kinds, newest)
}

/** Every number a line has, so tests can check that nothing was lost or changed. */
fun numbersIn(s: String): List<Double> = Regex("""\d+(?:[.,]\d+)?""").findAll(s).mapNotNull { num(it.value) }.toList()

// ---------- building the import ----------

data class GymImportResult(
    val exercises: List<Exercise>,
    val workouts: List<Workout>,
    val sets: List<GymSet>,
    val programs: List<Program>,
    val notes: List<GymNote>,
    val weights: List<WeightEntry>,
    /** Same exercise and day found twice with the same sets: kept once. */
    val duplicates: Int,
    /** Same exercise and day with different sets: what was kept and what was not. */
    val conflicts: List<String>,
)

private fun key(name: String) = name.lowercase().replace('ё', 'е').replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()

/**
 * Turns parsed notes into rows, using [names] (header → clean name) and the [existing] data,
 * so nothing that is already in the app is added twice.
 */
fun buildGymImport(
    parsed: ParsedNotes,
    names: Map<String, HeaderName>,
    programNames: Map<String, List<String>>,
    existingExercises: List<Exercise>,
    existingWorkouts: List<Workout>,
    existingSets: List<GymSet>,
    existingNotes: List<GymNote>,
    existingWeights: List<WeightEntry>,
    existingPrograms: List<Program>,
): GymImportResult {
    val byKey = existingExercises.associateBy { key(it.name) }.toMutableMap()
    val newExercises = mutableListOf<Exercise>()
    fun exercise(name: String, note: String = ""): Exercise {
        val k = key(name)
        byKey[k]?.let { return it }
        return guessExercise(name.trim().replaceFirstChar { it.uppercase() }, note).also { byKey[k] = it; newExercises += it }
    }
    fun nameOf(header: String) = names[header] ?: localName(header)

    // Sessions: one per exercise and day; the newest file wins a disagreement.
    val chosen = LinkedHashMap<Pair<String, LocalDate>, ParsedSession>()
    var duplicates = 0
    val conflicts = mutableListOf<String>()
    val fileRank = parsed.newest
    for (s in parsed.sessions.sortedWith(compareBy({ fileRank[it.file] }, { it.file }, { it.line }))) {
        val hn = nameOf(s.header)
        val ex = exercise(hn.name, hn.note)
        val k = ex.id to s.date
        val prev = chosen[k]
        if (prev == null) { chosen[k] = s; continue }
        // Twice on one day in the same file is the user's own record (two rounds), not a copy.
        if (prev.file == s.file) {
            chosen[k] = prev.copy(sets = prev.sets + s.sets, note = listOf(prev.note, s.note).filter { it.isNotBlank() }.joinToString("; "))
            continue
        }
        val a = prev.sets.toString(); val b = s.sets.toString()
        if (a == b) { duplicates++; if (s.note.isNotBlank() && prev.note.isBlank()) chosen[k] = prev.copy(note = s.note); continue }
        conflicts += "${ex.name} ${s.date}: «${fmtParsed(prev.sets)}» → «${fmtParsed(s.sets)}»"
        chosen[k] = s
    }

    // What is already in the app stays as it is.
    val dateOf = existingWorkouts.associate { it.id to it.date }
    val existingDays = existingSets.groupBy { it.exerciseId to dateOf[it.workoutId] }
    val workoutsByDate = existingWorkouts.associateBy { it.date }.toMutableMap()
    val newWorkouts = LinkedHashMap<LocalDate, Workout>()
    val sets = mutableListOf<GymSet>()
    val nextIdx = existingSets.groupBy { it.workoutId }.mapValues { (_, l) -> l.maxOf { it.idx } + 1 }.toMutableMap()
    val programTitles = existingPrograms.associateBy { key(it.name) }
    for ((k, s) in chosen.entries.sortedWith(compareBy({ it.key.second }, { it.value.file }, { it.value.line }))) {
        val (exId, date) = k
        val have = existingDays[exId to date]
        if (have != null) {
            val same = have.sortedBy { it.idx }.map { ParsedSet(it.kg, it.body, it.reps, it.note) }.toString() == s.sets.toString()
            if (same) duplicates++ else conflicts += "${byKey.values.first { it.id == exId }.name} $date: " + "уже в приложении, файл не применён"
            continue
        }
        val w = workoutsByDate[date] ?: newWorkouts.getOrPut(date) {
            Workout(date = date, title = s.section, programId = programTitles[key(s.section)]?.id, source = "import")
        }.also { workoutsByDate[date] = it }
        if (w.title.isBlank() && s.section.isNotBlank() && w.id in newWorkouts.values.map { it.id }) {
            newWorkouts[date] = w.copy(title = s.section).also { workoutsByDate[date] = it }
        }
        if (s.note.isNotBlank()) {
            val ww = workoutsByDate[date]!!
            val upd = ww.copy(notes = ww.notes + (exId to listOfNotNull(ww.notes[exId], s.note).joinToString("; ")))
            workoutsByDate[date] = upd
            // An existing day gets the note too: it is saved again with it.
            newWorkouts[date] = upd
        }
        val wid = workoutsByDate[date]!!.id
        for (p in s.sets) {
            val idx = nextIdx[wid] ?: 0
            nextIdx[wid] = idx + 1
            sets += GymSet(workoutId = wid, exerciseId = exId, idx = idx, kg = p.kg, body = p.body, reps = p.reps, note = p.note)
        }
    }

    // A program line names an exercise more briefly than its header in the notes ("Ноги рычажной гак тренажер"
    // vs "Рычажной гак тренажер без учета веса платформы"): match by words, the most recently done one wins.
    val lastDone = HashMap<String, LocalDate>()
    for ((k, _) in chosen) if (lastDone[k.first]?.isBefore(k.second) != false) lastDone[k.first] = k.second
    for (w in existingWorkouts) existingSets.filter { it.workoutId == w.id }.forEach { s -> if (lastDone[s.exerciseId]?.isBefore(w.date) != false) lastDone[s.exerciseId] = w.date }
    fun words(n: String) = key(stripGroup(n)).split(' ').filter { it.length > 1 }.toSet()
    fun matchSession(name: String): Exercise? {
        byKey[key(name)]?.let { return it }
        val w = words(name)
        if (w.isEmpty()) return null
        return byKey.values.filter { it.id in lastDone }.filter { e -> val ew = words(e.name); ew.containsAll(w) || w.containsAll(ew) }
            .maxByOrNull { lastDone[it.id]!! }
    }

    // Programs from the newest file, only when the app has none yet.
    val programs = mutableListOf<Program>()
    if (existingPrograms.isEmpty()) {
        val newestFile = parsed.programs.keys.maxByOrNull { fileRank[it] ?: LocalDate.MIN }
        parsed.programs[newestFile].orEmpty().forEachIndexed { pi, p ->
            val items = p.items.mapIndexed { ii, (n, text) ->
                val options = (programNames[text] ?: localOptions(text)).map { matchSession(it)?.id ?: exercise(it).id }.distinct()
                PlanItem(options = options, sets = n, label = p.labels.getOrNull(ii).orEmpty().takeIf { "/" in it }.orEmpty())
            }
            programs += Program(name = p.name, pos = pi, items = items)
        }
        // Workouts from the same file's sections get their program.
        val byTitle = programs.associateBy { key(it.name) }
        for ((d, w) in newWorkouts) byTitle[key(w.title)]?.let { newWorkouts[d] = w.copy(programId = it.id) }
    }

    val haveNotes = existingNotes.map { Triple(it.date, it.topic, it.text) }.toMutableSet()
    val notes = parsed.notes.mapNotNull { n ->
        val k = Triple(n.date, n.topic, n.text)
        if (!haveNotes.add(k)) null else GymNote(date = n.date, topic = n.topic, text = n.text)
    }
    val haveWeights = existingWeights.map { it.date }.toMutableSet()
    val weights = parsed.weights.mapNotNull { (d, kg) -> if (!haveWeights.add(d)) null else WeightEntry(date = d, kg = kg) }

    return GymImportResult(newExercises, newWorkouts.values.toList(), sets, programs, notes, weights, duplicates, conflicts)
}

private fun fmtParsed(sets: List<ParsedSet>) = formatSets(sets.mapIndexed { i, p -> GymSet(workoutId = "", exerciseId = "", idx = i, kg = p.kg, body = p.body, reps = p.reps, note = p.note) })
