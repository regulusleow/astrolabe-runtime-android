//
//  AndroidViewGeometryMapper.kt
//  astrolabe-runtime-android
//
//  Created by 轩辕十四 on 2026/7/20.
//

package dev.astrolabe.runtime.view

import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import dev.astrolabe.protocol.RuntimeCoordinateRect
import dev.astrolabe.protocol.RuntimeCoordinateSpace
import dev.astrolabe.protocol.RuntimeMeasurementUnit
import dev.astrolabe.protocol.RuntimeNodeGeometry
import dev.astrolabe.protocol.RuntimeNodeVisibility

/** Geometry and inherited visibility state calculated for one View. */
internal data class AndroidMappedViewGeometry(
    /** Wire geometry in logical coordinate spaces. */
    val geometry: RuntimeNodeGeometry,
    /** Explicit and inherited visibility facts. */
    val visibility: RuntimeNodeVisibility,
    /** Whether this View clips descendant content. */
    val clipsContent: Boolean,
    /** Hidden state inherited by direct children. */
    val descendantHidden: Boolean,
    /** Effective opacity inherited by direct children. */
    val descendantOpacity: Double,
    /** Screen-pixel clip rectangle inherited by direct children. */
    val descendantClipInScreenPixels: RectF?
)

/** Maps Android pixel geometry into Protocol logical coordinate spaces. */
internal class AndroidViewGeometryMapper(
    private val density: Double,
    viewport: RuntimeCoordinateRect
) {
    private val viewportInScreenPixels = RectF(
        (viewport.x * density).toFloat(),
        (viewport.y * density).toFloat(),
        ((viewport.x + viewport.width) * density).toFloat(),
        ((viewport.y + viewport.height) * density).toFloat()
    )

    init {
        require(density > 0.0) { "Android display density must be greater than zero" }
    }

    fun map(
        view: View,
        isRoot: Boolean,
        ancestorHidden: Boolean,
        ancestorOpacity: Double,
        ancestorClipInScreenPixels: RectF?
    ): AndroidMappedViewGeometry {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val frameInScreenPixels = RectF(
            location[0].toFloat(),
            location[1].toFloat(),
            location[0].toFloat() + view.width.toFloat(),
            location[1].toFloat() + view.height.toFloat()
        )
        val hidden = view.visibility != View.VISIBLE
        val opacity = view.alpha.toDouble().coerceIn(0.0, 1.0)
        val effectiveOpacity = (ancestorOpacity * opacity).coerceIn(0.0, 1.0)
        val hasArea = view.width > 0 && view.height > 0
        val intersectsViewport = view.isAttachedToWindow &&
            hasArea &&
            RectF.intersects(frameInScreenPixels, viewportInScreenPixels)
        val visibleWithinAncestors = ancestorClipInScreenPixels
            ?.let { clip -> intersection(frameInScreenPixels, clip) }
        val fullyClippedByAncestor = intersectsViewport && visibleWithinAncestors == null
        val clipsContent = view.clipBounds != null ||
            ((view as? ViewGroup)?.clipChildren == true)
        val descendantClip = descendantClip(
            view = view,
            frameInScreenPixels = frameInScreenPixels,
            ancestorClipInScreenPixels = ancestorClipInScreenPixels,
            clipsContent = clipsContent
        )
        return AndroidMappedViewGeometry(
            geometry = RuntimeNodeGeometry(
                bounds = logicalRect(
                    x = 0.0,
                    y = 0.0,
                    width = view.width.toDouble(),
                    height = view.height.toDouble(),
                    coordinateSpace = RuntimeCoordinateSpace.local
                ),
                frameInParent = if (isRoot) {
                    null
                } else {
                    logicalRect(
                        x = view.x.toDouble(),
                        y = view.y.toDouble(),
                        width = view.width.toDouble(),
                        height = view.height.toDouble(),
                        coordinateSpace = RuntimeCoordinateSpace.parent
                    )
                },
                frameInScreen = logicalRect(
                    x = frameInScreenPixels.left.toDouble(),
                    y = frameInScreenPixels.top.toDouble(),
                    width = frameInScreenPixels.width().toDouble(),
                    height = frameInScreenPixels.height().toDouble(),
                    coordinateSpace = RuntimeCoordinateSpace.screen
                )
            ),
            visibility = RuntimeNodeVisibility(
                hidden = hidden,
                hiddenByAncestor = ancestorHidden,
                opacity = opacity,
                effectiveOpacity = effectiveOpacity,
                intersectsViewport = intersectsViewport,
                fullyClippedByAncestor = fullyClippedByAncestor,
                onscreen = !hidden &&
                    !ancestorHidden &&
                    effectiveOpacity > VISIBILITY_THRESHOLD &&
                    intersectsViewport &&
                    !fullyClippedByAncestor
            ),
            clipsContent = clipsContent,
            descendantHidden = ancestorHidden || hidden,
            descendantOpacity = effectiveOpacity,
            descendantClipInScreenPixels = descendantClip
        )
    }

    private fun descendantClip(
        view: View,
        frameInScreenPixels: RectF,
        ancestorClipInScreenPixels: RectF?,
        clipsContent: Boolean
    ): RectF? {
        var clip = ancestorClipInScreenPixels?.let(::RectF) ?: return null
        if (clipsContent) {
            clip = boundedIntersection(clip, frameInScreenPixels)
        }
        val viewGroup = view as? ViewGroup
        if (viewGroup?.clipToPadding == true) {
            val contentInScreen = RectF(
                frameInScreenPixels.left + viewGroup.paddingLeft,
                frameInScreenPixels.top + viewGroup.paddingTop,
                frameInScreenPixels.right - viewGroup.paddingRight,
                frameInScreenPixels.bottom - viewGroup.paddingBottom
            )
            clip = boundedIntersection(clip, contentInScreen)
        }
        view.clipBounds?.let { localClip ->
            val clipInScreen = RectF(
                frameInScreenPixels.left + localClip.left,
                frameInScreenPixels.top + localClip.top,
                frameInScreenPixels.left + localClip.right,
                frameInScreenPixels.top + localClip.bottom
            )
            clip = boundedIntersection(clip, clipInScreen)
        }
        return clip
    }

    private fun boundedIntersection(first: RectF, second: RectF): RectF =
        intersection(first, second) ?: RectF()

    private fun logicalRect(
        x: Double,
        y: Double,
        width: Double,
        height: Double,
        coordinateSpace: RuntimeCoordinateSpace
    ): RuntimeCoordinateRect = RuntimeCoordinateRect(
        x = x / density,
        y = y / density,
        width = width / density,
        height = height / density,
        coordinateSpace = coordinateSpace,
        unit = RuntimeMeasurementUnit.logical
    )

    private fun intersection(first: RectF, second: RectF): RectF? =
        RectF(first).takeIf { result -> result.intersect(second) && !result.isEmpty }

    private companion object {
        const val VISIBILITY_THRESHOLD: Double = 0.01
    }
}
