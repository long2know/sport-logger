package com.long2know.sportlogger.recording;

import com.long2know.utilities.contract.proto.ActivityType;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RecordingActivityTypePolicyTest {
    @Test
    public void supportedTypesExcludeUnspecifiedAndUnknownValues() {
        assertTrue(RecordingActivityTypePolicy.isSupported(
                ActivityType.ACTIVITY_TYPE_RUNNING));
        assertTrue(RecordingActivityTypePolicy.isSupported(
                ActivityType.ACTIVITY_TYPE_SWIMMING));
        assertFalse(RecordingActivityTypePolicy.isSupported(
                ActivityType.ACTIVITY_TYPE_UNSPECIFIED));
        assertFalse(RecordingActivityTypePolicy.isSupported(
                ActivityType.UNRECOGNIZED));
        assertFalse(RecordingActivityTypePolicy.isSupported(null));
    }
}
