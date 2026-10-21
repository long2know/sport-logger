package com.long2know.sportlogger.ui

import com.long2know.sportlogger.acquisition.RecordingPermissionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionStartCoordinatorTest {
    @Test
    fun heartRateOnlyStartsOnceWhileRouteNotificationsAndActivityAreDenied() {
        val granted = setOf(RecordingPermissionPolicy.READ_HEART_RATE)
        val deniedOptional = setOf(
            RecordingPermissionPolicy.ACTIVITY_RECOGNITION,
            RecordingPermissionPolicy.ACCESS_COARSE_LOCATION,
            RecordingPermissionPolicy.ACCESS_FINE_LOCATION,
            RecordingPermissionPolicy.POST_NOTIFICATIONS,
        )
        val permissions = state(
            granted = granted,
            requested = deniedOptional,
            denied = deniedOptional,
        )
        val coordinator = PermissionStartCoordinator()

        val resolution = coordinator.requestStart(ActivityChoice.RUNNING, permissions)

        assertEquals(
            StartPermissionResolution.Start(ActivityChoice.RUNNING),
            resolution,
        )
        assertNull(coordinator.consumePendingIfReady(permissions, serviceReady = true))
    }

    @Test
    fun activityOnlyStartsOnceWhileRouteNotificationsAndHeartRateAreDenied() {
        val granted = setOf(RecordingPermissionPolicy.ACTIVITY_RECOGNITION)
        val deniedOptional = setOf(
            RecordingPermissionPolicy.READ_HEART_RATE,
            RecordingPermissionPolicy.ACCESS_COARSE_LOCATION,
            RecordingPermissionPolicy.ACCESS_FINE_LOCATION,
            RecordingPermissionPolicy.POST_NOTIFICATIONS,
        )
        val permissions = state(
            granted = granted,
            requested = deniedOptional,
            denied = deniedOptional,
        )
        val coordinator = PermissionStartCoordinator()

        val resolution = coordinator.requestStart(ActivityChoice.WALKING, permissions)

        assertEquals(
            StartPermissionResolution.Start(ActivityChoice.WALKING),
            resolution,
        )
        assertNull(coordinator.consumePendingIfReady(permissions, serviceReady = true))
    }

    @Test
    fun noHealthGrantWaitsThenConsumesPendingStartOnlyOnce() {
        val coordinator = PermissionStartCoordinator()
        val missingHealth = state()

        val resolution =
            coordinator.requestStart(ActivityChoice.CYCLING, missingHealth)

        assertTrue(resolution is StartPermissionResolution.Request)
        assertEquals(
            listOf(
                RecordingPermissionPolicy.READ_HEART_RATE,
                RecordingPermissionPolicy.ACTIVITY_RECOGNITION,
            ),
            (resolution as StartPermissionResolution.Request).permissions,
        )
        assertNull(
            coordinator.consumePendingIfReady(
                missingHealth,
                serviceReady = true,
            ),
        )

        val activityGranted = state(
            granted = setOf(RecordingPermissionPolicy.ACTIVITY_RECOGNITION),
        )
        assertEquals(
            ActivityChoice.CYCLING,
            coordinator.consumePendingIfReady(
                activityGranted,
                serviceReady = true,
            ),
        )
        assertNull(
            coordinator.consumePendingIfReady(
                activityGranted,
                serviceReady = true,
            ),
        )
    }

    @Test
    fun permanentlyDeniedHealthPathsBlockWithoutAnotherRequest() {
        val healthPermissions = setOf(
            RecordingPermissionPolicy.READ_HEART_RATE,
            RecordingPermissionPolicy.ACTIVITY_RECOGNITION,
        )
        val coordinator = PermissionStartCoordinator()

        val resolution = coordinator.requestStart(
            ActivityChoice.RUNNING,
            state(
                requested = healthPermissions,
                denied = healthPermissions,
            ),
        )

        assertEquals(StartPermissionResolution.Blocked, resolution)
    }

    private fun state(
        granted: Set<String> = emptySet(),
        requested: Set<String> = emptySet(),
        denied: Set<String> = emptySet(),
        rationale: Set<String> = emptySet(),
    ): RecordingPermissionPolicy.RuntimeState =
        RecordingPermissionPolicy.runtimeState(
            36,
            true,
            true,
            granted::contains,
            requested::contains,
            denied::contains,
            rationale::contains,
        )
}
