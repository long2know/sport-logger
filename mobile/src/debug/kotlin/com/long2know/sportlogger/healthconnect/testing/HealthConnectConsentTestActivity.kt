package com.long2know.sportlogger.healthconnect.testing

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.long2know.sportlogger.healthconnect.HealthConnectPermissions

class HealthConnectConsentTestActivity : ComponentActivity() {
    private val permissionLauncher =
        registerForActivityResult(HealthConnectPermissions.requestContract()) { granted ->
            sendBroadcast(
                Intent(ACTION_PERMISSION_RESULT)
                    .setPackage(packageName)
                    .putStringArrayListExtra(
                        EXTRA_GRANTED_PERMISSIONS,
                        ArrayList(granted.sorted()),
                    )
            )
            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val requested =
            intent.getStringArrayListExtra(EXTRA_REQUESTED_PERMISSIONS)
                ?.filter(String::isNotEmpty)
                ?.toSet()
                .orEmpty()
        require(requested.isNotEmpty()) {
            "At least one real Health Connect permission is required"
        }
        permissionLauncher.launch(requested)
    }

    companion object {
        const val ACTION_PERMISSION_RESULT =
            "com.long2know.sportlogger.healthconnect.testing.PERMISSION_RESULT"
        const val EXTRA_REQUESTED_PERMISSIONS = "requested_permissions"
        const val EXTRA_GRANTED_PERMISSIONS = "granted_permissions"
    }
}
