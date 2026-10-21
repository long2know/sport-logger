package com.long2know.sportlogger.healthconnect

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.health.connect.client.HealthConnectClient

internal class HealthConnectAvailabilityDetector(
    private val context: Context,
) {
    fun availability(): HealthConnectAvailability {
        val apiLevel = Build.VERSION.SDK_INT
        val sdkStatus =
            if (apiLevel >= Build.VERSION_CODES.P) {
                HealthConnectClient.getSdkStatus(context)
            } else {
                HealthConnectClient.SDK_UNAVAILABLE
            }
        return classify(
            apiLevel = apiLevel,
            sdkStatus = sdkStatus,
            providerInstalled = providerInstalled(),
        )
    }

    fun providerResolutionIntent(
        availability: HealthConnectAvailability,
    ): Intent? {
        if (!availability.canResolveWithProviderInstall) {
            return null
        }
        val marketIntent = Intent(Intent.ACTION_VIEW).apply {
            setPackage(PLAY_STORE_PACKAGE)
            data =
                Uri.parse(
                    "market://details?id=$PROVIDER_PACKAGE" +
                        "&url=healthconnect%3A%2F%2Fonboarding"
                )
            putExtra("overlay", true)
            putExtra("callerId", context.packageName)
        }
        if (marketIntent.resolveActivity(context.packageManager) != null) {
            return marketIntent
        }
        return Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://play.google.com/store/apps/details?id=$PROVIDER_PACKAGE"),
        )
    }

    private fun providerInstalled(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return false
        }
        return try {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getApplicationInfo(PROVIDER_PACKAGE, 0)
            info.enabled
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    companion object {
        internal const val PROVIDER_PACKAGE = "com.google.android.apps.healthdata"
        private const val PLAY_STORE_PACKAGE = "com.android.vending"

        internal fun classify(
            apiLevel: Int,
            sdkStatus: Int,
            providerInstalled: Boolean,
        ): HealthConnectAvailability =
            when {
                apiLevel < Build.VERSION_CODES.P ->
                    HealthConnectAvailability.UNSUPPORTED_ANDROID_VERSION
                apiLevel >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                    if (sdkStatus == HealthConnectClient.SDK_AVAILABLE) {
                        HealthConnectAvailability.AVAILABLE_FRAMEWORK
                    } else {
                        HealthConnectAvailability.FRAMEWORK_UNAVAILABLE
                    }
                sdkStatus == HealthConnectClient.SDK_AVAILABLE ->
                    HealthConnectAvailability.AVAILABLE_APK
                !providerInstalled ->
                    HealthConnectAvailability.INSTALL_REQUIRED
                else -> HealthConnectAvailability.UPDATE_REQUIRED
            }
    }
}
