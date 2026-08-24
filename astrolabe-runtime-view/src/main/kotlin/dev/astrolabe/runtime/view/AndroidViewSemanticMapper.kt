//
//  AndroidViewSemanticMapper.kt
//  astrolabe-runtime-android
//
//  Created by 轩辕十四 on 2026/7/20.
//

package dev.astrolabe.runtime.view

import android.content.res.Resources
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import dev.astrolabe.protocol.RuntimeAccessibility
import dev.astrolabe.protocol.RuntimeColor
import dev.astrolabe.protocol.RuntimeExtensionMap
import dev.astrolabe.protocol.RuntimeInteraction
import dev.astrolabe.protocol.RuntimeNamespacedIdentifier
import dev.astrolabe.protocol.RuntimeType
import kotlinx.serialization.json.JsonPrimitive

/** Maps semantic hierarchy facts without owning traversal or geometry. */
internal class AndroidViewSemanticMapper(
    private val roleStrategies: List<AndroidViewRoleStrategy> = defaultRoleStrategies,
    private val textPrivacyPolicy: AndroidViewTextPrivacyPolicy = AndroidViewTextPrivacyPolicy(),
    private val attributeCollectorRegistry: AndroidViewAttributeCollectorRegistry =
        AndroidViewAttributeCollectorRegistry(textPrivacyPolicy = textPrivacyPolicy)
) {
    private val runtimeTypesByClass = mutableMapOf<Class<*>, RuntimeType>()

    fun role(view: View, isRoot: Boolean): String {
        if (isRoot) {
            return "window"
        }
        return roleStrategies.firstNotNullOfOrNull { strategy -> strategy.role(view) } ?: "view"
    }

    fun runtimeType(view: View): RuntimeType = runtimeTypesByClass.getOrPut(view.javaClass) {
        val classes = generateSequence<Class<*>>(view.javaClass) { type -> type.superclass }
            .map(Class<*>::getName)
            .toList()
        RuntimeType(name = classes.first(), ancestors = classes.drop(1))
    }

    fun backgroundColor(view: View): RuntimeColor? {
        val drawable = view.background as? ColorDrawable ?: return null
        val color = drawable.color
        return RuntimeColor(
            colorSpace = "sRGB",
            red = Color.red(color).toDouble() / COLOR_COMPONENT_MAXIMUM,
            green = Color.green(color).toDouble() / COLOR_COMPONENT_MAXIMUM,
            blue = Color.blue(color).toDouble() / COLOR_COMPONENT_MAXIMUM,
            alpha = Color.alpha(color).toDouble() / COLOR_COMPONENT_MAXIMUM
        )
    }

    fun textPreview(view: View): String? = (view as? TextView)
        ?.let(textPrivacyPolicy::exposedText)
        ?.takeCodePoints(MAXIMUM_TEXT_PREVIEW_CODE_POINTS)

    fun accessibility(view: View): RuntimeAccessibility? {
        val resourceName = resourceName(view)
        val label = view.contentDescription?.toString()?.takeIf(String::isNotEmpty)
        val textView = view as? TextView
        val value = when (view) {
            is CompoundButton -> view.isChecked.toString()
            else -> textView?.let(textPrivacyPolicy::exposedText)
        }
        val hint = textView?.hint?.toString()?.takeIf(String::isNotEmpty)
            ?: tooltip(view)
        val traits = buildList {
            if (view.isClickable) add("clickable")
            if (view.isLongClickable) add("longClickable")
            if (view.isFocusable) add("focusable")
            if (view.isSelected) add("selected")
            if (view is CompoundButton && view.isChecked) add("checked")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && view.isAccessibilityHeading) {
                add("heading")
            }
        }
        val isElement = view.isImportantForAccessibility ||
            label != null ||
            value != null ||
            traits.isNotEmpty()
        if (!isElement && resourceName == null && hint == null) {
            return null
        }
        return RuntimeAccessibility(
            element = isElement,
            identifier = resourceName,
            label = label,
            value = value,
            hint = hint,
            traits = traits
        )
    }

    fun interaction(view: View): RuntimeInteraction = RuntimeInteraction(
        interactive = view.isClickable || view.isLongClickable || view.isFocusable,
        enabled = view.isEnabled,
        selected = view.isSelected,
        focused = view.isFocused
    )

    fun detailCategories(view: View): List<RuntimeNamespacedIdentifier> =
        attributeCollectorRegistry.categories(view)

    fun extensions(view: View): RuntimeExtensionMap {
        val values = buildMap {
            resourceName(view)?.let { name -> put("android.resourceName", JsonPrimitive(name)) }
            if (view.id != View.NO_ID) {
                put("android.resourceID", JsonPrimitive(view.id))
            }
        }
        return RuntimeExtensionMap(values)
    }

    private fun resourceName(view: View): String? {
        if (view.id == View.NO_ID) {
            return null
        }
        return try {
            view.resources.getResourceName(view.id)
        } catch (error: Resources.NotFoundException) {
            null
        }
    }

    private fun tooltip(view: View): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            view.tooltipText?.toString()?.takeIf(String::isNotEmpty)
        } else {
            null
        }

    private companion object {
        const val COLOR_COMPONENT_MAXIMUM: Double = 255.0
        const val MAXIMUM_TEXT_PREVIEW_CODE_POINTS: Int = 256

        val defaultRoleStrategies: List<AndroidViewRoleStrategy> = listOf(
            AndroidViewRoleStrategy { view -> if (view is CompoundButton) "toggle" else null },
            AndroidViewRoleStrategy { view -> if (view is Button) "button" else null },
            AndroidViewRoleStrategy { view -> if (view is EditText) "textInput" else null },
            AndroidViewRoleStrategy { view -> if (view is ImageView) "image" else null },
            AndroidViewRoleStrategy { view -> if (view is TextView) "label" else null },
            AndroidViewRoleStrategy { view ->
                if (view is ScrollView || view is HorizontalScrollView) "scroll" else null
            },
            AndroidViewRoleStrategy { view -> if (view is ViewGroup) "container" else null }
        )
    }
}

internal fun interface AndroidViewRoleStrategy {
    fun role(view: View): String?
}

private fun String.takeCodePoints(maximumCount: Int): String {
    val codePointCount = codePointCount(0, length)
    if (codePointCount <= maximumCount) {
        return this
    }
    return substring(0, offsetByCodePoints(0, maximumCount))
}
