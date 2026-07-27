//
//  AndroidViewAttributeMutationStrategies.kt
//  astrolabe-runtime-android
//
//  Created by 轩辕十四 on 2026/7/22.
//

package dev.astrolabe.runtime.view

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.util.TypedValue
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import dev.astrolabe.protocol.RuntimeAttributeIdentifier
import dev.astrolabe.protocol.RuntimeAttributeValue
import dev.astrolabe.protocol.RuntimeError
import dev.astrolabe.protocol.RuntimeErrorCode
import dev.astrolabe.protocol.RuntimeMeasurementUnit
import dev.astrolabe.protocol.RuntimePatchableAttribute
import dev.astrolabe.runtime.core.RuntimeAttributeMutation
import dev.astrolabe.runtime.core.RuntimeProviderFailure
import java.lang.ref.WeakReference
import kotlin.math.roundToInt

internal class AndroidTextMutationStrategy : AndroidViewAttributeMutationStrategy {
    override val patchableAttribute: RuntimePatchableAttribute =
        AndroidViewPatchCatalog.stringAttribute(AndroidViewPatchCatalog.text, listOf("text"))
    override val domainIdentifier: String = TEXT_PRESENTATION_DOMAIN
    override val effectIdentifiers: Set<String> = setOf(AndroidViewPatchCatalog.text.rawValue)

    override fun supports(view: View): Boolean = view is TextView

    override fun apply(view: View, value: RuntimeAttributeValue): RuntimeAttributeMutation {
        val textView = view as? TextView ?: throw unsupported(AndroidViewPatchCatalog.text)
        val requested = value.string(AndroidViewPatchCatalog.text)
        val originalText = textView.text
        textView.text = requested
        refresh(textView)
        return RuntimeAttributeMutation(
            originalValue = originalText?.toString()?.let(RuntimeAttributeValue::StringValue),
            actualValue = textView.text?.toString()?.let(RuntimeAttributeValue::StringValue),
            restore = weakRestore(textView) { target ->
                target.text = originalText
                refresh(target)
                target.text?.toString()?.let(RuntimeAttributeValue::StringValue)
            }
        )
    }
}

internal class AndroidFontSizeMutationStrategy : AndroidViewAttributeMutationStrategy {
    override val patchableAttribute: RuntimePatchableAttribute =
        AndroidViewPatchCatalog.measurementAttribute(
            AndroidViewPatchCatalog.fontSize,
            targetRoles = listOf("text"),
            minimum = 0.0,
            minimumExclusive = true
        )
    override val domainIdentifier: String = TEXT_PRESENTATION_DOMAIN
    override val effectIdentifiers: Set<String> = setOf(AndroidViewPatchCatalog.fontSize.rawValue)

    override fun supports(view: View): Boolean = view is TextView

    override fun apply(view: View, value: RuntimeAttributeValue): RuntimeAttributeMutation {
        val textView = view as? TextView ?: throw unsupported(AndroidViewPatchCatalog.fontSize)
        val requested = value.scaledLogical(AndroidViewPatchCatalog.fontSize)
        if (requested <= 0.0) {
            throw invalidValue(AndroidViewPatchCatalog.fontSize, "a positive scaledLogical value")
        }
        val originalPixels = textView.textSize
        val originalValue = scaledLogicalTextSize(textView)
        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, requested.toFloat())
        refresh(textView)
        return RuntimeAttributeMutation(
            originalValue = scaledLogicalMeasurement(originalValue),
            actualValue = scaledLogicalMeasurement(scaledLogicalTextSize(textView)),
            restore = weakRestore(textView) { target ->
                target.setTextSize(TypedValue.COMPLEX_UNIT_PX, originalPixels)
                refresh(target)
                scaledLogicalMeasurement(scaledLogicalTextSize(target))
            }
        )
    }
}

internal class AndroidTextColorMutationStrategy : AndroidViewAttributeMutationStrategy {
    override val patchableAttribute: RuntimePatchableAttribute =
        AndroidViewPatchCatalog.colorAttribute(AndroidViewPatchCatalog.textColor, listOf("text"))
    override val domainIdentifier: String = TEXT_PRESENTATION_DOMAIN
    override val effectIdentifiers: Set<String> = setOf(AndroidViewPatchCatalog.textColor.rawValue)

    override fun supports(view: View): Boolean = view is TextView

    override fun apply(view: View, value: RuntimeAttributeValue): RuntimeAttributeMutation {
        val textView = view as? TextView ?: throw unsupported(AndroidViewPatchCatalog.textColor)
        val requested = value.androidColor(AndroidViewPatchCatalog.textColor)
        val originalColors = textView.textColors
        val original = textView.currentTextColor
        textView.setTextColor(requested)
        refresh(textView)
        return RuntimeAttributeMutation(
            originalValue = colorValue(original),
            actualValue = colorValue(textView.currentTextColor),
            comparisonValue = colorValue(requested),
            restore = weakRestore(textView) { target ->
                target.setTextColor(originalColors)
                refresh(target)
                colorValue(target.currentTextColor)
            }
        )
    }
}

internal class AndroidAlphaMutationStrategy : AndroidViewAttributeMutationStrategy {
    override val patchableAttribute: RuntimePatchableAttribute =
        AndroidViewPatchCatalog.numberAttribute(
            AndroidViewPatchCatalog.alpha,
            targetRoles = emptyList(),
            minimum = 0.0,
            maximum = 1.0
        )
    override val domainIdentifier: String = VIEW_PRESENTATION_DOMAIN
    override val effectIdentifiers: Set<String> = setOf(AndroidViewPatchCatalog.alpha.rawValue)

    override fun supports(view: View): Boolean = true

    override fun apply(view: View, value: RuntimeAttributeValue): RuntimeAttributeMutation {
        val requested = value.number(AndroidViewPatchCatalog.alpha)
        if (requested !in 0.0..1.0) {
            throw invalidValue(AndroidViewPatchCatalog.alpha, "a number from 0 through 1")
        }
        val original = view.alpha
        view.alpha = requested.toFloat()
        refresh(view)
        return RuntimeAttributeMutation(
            originalValue = RuntimeAttributeValue.Number(original.toDouble()),
            actualValue = RuntimeAttributeValue.Number(view.alpha.toDouble()),
            restore = weakRestore(view) { target ->
                target.alpha = original
                refresh(target)
                RuntimeAttributeValue.Number(target.alpha.toDouble())
            }
        )
    }
}

internal class AndroidVisibilityMutationStrategy : AndroidViewAttributeMutationStrategy {
    override val patchableAttribute: RuntimePatchableAttribute =
        AndroidViewPatchCatalog.stringAttribute(
            AndroidViewPatchCatalog.visibility,
            targetRoles = emptyList(),
            allowedValues = VISIBILITY_VALUES.keys.toList()
        )
    override val domainIdentifier: String = VIEW_PRESENTATION_DOMAIN
    override val effectIdentifiers: Set<String> = setOf(AndroidViewPatchCatalog.visibility.rawValue)

    override fun supports(view: View): Boolean = true

    override fun apply(view: View, value: RuntimeAttributeValue): RuntimeAttributeMutation {
        val requestedName = value.string(AndroidViewPatchCatalog.visibility)
        val requested = VISIBILITY_VALUES[requestedName]
            ?: throw invalidValue(AndroidViewPatchCatalog.visibility, VISIBILITY_VALUES.keys.joinToString())
        val original = view.visibility
        view.visibility = requested
        refresh(view)
        return RuntimeAttributeMutation(
            originalValue = RuntimeAttributeValue.StringValue(visibilityName(original)),
            actualValue = RuntimeAttributeValue.StringValue(visibilityName(view.visibility)),
            restore = weakRestore(view) { target ->
                target.visibility = original
                refresh(target)
                RuntimeAttributeValue.StringValue(visibilityName(target.visibility))
            }
        )
    }
}

internal class AndroidBackgroundColorMutationStrategy : AndroidViewAttributeMutationStrategy {
    override val patchableAttribute: RuntimePatchableAttribute =
        AndroidViewPatchCatalog.colorAttribute(
            AndroidViewPatchCatalog.backgroundColor,
            targetRoles = emptyList()
        )
    override val domainIdentifier: String = VIEW_PRESENTATION_DOMAIN
    override val effectIdentifiers: Set<String> = setOf(AndroidViewPatchCatalog.backgroundColor.rawValue)

    override fun supports(view: View): Boolean = true

    override fun apply(view: View, value: RuntimeAttributeValue): RuntimeAttributeMutation {
        val requested = value.androidColor(AndroidViewPatchCatalog.backgroundColor)
        val originalBackground = view.background
        val originalValue = (originalBackground as? ColorDrawable)?.color?.let(::colorValue)
        view.setBackgroundColor(requested)
        refresh(view)
        return RuntimeAttributeMutation(
            originalValue = originalValue,
            actualValue = (view.background as? ColorDrawable)?.color?.let(::colorValue),
            comparisonValue = colorValue(requested),
            restore = weakRestore(view) { target ->
                target.background = originalBackground
                refresh(target)
                (target.background as? ColorDrawable)?.color?.let(::colorValue)
            }
        )
    }
}

internal class AndroidImageScaleTypeMutationStrategy : AndroidViewAttributeMutationStrategy {
    override val patchableAttribute: RuntimePatchableAttribute =
        AndroidViewPatchCatalog.stringAttribute(
            AndroidViewPatchCatalog.imageScaleType,
            targetRoles = listOf("image"),
            allowedValues = ImageView.ScaleType.values().map(Enum<*>::name)
        )
    override val domainIdentifier: String = IMAGE_PRESENTATION_DOMAIN
    override val effectIdentifiers: Set<String> = setOf(AndroidViewPatchCatalog.imageScaleType.rawValue)

    override fun supports(view: View): Boolean = view is ImageView

    override fun apply(view: View, value: RuntimeAttributeValue): RuntimeAttributeMutation {
        val imageView = view as? ImageView ?: throw unsupported(AndroidViewPatchCatalog.imageScaleType)
        val requestedName = value.string(AndroidViewPatchCatalog.imageScaleType)
        val requested = try {
            ImageView.ScaleType.valueOf(requestedName)
        } catch (error: IllegalArgumentException) {
            throw invalidValue(
                AndroidViewPatchCatalog.imageScaleType,
                ImageView.ScaleType.values().joinToString { scaleType -> scaleType.name }
            )
        }
        val original = imageView.scaleType
        imageView.scaleType = requested
        refresh(imageView)
        return RuntimeAttributeMutation(
            originalValue = RuntimeAttributeValue.StringValue(original.name),
            actualValue = RuntimeAttributeValue.StringValue(imageView.scaleType.name),
            restore = weakRestore(imageView) { target ->
                target.scaleType = original
                refresh(target)
                RuntimeAttributeValue.StringValue(target.scaleType.name)
            }
        )
    }
}

private fun RuntimeAttributeValue.string(
    attributeIdentifier: RuntimeAttributeIdentifier
): String = (this as? RuntimeAttributeValue.StringValue)?.value
    ?: throw invalidValue(attributeIdentifier, "a string")

private fun RuntimeAttributeValue.number(
    attributeIdentifier: RuntimeAttributeIdentifier
): Double = (this as? RuntimeAttributeValue.Number)?.value
    ?: throw invalidValue(attributeIdentifier, "a number")

private fun RuntimeAttributeValue.scaledLogical(
    attributeIdentifier: RuntimeAttributeIdentifier
): Double {
    val measurement = (this as? RuntimeAttributeValue.Measurement)?.value
        ?: throw invalidValue(attributeIdentifier, "a scaledLogical measurement")
    if (measurement.unit != RuntimeMeasurementUnit.scaledLogical) {
        throw invalidValue(attributeIdentifier, "a scaledLogical measurement")
    }
    return measurement.value
}

private fun RuntimeAttributeValue.androidColor(
    attributeIdentifier: RuntimeAttributeIdentifier
): Int {
    val color = (this as? RuntimeAttributeValue.Color)?.value
        ?: throw invalidValue(attributeIdentifier, "an sRGB color")
    if (!color.colorSpace.equals("sRGB", ignoreCase = true) &&
        !color.colorSpace.equals("extended-sRGB", ignoreCase = true)
    ) {
        throw invalidValue(attributeIdentifier, "an sRGB color")
    }
    return Color.argb(
        color.alpha.colorComponent(),
        color.red.colorComponent(),
        color.green.colorComponent(),
        color.blue.colorComponent()
    )
}

private fun Double.colorComponent(): Int =
    (coerceIn(0.0, 1.0) * COLOR_COMPONENT_MAXIMUM).roundToInt()

private fun colorValue(color: Int): RuntimeAttributeValue =
    RuntimeAttributeValue.Color(runtimeColor(color))

private fun scaledLogicalMeasurement(value: Double): RuntimeAttributeValue =
    RuntimeAttributeValue.Measurement(
        dev.astrolabe.protocol.RuntimeMeasurement(
            value = value,
            unit = RuntimeMeasurementUnit.scaledLogical
        )
    )

private fun <ViewType : View> weakRestore(
    view: ViewType,
    operation: (ViewType) -> RuntimeAttributeValue?
): () -> RuntimeAttributeValue? {
    val reference = WeakReference(view)
    return {
        val target = reference.get() ?: throw RuntimeProviderFailure(
            RuntimeError(
                code = RuntimeErrorCode.nodeNotFound,
                message = "The patched Android View is no longer available",
                recoverySuggestion = null
            )
        )
        operation(target)
    }
}

private fun refresh(view: View) {
    view.requestLayout()
    view.invalidate()
}

private fun unsupported(
    attributeIdentifier: RuntimeAttributeIdentifier
): RuntimeProviderFailure = RuntimeProviderFailure(
    RuntimeError(
        code = RuntimeErrorCode.unsupportedAttribute,
        message = "The requested attribute is not supported by this Android View",
        recoverySuggestion = "Inspect the patch catalog and target a compatible View"
    )
)

private fun invalidValue(
    attributeIdentifier: RuntimeAttributeIdentifier,
    expectation: String
): RuntimeProviderFailure = RuntimeProviderFailure(
    RuntimeError(
        code = RuntimeErrorCode.invalidAttributeValue,
        message = "The requested value is invalid for ${attributeIdentifier.rawValue}",
        recoverySuggestion = "Use $expectation"
    )
)

private const val TEXT_PRESENTATION_DOMAIN: String = "android.text.presentation"
private const val VIEW_PRESENTATION_DOMAIN: String = "android.view.presentation"
private const val IMAGE_PRESENTATION_DOMAIN: String = "android.image.presentation"
private const val COLOR_COMPONENT_MAXIMUM: Double = 255.0
private val VISIBILITY_VALUES: Map<String, Int> = linkedMapOf(
    "visible" to View.VISIBLE,
    "invisible" to View.INVISIBLE,
    "gone" to View.GONE
)
