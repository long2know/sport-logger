package com.long2know.sportlogger.healthconnect

import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseRouteResult
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import com.long2know.utilities.contract.CanonicalMeasurements
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class HealthConnectRecordMapperTest {
    private val mapper = HealthConnectRecordMapper()

    @Test
    fun routeIsIncludedOnlyWithPhoneRouteWritePermission() {
        val activity =
            HealthConnectTestData.activity(
                revision = 1,
                samples =
                    listOf(
                        HealthConnectTestData.sample(
                            1,
                            HealthConnectTestData.START_MS,
                            location = HealthConnectTestData.location(47.60, -122.33, 10.0, 3.0),
                        ),
                        HealthConnectTestData.sample(
                            2,
                            HealthConnectTestData.START_MS + 1_000,
                            location = HealthConnectTestData.location(47.61, -122.34, 11.0, 2.0),
                        ),
                    ),
            )
        val withRoute = mapper.plan(activity, null, null, includeRoute = true)
        val withoutRoute = mapper.plan(activity, null, null, includeRoute = false)

        val withRouteRecord = sessionRecord(withRoute)
        val withoutRouteRecord = sessionRecord(withoutRoute)
        assertTrue(withRoute.routePresent)
        assertTrue(withRoute.routeIncluded)
        assertTrue(withRouteRecord.exerciseRouteResult is ExerciseRouteResult.Data)
        assertTrue(withoutRoute.routePresent)
        assertFalse(withoutRoute.routeIncluded)
        assertTrue(withoutRouteRecord.exerciseRouteResult is ExerciseRouteResult.NoData)
        assertTrue(
            HealthConnectWritePermission.PHONE_EXERCISE_ROUTE in
                withRoute.requiredPermissions
        )
    }

    @Test
    fun heartRateAndSpeedUseDeterministicThousandSampleChunks() {
        val samples =
            (0 until 2_001).map { index ->
                HealthConnectTestData.sample(
                    sequence = index.toLong(),
                    timestampMs = HealthConnectTestData.START_MS + index,
                    heartRate =
                        HealthConnectTestData.doubleMeasurement(
                            CanonicalMeasurements.Metric.HEART_RATE_BPM,
                            100.0 + index % 20,
                        ),
                    speed =
                        HealthConnectTestData.doubleMeasurement(
                            CanonicalMeasurements.Metric.SPEED,
                            3.5 + index % 3,
                        ),
                )
            }
        val plan =
            mapper.plan(
                HealthConnectTestData.activity(1, samples),
                null,
                null,
                includeRoute = false,
            )
        val heartDrafts =
            plan.drafts.filter { it.recordType == HealthConnectRecordType.HEART_RATE }
        val speedDrafts =
            plan.drafts.filter { it.recordType == HealthConnectRecordType.SPEED }
        assertEquals(listOf(0, 1, 2), heartDrafts.map { it.chunkIndex })
        assertEquals(listOf(0, 1, 2), speedDrafts.map { it.chunkIndex })
        assertEquals(
            listOf(1_000, 1_000, 1),
            heartDrafts.mapIndexed { index, draft ->
                (draft.record("heart-$index", 1) as HeartRateRecord).samples.size
            },
        )
        assertEquals(
            listOf(1_000, 1_000, 1),
            speedDrafts.mapIndexed { index, draft ->
                (draft.record("speed-$index", 1) as SpeedRecord).samples.size
            },
        )
    }

    @Test
    fun absentMetricsDoNotBecomeInventedZeroRecords() {
        val absentSummary =
            HealthConnectTestData.summary(
                distanceM =
                    HealthConnectTestData.doubleMeasurement(
                        CanonicalMeasurements.Metric.DISTANCE
                    ),
                caloriesKcal =
                    HealthConnectTestData.doubleMeasurement(
                        CanonicalMeasurements.Metric.CALORIES
                    ),
                steps =
                    HealthConnectTestData.longMeasurement(
                        CanonicalMeasurements.Metric.STEPS
                    ),
                elevationGainM =
                    HealthConnectTestData.doubleMeasurement(
                        CanonicalMeasurements.Metric.ELEVATION_GAIN
                    ),
            )
        val absentPlan =
            mapper.plan(
                HealthConnectTestData.activity(1, summary = absentSummary),
                null,
                null,
                includeRoute = false,
            )
        assertEquals(
            setOf(HealthConnectRecordType.EXERCISE_SESSION),
            absentPlan.presentRecordTypes,
        )

        val presentZeros =
            HealthConnectTestData.summary(
                distanceM =
                    HealthConnectTestData.doubleMeasurement(
                        CanonicalMeasurements.Metric.DISTANCE,
                        0.0,
                    ),
                caloriesKcal =
                    HealthConnectTestData.doubleMeasurement(
                        CanonicalMeasurements.Metric.CALORIES,
                        0.0,
                    ),
                steps =
                    HealthConnectTestData.longMeasurement(
                        CanonicalMeasurements.Metric.STEPS,
                        0,
                    ),
                elevationGainM =
                    HealthConnectTestData.doubleMeasurement(
                        CanonicalMeasurements.Metric.ELEVATION_GAIN,
                        0.0,
                    ),
            )
        val presentPlan =
            mapper.plan(
                HealthConnectTestData.activity(1, summary = presentZeros),
                null,
                null,
                includeRoute = false,
            )
        assertTrue(HealthConnectRecordType.DISTANCE in presentPlan.presentRecordTypes)
        assertTrue(HealthConnectRecordType.TOTAL_CALORIES in presentPlan.presentRecordTypes)
        assertTrue(HealthConnectRecordType.ELEVATION_GAINED in presentPlan.presentRecordTypes)
        assertFalse(HealthConnectRecordType.STEPS in presentPlan.presentRecordTypes)
    }

    @Test
    fun totalsUseCanonicalUnitsTimesAndActivelyRecordedMetadata() {
        val summary =
            HealthConnectTestData.summary(
                distanceM =
                    HealthConnectTestData.doubleMeasurement(
                        CanonicalMeasurements.Metric.DISTANCE,
                        5_432.1,
                    ),
                caloriesKcal =
                    HealthConnectTestData.doubleMeasurement(
                        CanonicalMeasurements.Metric.CALORIES,
                        456.7,
                    ),
                elevationGainM =
                    HealthConnectTestData.doubleMeasurement(
                        CanonicalMeasurements.Metric.ELEVATION_GAIN,
                        87.6,
                    ),
            )
        val plan =
            mapper.plan(
                HealthConnectTestData.activity(1, summary = summary),
                "Morning run",
                "Steady",
                includeRoute = false,
            )
        val records =
            plan.drafts.associate { draft ->
                draft.record("client-${draft.providerRecordType}", 7) to draft.recordType
            }
        val distance =
            records.keys.filterIsInstance<DistanceRecord>().single()
        val calories =
            records.keys.filterIsInstance<TotalCaloriesBurnedRecord>().single()
        val elevation =
            records.keys.filterIsInstance<ElevationGainedRecord>().single()
        val session =
            records.keys.filterIsInstance<ExerciseSessionRecord>().single()

        assertEquals(5_432.1, distance.distance.inMeters, 0.0001)
        assertEquals(456.7, calories.energy.inKilocalories, 0.0001)
        assertEquals(87.6, elevation.elevation.inMeters, 0.0001)
        assertEquals(HealthConnectTestData.START_MS, distance.startTime.toEpochMilli())
        assertEquals(HealthConnectTestData.END_MS, distance.endTime.toEpochMilli())
        assertEquals("Morning run", session.title)
        assertEquals("Steady", session.notes)
        assertEquals(7, session.metadata.clientRecordVersion)
        assertEquals(
            androidx.health.connect.client.records.metadata.Metadata
                .RECORDING_METHOD_ACTIVELY_RECORDED,
            session.metadata.recordingMethod,
        )
    }

    private fun sessionRecord(plan: HealthConnectWritePlan): ExerciseSessionRecord {
        val draft =
            plan.drafts.single {
                it.recordType == HealthConnectRecordType.EXERCISE_SESSION
            }
        return draft.record("session-record", 1) as ExerciseSessionRecord
    }
}
