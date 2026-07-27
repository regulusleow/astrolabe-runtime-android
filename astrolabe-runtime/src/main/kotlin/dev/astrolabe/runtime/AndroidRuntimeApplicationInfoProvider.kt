//
//  AndroidRuntimeApplicationInfoProvider.kt
//  astrolabe-runtime-android
//
//  Created by 轩辕十四 on 2026/7/20.
//

package dev.astrolabe.runtime

import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build
import android.os.Process
import android.view.View
import dev.astrolabe.protocol.RuntimeApplication
import dev.astrolabe.protocol.RuntimeApplicationInfoPayload
import dev.astrolabe.protocol.RuntimeEnvironment
import dev.astrolabe.protocol.RuntimeLayoutDirection
import dev.astrolabe.protocol.RuntimeOpaqueIdentifier
import dev.astrolabe.protocol.RuntimeTarget
import dev.astrolabe.runtime.core.RuntimeApplicationInfoProvider
import dev.astrolabe.runtime.view.AndroidDisplayEnvironmentProvider
import java.util.Locale

internal class AndroidRuntimeApplicationInfoProvider(
    context: Context,
    private val targetIdentifier: RuntimeOpaqueIdentifier,
    private val displayEnvironmentProvider: AndroidDisplayEnvironmentProvider
) : RuntimeApplicationInfoProvider {
    private val applicationContext = context.applicationContext

    override fun applicationInfo(): RuntimeApplicationInfoPayload {
        val packageManager = applicationContext.packageManager
        val packageName = applicationContext.packageName
        val applicationInfo = applicationContext.applicationInfo
        val packageInfo = packageInfo()
        val displayEnvironment = displayEnvironmentProvider.capture()

        return RuntimeApplicationInfoPayload(
            application = RuntimeApplication(
                identifier = packageName,
                displayName = packageManager.getApplicationLabel(applicationInfo).toString(),
                version = packageInfo?.versionName,
                buildVersion = packageInfo?.astrolabeBuildVersion()
            ),
            target = RuntimeTarget(
                identifier = targetIdentifier,
                processIdentifier = Process.myPid().toString(),
                kind = "application",
                primary = true
            ),
            environment = RuntimeEnvironment(
                platform = "android",
                operatingSystemVersion = Build.VERSION.RELEASE.takeIf(String::isNotBlank)
                    ?: Build.VERSION.SDK_INT.toString(),
                deviceCategory = deviceCategory(),
                deviceName = null,
                deviceModel = Build.MODEL.takeIf(String::isNotBlank),
                virtualDevice = isVirtualDevice(),
                locale = Locale.getDefault().toLanguageTag().takeIf(String::isNotBlank),
                layoutDirection = layoutDirection(),
                display = displayEnvironment.display
            )
        )
    }

    @Suppress("DEPRECATION")
    private fun packageInfo(): PackageInfo? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            applicationContext.packageManager.getPackageInfo(
                applicationContext.packageName,
                android.content.pm.PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            applicationContext.packageManager.getPackageInfo(applicationContext.packageName, 0)
        }
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun PackageInfo.astrolabeBuildVersion(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            longVersionCode.toString()
        } else {
            versionCode.toString()
        }

    private fun deviceCategory(): String =
        if (applicationContext.resources.configuration.smallestScreenWidthDp >= TABLET_WIDTH_DP) {
            "tablet"
        } else {
            "phone"
        }

    private fun layoutDirection(): RuntimeLayoutDirection =
        when (applicationContext.resources.configuration.layoutDirection) {
            View.LAYOUT_DIRECTION_LTR -> RuntimeLayoutDirection.leftToRight
            View.LAYOUT_DIRECTION_RTL -> RuntimeLayoutDirection.rightToLeft
            else -> RuntimeLayoutDirection.unknown
        }

    private fun isVirtualDevice(): Boolean =
        Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.startsWith("unknown") ||
            Build.MODEL.contains("Emulator", ignoreCase = true) ||
            Build.MODEL.contains("Android SDK built for", ignoreCase = true) ||
            Build.MANUFACTURER.contains("Genymotion", ignoreCase = true) ||
            Build.PRODUCT.contains("sdk", ignoreCase = true)

    private companion object {
        const val TABLET_WIDTH_DP: Int = 600
    }
}
