//
//  AndroidDisplayEnvironmentProvider.kt
//  astrolabe-runtime-android
//
//  Created by 轩辕十四 on 2026/7/20.
//

package dev.astrolabe.runtime.view

import android.content.Context
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.view.Display
import dev.astrolabe.protocol.RuntimeCoordinateRect
import dev.astrolabe.protocol.RuntimeCoordinateSpace
import dev.astrolabe.protocol.RuntimeDisplayInfo
import dev.astrolabe.protocol.RuntimeMeasuredSize
import dev.astrolabe.protocol.RuntimeMeasurementUnit
import dev.astrolabe.protocol.RuntimeScale

/** Display and viewport facts shared by application and hierarchy providers. */
public data class AndroidDisplayEnvironmentSnapshot(
    /** Display dimensions and logical-to-pixel scale. */
    public val display: RuntimeDisplayInfo,
    /** Current application viewport in screen logical coordinates. */
    public val viewport: RuntimeCoordinateRect,
    /** Open orientation identifier for the current configuration. */
    public val orientation: String,
    /** Pixel density used to convert Android coordinates to logical units. */
    public val density: Double
)

/** Captures Android display facts without depending on any inspected View. */
public class AndroidDisplayEnvironmentProvider(context: Context) {
    private val applicationContext = context.applicationContext

    /** Captures current display facts. */
    @Suppress("DEPRECATION")
    public fun capture(): AndroidDisplayEnvironmentSnapshot {
        val metrics = applicationContext.resources.displayMetrics
        val density = metrics.density.toDouble().takeIf { it > 0.0 } ?: 1.0
        val pixelWidth = metrics.widthPixels.toDouble().coerceAtLeast(0.0)
        val pixelHeight = metrics.heightPixels.toDouble().coerceAtLeast(0.0)
        val display = applicationContext.getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
        val logicalWidth = pixelWidth / density
        val logicalHeight = pixelHeight / density
        return AndroidDisplayEnvironmentSnapshot(
            display = RuntimeDisplayInfo(
                logicalSize = RuntimeMeasuredSize(
                    logicalWidth,
                    logicalHeight,
                    RuntimeMeasurementUnit.logical
                ),
                pixelSize = RuntimeMeasuredSize(
                    pixelWidth,
                    pixelHeight,
                    RuntimeMeasurementUnit.pixel
                ),
                logicalToPixelScale = RuntimeScale(density, density),
                maximumRefreshRate = display?.mode?.refreshRate?.toDouble()
                    ?.takeIf { refreshRate -> refreshRate > 0.0 }
            ),
            viewport = RuntimeCoordinateRect(
                x = 0.0,
                y = 0.0,
                width = logicalWidth,
                height = logicalHeight,
                coordinateSpace = RuntimeCoordinateSpace.screen,
                unit = RuntimeMeasurementUnit.logical
            ),
            orientation = when (applicationContext.resources.configuration.orientation) {
                Configuration.ORIENTATION_PORTRAIT -> "portrait"
                Configuration.ORIENTATION_LANDSCAPE -> "landscape"
                else -> "unknown"
            },
            density = density
        )
    }
}
