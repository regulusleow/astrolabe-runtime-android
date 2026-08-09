//
//  AndroidViewLayoutAttributeCollector.kt
//  astrolabe-runtime-android
//
//  Created by 轩辕十四 on 2026/8/9.
//

package dev.astrolabe.runtime.view

import android.view.View
import android.widget.LinearLayout
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

    private val projector = AndroidViewLayoutRelationProjector(nodeRegistry)

    override fun supports(view: View): Boolean = true

    override fun attributes(view: View): List<RuntimeAttribute> = listOf(
        runtimeAttribute(
            "common.layout.relations",
            RuntimeAttributeValue.LayoutRelations(projector.relations(view))
        )
    )
}

/** Projects only exact LayoutParams dimensions into normalized constant relations. */
private class AndroidViewLayoutRelationProjector(
    private val nodeRegistry: RuntimeNodeRegistry<View>
) {
    fun relations(view: View): List<RuntimeLayoutRelation> {
        val layoutParams = view.layoutParams ?: return emptyList()
        val density = view.resources.displayMetrics.density.toDouble()
            .takeIf { value -> value.isFinite() && value > 0.0 }
            ?: 1.0
        return listOfNotNull(
            relation(
                view,
                "width",
                exactPixelValue(view, "width", layoutParams.width),
                density
            ),
            relation(
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

    private fun relation(
        view: View,
        anchor: String,
        pixelValue: Int?,
        density: Double
    ): RuntimeLayoutRelation? {
        if (pixelValue == null) {
            return null
        }
        return RuntimeLayoutRelation(
            identifier = null,
            source = RuntimeLayoutAnchor(
                nodeID = nodeRegistry.nodeID(view),
                anchor = anchor
            ),
            relation = RuntimeLayoutRelationKind.equal,
            target = null,
            multiplier = 1.0,
            offset = RuntimeMeasurement(
                value = pixelValue.toDouble() / density,
                unit = RuntimeMeasurementUnit.logical
            ),
            strength = null,
            active = null,
            extensions = RuntimeExtensionMap(emptyMap())
        )
    }
}
