package app.eatezy.data

import app.eatezy.health.DayActivity
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/** How far back an export or report goes; null days means everything. */
enum class Period(val days: Int?) {
    DAY(1), WEEK(7), MONTH(30), QUARTER(90), ALL(null);

    /** First day of the period that ends [today], or [first] (the first diary day) for all time. */
    fun from(today: LocalDate, first: LocalDate?): LocalDate =
        days?.let { today.minusDays(it - 1L) } ?: first?.takeIf { it.isBefore(today) } ?: today
}

/**
 * A plain-text (Markdown) report of [from]..[to] meant to be pasted into an AI chat:
 * profile, targets, a summary with averages, and every day with its foods, ingredients,
 * nutrients, steps and burned calories.
 */
fun aiReport(
    from: LocalDate,
    to: LocalDate,
    entries: List<FoodEntry>,
    weights: List<WeightEntry>,
    activity: Map<LocalDate, DayActivity>,
    prefs: Prefs,
    ru: Boolean,
    now: LocalDateTime = LocalDateTime.now(),
    healthOn: Boolean = activity.isNotEmpty(),
    sleep: List<SleepEntry> = emptyList(),
): String {
    fun tr(en: String, r: String) = if (ru) r else en
    fun name(key: String) = Nutrients[key]?.let { if (ru) it.ru else it.en } ?: key
    fun unit(key: String) = Nutrients[key]?.unit ?: ""
    fun num(v: Double) = v.fmt(if (v >= 100) 0 else if (v >= 10) 1 else 2)

    val today = now.toLocalDate()
    val ok = entries.filter { it.status == EntryStatus.OK && !it.date.isBefore(from) && !it.date.isAfter(to) }
    val byDay = ok.groupBy { it.date }
    val act = activity.filterKeys { !it.isBefore(from) && !it.isAfter(to) }.filterValues { it.steps > 0 || it.burnedKcal > 0 }
    val g = prefs.goals
    val p = prefs.profile
    val allDays = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.toList()
    val totals = byDay.mapValues { (_, l) -> l.fold(Totals()) { a, e -> a + e.totals } }
    // Averages count only finished days with food; today is partial.
    val fullDays = totals.filterKeys { it != today }
    val avgBase = fullDays.ifEmpty { totals }
    val avg = avgBase.values.fold(Totals()) { a, t -> a + t } / avgBase.size
    val nights = sleep.filter { !it.date.isBefore(from) && !it.date.isAfter(to) }.sortedBy { it.date }
    val nightsByDay = nights.groupBy { it.date }
    fun nightLine(n: SleepEntry) = "${n.bed?.toLocalTime()?.toString()?.take(5) ?: "?"} → ${n.wake?.toLocalTime()?.toString()?.take(5) ?: "?"}" +
        (n.minutes?.let { " (${it.hm()})" } ?: "") + if (n.source == "usual") " " + tr("[bedtime = usual, not marked]", "[отбой = обычный, не отмечен]") else ""
    val keys = ok.flatMap { it.nutrients.keys }.toSet().let { s -> Nutrients.all.map { it.key }.filter { it in s } }

    return buildString {
        appendLine("# EatEzy — " + tr("nutrition and activity report", "отчёт о питании и активности"))
        appendLine()
        appendLine(tr("Period", "Период") + ": $from — $to (${ChronoUnit.DAYS.between(from, to) + 1} " + tr("days", "дн.") + ")")
        appendLine(tr("Generated", "Сформирован") + ": ${now.withSecond(0).withNano(0)}")
        if (!to.isBefore(today)) appendLine(tr("Today ($today) is not over yet, its numbers are partial.", "Сегодня ($today) ещё не закончилось, данные за день неполные."))
        appendLine(
            tr(
                "Food values are estimates (mostly recognized by AI from photos or text). Days without entries may mean the user did not log, not that they did not eat.",
                "Значения еды — оценки (в основном распознаны ИИ по фото или тексту). Дни без записей могут означать, что пользователь не вёл дневник, а не что он не ел.",
            ),
        )
        appendLine()

        appendLine("## " + tr("Profile", "Профиль"))
        appendLine("- " + tr("Sex", "Пол") + ": " + if (p.male) tr("male", "мужской") else tr("female", "женский"))
        appendLine("- " + tr("Age", "Возраст") + ": ${today.year - p.birthYear} (${p.birthYear})")
        appendLine("- " + tr("Height", "Рост") + ": ${p.heightCm.fmt(0)} cm")
        appendLine("- " + tr("Weight in profile", "Вес в профиле") + ": ${p.weightKg.fmt(1)} kg")
        appendLine("- " + tr("Activity factor", "Коэффициент активности") + ": ${p.activity}")
        appendLine("- " + tr("Goal", "Цель") + ": ${p.rateKgWeek} kg/" + tr("week", "нед."))
        appendLine()

        appendLine("## " + tr("Daily targets", "Дневные цели") + " (" + (if (g.manual) tr("set manually", "заданы вручную") else tr("computed from profile", "рассчитаны по профилю")) + ")")
        appendLine("- kcal ${g.kcal.fmt(0)}, " + tr("protein", "белки") + " ${g.protein.fmt(0)} g, " + tr("carbs", "углеводы") + " ${g.carbs.fmt(0)} g, " + tr("fat", "жиры") + " ${g.fat.fmt(0)} g")
        val normed = keys.mapNotNull { k -> g.of(k)?.let { k to it } }
        if (normed.isNotEmpty()) {
            appendLine("- " + tr("Nutrients", "Нутриенты") + ": " + normed.joinToString(", ") { (k, v) ->
                "${name(k)} ${num(v)} ${unit(k)}" + if (Nutrients[k]?.limit == true) " (" + tr("max", "макс.") + ")" else ""
            })
        }
        appendLine()

        appendLine("## " + tr("Summary", "Сводка"))
        appendLine("- " + tr("Days with food logged", "Дней с записями еды") + ": ${totals.size} / ${allDays.size}")
        appendLine("- " + tr("Foods logged", "Записей еды") + ": ${ok.size}")
        if (totals.isNotEmpty()) {
            val label = if (fullDays.isNotEmpty()) tr("Average per logged day (today excluded)", "В среднем за день с записями (без сегодня)")
            else tr("Average per logged day", "В среднем за день с записями")
            appendLine("- $label: ${avg.kcal.fmt(0)} kcal (${pct(avg.kcal, g.kcal)}), P ${avg.protein.fmt(0)} g (${pct(avg.protein, g.protein)}), C ${avg.carbs.fmt(0)} g (${pct(avg.carbs, g.carbs)}), F ${avg.fat.fmt(0)} g (${pct(avg.fat, g.fat)})")
            val kcalDays = avgBase.values.map { it.kcal }
            appendLine("- kcal: min ${kcalDays.min().fmt(0)}, max ${kcalDays.max().fmt(0)}; " +
                tr("days within ±10% of target", "дней в пределах ±10% от цели") + ": ${kcalDays.count { kotlin.math.abs(it - g.kcal) <= g.kcal * 0.1 }}/${kcalDays.size}")
            if (keys.isNotEmpty()) {
                appendLine("- " + tr("Average nutrients per day", "Нутриенты в среднем за день") + ": " + keys.joinToString(", ") { k ->
                    val v = avg.nutrients[k] ?: 0.0
                    "${name(k)} ${num(v)} ${unit(k)}" + (g.of(k)?.let { " (${pct(v, it)})" } ?: "")
                })
            }
        }
        if (act.isNotEmpty()) {
            val full = act.filterKeys { it != today }.ifEmpty { act }
            appendLine("- " + tr("Average steps per day", "Шагов в среднем за день") + ": ${full.values.map { it.steps }.average().fmt(0)} (" +
                tr("days with data", "дней с данными") + ": ${act.size})")
            val burned = full.values.filter { it.burnedKcal > 0 }
            if (burned.isNotEmpty()) appendLine("- " + tr("Average burned per day", "Сожжено в среднем за день") + ": ${burned.map { it.burnedKcal }.average().fmt(0)} kcal")
            val both = full.filterKeys { it in fullDays && full[it]!!.burnedKcal > 0 }
            if (both.isNotEmpty()) appendLine("- " + tr("Average balance (eaten − burned) on days with both", "Средний баланс (съедено − сожжено) в дни с обоими данными") + ": " +
                both.map { (d, a) -> totals[d]!!.kcal - a.burnedKcal }.average().fmt(0) + " kcal")
        } else {
            appendLine("- " + tr("Steps and burned calories", "Шаги и сожжённые калории") + ": " +
                if (healthOn) tr("no data for this period", "нет данных за период") else tr("Health Connect is off", "Health Connect выключен"))
        }
        if (nights.isNotEmpty()) {
            val mins = nights.mapNotNull { it.minutes }
            val beds = nights.mapNotNull { it.bed?.toLocalTime() }
            val wakes = nights.mapNotNull { it.wake?.toLocalTime() }
            append("- " + tr("Sleep", "Сон") + ": " + tr("nights logged", "записано ночей") + " ${nights.size}")
            if (mins.isNotEmpty()) append(", " + tr("average", "в среднем") + " ${mins.average().toLong().hm()} (min ${mins.min().hm()}, max ${mins.max().hm()})")
            averageClock(beds)?.let { append(", " + tr("bedtime", "отбой") + " ~${it.toString().take(5)} (±${clockSpread(beds)} " + tr("min", "мин") + ")") }
            averageClock(wakes)?.let { append(", " + tr("wake-up", "подъём") + " ~${it.toString().take(5)} (±${clockSpread(wakes)} " + tr("min", "мин") + ")") }
            appendLine()
        } else appendLine("- " + tr("Sleep: not logged in this period", "Сон: за период не записан"))
        val inPeriod = weights.filter { !it.date.isBefore(from) && !it.date.isAfter(to) }.sortedBy { it.date }
        val before = weights.filter { it.date.isBefore(from) }.maxByOrNull { it.date }
        if (inPeriod.isNotEmpty()) {
            val start = before ?: inPeriod.first()
            appendLine("- " + tr("Weight", "Вес") + ": ${start.kg.fmt(1)} kg (${start.date}) → ${inPeriod.last().kg.fmt(1)} kg (${inPeriod.last().date}), " +
                tr("change", "изменение") + " ${(inPeriod.last().kg - start.kg).let { (if (it > 0) "+" else "") + it.fmt(1) }} kg")
        }
        val top = ok.groupBy { it.name.lowercase() }.values.sortedByDescending { it.size }.filter { it.size > 1 }.take(10)
        if (top.isNotEmpty()) appendLine("- " + tr("Most frequent foods", "Самые частые блюда") + ": " + top.joinToString(", ") { "${it.first().name} ×${it.size}" })
        appendLine()

        if (inPeriod.isNotEmpty()) {
            appendLine("## " + tr("Weight log", "Журнал веса"))
            for (w in inPeriod) appendLine("- ${w.date}: ${w.kg.fmt(1)} kg")
            appendLine()
        }

        appendLine("## " + tr("Days", "По дням"))
        appendLine(tr("Sleep of a day is the night that ended that morning; bed and wake times are approximate.", "Сон дня — ночь, закончившаяся утром этого дня; время отбоя и подъёма примерное."))
        for (d in allDays.reversed()) {
            val list = byDay[d].orEmpty().sortedBy { it.time }
            val a = act[d]
            appendLine()
            append("### $d, ${dayName(d, ru)}")
            if (d == today) append(" — " + tr("today, not over", "сегодня, день не закончен"))
            appendLine()
            if (list.isEmpty()) appendLine("- " + tr("No food logged", "Еда не записана"))
            else {
                val t = totals[d]!!
                appendLine("- " + tr("Eaten", "Съедено") + ": ${t.kcal.fmt(0)} kcal (${pct(t.kcal, g.kcal)}), P ${t.protein.fmt(1)} g, C ${t.carbs.fmt(1)} g, F ${t.fat.fmt(1)} g")
            }
            for (n in nightsByDay[d].orEmpty()) appendLine("- " + tr("Sleep", "Сон") + ": " + nightLine(n))
            if (a != null) {
                append("- " + tr("Steps", "Шаги") + ": ${a.steps}")
                if (a.burnedKcal > 0) {
                    append(", " + tr("burned", "сожжено") + " ${a.burnedKcal.fmt(0)} kcal")
                    totals[d]?.let { append(", " + tr("balance", "баланс") + " ${(it.kcal - a.burnedKcal).fmt(0)} kcal") }
                }
                appendLine()
            }
            if (list.isEmpty()) continue
            totals[d]!!.nutrients.filterValues { it > 0 }.takeIf { it.isNotEmpty() }?.let { n ->
                appendLine("- " + tr("Nutrients", "Нутриенты") + ": " + keys.filter { it in n }.joinToString(", ") { "${name(it)} ${num(n[it]!!)} ${unit(it)}" })
            }
            appendLine("- " + tr("Foods", "Блюда") + ":")
            for (e in list) {
                append("  - ${e.time.toString().take(5)} ${mealName(e.meal, ru)}: ${e.name}, ${e.grams.fmt(0)} g — ")
                append("${e.kcal.fmt(0)} kcal, P ${e.protein.fmt(1)}, C ${e.carbs.fmt(1)}, F ${e.fat.fmt(1)}")
                append(" [" + sourceName(e.source, ru) + "]")
                appendLine()
                if (e.note.isNotBlank() && e.source != "barcode") appendLine("    " + tr("note", "заметка") + ": ${e.note.trim().replace('\n', ' ').take(300)}")
                if (e.ingredients.size > 1 || (e.ingredients.size == 1 && !e.ingredients[0].name.equals(e.name, true))) {
                    for (i in e.ingredients) appendLine("    - ${i.name} ${i.grams.fmt(0)} g: ${i.kcal.fmt(0)} kcal, P ${i.protein.fmt(1)}, C ${i.carbs.fmt(1)}, F ${i.fat.fmt(1)}")
                }
                e.nutrients.filterValues { it > 0 }.takeIf { it.isNotEmpty() }?.let { n ->
                    appendLine("    " + tr("nutrients", "нутриенты") + ": " + keys.filter { it in n }.joinToString(", ") { "${name(it)} ${num(n[it]!!)} ${unit(it)}" })
                }
            }
        }
    }.trimEnd() + "\n"
}

/** Spread of clock times in minutes (standard deviation around midnight). */
private fun clockSpread(times: List<LocalTime>): Long {
    val m = times.map { (((it.toSecondOfDay() / 60) - 720 + 1440) % 1440).toDouble() }
    val avg = m.average()
    return kotlin.math.sqrt(m.sumOf { (it - avg) * (it - avg) } / m.size).toLong()
}

private fun pct(v: Double, target: Double) = if (target > 0) "${(v / target * 100).fmt(0)}%" else "—"

private fun dayName(d: LocalDate, ru: Boolean): String {
    val i = d.dayOfWeek.value - 1
    return if (ru) listOf("пн", "вт", "ср", "чт", "пт", "сб", "вс")[i] else d.dayOfWeek.name.lowercase().take(3)
}

private fun mealName(m: Meal, ru: Boolean) = if (!ru) m.key else when (m) {
    Meal.BREAKFAST -> "завтрак"
    Meal.LUNCH -> "обед"
    Meal.DINNER -> "ужин"
    Meal.SNACK -> "перекус"
}

private fun sourceName(s: String, ru: Boolean) = when (s) {
    "ai" -> if (ru) "ИИ" else "AI"
    "barcode" -> if (ru) "штрихкод" else "barcode"
    "manual" -> if (ru) "вручную" else "manual"
    "imported", "ai_estimated" -> if (ru) "импорт" else "imported"
    else -> s
}
