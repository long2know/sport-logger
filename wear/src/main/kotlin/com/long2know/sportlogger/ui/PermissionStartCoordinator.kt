package com.long2know.sportlogger.ui

import com.long2know.sportlogger.acquisition.RecordingPermissionPolicy

internal sealed interface StartPermissionResolution {
    data class Start(val choice: ActivityChoice) : StartPermissionResolution

    data class Request(val permissions: List<String>) : StartPermissionResolution

    data object Blocked : StartPermissionResolution
}

internal class PermissionStartCoordinator {
    private var pending: ActivityChoice? = null

    fun requestStart(
        choice: ActivityChoice,
        permissions: RecordingPermissionPolicy.RuntimeState,
    ): StartPermissionResolution {
        if (permissions.hasHealthForegroundPrerequisite()) {
            pending = null
            return StartPermissionResolution.Start(choice)
        }
        pending = choice
        val requestable = permissions.requestableRequiredPermissions
        return if (requestable.isEmpty()) {
            StartPermissionResolution.Blocked
        } else {
            StartPermissionResolution.Request(requestable)
        }
    }

    fun consumePendingIfReady(
        permissions: RecordingPermissionPolicy.RuntimeState,
        serviceReady: Boolean,
    ): ActivityChoice? {
        if (!serviceReady || !permissions.hasHealthForegroundPrerequisite()) {
            return null
        }
        return pending.also { pending = null }
    }
}
