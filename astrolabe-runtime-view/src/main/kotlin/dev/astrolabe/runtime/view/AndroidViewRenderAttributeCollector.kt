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
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
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
import java.util.IdentityHashMap
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
        AndroidStateListDrawableAttributeProjector(),
        AndroidInsetDrawableAttributeProjector(),
        AndroidRippleDrawableAttributeProjector(),
        AndroidLayerDrawableAttributeProjector(),
        AndroidColorDrawableAttributeProjector(),
        AndroidGradientDrawableAttributeProjector()
    )
) {
    fun attributes(
        drawable: Drawable,
        prefix: String,
        density: Double,
        drawableState: IntArray
    ): List<RuntimeAttribute> = AndroidDrawableProjectionSession(
        projectors = projectors,
        density = density,
        drawableState = drawableState
    ).attributes(drawable, prefix)
}

/** Projects one supported Drawable type into bounded runtime attributes. */
internal interface AndroidDrawableAttributeProjecting {
    fun supports(drawable: Drawable): Boolean

    fun projection(
        drawable: Drawable,
        prefix: String,
        density: Double,
        drawableState: IntArray
    ): AndroidDrawableProjection
}

/** One-level projection returned by a Drawable projector. */
internal data class AndroidDrawableProjection(
    /** Attributes read directly from this Drawable. */
    val attributes: List<RuntimeAttribute>,
    /** Current child Drawables eligible for bounded recursive projection. */
    val children: List<AndroidDrawableProjectionChild> = emptyList()
)

/** One current child Drawable and the attribute prefix assigned to it. */
internal data class AndroidDrawableProjectionChild(
    /** Child Drawable participating in the current rendered state. */
    val drawable: Drawable,
    /** Attribute prefix under which the child's facts are projected. */
    val prefix: String
)

private class AndroidDrawableProjectionSession(
    private val projectors: List<AndroidDrawableAttributeProjecting>,
    private val density: Double,
    private val drawableState: IntArray
) {
    private val activeDrawables = IdentityHashMap<Drawable, Unit>()

    fun attributes(drawable: Drawable, prefix: String): List<RuntimeAttribute> =
        project(drawable, prefix, depth = 0, includeType = false)

    private fun project(
        drawable: Drawable,
        prefix: String,
        depth: Int,
        includeType: Boolean
    ): List<RuntimeAttribute> {
        if (depth > MAXIMUM_DRAWABLE_PROJECTION_DEPTH || activeDrawables.containsKey(drawable)) {
            return emptyList()
        }
        val projector = runCatching {
            projectors.firstOrNull { candidate -> candidate.supports(drawable) }
        }.getOrNull() ?: return emptyList()
        activeDrawables[drawable] = Unit
        return try {
            val projection = runCatching {
                projector.projection(drawable, prefix, density, drawableState)
            }.getOrNull() ?: return emptyList()
            buildList {
                if (includeType) {
                    add(stringAttribute("$prefix.type", drawable.javaClass.name))
                }
                addAll(projection.attributes)
                projection.children.forEach { child ->
                    addAll(project(child.drawable, child.prefix, depth + 1, includeType = true))
                }
            }
        } finally {
            activeDrawables.remove(drawable)
        }
    }
}

private class AndroidStateListDrawableAttributeProjector :
    AndroidDrawableAttributeProjecting {
    override fun supports(drawable: Drawable): Boolean = drawable is StateListDrawable

    override fun projection(
        drawable: Drawable,
        prefix: String,
        density: Double,
        drawableState: IntArray
    ): AndroidDrawableProjection {
        val stateList = drawable as? StateListDrawable
            ?: return AndroidDrawableProjection(emptyList())
        val current = stateList.current
        return AndroidDrawableProjection(
            attributes = listOf(booleanAttribute("$prefix.current.present", true)),
            children = listOf(AndroidDrawableProjectionChild(current, "$prefix.current"))
        )
    }
}

private class AndroidInsetDrawableAttributeProjector : AndroidDrawableAttributeProjecting {
    override fun supports(drawable: Drawable): Boolean = drawable is InsetDrawable

    override fun projection(
        drawable: Drawable,
        prefix: String,
        density: Double,
        drawableState: IntArray
    ): AndroidDrawableProjection {
        val inset = drawable as? InsetDrawable ?: return AndroidDrawableProjection(emptyList())
        val content = inset.drawable
        return AndroidDrawableProjection(
            attributes = buildList {
                add(booleanAttribute("$prefix.content.present", content != null))
                if (content != null) {
                    addEffectiveInsetFacts(inset.bounds, content.bounds, prefix, density)
                }
            },
            children = content?.let { child ->
                listOf(AndroidDrawableProjectionChild(child, "$prefix.content"))
            }.orEmpty()
        )
    }

    private fun MutableList<RuntimeAttribute>.addEffectiveInsetFacts(
        outerBounds: Rect,
        contentBounds: Rect,
        prefix: String,
        density: Double
    ) {
        if (!outerBounds.hasOrderedEdges() || !contentBounds.hasOrderedEdges()) {
            return
        }
        logicalMeasurement(
            "$prefix.insets.left",
            contentBounds.left - outerBounds.left,
            density
        )?.let(::add)
        logicalMeasurement(
            "$prefix.insets.top",
            contentBounds.top - outerBounds.top,
            density
        )?.let(::add)
        logicalMeasurement(
            "$prefix.insets.right",
            outerBounds.right - contentBounds.right,
            density
        )?.let(::add)
        logicalMeasurement(
            "$prefix.insets.bottom",
            outerBounds.bottom - contentBounds.bottom,
            density
        )?.let(::add)
    }
}

private class AndroidLayerDrawableAttributeProjector : AndroidDrawableAttributeProjecting {
    override fun supports(drawable: Drawable): Boolean = drawable is LayerDrawable

    override fun projection(
        drawable: Drawable,
        prefix: String,
        density: Double,
        drawableState: IntArray
    ): AndroidDrawableProjection {
        val layerDrawable = drawable as? LayerDrawable
            ?: return AndroidDrawableProjection(emptyList())
        val layerCount = layerDrawable.numberOfLayers
        if (layerCount < 0) {
            return AndroidDrawableProjection(emptyList())
        }
        val projectedLayerCount = layerCount.coerceAtMost(MAXIMUM_DRAWABLE_LAYER_COUNT)
        val layerProjections = (0 until projectedLayerCount).map { index ->
            val layerPrefix = "$prefix.layers.layer$index"
            layerProjection(
                layerDrawable = layerDrawable,
                index = index,
                prefix = layerPrefix,
                childPrefix = "$layerPrefix.drawable",
                density = density
            )
        }
        return AndroidDrawableProjection(
            attributes = buildList {
                add(integerAttribute("$prefix.layerCount", layerCount.toLong()))
                add(
                    integerAttribute(
                        "$prefix.projectedLayerCount",
                        projectedLayerCount.toLong()
                    )
                )
                add(
                    booleanAttribute(
                        "$prefix.layersTruncated",
                        projectedLayerCount < layerCount
                    )
                )
                layerProjections.forEach { projection -> addAll(projection.attributes) }
            },
            children = layerProjections.flatMap { projection -> projection.children }
        )
    }
}

private class AndroidRippleDrawableAttributeProjector : AndroidDrawableAttributeProjecting {
    override fun supports(drawable: Drawable): Boolean = drawable is RippleDrawable

    override fun projection(
        drawable: Drawable,
        prefix: String,
        density: Double,
        drawableState: IntArray
    ): AndroidDrawableProjection {
        val ripple = drawable as? RippleDrawable
            ?: return AndroidDrawableProjection(emptyList())
        val layerCount = ripple.numberOfLayers
        if (layerCount < 0) {
            return AndroidDrawableProjection(emptyList())
        }
        val maskIndex = ripple.findIndexByLayerId(android.R.id.mask)
            .takeIf { index -> index in 0 until layerCount }
        val contentLayerCount = layerCount - if (maskIndex == null) 0 else 1
        val contentIndices = buildList {
            var layerIndex = 0
            while (
                layerIndex < layerCount &&
                size < MAXIMUM_DRAWABLE_LAYER_COUNT
            ) {
                if (layerIndex != maskIndex) {
                    add(layerIndex)
                }
                layerIndex += 1
            }
        }
        val contentProjections = contentIndices.mapIndexed { contentIndex, layerIndex ->
            val contentPrefix = "$prefix.contents.content$contentIndex"
            layerProjection(
                layerDrawable = ripple,
                index = layerIndex,
                prefix = contentPrefix,
                childPrefix = "$contentPrefix.drawable",
                density = density
            )
        }
        val maskProjection = maskIndex?.let { index ->
            layerProjection(
                layerDrawable = ripple,
                index = index,
                prefix = "$prefix.mask",
                childPrefix = "$prefix.mask.drawable",
                density = density
            )
        }
        return AndroidDrawableProjection(
            attributes = buildList {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    resolvedColor(ripple.effectColor, drawableState)?.let { color ->
                        add(colorAttribute("$prefix.effectColor", color))
                    }
                }
                add(integerAttribute("$prefix.contentLayerCount", contentLayerCount.toLong()))
                add(
                    integerAttribute(
                        "$prefix.projectedContentLayerCount",
                        contentIndices.size.toLong()
                    )
                )
                add(
                    booleanAttribute(
                        "$prefix.contentLayersTruncated",
                        contentIndices.size < contentLayerCount
                    )
                )
                add(booleanAttribute("$prefix.mask.present", maskProjection != null))
                contentProjections.forEach { projection -> addAll(projection.attributes) }
                maskProjection?.let { projection -> addAll(projection.attributes) }
            },
            children = buildList {
                contentProjections.forEach { projection -> addAll(projection.children) }
                maskProjection?.let { projection -> addAll(projection.children) }
            }
        )
    }
}

private fun layerProjection(
    layerDrawable: LayerDrawable,
    index: Int,
    prefix: String,
    childPrefix: String,
    density: Double
): AndroidDrawableProjection {
    val child = layerDrawable.getDrawable(index)
    return AndroidDrawableProjection(
        attributes = buildList {
            add(integerAttribute("$prefix.id", layerDrawable.getId(index).toLong()))
            child.bounds.takeIf(Rect::hasOrderedEdges)?.let { bounds ->
                add(rectAttribute("$prefix.bounds", bounds, density))
            }
            addLayerInset("$prefix.insets.left", layerDrawable.getLayerInsetLeft(index), density)
            addLayerInset("$prefix.insets.top", layerDrawable.getLayerInsetTop(index), density)
            addLayerInset("$prefix.insets.right", layerDrawable.getLayerInsetRight(index), density)
            addLayerInset("$prefix.insets.bottom", layerDrawable.getLayerInsetBottom(index), density)
        },
        children = listOf(AndroidDrawableProjectionChild(child, childPrefix))
    )
}

private fun MutableList<RuntimeAttribute>.addLayerInset(
    identifier: String,
    pixels: Int,
    density: Double
) {
    if (pixels == LayerDrawable.INSET_UNDEFINED) {
        return
    }
    logicalMeasurement(identifier, pixels, density)?.let(::add)
}

private class AndroidColorDrawableAttributeProjector : AndroidDrawableAttributeProjecting {
    override fun supports(drawable: Drawable): Boolean = drawable is ColorDrawable

    override fun projection(
        drawable: Drawable,
        prefix: String,
        density: Double,
        drawableState: IntArray
    ): AndroidDrawableProjection {
        val colorDrawable = drawable as? ColorDrawable
            ?: return AndroidDrawableProjection(emptyList())
        return AndroidDrawableProjection(
            listOf(colorAttribute("$prefix.color", colorDrawable.color))
        )
    }
}

private class AndroidGradientDrawableAttributeProjector : AndroidDrawableAttributeProjecting {
    override fun supports(drawable: Drawable): Boolean = drawable is GradientDrawable

    override fun projection(
        drawable: Drawable,
        prefix: String,
        density: Double,
        drawableState: IntArray
    ): AndroidDrawableProjection {
        val gradient = drawable as? GradientDrawable
            ?: return AndroidDrawableProjection(emptyList())
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return AndroidDrawableProjection(emptyList())
        }
        return AndroidDrawableProjection(
            attributes = buildList {
                add(stringAttribute("$prefix.shape", shapeName(gradient.shape)))
                resolvedColor(gradient.color, drawableState)?.let { color ->
                    add(colorAttribute("$prefix.color", color))
                }
                addCornerFacts(gradient, prefix, density)
                val colors = gradient.colors
                if (colors != null) {
                    add(
                        stringAttribute(
                            "$prefix.gradient.type",
                            gradientTypeName(gradient.gradientType)
                        )
                    )
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
        )
    }

    private fun MutableList<RuntimeAttribute>.addCornerFacts(
        gradient: GradientDrawable,
        prefix: String,
        density: Double
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return
        }
        val cornerRadii = runCatching { gradient.cornerRadii }.getOrNull()
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

private fun Rect.hasOrderedEdges(): Boolean = left <= right && top <= bottom

private const val MAXIMUM_DRAWABLE_PROJECTION_DEPTH: Int = 8
private const val MAXIMUM_DRAWABLE_LAYER_COUNT: Int = 16
private const val UNSIGNED_INT_MASK: Long = 0xFFFF_FFFFL
