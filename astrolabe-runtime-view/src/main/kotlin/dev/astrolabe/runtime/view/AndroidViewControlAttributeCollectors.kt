//
//  AndroidViewControlAttributeCollectors.kt
//  astrolabe-runtime-android
//
//  Created by 轩辕十四 on 2026/7/22.
//

package dev.astrolabe.runtime.view

import android.graphics.RectF
import android.os.Build
import android.text.TextUtils
import android.util.TypedValue
import android.view.View
import android.widget.AbsListView
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import dev.astrolabe.protocol.RuntimeAttribute
import dev.astrolabe.protocol.RuntimeAttributeCategory
import dev.astrolabe.protocol.RuntimeAttributeValue
import dev.astrolabe.protocol.RuntimeCoordinateRect
import dev.astrolabe.protocol.RuntimeCoordinateSpace
import dev.astrolabe.protocol.RuntimeMeasuredSize
import dev.astrolabe.protocol.RuntimeMeasurement
import dev.astrolabe.protocol.RuntimeMeasurementUnit

internal class AndroidTextAttributeCollector(
    private val textPrivacyPolicy: AndroidViewTextPrivacyPolicy
) : AndroidViewAttributeCollecting {
    override val category: RuntimeAttributeCategory = AndroidViewDetailSchema.textCategory

    override fun supports(view: View): Boolean = view is TextView

    override fun attributes(view: View): List<RuntimeAttribute> {
        val textView = view as? TextView ?: return emptyList()
        return buildList {
            textPrivacyPolicy.exposedText(textView)?.let { value ->
                add(stringValue("android.text.text", value))
            }
            nonempty(textView.hint)?.let { value ->
                add(stringValue("android.text.hint", value))
            }
            add(
                runtimeAttribute(
                    "android.text.fontSize",
                    RuntimeAttributeValue.Measurement(
                        RuntimeMeasurement(
                            value = scaledLogicalTextSize(textView),
                            unit = RuntimeMeasurementUnit.scaledLogical
                        )
                    )
                )
            )
            add(
                runtimeAttribute(
                    "android.text.color",
                    RuntimeAttributeValue.Color(runtimeColor(textView.currentTextColor))
                )
            )
            add(integerValue("android.text.typefaceStyle", textView.typeface?.style?.toLong() ?: 0L))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                textView.typeface?.weight?.let { weight ->
                    add(integerValue("android.text.typefaceWeight", weight.toLong()))
                }
            }
            add(integerValue("android.text.lineCount", textView.lineCount.toLong()))
            add(integerValue("android.text.maxLines", textView.maxLines.toLong()))
            add(integerValue("android.text.gravity", textView.gravity.toLong()))
            add(
                stringValue(
                    "android.text.ellipsize",
                    ellipsizeName(textView.ellipsize)
                )
            )
            add(numberValue("android.text.letterSpacing", textView.letterSpacing.toDouble()))
            add(
                booleanValue(
                    "android.text.includeFontPadding",
                    textView.includeFontPadding
                )
            )
        }
    }
}

internal fun scaledLogicalTextSize(textView: TextView): Double {
    val metrics = textView.resources.displayMetrics
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        return TypedValue.deriveDimension(
            TypedValue.COMPLEX_UNIT_SP,
            textView.textSize,
            metrics
        ).toDouble()
    }
    @Suppress("DEPRECATION")
    val scaledDensity = metrics.scaledDensity.toDouble().takeIf { it > 0.0 } ?: 1.0
    return textView.textSize.toDouble() / scaledDensity
}

internal class AndroidTextInputAttributeCollector(
    private val textPrivacyPolicy: AndroidViewTextPrivacyPolicy
) : AndroidViewAttributeCollecting {
    override val category: RuntimeAttributeCategory = AndroidViewDetailSchema.textInputCategory

    override fun supports(view: View): Boolean = view is EditText

    override fun attributes(view: View): List<RuntimeAttribute> {
        val input = view as? EditText ?: return emptyList()
        val isSensitive = textPrivacyPolicy.isSensitive(input)
        return buildList {
            add(integerValue("android.textInput.inputType", input.inputType.toLong()))
            add(integerValue("android.textInput.imeOptions", input.imeOptions.toLong()))
            add(booleanValue("android.textInput.secure", isSensitive))
            add(booleanValue("android.textInput.singleLine", input.maxLines == 1))
            add(booleanValue("android.textInput.cursorVisible", input.isCursorVisible))
            if (!isSensitive) {
                add(integerValue("android.textInput.selectionStart", input.selectionStart.toLong()))
                add(integerValue("android.textInput.selectionEnd", input.selectionEnd.toLong()))
            }
        }
    }
}

internal class AndroidImageAttributeCollector : AndroidViewAttributeCollecting {
    override val category: RuntimeAttributeCategory = AndroidViewDetailSchema.imageCategory

    override fun supports(view: View): Boolean = view is ImageView

    override fun attributes(view: View): List<RuntimeAttribute> {
        val imageView = view as? ImageView ?: return emptyList()
        val density = imageView.resources.displayMetrics.density.toDouble().takeIf { it > 0.0 }
            ?: 1.0
        val drawable = imageView.drawable
        return buildList {
            add(booleanValue("android.image.present", drawable != null))
            add(stringValue("android.image.scaleType", imageView.scaleType.name))
            add(booleanValue("android.image.cropToPadding", imageView.cropToPadding))
            if (drawable != null) {
                add(stringValue("android.image.drawableType", drawable.javaClass.name))
                renderedImageBounds(imageView, density)?.let { bounds ->
                    add(rectValue("android.image.renderedBounds", bounds, density))
                    add(
                        rectValue(
                            "android.image.visibleBoundsInView",
                            visibleImageBoundsInView(imageView, bounds),
                            density
                        )
                    )
                    add(
                        booleanValue(
                            "android.image.overflowsViewBounds",
                            bounds.left < 0f ||
                                bounds.top < 0f ||
                                bounds.right > imageView.width.toFloat() ||
                                bounds.bottom > imageView.height.toFloat()
                        )
                    )
                }
                if (drawable.intrinsicWidth >= 0 && drawable.intrinsicHeight >= 0) {
                    add(
                        runtimeAttribute(
                            "android.image.intrinsicSize",
                            RuntimeAttributeValue.Size(
                                RuntimeMeasuredSize(
                                    width = drawable.intrinsicWidth.toDouble() / density,
                                    height = drawable.intrinsicHeight.toDouble() / density,
                                    unit = RuntimeMeasurementUnit.logical
                                )
                            )
                        )
                    )
                }
            }
            imageView.imageTintList?.getColorForState(
                imageView.drawableState,
                imageView.imageTintList?.defaultColor ?: 0
            )?.let { color ->
                add(
                    runtimeAttribute(
                        "android.image.tintColor",
                        RuntimeAttributeValue.Color(runtimeColor(color))
                    )
                )
            }
        }
    }
}

private fun visibleImageBoundsInView(imageView: ImageView, renderedBounds: RectF): RectF {
    val visibleBounds = RectF(renderedBounds)
    if (!visibleBounds.intersect(0f, 0f, imageView.width.toFloat(), imageView.height.toFloat())) {
        visibleBounds.setEmpty()
        return visibleBounds
    }
    if (imageView.cropToPadding && !visibleBounds.intersect(
            (imageView.scrollX + imageView.paddingLeft).toFloat(),
            (imageView.scrollY + imageView.paddingTop).toFloat(),
            (imageView.scrollX + imageView.width - imageView.paddingRight).toFloat(),
            (imageView.scrollY + imageView.height - imageView.paddingBottom).toFloat()
        )
    ) {
        visibleBounds.setEmpty()
        return visibleBounds
    }
    imageView.clipBounds?.let { clipBounds ->
        if (!visibleBounds.intersect(
                clipBounds.left.toFloat(),
                clipBounds.top.toFloat(),
                clipBounds.right.toFloat(),
                clipBounds.bottom.toFloat()
            )
        ) {
            visibleBounds.setEmpty()
        }
    }
    return visibleBounds
}

private fun renderedImageBounds(imageView: ImageView, density: Double): RectF? {
    if (!density.isFinite() || density <= 0.0) {
        return null
    }
    val drawable = imageView.drawable ?: return null
    val bounds = RectF(drawable.bounds)
    if (bounds.isEmpty) {
        return null
    }
    imageView.imageMatrix.mapRect(bounds)
    bounds.offset(imageView.paddingLeft.toFloat(), imageView.paddingTop.toFloat())
    return bounds.takeIf { value ->
        value.left.isFinite() &&
            value.top.isFinite() &&
            value.right.isFinite() &&
            value.bottom.isFinite()
    }
}

internal class AndroidControlAttributeCollector : AndroidViewAttributeCollecting {
    override val category: RuntimeAttributeCategory = AndroidViewDetailSchema.controlCategory

    override fun supports(view: View): Boolean = view is Button || view is CompoundButton

    override fun attributes(view: View): List<RuntimeAttribute> = buildList {
        add(booleanValue("android.control.enabled", view.isEnabled))
        add(booleanValue("android.control.selected", view.isSelected))
        add(booleanValue("android.control.activated", view.isActivated))
        if (view is CompoundButton) {
            add(booleanValue("android.control.checked", view.isChecked))
        }
    }
}

internal class AndroidScrollAttributeCollector : AndroidViewAttributeCollecting {
    override val category: RuntimeAttributeCategory = AndroidViewDetailSchema.scrollCategory

    override fun supports(view: View): Boolean = view is ScrollView ||
        view is HorizontalScrollView ||
        view is AbsListView ||
        view.hasClassInHierarchy("androidx.recyclerview.widget.RecyclerView")

    override fun attributes(view: View): List<RuntimeAttribute> {
        val density = view.resources.displayMetrics.density.toDouble().takeIf { it > 0.0 } ?: 1.0
        return listOf(
            measurementValue("android.scroll.offsetX", view.scrollX.toDouble() / density),
            measurementValue("android.scroll.offsetY", view.scrollY.toDouble() / density),
            booleanValue("android.scroll.canScrollLeft", view.canScrollHorizontally(-1)),
            booleanValue("android.scroll.canScrollRight", view.canScrollHorizontally(1)),
            booleanValue("android.scroll.canScrollUp", view.canScrollVertically(-1)),
            booleanValue("android.scroll.canScrollDown", view.canScrollVertically(1))
        )
    }
}

private fun View.hasClassInHierarchy(className: String): Boolean =
    generateSequence<Class<*>>(javaClass) { type -> type.superclass }
        .any { type -> type.name == className }

private fun stringValue(identifier: String, value: String): RuntimeAttribute =
    runtimeAttribute(identifier, RuntimeAttributeValue.StringValue(value))

private fun booleanValue(identifier: String, value: Boolean): RuntimeAttribute =
    runtimeAttribute(identifier, RuntimeAttributeValue.BooleanValue(value))

private fun integerValue(identifier: String, value: Long): RuntimeAttribute =
    runtimeAttribute(identifier, RuntimeAttributeValue.Integer(value))

private fun numberValue(identifier: String, value: Double): RuntimeAttribute =
    runtimeAttribute(identifier, RuntimeAttributeValue.Number(value))

private fun measurementValue(identifier: String, value: Double): RuntimeAttribute =
    runtimeAttribute(
        identifier,
        RuntimeAttributeValue.Measurement(
            RuntimeMeasurement(value, RuntimeMeasurementUnit.logical)
        )
    )

private fun rectValue(identifier: String, bounds: RectF, density: Double): RuntimeAttribute =
    runtimeAttribute(
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

private fun ellipsizeName(value: TextUtils.TruncateAt?): String = when (value) {
    TextUtils.TruncateAt.START -> "start"
    TextUtils.TruncateAt.MIDDLE -> "middle"
    TextUtils.TruncateAt.END -> "end"
    TextUtils.TruncateAt.MARQUEE -> "marquee"
    null -> "none"
}
