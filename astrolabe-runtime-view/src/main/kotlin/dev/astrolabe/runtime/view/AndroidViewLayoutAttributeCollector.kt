//
//  AndroidViewLayoutAttributeCollector.kt
//  astrolabe-runtime-android
//
//  Created by 轩辕十四 on 2026/8/9.
//

package dev.astrolabe.runtime.view

import android.view.View
import android.widget.LinearLayout
import androidx.constraintlayout.widget.ConstraintLayout
import dev.astrolabe.protocol.RuntimeAttribute
import dev.astrolabe.protocol.RuntimeAttributeCategory
import dev.astrolabe.protocol.RuntimeAttributeValue
import dev.astrolabe.protocol.RuntimeExtensionMap
import dev.astrolabe.protocol.RuntimeLayoutAnchor
import dev.astrolabe.protocol.RuntimeLayoutRelation
import dev.astrolabe.protocol.RuntimeLayoutRelationKind
import dev.astrolabe.protocol.RuntimeMeasurement
import dev.astrolabe.protocol.RuntimeMeasurementUnit
import dev.astrolabe.runtime.core.RuntimeNodeRegistry

/** Collects platform-neutral relations declared by Android View layout parameters. */
internal class AndroidViewLayoutAttributeCollector(
    nodeRegistry: RuntimeNodeRegistry<View>
) : AndroidViewAttributeCollecting {
    override val category: RuntimeAttributeCategory =
        AndroidViewDetailSchema.commonLayoutCategory

    private val projectorRegistry = AndroidViewLayoutRelationProjectorRegistry(nodeRegistry)

    override fun supports(view: View): Boolean = true

    override fun attributes(view: View): List<RuntimeAttribute> = listOf(
        runtimeAttribute(
            "common.layout.relations",
            RuntimeAttributeValue.LayoutRelations(projectorRegistry.relations(view))
        )
    )
}

/** Projects one Android layout source into normalized relations. */
private interface AndroidViewLayoutRelationProjecting {
    fun relations(view: View, density: Double): List<RuntimeLayoutRelation>
}

/** Combines independent layout projectors in stable semantic order. */
private class AndroidViewLayoutRelationProjectorRegistry(
    nodeRegistry: RuntimeNodeRegistry<View>
) {
    private val relationFactory = AndroidViewLayoutRelationFactory(nodeRegistry)
    private val projectors: List<AndroidViewLayoutRelationProjecting> = buildList {
        add(AndroidViewExactDimensionRelationProjector(relationFactory))
        if (isClassAvailable(CONSTRAINT_LAYOUT_CLASS_NAME)) {
            add(AndroidConstraintLayoutRelationProjector(relationFactory))
        }
    }

    fun relations(view: View): List<RuntimeLayoutRelation> {
        val density = view.resources.displayMetrics.density.toDouble()
            .takeIf { value -> value.isFinite() && value > 0.0 }
            ?: 1.0
        return projectors.flatMap { projector -> projector.relations(view, density) }
    }

    private fun isClassAvailable(className: String): Boolean = try {
        Class.forName(
            className,
            false,
            AndroidViewLayoutRelationProjectorRegistry::class.java.classLoader
        )
        true
    } catch (_: ClassNotFoundException) {
        false
    }

    private companion object {
        const val CONSTRAINT_LAYOUT_CLASS_NAME =
            "androidx.constraintlayout.widget.ConstraintLayout"
    }
}

/** Projects only exact LayoutParams dimensions into normalized constant relations. */
private class AndroidViewExactDimensionRelationProjector(
    private val relationFactory: AndroidViewLayoutRelationFactory
) : AndroidViewLayoutRelationProjecting {
    override fun relations(view: View, density: Double): List<RuntimeLayoutRelation> {
        val layoutParams = view.layoutParams ?: return emptyList()
        return listOfNotNull(
            relationFactory.constant(
                view,
                "width",
                exactPixelValue(view, "width", layoutParams.width),
                density
            ),
            relationFactory.constant(
                view,
                "height",
                exactPixelValue(view, "height", layoutParams.height),
                density
            )
        )
    }

    private fun exactPixelValue(view: View, anchor: String, pixelValue: Int): Int? {
        if (pixelValue <= 0) {
            return null
        }
        val parent = view.parent as? LinearLayout ?: return pixelValue
        val layoutParams = view.layoutParams as? LinearLayout.LayoutParams ?: return pixelValue
        val weightedAnchor = when (parent.orientation) {
            LinearLayout.HORIZONTAL -> "width"
            LinearLayout.VERTICAL -> "height"
            else -> null
        }
        return pixelValue.takeUnless {
            layoutParams.weight > 0.0f && anchor == weightedAnchor
        }
    }
}

/** Projects ConstraintLayout side and baseline anchors into cross-View relations. */
private class AndroidConstraintLayoutRelationProjector(
    private val relationFactory: AndroidViewLayoutRelationFactory
) : AndroidViewLayoutRelationProjecting {
    override fun relations(view: View, density: Double): List<RuntimeLayoutRelation> {
        val parent = view.parent as? ConstraintLayout ?: return emptyList()
        val layoutParams = view.layoutParams as? ConstraintLayout.LayoutParams
            ?: return emptyList()
        return listOfNotNull(
            relation(parent, view, "start", "start", layoutParams.startToStart,
                layoutParams.marginStart, layoutParams.goneStartMargin, 1.0, density),
            relation(parent, view, "start", "end", layoutParams.startToEnd,
                layoutParams.marginStart, layoutParams.goneStartMargin, 1.0, density),
            relation(parent, view, "end", "start", layoutParams.endToStart,
                layoutParams.marginEnd, layoutParams.goneEndMargin, -1.0, density),
            relation(parent, view, "end", "end", layoutParams.endToEnd,
                layoutParams.marginEnd, layoutParams.goneEndMargin, -1.0, density),
            relation(parent, view, "left", "left", layoutParams.leftToLeft,
                layoutParams.leftMargin, layoutParams.goneLeftMargin, 1.0, density),
            relation(parent, view, "left", "right", layoutParams.leftToRight,
                layoutParams.leftMargin, layoutParams.goneLeftMargin, 1.0, density),
            relation(parent, view, "right", "left", layoutParams.rightToLeft,
                layoutParams.rightMargin, layoutParams.goneRightMargin, -1.0, density),
            relation(parent, view, "right", "right", layoutParams.rightToRight,
                layoutParams.rightMargin, layoutParams.goneRightMargin, -1.0, density),
            relation(parent, view, "top", "top", layoutParams.topToTop,
                layoutParams.topMargin, layoutParams.goneTopMargin, 1.0, density),
            relation(parent, view, "top", "bottom", layoutParams.topToBottom,
                layoutParams.topMargin, layoutParams.goneTopMargin, 1.0, density),
            relation(parent, view, "bottom", "top", layoutParams.bottomToTop,
                layoutParams.bottomMargin, layoutParams.goneBottomMargin, -1.0, density),
            relation(parent, view, "bottom", "bottom", layoutParams.bottomToBottom,
                layoutParams.bottomMargin, layoutParams.goneBottomMargin, -1.0, density),
            relation(parent, view, "baseline", "baseline", layoutParams.baselineToBaseline,
                layoutParams.baselineMargin, layoutParams.goneBaselineMargin, 1.0, density),
            relation(parent, view, "baseline", "top", layoutParams.baselineToTop,
                layoutParams.baselineMargin, layoutParams.goneBaselineMargin, 1.0, density),
            relation(parent, view, "baseline", "bottom", layoutParams.baselineToBottom,
                layoutParams.baselineMargin, layoutParams.goneBaselineMargin, 1.0, density)
        )
    }

    private fun relation(
        parent: ConstraintLayout,
        source: View,
        sourceAnchor: String,
        targetAnchor: String,
        targetID: Int,
        margin: Int,
        goneMargin: Int,
        marginSign: Double,
        density: Double
    ): RuntimeLayoutRelation? {
        val target = targetView(parent, targetID) ?: return null
        val effectiveMargin = if (
            target.visibility == View.GONE &&
            goneMargin != ConstraintLayout.LayoutParams.GONE_UNSET
        ) {
            goneMargin
        } else {
            margin
        }
        return relationFactory.anchored(
            source = source,
            sourceAnchor = sourceAnchor,
            target = target,
            targetAnchor = targetAnchor,
            pixelOffset = effectiveMargin.toDouble() * marginSign,
            density = density
        )
    }

    private fun targetView(parent: ConstraintLayout, targetID: Int): View? {
        if (targetID == ConstraintLayout.LayoutParams.PARENT_ID) {
            return parent
        }
        if (targetID == ConstraintLayout.LayoutParams.UNSET) {
            return null
        }
        for (index in 0 until parent.childCount) {
            val child = parent.getChildAt(index)
            if (child.id == targetID) {
                return child
            }
        }
        return null
    }
}

/** Owns normalized Runtime relation construction for every Android layout adapter. */
private class AndroidViewLayoutRelationFactory(
    private val nodeRegistry: RuntimeNodeRegistry<View>
) {
    fun constant(
        source: View,
        sourceAnchor: String,
        pixelOffset: Int?,
        density: Double
    ): RuntimeLayoutRelation? {
        if (pixelOffset == null) {
            return null
        }
        return relation(
            source = source,
            sourceAnchor = sourceAnchor,
            target = null,
            targetAnchor = null,
            pixelOffset = pixelOffset.toDouble(),
            density = density
        )
    }

    fun anchored(
        source: View,
        sourceAnchor: String,
        target: View,
        targetAnchor: String,
        pixelOffset: Double,
        density: Double
    ): RuntimeLayoutRelation = relation(
        source = source,
        sourceAnchor = sourceAnchor,
        target = target,
        targetAnchor = targetAnchor,
        pixelOffset = pixelOffset,
        density = density
    )

    private fun relation(
        source: View,
        sourceAnchor: String,
        target: View?,
        targetAnchor: String?,
        pixelOffset: Double,
        density: Double
    ): RuntimeLayoutRelation {
        return RuntimeLayoutRelation(
            identifier = null,
            source = RuntimeLayoutAnchor(
                nodeID = nodeRegistry.nodeID(source),
                anchor = sourceAnchor
            ),
            relation = RuntimeLayoutRelationKind.equal,
            target = target?.let { targetView ->
                RuntimeLayoutAnchor(
                    nodeID = nodeRegistry.nodeID(targetView),
                    anchor = checkNotNull(targetAnchor)
                )
            },
            multiplier = 1.0,
            offset = RuntimeMeasurement(
                value = pixelOffset / density,
                unit = RuntimeMeasurementUnit.logical
            ),
            strength = null,
            active = null,
            extensions = RuntimeExtensionMap(emptyMap())
        )
    }
}
