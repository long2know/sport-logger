package com.long2know.sportlogger.acquisition;

import com.long2know.sportlogger.measurements.MeasurementEvent;
import com.long2know.utilities.models.LocationData;
import com.long2know.utilities.models.SharedData;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

public final class LegacyMeasurementBridge {
    public interface SampleListener {
        void onAcceptedSample(MeasurementSample<?> sample);
    }

    private final MetricSourceGate sourceGate = new MetricSourceGate();
    private final EnumSet<ExerciseMetric> activeHealthServicesMetrics =
            EnumSet.noneOf(ExerciseMetric.class);
    private int activityId;
    private long stepTotal;
    private double distanceTotal;
    private LiveMeasurementSnapshot.Value<Double> liveHeartRate;
    private LiveMeasurementSnapshot.Value<Long> liveSteps;
    private LiveMeasurementSnapshot.Value<Double> liveDistance;
    private LiveMeasurementSnapshot.Value<LocationSample> liveLocation;
    private final SampleListener sampleListener;

    public LegacyMeasurementBridge() {
        this(sample -> {
        });
    }

    public LegacyMeasurementBridge(SampleListener sampleListener) {
        this.sampleListener = java.util.Objects.requireNonNull(sampleListener);
        configure(Collections.emptySet());
    }

    public synchronized void beginSession() {
        beginSession(0);
    }

    public synchronized void beginSession(int activityId) {
        this.activityId = Math.max(0, activityId);
        stepTotal = 0L;
        distanceTotal = 0D;
        liveHeartRate = null;
        liveSteps = null;
        liveDistance = null;
        liveLocation = null;
    }

    public synchronized LiveMeasurementSnapshot getLiveSnapshot() {
        return new LiveMeasurementSnapshot(
                activityId,
                liveHeartRate,
                liveSteps,
                liveDistance,
                liveLocation);
    }

    public synchronized void configure(Set<ExerciseMetric> healthServicesMetrics) {
        activeHealthServicesMetrics.clear();
        activeHealthServicesMetrics.addAll(healthServicesMetrics);

        select(
                ExerciseMetric.HEART_RATE_BPM,
                activeHealthServicesMetrics.contains(ExerciseMetric.HEART_RATE_BPM));
        if (activeHealthServicesMetrics.contains(ExerciseMetric.STEPS_TOTAL)) {
            sourceGate.select(
                    ExerciseMetric.STEPS_TOTAL,
                    MeasurementProvenance.HEALTH_SERVICES);
            sourceGate.clear(ExerciseMetric.STEPS);
        } else if (activeHealthServicesMetrics.contains(ExerciseMetric.STEPS)) {
            sourceGate.select(
                    ExerciseMetric.STEPS,
                    MeasurementProvenance.HEALTH_SERVICES);
            sourceGate.clear(ExerciseMetric.STEPS_TOTAL);
        } else {
            sourceGate.select(
                    ExerciseMetric.STEPS_TOTAL,
                    MeasurementProvenance.RAW_PLATFORM);
            sourceGate.clear(ExerciseMetric.STEPS);
        }
        select(
                ExerciseMetric.LOCATION,
                activeHealthServicesMetrics.contains(ExerciseMetric.LOCATION));
        selectPreferredPair(
                ExerciseMetric.DISTANCE,
                ExerciseMetric.DISTANCE_TOTAL);
        select(
                ExerciseMetric.SPEED,
                activeHealthServicesMetrics.contains(ExerciseMetric.SPEED));
        select(
                ExerciseMetric.ABSOLUTE_ELEVATION,
                activeHealthServicesMetrics.contains(ExerciseMetric.ABSOLUTE_ELEVATION));
        sourceGate.select(
                ExerciseMetric.PRESSURE,
                MeasurementProvenance.RAW_PLATFORM);

        for (ExerciseMetric metric : activeHealthServicesMetrics) {
            sourceGate.select(metric, MeasurementProvenance.HEALTH_SERVICES);
        }
    }

    public synchronized boolean needsRawSensorSource() {
        return sourceGate.selectedSource(ExerciseMetric.HEART_RATE_BPM)
                == MeasurementProvenance.RAW_PLATFORM
                || sourceGate.selectedSource(ExerciseMetric.STEPS_TOTAL)
                == MeasurementProvenance.RAW_PLATFORM;
    }

    public synchronized boolean needsRawLocationSource() {
        return sourceGate.selectedSource(ExerciseMetric.LOCATION)
                == MeasurementProvenance.RAW_PLATFORM;
    }

    public synchronized boolean applyRaw(MeasurementEvent event) {
        SharedData sharedData = SharedData.getInstance();
        switch (event.getKind()) {
            case HEART_RATE:
                MeasurementSample<Double> heartRate = new MeasurementSample<>(
                        ExerciseMetric.HEART_RATE_BPM,
                        (double) event.getHeartRate(),
                        Math.max(0L, event.getSequence()),
                        MeasurementProvenance.RAW_PLATFORM,
                        MeasurementSample.Quality.UNKNOWN);
                if (!sourceGate.accept(heartRate)) {
                    return false;
                }
                sharedData.setHeartRate(event.getHeartRate());
                liveHeartRate = liveValue((double) event.getHeartRate(), heartRate);
                sampleListener.onAcceptedSample(heartRate);
                return true;
            case STEPS:
                MeasurementSample<Long> steps = new MeasurementSample<>(
                        ExerciseMetric.STEPS_TOTAL,
                        (long) event.getSteps(),
                        Math.max(0L, event.getSequence()),
                        MeasurementProvenance.RAW_PLATFORM,
                        MeasurementSample.Quality.UNKNOWN);
                if (!sourceGate.accept(steps)) {
                    return false;
                }
                stepTotal = Math.max(stepTotal, steps.getValue());
                sharedData.setSteps(MeasurementUnits.countToLegacyInt(stepTotal));
                liveSteps = liveValue(stepTotal, steps);
                sampleListener.onAcceptedSample(steps);
                return true;
            case LOCATION:
                return applyRawLocation(event, sharedData);
            case PRESSURE:
                MeasurementSample<Double> pressure = new MeasurementSample<>(
                        ExerciseMetric.PRESSURE,
                        event.getPressureHpa(),
                        Math.max(0L, event.getSequence()),
                        MeasurementProvenance.RAW_PLATFORM,
                        MeasurementSample.Quality.UNKNOWN);
                if (!sourceGate.accept(pressure)) {
                    return false;
                }
                sampleListener.onAcceptedSample(pressure);
                return false;
            default:
                return false;
        }
    }

    public synchronized boolean applyHealthServices(MeasurementSample<?> sample) {
        if (!sourceGate.accept(sample)) {
            return false;
        }
        sampleListener.onAcceptedSample(sample);
        SharedData sharedData = SharedData.getInstance();
        switch (sample.getMetric()) {
            case HEART_RATE_BPM:
                double heartRate = (Double) sample.getValue();
                if (Double.isFinite(heartRate) && heartRate >= 0D) {
                    sharedData.setHeartRate((float) heartRate);
                    liveHeartRate = liveValue(heartRate, sample);
                    return true;
                }
                return false;
            case STEPS_TOTAL:
                stepTotal = Math.max(0L, (Long) sample.getValue());
                sharedData.setSteps(MeasurementUnits.countToLegacyInt(stepTotal));
                liveSteps = liveValue(stepTotal, sample);
                return true;
            case STEPS:
                stepTotal = saturatedAdd(stepTotal, Math.max(0L, (Long) sample.getValue()));
                sharedData.setSteps(MeasurementUnits.countToLegacyInt(stepTotal));
                liveSteps = liveValue(stepTotal, sample);
                return true;
            case DISTANCE_TOTAL:
                double totalCandidate = (Double) sample.getValue();
                boolean totalIsValid =
                        Double.isFinite(totalCandidate) && totalCandidate >= 0D;
                distanceTotal = nonNegativeFinite(totalCandidate, distanceTotal);
                updateDistance(sharedData, 0D, distanceTotal);
                if (totalIsValid) {
                    liveDistance = liveValue(distanceTotal, sample);
                }
                return true;
            case DISTANCE:
                double distanceCandidate = (Double) sample.getValue();
                boolean distanceIsValid =
                        Double.isFinite(distanceCandidate) && distanceCandidate >= 0D;
                double distance = nonNegativeFinite(distanceCandidate, 0D);
                distanceTotal += distance;
                updateDistance(sharedData, distance, distanceTotal);
                if (distanceIsValid) {
                    liveDistance = liveValue(distanceTotal, sample);
                }
                return true;
            case SPEED:
                updateSpeed(sharedData, (Double) sample.getValue());
                return true;
            case ABSOLUTE_ELEVATION:
                updateElevation(sharedData, (Double) sample.getValue());
                return true;
            case LOCATION:
                LocationSample location = (LocationSample) sample.getValue();
                updateLocation(sharedData, location);
                liveLocation = liveValue(location, sample);
                return true;
            default:
                return true;
        }
    }

    private boolean applyRawLocation(
            MeasurementEvent event,
            SharedData sharedData) {
        LocationData raw = event.getLocation();
        LocationSample location = new LocationSample(
                raw.Latitude,
                raw.Longitude,
                raw.HasAltitude ? raw.Altitude : null,
                raw.HasBearing ? raw.Bearing : null,
                raw.HasAccuracy ? raw.Accuracy : null);
        MeasurementSample<LocationSample> sample = new MeasurementSample<>(
                ExerciseMetric.LOCATION,
                location,
                Math.max(0L, event.getSequence()),
                MeasurementProvenance.RAW_PLATFORM,
                MeasurementSample.Quality.UNKNOWN);
        if (!sourceGate.accept(sample)) {
            return false;
        }
        sampleListener.onAcceptedSample(sample);
        liveLocation = liveValue(location, sample);

        LocationData merged = new LocationData(raw);
        if (sourceGate.selectedSource(ExerciseMetric.DISTANCE_TOTAL)
                != MeasurementProvenance.DERIVED) {
            LocationData current = sharedData.getData();
            merged.Distance = 0D;
            merged.TotalDistance = current.TotalDistance;
        } else {
            distanceTotal = Math.max(distanceTotal, raw.TotalDistance);
            MeasurementSample<Double> distance = new MeasurementSample<>(
                    ExerciseMetric.DISTANCE_TOTAL,
                    distanceTotal,
                    Math.max(0L, event.getSequence()),
                    MeasurementProvenance.DERIVED,
                    MeasurementSample.Quality.LOW);
            if (sourceGate.accept(distance)) {
                sampleListener.onAcceptedSample(distance);
                if (Double.isFinite(distanceTotal) && distanceTotal >= 0D) {
                    liveDistance = liveValue(distanceTotal, distance);
                }
            }
        }
        if (sourceGate.selectedSource(ExerciseMetric.SPEED)
                != MeasurementProvenance.RAW_PLATFORM) {
            LocationData current = sharedData.getData();
            merged.Speed = current.Speed;
            merged.HasSpeed = current.HasSpeed;
        } else if (raw.HasSpeed) {
            MeasurementSample<Double> speed = new MeasurementSample<>(
                    ExerciseMetric.SPEED,
                    raw.Speed,
                    Math.max(0L, event.getSequence()),
                    MeasurementProvenance.RAW_PLATFORM,
                    MeasurementSample.Quality.UNKNOWN);
            if (sourceGate.accept(speed)) {
                sampleListener.onAcceptedSample(speed);
            }
        }
        if (sourceGate.selectedSource(ExerciseMetric.ABSOLUTE_ELEVATION)
                != MeasurementProvenance.RAW_PLATFORM) {
            LocationData current = sharedData.getData();
            merged.Altitude = current.Altitude;
            merged.HasAltitude = current.HasAltitude;
        } else if (raw.HasAltitude) {
            MeasurementSample<Double> elevation = new MeasurementSample<>(
                    ExerciseMetric.ABSOLUTE_ELEVATION,
                    raw.Altitude,
                    Math.max(0L, event.getSequence()),
                    MeasurementProvenance.RAW_PLATFORM,
                    MeasurementSample.Quality.UNKNOWN);
            if (sourceGate.accept(elevation)) {
                sampleListener.onAcceptedSample(elevation);
            }
        }
        sharedData.setLocation(merged);
        return true;
    }

    private void select(ExerciseMetric metric, boolean healthServices) {
        sourceGate.select(
                metric,
                healthServices
                        ? MeasurementProvenance.HEALTH_SERVICES
                        : MeasurementProvenance.RAW_PLATFORM);
    }

    private void selectPreferredPair(
            ExerciseMetric delta,
            ExerciseMetric total) {
        if (activeHealthServicesMetrics.contains(total)) {
            sourceGate.select(total, MeasurementProvenance.HEALTH_SERVICES);
            sourceGate.clear(delta);
        } else if (activeHealthServicesMetrics.contains(delta)) {
            sourceGate.select(delta, MeasurementProvenance.HEALTH_SERVICES);
            sourceGate.clear(total);
        } else {
            sourceGate.select(total, MeasurementProvenance.DERIVED);
            sourceGate.clear(delta);
        }
    }

    private static void updateLocation(
            SharedData sharedData,
            LocationSample location) {
        LocationData merged = sharedData.getData();
        merged.Latitude = location.getLatitude();
        merged.Longitude = location.getLongitude();
        if (location.getAltitudeMeters() == null) {
            merged.Altitude = 0D;
            merged.HasAltitude = false;
        } else {
            merged.Altitude = location.getAltitudeMeters();
            merged.HasAltitude = true;
        }
        if (location.getBearingDegrees() == null) {
            merged.Bearing = 0D;
            merged.HasBearing = false;
        } else {
            merged.Bearing = location.getBearingDegrees();
            merged.HasBearing = true;
        }
        if (location.getHorizontalAccuracyMeters() == null) {
            merged.Accuracy = 0D;
            merged.HasAccuracy = false;
        } else {
            merged.Accuracy = location.getHorizontalAccuracyMeters();
            merged.HasAccuracy = true;
        }
        sharedData.setLocation(merged);
    }

    private static void updateDistance(
            SharedData sharedData,
            double distance,
            double totalDistance) {
        LocationData merged = sharedData.getData();
        merged.Distance = distance;
        merged.TotalDistance = totalDistance;
        sharedData.setLocation(merged);
    }

    private static void updateSpeed(SharedData sharedData, double speed) {
        LocationData merged = sharedData.getData();
        if (Double.isFinite(speed) && speed >= 0D) {
            merged.Speed = speed;
            merged.HasSpeed = true;
        } else {
            merged.Speed = 0D;
            merged.HasSpeed = false;
        }
        sharedData.setLocation(merged);
    }

    private static void updateElevation(SharedData sharedData, double elevation) {
        LocationData merged = sharedData.getData();
        if (Double.isFinite(elevation)) {
            merged.Altitude = elevation;
            merged.HasAltitude = true;
        } else {
            merged.Altitude = 0D;
            merged.HasAltitude = false;
        }
        sharedData.setLocation(merged);
    }

    private static double nonNegativeFinite(double candidate, double fallback) {
        return Double.isFinite(candidate) && candidate >= 0D ? candidate : fallback;
    }

    private static <T> LiveMeasurementSnapshot.Value<T> liveValue(
            T value,
            MeasurementSample<?> sample) {
        return new LiveMeasurementSnapshot.Value<>(
                value,
                sample.getProvenance(),
                sample.getSequenceNanos(),
                sample.getQuality());
    }

    private static long saturatedAdd(long current, long increment) {
        if (increment > 0L && current > Long.MAX_VALUE - increment) {
            return Long.MAX_VALUE;
        }
        return current + increment;
    }
}
