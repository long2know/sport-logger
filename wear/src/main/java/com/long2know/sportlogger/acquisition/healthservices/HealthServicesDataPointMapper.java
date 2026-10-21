package com.long2know.sportlogger.acquisition.healthservices;

import androidx.health.services.client.data.CumulativeDataPoint;
import androidx.health.services.client.data.DataPoint;
import androidx.health.services.client.data.DataPointAccuracy;
import androidx.health.services.client.data.DataPointContainer;
import androidx.health.services.client.data.ExerciseUpdate;
import androidx.health.services.client.data.HeartRateAccuracy;
import androidx.health.services.client.data.IntervalDataPoint;
import androidx.health.services.client.data.LocationAccuracy;
import androidx.health.services.client.data.SampleDataPoint;

import com.long2know.sportlogger.acquisition.ExerciseMetric;
import com.long2know.sportlogger.acquisition.LocationSample;
import com.long2know.sportlogger.acquisition.MeasurementProvenance;
import com.long2know.sportlogger.acquisition.MeasurementSample;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public final class HealthServicesDataPointMapper {
    public List<MeasurementSample<?>> map(ExerciseUpdate update) {
        if (update == null) {
            return Collections.emptyList();
        }
        DataPointContainer container = update.getLatestMetrics();
        long updateSequence = toNanos(update.getUpdateDurationFromBoot());
        Long checkpointActive = update.getActiveDurationCheckpoint() == null
                ? null
                : update.getActiveDurationCheckpoint()
                .getActiveDuration()
                .toMillis();
        List<MeasurementSample<?>> samples = new ArrayList<>();
        for (SampleDataPoint<?> point : container.getSampleDataPoints()) {
            add(
                    samples,
                    point,
                    point.getValue(),
                    toNanos(point.getTimeDurationFromBoot()),
                    point.getTimeInstant(update.getStartTime()).toEpochMilli(),
                    update.getActiveDurationAtDataPoint(point).toMillis());
        }
        for (IntervalDataPoint<?> point : container.getIntervalDataPoints()) {
            add(
                    samples,
                    point,
                    point.getValue(),
                    toNanos(point.getEndDurationFromBoot()),
                    point.getEndInstant(update.getStartTime()).toEpochMilli(),
                    update.getActiveDurationAtDataPoint(point).toMillis());
        }
        for (CumulativeDataPoint<?> point : container.getCumulativeDataPoints()) {
            add(
                    samples,
                    point,
                    point.getTotal(),
                    updateSequence,
                    point.getEnd().toEpochMilli(),
                    checkpointActive);
        }
        samples.sort(Comparator.comparingLong(MeasurementSample::getSequenceNanos));
        return samples;
    }

    public List<MeasurementSample<?>> map(
            DataPointContainer container,
            Duration updateDurationFromBoot) {
        if (container == null) {
            return Collections.emptyList();
        }
        long updateSequence = toNanos(updateDurationFromBoot);
        List<MeasurementSample<?>> samples = new ArrayList<>();
        for (SampleDataPoint<?> point : container.getSampleDataPoints()) {
            add(
                    samples,
                    point,
                    point.getValue(),
                    toNanos(point.getTimeDurationFromBoot()),
                    null,
                    null);
        }
        for (IntervalDataPoint<?> point : container.getIntervalDataPoints()) {
            add(
                    samples,
                    point,
                    point.getValue(),
                    toNanos(point.getEndDurationFromBoot()),
                    null,
                    null);
        }
        for (CumulativeDataPoint<?> point : container.getCumulativeDataPoints()) {
            add(samples, point, point.getTotal(), updateSequence, null, null);
        }
        return samples;
    }

    private static void add(
            List<MeasurementSample<?>> samples,
            DataPoint<?> point,
            Object rawValue,
            long sequence,
            Long timestampUtcMillis,
            Long activeDurationMillis) {
        ExerciseMetric metric = HealthServicesMetricMapper.metric(point.getDataType());
        if (metric == null || rawValue == null) {
            return;
        }
        Object value = rawValue;
        if (metric == ExerciseMetric.LOCATION) {
            androidx.health.services.client.data.LocationData location =
                    (androidx.health.services.client.data.LocationData) rawValue;
            Double altitude = Double.isFinite(location.getAltitude())
                    ? location.getAltitude()
                    : null;
            Double bearing = Double.isFinite(location.getBearing())
                    ? location.getBearing()
                    : null;
            Double horizontalAccuracy = null;
            DataPointAccuracy accuracy = accuracy(point);
            if (accuracy instanceof LocationAccuracy) {
                double candidate =
                        ((LocationAccuracy) accuracy).getHorizontalPositionErrorMeters();
                if (Double.isFinite(candidate)) {
                    horizontalAccuracy = candidate;
                }
            }
            try {
                value = new LocationSample(
                        location.getLatitude(),
                        location.getLongitude(),
                        altitude,
                        bearing,
                        horizontalAccuracy);
            } catch (IllegalArgumentException ignored) {
                return;
            }
        }
        try {
            samples.add(new MeasurementSample<>(
                    metric,
                    value,
                    Math.max(0L, sequence),
                    MeasurementProvenance.HEALTH_SERVICES,
                    quality(point),
                    timestampUtcMillis,
                    activeDurationMillis));
        } catch (IllegalArgumentException ignored) {
            // A malformed platform point is ignored rather than converted to a fake zero.
        }
    }

    private static DataPointAccuracy accuracy(DataPoint<?> point) {
        if (point instanceof SampleDataPoint) {
            return ((SampleDataPoint<?>) point).getAccuracy();
        }
        if (point instanceof IntervalDataPoint) {
            return ((IntervalDataPoint<?>) point).getAccuracy();
        }
        return null;
    }

    private static MeasurementSample.Quality quality(DataPoint<?> point) {
        DataPointAccuracy accuracy = accuracy(point);
        if (!(accuracy instanceof HeartRateAccuracy)) {
            return MeasurementSample.Quality.UNKNOWN;
        }
        HeartRateAccuracy.SensorStatus status =
                ((HeartRateAccuracy) accuracy).getSensorStatus();
        if (status.equals(HeartRateAccuracy.SensorStatus.ACCURACY_HIGH)) {
            return MeasurementSample.Quality.HIGH;
        }
        if (status.equals(HeartRateAccuracy.SensorStatus.ACCURACY_MEDIUM)) {
            return MeasurementSample.Quality.MEDIUM;
        }
        if (status.equals(HeartRateAccuracy.SensorStatus.ACCURACY_LOW)) {
            return MeasurementSample.Quality.LOW;
        }
        if (status.equals(HeartRateAccuracy.SensorStatus.NO_CONTACT)
                || status.equals(HeartRateAccuracy.SensorStatus.UNRELIABLE)) {
            return MeasurementSample.Quality.UNRELIABLE;
        }
        return MeasurementSample.Quality.UNKNOWN;
    }

    private static long toNanos(Duration duration) {
        if (duration == null || duration.isNegative()) {
            return 0L;
        }
        try {
            return duration.toNanos();
        } catch (ArithmeticException error) {
            return Long.MAX_VALUE;
        }
    }
}
