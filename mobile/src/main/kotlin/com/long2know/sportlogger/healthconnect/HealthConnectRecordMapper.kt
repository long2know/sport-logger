package com.long2know.sportlogger.healthconnect

import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseRoute
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Velocity
import com.long2know.utilities.contract.CanonicalActivityValidator
import com.long2know.utilities.contract.proto.ActivityRecord
import com.long2know.utilities.contract.proto.ActivityType
import com.long2know.utilities.contract.proto.DoubleMeasurement
import com.long2know.utilities.contract.proto.LongMeasurement
import com.long2know.utilities.contract.proto.MeasurementSample
import com.long2know.utilities.contract.proto.PositionMeasurement
import com.long2know.utilities.contract.proto.Quality
import com.long2know.utilities.contract.proto.SourceKind
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import kotlin.math.roundToLong

internal class HealthConnectRecordMapper {
    fun plan(
        activity: ActivityRecord,
        phoneTitle: String?,
        phoneNotes: String?,
        includeRoute: Boolean,
    ): HealthConnectWritePlan {
        CanonicalActivityValidator.validateCompleted(activity)
        if (activity.endTimeUtcMs <= activity.startTimeUtcMs) {
            throw HealthConnectMappingException(
                HealthConnectErrorCode.INVALID_CANONICAL_ACTIVITY,
                "Health Connect requires a completed activity with positive duration",
            )
        }

        val start = Instant.ofEpochMilli(activity.startTimeUtcMs)
        val end = Instant.ofEpochMilli(activity.endTimeUtcMs)
        val device = device(activity)
        val route = route(activity, start, end)
        val drafts = mutableListOf<HealthConnectRecordDraft>()
        val mappingFailures = mutableListOf<HealthConnectFailure>()
        drafts +=
            exerciseSessionDraft(
                activity = activity,
                phoneTitle = phoneTitle,
                phoneNotes = phoneNotes,
                start = start,
                end = end,
                route = if (includeRoute) route else null,
                routeIncluded = includeRoute && route != null,
                device = device,
            )

        val heartRateSamples = heartRateSamples(activity, start, end)
        heartRateSamples.chunked(SERIES_CHUNK_SIZE).forEachIndexed { index, samples ->
            drafts += heartRateDraft(activity, index, samples, device)
        }

        stepsTotal(activity)?.let { count ->
            if (count in 1..MAX_STEPS) {
                drafts += stepsDraft(activity, count, start, end, device)
            } else {
                mappingFailures += invalidMetric("steps")
            }
        }
        distanceTotal(activity)?.let { distance ->
            if (distance in 0.0..MAX_DOUBLE_TOTAL) {
                drafts += distanceDraft(activity, distance, start, end, device)
            } else {
                mappingFailures += invalidMetric("distance")
            }
        }
        caloriesTotal(activity)?.let { calories ->
            if (calories in 0.0..MAX_DOUBLE_TOTAL) {
                drafts += caloriesDraft(activity, calories, start, end, device)
            } else {
                mappingFailures += invalidMetric("total calories")
            }
        }
        elevationTotal(activity)?.let { elevation ->
            if (elevation in -MAX_DOUBLE_TOTAL..MAX_DOUBLE_TOTAL) {
                drafts += elevationDraft(activity, elevation, start, end, device)
            } else {
                mappingFailures += invalidMetric("elevation gained")
            }
        }

        val speedSamples = speedSamples(activity, start, end)
        speedSamples.chunked(SERIES_CHUNK_SIZE).forEachIndexed { index, samples ->
            drafts += speedDraft(activity, index, samples, device)
        }

        val requiredPermissions =
            drafts.mapTo(linkedSetOf()) { it.requiredPermission }.apply {
                if (route != null) {
                    add(HealthConnectWritePermission.PHONE_EXERCISE_ROUTE)
                }
            }
        return HealthConnectWritePlan(
            drafts = drafts,
            requiredPermissions = requiredPermissions,
            presentRecordTypes = drafts.mapTo(linkedSetOf()) { it.recordType },
            routePresent = route != null,
            routeIncluded = includeRoute && route != null,
            mappingFailures = mappingFailures,
        )
    }

    private fun exerciseSessionDraft(
        activity: ActivityRecord,
        phoneTitle: String?,
        phoneNotes: String?,
        start: Instant,
        end: Instant,
        route: ExerciseRoute?,
        routeIncluded: Boolean,
        device: Device,
    ): HealthConnectRecordDraft {
        val title =
            phoneTitle.cleanText()
                ?: if (activity.hasRecordedName()) activity.recordedName.cleanText() else null
        val notes =
            phoneNotes.cleanText()
                ?: if (activity.hasRecordedDescription()) {
                    activity.recordedDescription.cleanText()
                } else {
                    null
                }
        val startOffset = zoneOffset(activity, start)
        val endOffset = zoneOffset(activity, end)
        val exerciseType = exerciseType(activity.activityType)
        val routeSignature =
            route?.route.orEmpty().joinToString(";") {
                listOf(
                        it.time.toEpochMilli().toString(),
                        stableDouble(it.latitude),
                        stableDouble(it.longitude),
                        it.altitude?.inMeters?.let(::stableDouble).orEmpty(),
                        it.horizontalAccuracy?.inMeters?.let(::stableDouble).orEmpty(),
                    )
                    .joinToString(",")
            }
        val signature =
            listOf(
                    "type=ExerciseSessionRecord",
                    "start=${start.toEpochMilli()}",
                    "end=${end.toEpochMilli()}",
                    "startOffset=${startOffset?.totalSeconds.orEmpty()}",
                    "endOffset=${endOffset?.totalSeconds.orEmpty()}",
                    "exerciseType=$exerciseType",
                    "title=${title.orEmpty()}",
                    "notes=${notes.orEmpty()}",
                    "routeIncluded=$routeIncluded",
                    "route=$routeSignature",
                    deviceSignature(device),
                )
                .joinToString("|")
        return HealthConnectRecordDraft(
            recordType = HealthConnectRecordType.EXERCISE_SESSION,
            providerRecordType = PROVIDER_EXERCISE_SESSION,
            chunkIndex = 0,
            requiredPermission = HealthConnectWritePermission.EXERCISE_SESSION,
            mappingSha256 = sha256(signature),
            device = device,
        ) { metadata ->
            ExerciseSessionRecord(
                startTime = start,
                startZoneOffset = startOffset,
                endTime = end,
                endZoneOffset = endOffset,
                metadata = metadata,
                exerciseType = exerciseType,
                title = title,
                notes = notes,
                exerciseRoute = route,
            )
        }
    }

    private fun heartRateDraft(
        activity: ActivityRecord,
        chunkIndex: Int,
        samples: List<HeartRateRecord.Sample>,
        device: Device,
    ): HealthConnectRecordDraft {
        val start = samples.first().time
        val end = samples.last().time
        val startOffset = zoneOffset(activity, start)
        val endOffset = zoneOffset(activity, end)
        val signature =
            buildString {
                append("type=HeartRateRecord|")
                append("start=").append(start.toEpochMilli()).append('|')
                append("end=").append(end.toEpochMilli()).append('|')
                append("startOffset=").append(startOffset?.totalSeconds.orEmpty()).append('|')
                append("endOffset=").append(endOffset?.totalSeconds.orEmpty()).append('|')
                append(deviceSignature(device)).append('|')
                samples.forEach {
                    append(it.time.toEpochMilli())
                        .append(',')
                        .append(it.beatsPerMinute)
                        .append(';')
                }
            }
        return HealthConnectRecordDraft(
            recordType = HealthConnectRecordType.HEART_RATE,
            providerRecordType = PROVIDER_HEART_RATE,
            chunkIndex = chunkIndex,
            requiredPermission = HealthConnectWritePermission.HEART_RATE,
            mappingSha256 = sha256(signature),
            device = device,
        ) { metadata ->
            HeartRateRecord(
                startTime = start,
                startZoneOffset = startOffset,
                endTime = end,
                endZoneOffset = endOffset,
                samples = samples,
                metadata = metadata,
            )
        }
    }

    private fun stepsDraft(
        activity: ActivityRecord,
        count: Long,
        start: Instant,
        end: Instant,
        device: Device,
    ): HealthConnectRecordDraft =
        intervalDraft(
            activity = activity,
            recordType = HealthConnectRecordType.STEPS,
            providerRecordType = PROVIDER_STEPS,
            permission = HealthConnectWritePermission.STEPS,
            valueSignature = count.toString(),
            start = start,
            end = end,
            device = device,
        ) { startOffset, endOffset, metadata ->
            StepsRecord(
                startTime = start,
                startZoneOffset = startOffset,
                endTime = end,
                endZoneOffset = endOffset,
                count = count,
                metadata = metadata,
            )
        }

    private fun distanceDraft(
        activity: ActivityRecord,
        distance: Double,
        start: Instant,
        end: Instant,
        device: Device,
    ): HealthConnectRecordDraft =
        intervalDraft(
            activity = activity,
            recordType = HealthConnectRecordType.DISTANCE,
            providerRecordType = PROVIDER_DISTANCE,
            permission = HealthConnectWritePermission.DISTANCE,
            valueSignature = stableDouble(distance),
            start = start,
            end = end,
            device = device,
        ) { startOffset, endOffset, metadata ->
            DistanceRecord(
                startTime = start,
                startZoneOffset = startOffset,
                endTime = end,
                endZoneOffset = endOffset,
                distance = Length.meters(distance),
                metadata = metadata,
            )
        }

    private fun caloriesDraft(
        activity: ActivityRecord,
        calories: Double,
        start: Instant,
        end: Instant,
        device: Device,
    ): HealthConnectRecordDraft =
        intervalDraft(
            activity = activity,
            recordType = HealthConnectRecordType.TOTAL_CALORIES,
            providerRecordType = PROVIDER_TOTAL_CALORIES,
            permission = HealthConnectWritePermission.TOTAL_CALORIES,
            valueSignature = stableDouble(calories),
            start = start,
            end = end,
            device = device,
        ) { startOffset, endOffset, metadata ->
            TotalCaloriesBurnedRecord(
                startTime = start,
                startZoneOffset = startOffset,
                endTime = end,
                endZoneOffset = endOffset,
                energy = Energy.kilocalories(calories),
                metadata = metadata,
            )
        }

    private fun elevationDraft(
        activity: ActivityRecord,
        elevation: Double,
        start: Instant,
        end: Instant,
        device: Device,
    ): HealthConnectRecordDraft =
        intervalDraft(
            activity = activity,
            recordType = HealthConnectRecordType.ELEVATION_GAINED,
            providerRecordType = PROVIDER_ELEVATION_GAINED,
            permission = HealthConnectWritePermission.ELEVATION_GAINED,
            valueSignature = stableDouble(elevation),
            start = start,
            end = end,
            device = device,
        ) { startOffset, endOffset, metadata ->
            ElevationGainedRecord(
                startTime = start,
                startZoneOffset = startOffset,
                endTime = end,
                endZoneOffset = endOffset,
                elevation = Length.meters(elevation),
                metadata = metadata,
            )
        }

    private fun intervalDraft(
        activity: ActivityRecord,
        recordType: HealthConnectRecordType,
        providerRecordType: String,
        permission: HealthConnectWritePermission,
        valueSignature: String,
        start: Instant,
        end: Instant,
        device: Device,
        create: (ZoneOffset?, ZoneOffset?, Metadata) -> Record,
    ): HealthConnectRecordDraft {
        val startOffset = zoneOffset(activity, start)
        val endOffset = zoneOffset(activity, end)
        val signature =
            listOf(
                    "type=$providerRecordType",
                    "start=${start.toEpochMilli()}",
                    "end=${end.toEpochMilli()}",
                    "startOffset=${startOffset?.totalSeconds.orEmpty()}",
                    "endOffset=${endOffset?.totalSeconds.orEmpty()}",
                    "value=$valueSignature",
                    deviceSignature(device),
                )
                .joinToString("|")
        return HealthConnectRecordDraft(
            recordType = recordType,
            providerRecordType = providerRecordType,
            chunkIndex = 0,
            requiredPermission = permission,
            mappingSha256 = sha256(signature),
            device = device,
        ) { metadata -> create(startOffset, endOffset, metadata) }
    }

    private fun speedDraft(
        activity: ActivityRecord,
        chunkIndex: Int,
        samples: List<SpeedRecord.Sample>,
        device: Device,
    ): HealthConnectRecordDraft {
        val start = samples.first().time
        val end = samples.last().time
        val startOffset = zoneOffset(activity, start)
        val endOffset = zoneOffset(activity, end)
        val signature =
            buildString {
                append("type=SpeedRecord|")
                append("start=").append(start.toEpochMilli()).append('|')
                append("end=").append(end.toEpochMilli()).append('|')
                append("startOffset=").append(startOffset?.totalSeconds.orEmpty()).append('|')
                append("endOffset=").append(endOffset?.totalSeconds.orEmpty()).append('|')
                append(deviceSignature(device)).append('|')
                samples.forEach {
                    append(it.time.toEpochMilli())
                        .append(',')
                        .append(stableDouble(it.speed.inMetersPerSecond))
                        .append(';')
                }
            }
        return HealthConnectRecordDraft(
            recordType = HealthConnectRecordType.SPEED,
            providerRecordType = PROVIDER_SPEED,
            chunkIndex = chunkIndex,
            requiredPermission = HealthConnectWritePermission.SPEED,
            mappingSha256 = sha256(signature),
            device = device,
        ) { metadata ->
            SpeedRecord(
                startTime = start,
                startZoneOffset = startOffset,
                endTime = end,
                endZoneOffset = endOffset,
                samples = samples,
                metadata = metadata,
            )
        }
    }

    private fun route(
        activity: ActivityRecord,
        start: Instant,
        end: Instant,
    ): ExerciseRoute? {
        val byTimestamp = linkedMapOf<Long, ExerciseRoute.Location>()
        activity.samplesList
            .asSequence()
            .sortedWith(compareBy<MeasurementSample> { it.timestampUtcMs }.thenBy { it.sequence })
            .forEach { sample ->
                val timestamp = Instant.ofEpochMilli(sample.timestampUtcMs)
                val location = sample.location
                if (
                    !timestamp.isBefore(start) &&
                        timestamp.isBefore(end) &&
                        location.hasPublishableValue()
                ) {
                    val position = location.value
                    byTimestamp[sample.timestampUtcMs] =
                        ExerciseRoute.Location(
                            time = timestamp,
                            latitude = position.latitudeDegrees,
                            longitude = position.longitudeDegrees,
                            horizontalAccuracy =
                                position
                                    .takeIf { it.hasAccuracyM() && it.accuracyM.isFinite() }
                                    ?.accuracyM
                                    ?.takeIf { it >= 0.0 }
                                    ?.let(Length::meters),
                            altitude =
                                position
                                    .takeIf { it.hasAltitudeM() && it.altitudeM.isFinite() }
                                    ?.altitudeM
                                    ?.let(Length::meters),
                        )
                }
            }
        return byTimestamp.values.toList().takeIf { it.isNotEmpty() }?.let(::ExerciseRoute)
    }

    private fun heartRateSamples(
        activity: ActivityRecord,
        start: Instant,
        end: Instant,
    ): List<HeartRateRecord.Sample> {
        val byTimestamp = linkedMapOf<Long, HeartRateRecord.Sample>()
        activity.samplesList
            .asSequence()
            .sortedWith(compareBy<MeasurementSample> { it.timestampUtcMs }.thenBy { it.sequence })
            .forEach { sample ->
                val timestamp = Instant.ofEpochMilli(sample.timestampUtcMs)
                val bpm = sample.heartRateBpm.publishableValue()
                if (
                    bpm != null &&
                        !timestamp.isBefore(start) &&
                        !timestamp.isAfter(end)
                ) {
                    val rounded = bpm.roundToLong()
                    if (rounded in 1..300) {
                        byTimestamp[sample.timestampUtcMs] =
                            HeartRateRecord.Sample(timestamp, rounded)
                    }
                }
            }
        return byTimestamp.values.toList()
    }

    private fun speedSamples(
        activity: ActivityRecord,
        start: Instant,
        end: Instant,
    ): List<SpeedRecord.Sample> {
        val byTimestamp = linkedMapOf<Long, SpeedRecord.Sample>()
        activity.samplesList
            .asSequence()
            .sortedWith(compareBy<MeasurementSample> { it.timestampUtcMs }.thenBy { it.sequence })
            .forEach { sample ->
                val timestamp = Instant.ofEpochMilli(sample.timestampUtcMs)
                val speed = sample.speedMps.publishableValue()
                if (
                    speed != null &&
                        speed >= 0.0 &&
                        speed <= MAX_DOUBLE_TOTAL &&
                        !timestamp.isBefore(start) &&
                        !timestamp.isAfter(end)
                ) {
                    byTimestamp[sample.timestampUtcMs] =
                        SpeedRecord.Sample(timestamp, Velocity.metersPerSecond(speed))
                }
            }
        return byTimestamp.values.toList()
    }

    private fun stepsTotal(activity: ActivityRecord): Long? {
        val summary =
            if (activity.hasSummary()) activity.summary.steps.publishableValue() else null
        if (summary != null) {
            return summary
        }
        val cumulative =
            activity.samplesList.mapNotNull { it.stepsTotal.publishableValue() }.lastOrNull()
        if (cumulative != null) {
            return cumulative
        }
        val deltas = activity.samplesList.mapNotNull { it.steps.publishableValue() }
        return deltas.takeIf { it.isNotEmpty() }?.fold(0L) { total, value ->
            try {
                Math.addExact(total, value)
            } catch (_: ArithmeticException) {
                return null
            }
        }
    }

    private fun distanceTotal(activity: ActivityRecord): Double? =
        total(
            summary =
                if (activity.hasSummary()) activity.summary.distanceM.publishableValue() else null,
            cumulative = activity.samplesList.mapNotNull { it.distanceTotalM.publishableValue() },
            deltas = activity.samplesList.mapNotNull { it.distanceM.publishableValue() },
        )

    private fun caloriesTotal(activity: ActivityRecord): Double? =
        total(
            summary =
                if (activity.hasSummary()) {
                    activity.summary.caloriesKcal.publishableValue()
                } else {
                    null
                },
            cumulative = activity.samplesList.mapNotNull { it.caloriesTotalKcal.publishableValue() },
            deltas = activity.samplesList.mapNotNull { it.caloriesKcal.publishableValue() },
        )

    private fun elevationTotal(activity: ActivityRecord): Double? =
        total(
            summary =
                if (activity.hasSummary()) {
                    activity.summary.elevationGainM.publishableValue()
                } else {
                    null
                },
            cumulative =
                activity.samplesList.mapNotNull { it.elevationGainTotalM.publishableValue() },
            deltas = activity.samplesList.mapNotNull { it.elevationGainM.publishableValue() },
        )

    private fun total(
        summary: Double?,
        cumulative: List<Double>,
        deltas: List<Double>,
    ): Double? =
        summary
            ?: cumulative.lastOrNull()
            ?: deltas.takeIf { it.isNotEmpty() }?.sum()?.takeIf(Double::isFinite)

    private fun DoubleMeasurement.publishableValue(): Double? =
        if (
            hasValue() &&
                value.isFinite() &&
                status.quality != Quality.QUALITY_INVALID
        ) {
            value
        } else {
            null
        }

    private fun LongMeasurement.publishableValue(): Long? =
        if (hasValue() && status.quality != Quality.QUALITY_INVALID) value else null

    private fun PositionMeasurement.hasPublishableValue(): Boolean =
        hasValue() &&
            status.quality != Quality.QUALITY_INVALID &&
            value.latitudeDegrees.isFinite() &&
            value.longitudeDegrees.isFinite() &&
            value.latitudeDegrees in -90.0..90.0 &&
            value.longitudeDegrees in -180.0..180.0

    private fun zoneOffset(
        activity: ActivityRecord,
        instant: Instant,
    ): ZoneOffset? {
        if (activity.hasTimeZoneId()) {
            try {
                return ZoneId.of(activity.timeZoneId).rules.getOffset(instant)
            } catch (_: DateTimeException) {
                // Fall through to the explicit fixed offset when the historical zone is invalid.
            }
        }
        if (activity.hasUtcOffsetMinutesAtStart()) {
            return try {
                ZoneOffset.ofTotalSeconds(
                    Math.multiplyExact(activity.utcOffsetMinutesAtStart, 60)
                )
            } catch (_: DateTimeException) {
                null
            } catch (_: ArithmeticException) {
                null
            }
        }
        return null
    }

    private fun device(activity: ActivityRecord): Device {
        val source = activity.source
        val type =
            when (source.kind) {
                SourceKind.SOURCE_KIND_USER -> Device.TYPE_PHONE
                SourceKind.SOURCE_KIND_DERIVED -> Device.TYPE_UNKNOWN
                else -> Device.TYPE_WATCH
            }
        return Device(
            type = type,
            manufacturer = if (source.hasManufacturer()) source.manufacturer.cleanText() else null,
            model = if (source.hasModel()) source.model.cleanText() else null,
        )
    }

    private fun exerciseType(type: ActivityType): Int =
        when (type) {
            ActivityType.ACTIVITY_TYPE_RUNNING -> ExerciseSessionRecord.EXERCISE_TYPE_RUNNING
            ActivityType.ACTIVITY_TYPE_WALKING -> ExerciseSessionRecord.EXERCISE_TYPE_WALKING
            ActivityType.ACTIVITY_TYPE_CYCLING -> ExerciseSessionRecord.EXERCISE_TYPE_BIKING
            ActivityType.ACTIVITY_TYPE_HIKING -> ExerciseSessionRecord.EXERCISE_TYPE_HIKING
            ActivityType.ACTIVITY_TYPE_STRENGTH ->
                ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING
            ActivityType.ACTIVITY_TYPE_SWIMMING,
            ActivityType.ACTIVITY_TYPE_OTHER,
            ActivityType.ACTIVITY_TYPE_UNSPECIFIED,
            ActivityType.UNRECOGNIZED,
            -> ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT
        }

    private fun String?.cleanText(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

    private fun stableDouble(value: Double): String =
        String.format(Locale.ROOT, "%.17g", value)

    private fun deviceSignature(device: Device): String =
        "device=${device.type},${device.manufacturer.orEmpty()},${device.model.orEmpty()}"

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }

    private fun Int?.orEmpty(): String = this?.toString().orEmpty()

    private fun invalidMetric(metric: String): HealthConnectFailure =
        HealthConnectFailure(
            HealthConnectErrorCode.INVALID_PROVIDER_RECORD,
            "The canonical $metric value cannot be represented by Health Connect",
            retryable = false,
        )

    companion object {
        internal const val SERIES_CHUNK_SIZE = 1_000
        internal const val PROVIDER_EXERCISE_SESSION = "ExerciseSessionRecord"
        internal const val PROVIDER_HEART_RATE = "HeartRateRecord"
        internal const val PROVIDER_STEPS = "StepsRecord"
        internal const val PROVIDER_DISTANCE = "DistanceRecord"
        internal const val PROVIDER_TOTAL_CALORIES = "TotalCaloriesBurnedRecord"
        internal const val PROVIDER_ELEVATION_GAINED = "ElevationGainedRecord"
        internal const val PROVIDER_SPEED = "SpeedRecord"

        private const val MAX_STEPS = 1_000_000L
        private const val MAX_DOUBLE_TOTAL = 1_000_000.0
    }
}

internal data class HealthConnectWritePlan(
    val drafts: List<HealthConnectRecordDraft>,
    val requiredPermissions: Set<HealthConnectWritePermission>,
    val presentRecordTypes: Set<HealthConnectRecordType>,
    val routePresent: Boolean,
    val routeIncluded: Boolean,
    val mappingFailures: List<HealthConnectFailure>,
)

internal class HealthConnectRecordDraft(
    val recordType: HealthConnectRecordType,
    val providerRecordType: String,
    val chunkIndex: Int,
    val requiredPermission: HealthConnectWritePermission,
    val mappingSha256: String,
    private val device: Device,
    private val create: (Metadata) -> Record,
) {
    fun record(
        clientRecordId: String,
        clientRecordVersion: Long,
    ): Record =
        create(
            Metadata.activelyRecorded(
                device = device,
                clientRecordId = clientRecordId,
                clientRecordVersion = clientRecordVersion,
            )
        )
}

internal class HealthConnectMappingException(
    val code: HealthConnectErrorCode,
    message: String,
) : IllegalArgumentException(message)
