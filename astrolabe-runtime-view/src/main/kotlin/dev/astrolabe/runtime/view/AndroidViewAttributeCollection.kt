//
//  AndroidViewAttributeCollection.kt
//  astrolabe-runtime-android
//
//  Created by 轩辕十四 on 2026/7/22.
//

package dev.astrolabe.runtime.view

import android.graphics.Color
import android.view.View
import dev.astrolabe.protocol.RuntimeAttribute
import dev.astrolabe.protocol.RuntimeAttributeCategory
import dev.astrolabe.protocol.RuntimeAttributeIdentifier
import dev.astrolabe.protocol.RuntimeAttributeSection
import dev.astrolabe.protocol.RuntimeAttributeValue
import dev.astrolabe.protocol.RuntimeColor
import dev.astrolabe.protocol.RuntimeNamespacedIdentifier

/** Collects one cohesive category of attributes from supported Android Views. */
internal interface AndroidViewAttributeCollecting {
    val category: RuntimeAttributeCategory

    fun supports(view: View): Boolean

    fun attributes(view: View): List<RuntimeAttribute>
}

/** Selects and combines independent View attribute collectors in stable order. */
internal class AndroidViewAttributeCollectorRegistry(
    private val collectors: List<AndroidViewAttributeCollecting> = defaultCollectors
) {
    init {
        require(collectors.map { collector -> collector.category }.distinct().size == collectors.size) {
            "Android View attribute collectors must use unique categories"
        }
    }

    fun categories(view: View): List<RuntimeNamespacedIdentifier> = collectors.mapNotNull { collector ->
        if (collector.supports(view)) {
            RuntimeNamespacedIdentifier(collector.category.rawValue)
        } else {
            null
        }
    }

    fun sections(view: View): List<RuntimeAttributeSection> = collectors.mapNotNull { collector ->
        if (!collector.supports(view)) {
            return@mapNotNull null
        }
        RuntimeAttributeSection(
            category = collector.category,
            attributes = collector.attributes(view)
        )
    }

    private companion object {
        val defaultCollectors: List<AndroidViewAttributeCollecting> = listOf(
            AndroidCommonAttributeCollector(),
            AndroidAccessibilityAttributeCollector(),
            AndroidTextAttributeCollector(),
            AndroidTextInputAttributeCollector(),
            AndroidImageAttributeCollector(),
            AndroidControlAttributeCollector(),
            AndroidScrollAttributeCollector()
        )
    }
}

internal object AndroidViewDetailSchema {
    val commonCategory = RuntimeAttributeCategory("android.common")
    val accessibilityCategory = RuntimeAttributeCategory("android.accessibility")
    val textCategory = RuntimeAttributeCategory("android.text")
    val textInputCategory = RuntimeAttributeCategory("android.textInput")
    val imageCategory = RuntimeAttributeCategory("android.image")
    val controlCategory = RuntimeAttributeCategory("android.control")
    val scrollCategory = RuntimeAttributeCategory("android.scroll")
}

internal fun runtimeAttribute(
    identifier: String,
    value: RuntimeAttributeValue
): RuntimeAttribute = RuntimeAttribute(
    identifier = RuntimeAttributeIdentifier(identifier),
    value = value
)

internal fun runtimeColor(color: Int): RuntimeColor = RuntimeColor(
    colorSpace = "sRGB",
    red = Color.red(color).toDouble() / COLOR_COMPONENT_MAXIMUM,
    green = Color.green(color).toDouble() / COLOR_COMPONENT_MAXIMUM,
    blue = Color.blue(color).toDouble() / COLOR_COMPONENT_MAXIMUM,
    alpha = Color.alpha(color).toDouble() / COLOR_COMPONENT_MAXIMUM
)

internal fun nonempty(value: CharSequence?): String? = value
    ?.toString()
    ?.takeIf(String::isNotEmpty)

private const val COLOR_COMPONENT_MAXIMUM: Double = 255.0
