//
//  AndroidViewCommonAttributeCollectors.kt
//  astrolabe-runtime-android
//
//  Created by 轩辕十四 on 2026/7/22.
//

package dev.astrolabe.runtime.view

import android.content.res.Resources
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.View
import dev.astrolabe.protocol.RuntimeAttribute
import dev.astrolabe.protocol.RuntimeAttributeCategory
import dev.astrolabe.protocol.RuntimeAttributeValue
import dev.astrolabe.protocol.RuntimeCoordinateRect
import dev.astrolabe.protocol.RuntimeCoordinateSpace
import dev.astrolabe.protocol.RuntimeInsets
import dev.astrolabe.protocol.RuntimeMeasurementUnit

internal class AndroidCommonAttributeCollector : AndroidViewAttributeCollecting {
    override val category: RuntimeAttributeCategory = AndroidViewDetailSchema.commonCategory

    override fun supports(view: View): Boolean = true

    override fun attributes(view: View): List<RuntimeAttribute> {
        val density = view.resources.displayMetrics.density.toDouble().takeIf { it > 0.0 } ?: 1.0
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val ancestorState = ancestorState(view)
        val effectiveAlpha = (ancestorState.alpha * view.alpha.toDouble()).coerceIn(0.0, 1.0)
        val globalVisibleRect = Rect()
        val hasVisibleArea = view.getGlobalVisibleRect(globalVisibleRect) && !globalVisibleRect.isEmpty
        return buildList {
            add(stringAttribute("android.view.runtimeType", view.javaClass.name))
            add(
                runtimeAttribute(
                    "android.view.classChain",
                    RuntimeAttributeValue.StringList(classChain(view))
                )
            )
            resourceName(view)?.let { name ->
                add(stringAttribute("android.view.resourceName", name))
            }
            if (view.id != View.NO_ID) {
                add(
                    runtimeAttribute(
                        "android.view.resourceID",
                        RuntimeAttributeValue.Integer(view.id.toLong())
                    )
                )
            }
            add(
                rectAttribute(
                    "android.view.bounds",
                    x = 0.0,
                    y = 0.0,
                    width = view.width.toDouble() / density,
                    height = view.height.toDouble() / density,
                    coordinateSpace = RuntimeCoordinateSpace.local
                )
            )
            add(
                rectAttribute(
                    "android.view.frameInParent",
                    x = view.x.toDouble() / density,
                    y = view.y.toDouble() / density,
                    width = view.width.toDouble() / density,
                    height = view.height.toDouble() / density,
                    coordinateSpace = RuntimeCoordinateSpace.parent
                )
            )
            add(
                rectAttribute(
                    "android.view.frameInScreen",
                    x = location[0].toDouble() / density,
                    y = location[1].toDouble() / density,
                    width = view.width.toDouble() / density,
                    height = view.height.toDouble() / density,
                    coordinateSpace = RuntimeCoordinateSpace.screen
                )
            )
            add(stringAttribute("android.view.visibility", visibilityName(view.visibility)))
            add(booleanAttribute("android.view.hidden", view.visibility != View.VISIBLE))
            add(booleanAttribute("android.view.hiddenByAncestor", ancestorState.hidden))
            add(numberAttribute("android.view.alpha", view.alpha.toDouble()))
            add(numberAttribute("android.view.effectiveAlpha", effectiveAlpha))
            add(
                booleanAttribute(
                    "android.view.onscreen",
                    view.visibility == View.VISIBLE &&
                        !ancestorState.hidden &&
                        effectiveAlpha > VISIBILITY_THRESHOLD &&
                        view.isAttachedToWindow &&
                        hasVisibleArea
                )
            )
            add(booleanAttribute("android.view.enabled", view.isEnabled))
            add(booleanAttribute("android.view.clickable", view.isClickable))
            add(booleanAttribute("android.view.longClickable", view.isLongClickable))
            add(booleanAttribute("android.view.focusable", view.isFocusable))
            add(booleanAttribute("android.view.selected", view.isSelected))
            add(booleanAttribute("android.view.activated", view.isActivated))
            add(
                runtimeAttribute(
                    "android.view.padding",
                    RuntimeAttributeValue.Insets(
                        RuntimeInsets(
                            top = view.paddingTop.toDouble() / density,
                            left = view.paddingLeft.toDouble() / density,
                            bottom = view.paddingBottom.toDouble() / density,
                            right = view.paddingRight.toDouble() / density,
                            unit = RuntimeMeasurementUnit.logical
                        )
                    )
                )
            )
            (view.background as? ColorDrawable)?.color?.let { color ->
                add(
                    runtimeAttribute(
                        "android.view.backgroundColor",
                        RuntimeAttributeValue.Color(runtimeColor(color))
                    )
                )
            }
        }
    }

    private fun classChain(view: View): List<String> =
        generateSequence<Class<*>>(view.javaClass) { type -> type.superclass }
            .map(Class<*>::getName)
            .toList()

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

    private fun ancestorState(view: View): AndroidAncestorState {
        var hidden = false
        var alpha = 1.0
        var ancestor = view.parent as? View
        while (ancestor != null) {
            hidden = hidden || ancestor.visibility != View.VISIBLE
            alpha *= ancestor.alpha.toDouble()
            ancestor = ancestor.parent as? View
        }
        return AndroidAncestorState(hidden = hidden, alpha = alpha.coerceIn(0.0, 1.0))
    }
}

internal class AndroidAccessibilityAttributeCollector : AndroidViewAttributeCollecting {
    override val category: RuntimeAttributeCategory = AndroidViewDetailSchema.accessibilityCategory

    override fun supports(view: View): Boolean = true

    override fun attributes(view: View): List<RuntimeAttribute> = buildList {
        nonempty(view.contentDescription)?.let { value ->
            add(stringAttribute("android.accessibility.contentDescription", value))
        }
        add(
            stringAttribute(
                "android.accessibility.importance",
                accessibilityImportanceName(view.importantForAccessibility)
            )
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            add(
                booleanAttribute(
                    "android.accessibility.heading",
                    view.isAccessibilityHeading
                )
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nonempty(view.tooltipText)?.let { value ->
                add(stringAttribute("android.accessibility.tooltip", value))
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            nonempty(view.stateDescription)?.let { value ->
                add(stringAttribute("android.accessibility.stateDescription", value))
            }
        }
    }
}

private data class AndroidAncestorState(
    /** Whether any ancestor is not visible. */
    val hidden: Boolean,
    /** Product of ancestor alpha values. */
    val alpha: Double
)

private fun stringAttribute(identifier: String, value: String): RuntimeAttribute =
    runtimeAttribute(identifier, RuntimeAttributeValue.StringValue(value))

private fun booleanAttribute(identifier: String, value: Boolean): RuntimeAttribute =
    runtimeAttribute(identifier, RuntimeAttributeValue.BooleanValue(value))

private fun numberAttribute(identifier: String, value: Double): RuntimeAttribute =
    runtimeAttribute(identifier, RuntimeAttributeValue.Number(value.coerceIn(0.0, 1.0)))

private fun rectAttribute(
    identifier: String,
    x: Double,
    y: Double,
    width: Double,
    height: Double,
    coordinateSpace: RuntimeCoordinateSpace
): RuntimeAttribute = runtimeAttribute(
    identifier,
    RuntimeAttributeValue.Rect(
        RuntimeCoordinateRect(
            x = x,
            y = y,
            width = width.coerceAtLeast(0.0),
            height = height.coerceAtLeast(0.0),
            coordinateSpace = coordinateSpace,
            unit = RuntimeMeasurementUnit.logical
        )
    )
)

internal fun visibilityName(visibility: Int): String = when (visibility) {
    View.VISIBLE -> "visible"
    View.INVISIBLE -> "invisible"
    View.GONE -> "gone"
    else -> "unknown"
}

private fun accessibilityImportanceName(value: Int): String = when (value) {
    View.IMPORTANT_FOR_ACCESSIBILITY_AUTO -> "auto"
    View.IMPORTANT_FOR_ACCESSIBILITY_YES -> "yes"
    View.IMPORTANT_FOR_ACCESSIBILITY_NO -> "no"
    View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS -> "noHideDescendants"
    else -> "unknown"
}

private const val VISIBILITY_THRESHOLD: Double = 0.01
