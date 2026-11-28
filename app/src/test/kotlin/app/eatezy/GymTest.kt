package app.eatezy

import app.eatezy.data.Exercise
import app.eatezy.data.GymSet
import app.eatezy.data.Load
import app.eatezy.data.Program
import app.eatezy.data.Workout
import app.eatezy.data.buildGymImport
import app.eatezy.data.formatSets
import app.eatezy.data.nextProgram
import app.eatezy.data.numbersIn
import app.eatezy.data.parseNotes
import app.eatezy.data.parseSets
import app.eatezy.data.suggest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

class GymTest {
    @Test fun readsSetLines() {
        val s = parseSets("80кг: 6 и 6; 60кг: 6")
        assertEquals(listOf(80.0, 80.0, 60.0), s.map { it.kg })
        assertEquals(listOf(6.0, 6.0, 6.0), s.map { it.reps })

        val b = parseSets("собственный вес: 8 и 6 и 4")
        assertTrue(b.all { it.body && it.kg == null })

        val n = parseSets("65кг: 7 (рука устала и не сгибается); 55кг: 9 и 7")
        assertEquals("рука устала и не сгибается", n[0].note)
        assertEquals(3, n.size)

        val d = parseSets("45кг(по 22,5кг): 9 и 6,5 и 6")
        assertEquals(45.0, d[0].kg)
        assertEquals(6.5, d[1].reps)
        assertEquals("по 22,5кг", d[0].note)

        val f = parseSets("110кг: 10 и 7 пиздец устают квадры и 8")
        assertEquals(listOf(10.0, 7.0, 8.0), f.map { it.reps })
        assertEquals("пиздец устают квадры", f[1].note)

        val g = parseSets("готово")
        assertEquals(1, g.size); assertEquals(null, g[0].reps); assertEquals("готово", g[0].note)

        assertEquals(listOf(20.0), parseSets("20").map { it.reps })
        val q = parseSets("собственный вес:  ? и 6 и 10")
        assertEquals(listOf(null, 6.0, 10.0), q.map { it.reps })
    }

    @Test fun blocksNotesAndPrograms() {
        val text = """
            Таиланд тренировки

            Тренировка фулбади А
            3 Ноги гак + 3 Спина подтягивания или тяга вертикальная

            Тренировка фулбади Б
            4/3 Ноги разгибания сидя по одной + 2 шея

            ТРЕНИРОВКА ФУЛБАДИ А
            Тяга на спину вертикальная:
            04.10.26 80кг: 5; 70кг: 7 и 7
            Теперь за спину завожу
            16.10.26 65кг: 10 и 7 и 8

            Заменяем

            Вес утром:
            06.10.26: 77.40кг
            Болезнь
            12.10.26 75.2кг
            18.10.26 1 час на сапе

            10.02.26 Талия: 75 см
            06.05.26 77см
        """.trimIndent()
        val p = parseNotes(listOf(text))
        assertEquals(2, p.sessions.size)
        assertEquals("Фулбади А", p.sessions[0].section)
        assertTrue(p.sessions[1].note.startsWith("Теперь за спину завожу"))
        assertEquals(listOf(77.4, 75.2), p.weights.map { it.second })
        assertTrue(p.notes.any { it.text == "Болезнь" && it.date == LocalDate.of(2026, 10, 12) })
        assertTrue(p.notes.any { it.text == "1 час на сапе" })
        assertEquals(2, p.notes.count { it.topic == "measure:Талия" })
        assertEquals(listOf("Фулбади А", "Фулбади Б"), p.programs[0]!!.map { it.name })
        // "Заменяем" belongs to the last day of the block above.
        assertTrue(p.sessions[1].note.contains("Заменяем"))
    }

    @Test fun repeatedDaysAreKeptOnce() {
        val a = "Жим лёжа:\n20.11.26 80кг: 8 и 7 и 5\n24.11.26 80кг: 8 и 8"
        val b = "Жим лежа:\n24.11.26 80кг: 8 и 8\n28.11.26 82,5кг: 6"
        val p = parseNotes(listOf(a, b))
        val r = buildGymImport(p, emptyMap(), emptyMap(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        assertEquals(1, r.exercises.size)
        assertEquals(3, r.workouts.size)
        assertEquals(1, r.duplicates)
        assertEquals(6, r.sets.size)
        // Importing again adds nothing.
        val again = buildGymImport(p, emptyMap(), emptyMap(), r.exercises, r.workouts, r.sets, r.notes, r.weights, r.programs)
        assertEquals(0, again.sets.size)
        assertEquals(0, again.workouts.size)
    }

    @Test fun doubleProgression() {
        val ex = Exercise(name = "Жим", top = 10, step = 2.5)
        fun s(kg: Double, vararg reps: Int) = reps.mapIndexed { i, r -> GymSet(workoutId = "w", exerciseId = ex.id, idx = i, kg = kg, reps = r.toDouble()) }
        val h1 = listOf(s(80.0, 8, 7, 5))
        assertEquals(80.0, suggest(ex, h1, 0).kg)
        assertEquals(9.0, suggest(ex, h1, 0).reps)
        assertEquals(6.0, suggest(ex, h1, 2).reps)
        // Every set at the top: next weight, reps of the first time at the old weight.
        val h2 = listOf(s(80.0, 8, 7, 5), s(80.0, 10, 10, 10))
        assertEquals(82.5, suggest(ex, h2, 0).kg)
        assertEquals(8.0, suggest(ex, h2, 0).reps)
        // Heavier weight, fewer reps: grow reps from there.
        val h3 = h2 + listOf(s(82.5, 6, 5, 5))
        assertEquals(82.5, suggest(ex, h3, 1).kg)
        assertEquals(6.0, suggest(ex, h3, 1).reps)
    }

    @Test fun rotation() {
        val a = Program(name = "А", pos = 0); val b = Program(name = "Б", pos = 1); val c = Program(name = "Выходная", pos = 2)
        val list = listOf(a, b, c)
        assertEquals(a, nextProgram(list, emptyList()))
        val w = Workout(date = LocalDate.now(), programId = c.id, start = 1, end = 2)
        assertEquals(a, nextProgram(list, listOf(w)))
        assertEquals(c, nextProgram(list, listOf(w.copy(programId = b.id))))
    }

    @Test fun formatsLikeTheNotes() {
        val sets = listOf(80.0 to 7.0, 80.0 to 6.0, 60.0 to 12.0).mapIndexed { i, (k, r) -> GymSet(workoutId = "", exerciseId = "", idx = i, kg = k, reps = r) }
        assertEquals("80: 7 и 6; 60: 12", formatSets(sets))
    }

    /** Set GYM_NOTES to a folder with the user's .txt notes to check the parser on them. */
    @Test fun realNotes() {
        val dir = System.getenv("GYM_NOTES")?.let(::File)?.takeIf { it.isDirectory } ?: return
        val files = dir.listFiles { f -> f.extension == "txt" }!!.sortedBy { it.name }
        val texts = files.map { it.readText() }
        val p = parseNotes(texts)
        // Every number of every dated exercise line is in the parsed sets (as weight, reps or note).
        var lines = 0
        for (s in p.sessions) {
            val src = texts[s.file].replace("\r", "").split('\n')[s.line]
            val rest = src.trim().replace(Regex("""^\d{1,2}[.\s]\d{1,2}[.\s](\d{4}|\d{2})\s*:?"""), "")
            val got = s.sets.flatMap { listOfNotNull(it.kg, it.reps) + numbersIn(it.note) } + numbersIn(s.note)
            val want = numbersIn(rest)
            val missing = want.toMutableList().also { m -> got.forEach { g -> m.remove(g) } }
            assertTrue("${files[s.file].name}:${s.line + 1} «$src» lost $missing from ${s.sets}", missing.isEmpty())
            lines++
        }
        val r = buildGymImport(p, emptyMap(), emptyMap(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        val out = StringBuilder()
        out.appendLine("lines $lines, sessions ${p.sessions.size}, exercises ${r.exercises.size}, workouts ${r.workouts.size}, sets ${r.sets.size}, weights ${r.weights.size}, notes ${r.notes.size}, dup ${r.duplicates}, conflicts ${r.conflicts.size}")
        r.conflicts.forEach { out.appendLine("conflict: $it") }
        r.exercises.sortedBy { it.name }.forEach { e -> out.appendLine("ex: ${e.name} | ${e.note} | step ${e.step} top ${e.top} ${e.load}") }
        r.programs.forEach { pr -> out.appendLine("program ${pr.name}: " + pr.items.joinToString(" + ") { it.sets.toString() + " " + it.options.joinToString("/") { id -> r.exercises.first { e -> e.id == id }.name } }) }
        p.notes.forEach { out.appendLine("note ${it.date} ${it.topic}: ${it.text}") }
        r.workouts.sortedBy { it.date }.forEach { w ->
            out.appendLine("${w.date} ${w.title}: " + r.sets.filter { it.workoutId == w.id }.groupBy { it.exerciseId }.entries.joinToString(" | ") { (id, l) ->
                r.exercises.first { it.id == id }.name + " " + formatSets(l) + (w.notes[id]?.let { " [$it]" } ?: "")
            })
        }
        File("build/gym-import.txt").writeText(out.toString())
        assertTrue(r.sets.isNotEmpty())
        assertTrue(r.exercises.none { it.load == Load.WEIGHT && it.name.isBlank() })
    }
}
