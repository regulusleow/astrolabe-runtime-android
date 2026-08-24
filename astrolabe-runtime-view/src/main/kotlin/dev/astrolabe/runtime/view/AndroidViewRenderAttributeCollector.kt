//
//  AndroidViewRenderAttributeCollector.kt
//  astrolabe-runtime-android
//
//  Created by 轩辕十四 on 2026/8/24.
//

package dev.astrolabe.runtime.view

import android.content.res.ColorStateList
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import dev.astrolabe.protocol.RuntimeAttribute
import dev.astrolabe.protocol.RuntimeAttributeCategory
import dev.astrolabe.protocol.RuntimeAttributeValue
import dev.astrolabe.protocol.RuntimeCoordinateRect
import dev.astrolabe.protocol.RuntimeCoordinateSpace
import dev.astrolabe.protocol.RuntimeMeasuredSize
import dev.astrolabe.protocol.RuntimeMeasurement
import dev.astrolabe.protocol.RuntimeMeasurementUnit
import java.util.Locale

/** Collects Android-native rendering facts without changing drawable or View state. */
internal class AndroidViewRenderAttributeCollector(
    private val drawableProjectorRegistry: AndroidDrawableAttributeProjectorRegistry =
        AndroidDrawableAttributeProjectorRegistry()
) : AndroidViewAttributeCollecting {
    override val category: RuntimeAttributeCategory = AndroidViewDetailSchema.renderCategory

    override fun supports(view: View): Boolean = true

    override fun attributes(view: View): List<RuntimeAttribute> {
        val density = view.resources.displayMetrics.density.toDouble().takeIf { it > 0.0 } ?: 1.0
        return buildList {
            addDrawableFacts(
                drawable = view.background,
                prefix = "android.render.background",
                density = density,
                drawableState = view.drawableState
            )
            addDrawableFacts(
                drawable = view.foreground,
                prefix = "android.render.foreground",
                density = density,
                drawableState = view.drawableState
            )
            resolvedColor(view.backgroundTintList, view.drawableState)?.let { color ->
                add(colorAttribute("android.render.background.tintColor", color))
            }
            resolvedColor(view.foregroundTintList, view.drawableState)?.let { color ->
                add(colorAttribute("android.render.foreground.tintColor", color))
            }
            logicalMeasurement("android.render.elevation", view.elevation, density)?.let(::add)
            logicalMeasurement(
                "android.render.translationZ",
                view.translationZ,
                density
            )?.let(::add)
            add(booleanAttribute("android.render.clipToOutline", view.clipToOutline))
            view.clipBounds?.let { bounds ->
                add(rectAttribute("android.render.clipBounds", bounds, density))
            }
            if (view is ViewGroup) {
                add(booleanAttribute("android.render.clipChildren", view.clipChildren))
                add(booleanAttribute("android.render.clipToPadding", view.clipToPadding))
            }
            addOutlineFacts(view, density)
        }
    }

    private fun MutableList<RuntimeAttribute>.addDrawableFacts(
        drawable: Drawable?,
        prefix: String,
        density: Double,
        drawableState: IntArray
    ) {
        add(booleanAttribute("$prefix.present", drawable != null))
        if (drawable == null) {
            return
        }
        add(stringAttribute("$prefix.type", drawable.javaClass.name))
        addAll(drawableProjectorRegistry.attributes(drawable, prefix, density, drawableState))
    }

    private fun MutableList<RuntimeAttribute>.addOutlineFacts(view: View, density: Double) {
        val provider = view.outlineProvider ?: return
        val outline = runCatching {
            Outline().also { value -> provider.getOutline(view, value) }
        }.getOrNull() ?: return
        add(booleanAttribute("android.render.outline.empty", outline.isEmpty))
        add(booleanAttribute("android.render.outline.canClip", outline.canClip()))
        outline.alpha.toDouble().takeIf(Double::isFinite)?.let { alpha ->
            add(numberAttribute("android.render.outline.alpha", alpha.coerceIn(0.0, 1.0)))
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return
        }
        val bounds = Rect()
        if (outline.getRect(bounds)) {
            add(rectAttribute("android.render.outline.bounds", bounds, density))
        }
        outline.radius.toDouble().takeIf { radius -> radius.isFinite() && radius >= 0.0 }
            ?.let { radius ->
                logicalMeasurement(
                    "android.render.outline.cornerRadius",
                    radius,
                    density
                )?.let(::add)
            }
    }
}

/** Selects one bounded projector for the resolved Drawable type. */
internal class AndroidDrawableAttributeProjectorRegistry(
    private val projectors: List<AndroidDrawableAttributeProjecting> = listOf(
        AndroidColorDrawableAttributeProjector(),
        AndroidGradientDrawableAttributeProjector()
    )
) {
    fun attributes(
        drawable: Drawable,
        prefix: String,
        density: Double,
        drawableState: IntArray
    ): List<RuntimeAttribute> = projectors
        .firstOrNull { projector -> projector.supports(drawable) }
        ?.attributes(drawable, prefix, density, drawableState)
        .orEmpty()
}

/** Projects one supported Drawable type into bounded runtime attributes. */
internal interface AndroidDrawableAttributeProjecting {
    fun supports(drawable: Drawable): Boolean

    fun attributes(
        drawable: Drawable,
        prefix: String,
        density: Double,
        drawableState: IntArray
    ): List<RuntimeAttribute>
}

private class AndroidColorDrawableAttributeProjector : AndroidDrawableAttributeProjecting {
    override fun supports(drawable: Drawable): Boolean = drawable is ColorDrawable

    override fun attributes(
        drawable: Drawable,
        prefix: String,
        density: Double,
        drawableState: IntArray
    ): List<RuntimeAttribute> {
        val colorDrawable = drawable as? ColorDrawable ?: return emptyList()
        return listOf(colorAttribute("$prefix.color", colorDrawable.color))
    }
}

private class AndroidGradientDrawableAttributeProjector : AndroidDrawableAttributeProjecting {
    override fun supports(drawable: Drawable): Boolean = drawable is GradientDrawable

    override fun attributes(
        drawable: Drawable,
        prefix: String,
        density: Double,
        drawableState: IntArray
    ): List<RuntimeAttribute> {
        val gradient = drawable as? GradientDrawable ?: return emptyList()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return emptyList()
        }
        return buildList {
            add(stringAttribute("$prefix.shape", shapeName(gradient.shape)))
            resolvedColor(gradient.color, drawableState)?.let { color ->
                add(colorAttribute("$prefix.color", color))
            }
            addCornerFacts(gradient, prefix, density)
            val colors = gradient.colors
            if (colors != null) {
                add(stringAttribute("$prefix.gradient.type", gradientTypeName(gradient.gradientType)))
                if (gradient.gradientType == GradientDrawable.LINEAR_GRADIENT) {
                    add(
                        stringAttribute(
                            "$prefix.gradient.orientation",
                            orientationName(gradient.orientation)
                        )
                    )
                }
                add(
                    stringListAttribute(
                        "$prefix.gradient.colors",
                        colors.take(MAXIMUM_GRADIENT_COLOR_COUNT).map(::argbHex)
                    )
                )
                add(integerAttribute("$prefix.gradient.colorCount", colors.size.toLong()))
                add(
                    booleanAttribute(
                        "$prefix.gradient.colorsTruncated",
                        colors.size > MAXIMUM_GRADIENT_COLOR_COUNT
                    )
                )
                add(booleanAttribute("$prefix.gradient.useLevel", gradient.useLevel))
                if (gradient.gradientType != GradientDrawable.LINEAR_GRADIENT) {
                    finiteNumberAttribute(
                        "$prefix.gradient.centerX",
                        gradient.gradientCenterX
                    )?.let(::add)
                    finiteNumberAttribute(
                        "$prefix.gradient.centerY",
                        gradient.gradientCenterY
                    )?.let(::add)
                    if (gradient.gradientType == GradientDrawable.RADIAL_GRADIENT) {
                        logicalMeasurement(
                            "$prefix.gradient.radius",
                            gradient.gradientRadius,
                            density
                        )?.let(::add)
                    }
                }
            }
        }
    }

    private fun MutableList<RuntimeAttribute>.addCornerFacts(
        gradient: GradientDrawable,
        prefix: String,
        density: Double
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return
        }
        val cornerRadii = gradient.cornerRadii
        if (cornerRadii != null &&
            cornerRadii.size == CORNER_RADII_VALUE_COUNT &&
            cornerRadii.all { radius -> radius.isFinite() && radius >= 0f }
        ) {
            CORNER_NAMES.forEachIndexed { index, name ->
                val radiusIndex = index * 2
                add(
                    sizeAttribute(
                        identifier = "$prefix.cornerRadii.$name",
                        width = cornerRadii[radiusIndex].toDouble() / density,
                        height = cornerRadii[radiusIndex + 1].toDouble() / density
                    )
                )
            }
            return
        }
        logicalMeasurement("$prefix.cornerRadius", gradient.cornerRadius, density)?.let(::add)
    }

    private companion object {
        const val MAXIMUM_GRADIENT_COLOR_COUNT: Int = 32
        const val CORNER_RADII_VALUE_COUNT: Int = 8
        val CORNER_NAMES: List<String> = listOf(
            "topLeft",
            "topRight",
            "bottomRight",
            "bottomLeft"
        )
    }
}

private fun resolvedColor(colorStateList: ColorStateList?, drawableState: IntArray): Int? =
    colorStateList?.getColorForState(drawableState, colorStateList.defaultColor)

private fun shapeName(shape: Int): String = when (shape) {
    GradientDrawable.RECTANGLE -> "rectangle"
    GradientDrawable.OVAL -> "oval"
    GradientDrawable.LINE -> "line"
    GradientDrawable.RING -> "ring"
    else -> "unknown"
}

private fun gradientTypeName(type: Int): String = when (type) {
    GradientDrawable.LINEAR_GRADIENT -> "linear"
    GradientDrawable.RADIAL_GRADIENT -> "radial"
    GradientDrawable.SWEEP_GRADIENT -> "sweep"
    else -> "unknown"
}

private fun orientationName(orientation: GradientDrawable.Orientation): String = when (orientation) {
    GradientDrawable.Orientation.TOP_BOTTOM -> "topBottom"
    GradientDrawable.Orientation.TR_BL -> "topRightBottomLeft"
    GradientDrawable.Orientation.RIGHT_LEFT -> "rightLeft"
    GradientDrawable.Orientation.BR_TL -> "bottomRightTopLeft"
    GradientDrawable.Orientation.BOTTOM_TOP -> "bottomTop"
    GradientDrawable.Orientation.BL_TR -> "bottomLeftTopRight"
    GradientDrawable.Orientation.LEFT_RIGHT -> "leftRight"
    GradientDrawable.Orientation.TL_BR -> "topLeftBottomRight"
}

private fun argbHex(color: Int): String = String.format(
    Locale.ROOT,
    "#%08X",
    color.toLong() and UNSIGNED_INT_MASK
)

private fun booleanAttribute(identifier: String, value: Boolean): RuntimeAttribute =
    runtimeAttribute(identifier, RuntimeAttributeValue.BooleanValue(value))

private fun integerAttribute(identifier: String, value: Long): RuntimeAttribute =
    runtimeAttribute(identifier, RuntimeAttributeValue.Integer(value))

private fun numberAttribute(identifier: String, value: Double): RuntimeAttribute =
    runtimeAttribute(identifier, RuntimeAttributeValue.Number(value))

private fun finiteNumberAttribute(identifier: String, value: Number): RuntimeAttribute? =
    value.toDouble().takeIf(Double::isFinite)?.let { finiteValue ->
        numberAttribute(identifier, finiteValue)
    }

private fun stringAttribute(identifier: String, value: String): RuntimeAttribute =
    runtimeAttribute(identifier, RuntimeAttributeValue.StringValue(value))

private fun stringListAttribute(identifier: String, value: List<String>): RuntimeAttribute =
    runtimeAttribute(identifier, RuntimeAttributeValue.StringList(value))

private fun colorAttribute(identifier: String, color: Int): RuntimeAttribute =
    runtimeAttribute(identifier, RuntimeAttributeValue.Color(runtimeColor(color)))

private fun logicalMeasurement(
    identifier: String,
    pixels: Number,
    density: Double
): RuntimeAttribute? {
    val value = pixels.toDouble() / density
    if (!value.isFinite()) {
        return null
    }
    return runtimeAttribute(
        identifier,
        RuntimeAttributeValue.Measurement(
            RuntimeMeasurement(value, RuntimeMeasurementUnit.logical)
        )
    )
}

private fun sizeAttribute(
    identifier: String,
    width: Double,
    height: Double
): RuntimeAttribute = runtimeAttribute(
    identifier,
    RuntimeAttributeValue.Size(
        RuntimeMeasuredSize(width, height, RuntimeMeasurementUnit.logical)
    )
)

private fun rectAttribute(
    identifier: String,
    bounds: Rect,
    density: Double
): RuntimeAttribute = runtimeAttribute(
    identifier,
    RuntimeAttributeValue.Rect(
        RuntimeCoordinateRect(
            x = bounds.left.toDouble() / density,
            y = bounds.top.toDouble() / density,
            width = bounds.width().toDouble() / density,
            height = bounds.height().toDouble() / density,
            coordinateSpace = RuntimeCoordinateSpace.local,
            unit = RuntimeMeasurementUnit.logical
        )
    )
)

private const val UNSIGNED_INT_MASK: Long = 0xFFFF_FFFFL
