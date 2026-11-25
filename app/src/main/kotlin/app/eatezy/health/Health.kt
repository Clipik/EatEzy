package app.eatezy.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Mass
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import java.time.ZonedDateTime

data class DayActivity(val steps: Long = 0, val burnedKcal: Double = 0.0)

/** Steps, burned calories and weight from Health Connect (Samsung Health, Google Fit, watches write there). */
class Health(private val context: Context) {
    val permissions = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(WeightRecord::class),
        HealthPermission.getWritePermission(WeightRecord::class),
    )

    val available: Boolean
        get() = runCatching { HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE }.getOrDefault(false)

    private val client by lazy { HealthConnectClient.getOrCreate(context) }

    fun permissionContract() = PermissionController.createRequestPermissionResultContract()

    suspend fun granted(): Boolean = available && runCatching {
        client.permissionController.getGrantedPermissions().containsAll(
            setOf(HealthPermission.getReadPermission(StepsRecord::class)),
        )
    }.getOrDefault(false)

    private fun range(from: LocalDate, toExclusive: LocalDate): TimeRangeFilter {
        val zone = ZoneId.systemDefault()
        return TimeRangeFilter.between(from.atStartOfDay(zone).toInstant(), toExclusive.atStartOfDay(zone).toInstant())
    }

    /** One day; for today the burned value is what was burned so far. */
    suspend fun day(date: LocalDate): DayActivity = runCatching {
        val res = client.aggregate(
            AggregateRequest(
                setOf(StepsRecord.COUNT_TOTAL, TotalCaloriesBurnedRecord.ENERGY_TOTAL, ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL),
                range(date, date.plusDays(1)),
            ),
        )
        val total = res[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories
        val active = res[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories
        DayActivity(res[StepsRecord.COUNT_TOTAL] ?: 0, total ?: active ?: 0.0)
    }.getOrDefault(DayActivity())

    /** Steps per day in [from, to]. */
    suspend fun steps(from: LocalDate, to: LocalDate): Map<LocalDate, Long> = runCatching {
        client.aggregateGroupByPeriod(
            AggregateGroupByPeriodRequest(
                setOf(StepsRecord.COUNT_TOTAL),
                TimeRangeFilter.between(from.atStartOfDay(), to.plusDays(1).atStartOfDay()),
                Period.ofDays(1),
            ),
        ).associate { it.startTime.toLocalDate() to (it.result[StepsRecord.COUNT_TOTAL] ?: 0L) }
    }.getOrDefault(emptyMap())

    suspend fun weights(from: LocalDate): List<Pair<LocalDate, Double>> = runCatching {
        client.readRecords(
            ReadRecordsRequest(WeightRecord::class, range(from, LocalDate.now().plusDays(1))),
        ).records.map { it.time.atZone(ZoneId.systemDefault()).toLocalDate() to it.weight.inKilograms }
    }.getOrDefault(emptyList())

    suspend fun writeWeight(date: LocalDate, kg: Double) {
        runCatching {
            val t = ZonedDateTime.now().with(date.atTime(9, 0)).toInstant()
            client.insertRecords(
                listOf(WeightRecord(time = t, zoneOffset = null, weight = Mass.kilograms(kg), metadata = Metadata.manualEntry())),
            )
        }
    }
}
