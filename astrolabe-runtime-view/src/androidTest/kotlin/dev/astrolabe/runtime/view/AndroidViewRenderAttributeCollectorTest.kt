//
//  AndroidViewRenderAttributeCollectorTest.kt
//  astrolabe-runtime-android
//
//  Created by 轩辕十四 on 2026/8/24.
//

package dev.astrolabe.runtime.view

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.astrolabe.protocol.RuntimeAttributeValue
import dev.astrolabe.protocol.RuntimeCoordinateSpace
import dev.astrolabe.protocol.RuntimeMeasurementUnit
import dev.astrolabe.protocol.RuntimeNodeDetailPayload
import dev.astrolabe.runtime.core.RuntimeCancellationToken
import dev.astrolabe.runtime.core.RuntimeNodeRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidViewRenderAttributeCollectorTest {
    @Test
    fun viewRenderFactsUseLogicalUnits() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val view = FrameLayout(instrumentation.targetContext).apply {
                elevation = 12f
                translationZ = 4f
                clipToOutline = true
                clipChildren = false
                clipToPadding = false
                clipBounds = Rect(2, 3, 42, 23)
            }
            val payload = nodeDetail(view)
            val density = view.resources.displayMetrics.density.toDouble()

            assertEquals(12.0 / density, payload.measurement("android.render.elevation"), 0.0001)
            assertEquals(
                4.0 / density,
                payload.measurement("android.render.translationZ"),
                0.0001
            )
            assertTrue(payload.boolean("android.render.clipToOutline"))
            assertFalse(payload.boolean("android.render.clipChildren"))
            assertFalse(payload.boolean("android.render.clipToPadding"))
            val clipBounds = (payload.attribute("android.render.clipBounds") as
                RuntimeAttributeValue.Rect).value
            assertEquals(2.0 / density, clipBounds.x, 0.0001)
            assertEquals(3.0 / density, clipBounds.y, 0.0001)
            assertEquals(40.0 / density, clipBounds.width, 0.0001)
            assertEquals(20.0 / density, clipBounds.height, 0.0001)
            assertEquals(RuntimeCoordinateSpace.local, clipBounds.coordinateSpace)
            assertEquals(RuntimeMeasurementUnit.logical, clipBounds.unit)
        }
    }

    @Test
    fun drawablePresenceTypesAndResolvedTintsAreExposed() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val view = View(instrumentation.targetContext).apply {
                background = ColorDrawable(Color.RED)
                foreground = ColorDrawable(Color.BLUE)
                backgroundTintList = ColorStateList.valueOf(Color.GREEN)
                foregroundTintList = ColorStateList.valueOf(Color.YELLOW)
            }
            val payload = nodeDetail(view)

            assertTrue(payload.boolean("android.render.background.present"))
            assertEquals(
                ColorDrawable::class.java.name,
                payload.string("android.render.background.type")
            )
            assertTrue(payload.boolean("android.render.foreground.present"))
            assertEquals(
                ColorDrawable::class.java.name,
                payload.string("android.render.foreground.type")
            )
            assertColor(Color.GREEN, payload.attribute("android.render.background.tintColor"))
            assertColor(Color.YELLOW, payload.attribute("android.render.foreground.tintColor"))
        }
    }

    @Test
    fun colorDrawableExposesResolvedColor() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val view = View(instrumentation.targetContext).apply {
                background = ColorDrawable(Color.argb(128, 10, 20, 30))
            }

            assertColor(
                Color.argb(128, 10, 20, 30),
                nodeDetail(view).attribute("android.render.background.color")
            )
        }
    }

    @Test
    fun gradientDrawableExposesShapeColorsAndOrientation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val drawable = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.RED, Color.BLUE)
            ).apply {
                shape = GradientDrawable.RECTANGLE
                gradientType = GradientDrawable.LINEAR_GRADIENT
            }
            val view = View(instrumentation.targetContext).apply {
                background = drawable
            }
            val payload = nodeDetail(view)

            assertEquals("rectangle", payload.string("android.render.background.shape"))
            assertEquals("linear", payload.string("android.render.background.gradient.type"))
            assertEquals(
                "topLeftBottomRight",
                payload.string("android.render.background.gradient.orientation")
            )
            assertEquals(
                listOf("#FFFF0000", "#FF0000FF"),
                (payload.attribute("android.render.background.gradient.colors") as
                    RuntimeAttributeValue.StringList).value
            )
            assertNull(payload.attribute("android.render.background.gradient.centerX"))
            assertNull(payload.attribute("android.render.background.gradient.centerY"))
        }
    }

    @Test
    fun radialGradientOmitsNonFiniteCenterAndLinearOrientation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val drawable = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.RED, Color.BLUE)
            ).apply {
                gradientType = GradientDrawable.RADIAL_GRADIENT
                gradientRadius = 24f
                setGradientCenter(Float.NaN, 0.75f)
            }
            val view = View(instrumentation.targetContext).apply {
                background = drawable
            }
            val payload = nodeDetail(view)

            assertEquals("radial", payload.string("android.render.background.gradient.type"))
            assertNull(payload.attribute("android.render.background.gradient.orientation"))
            assertNull(payload.attribute("android.render.background.gradient.centerX"))
            assertEquals(
                0.75,
                (payload.attribute("android.render.background.gradient.centerY") as
                    RuntimeAttributeValue.Number).value,
                0.0001
            )
            assertTrue(payload.measurement("android.render.background.gradient.radius") > 0.0)
        }
    }

    @Test
    fun gradientDrawableExposesEllipticalCornerRadii() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val drawable = GradientDrawable().apply {
                cornerRadii = floatArrayOf(2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f)
            }
            val view = View(instrumentation.targetContext).apply {
                background = drawable
            }
            val payload = nodeDetail(view)
            val density = view.resources.displayMetrics.density.toDouble()

            assertCornerSize(payload, "topLeft", 2.0 / density, 3.0 / density)
            assertCornerSize(payload, "topRight", 4.0 / density, 5.0 / density)
            assertCornerSize(payload, "bottomRight", 6.0 / density, 7.0 / density)
            assertCornerSize(payload, "bottomLeft", 8.0 / density, 9.0 / density)
        }
    }

    @Test
    fun roundRectOutlineExposesClipGeometry() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val view = View(instrumentation.targetContext).apply {
                layout(0, 0, 80, 40)
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(0, 0, 80, 40, 12f)
                    }
                }
            }
            val payload = nodeDetail(view)
            val density = view.resources.displayMetrics.density.toDouble()

            assertFalse(payload.boolean("android.render.outline.empty"))
            assertTrue(payload.boolean("android.render.outline.canClip"))
            val bounds = (payload.attribute("android.render.outline.bounds") as
                RuntimeAttributeValue.Rect).value
            assertEquals(80.0 / density, bounds.width, 0.0001)
            assertEquals(40.0 / density, bounds.height, 0.0001)
            assertEquals(
                12.0 / density,
                payload.measurement("android.render.outline.cornerRadius"),
                0.0001
            )
        }
    }

    @Test
    fun unsupportedDrawableExposesTypeWithoutInventedSemantics() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val view = View(instrumentation.targetContext).apply {
                background = InsetDrawable(ColorDrawable(Color.RED), 4)
            }
            val payload = nodeDetail(view)

            assertEquals(
                InsetDrawable::class.java.name,
                payload.string("android.render.background.type")
            )
            assertNull(payload.attribute("android.render.background.color"))
            assertNull(payload.attribute("android.render.background.shape"))
        }
    }

    private fun nodeDetail(view: View): RuntimeNodeDetailPayload {
        val nodeRegistry = RuntimeNodeRegistry<View>()
        return AndroidViewNodeDetailProvider(
            nodeRegistry = nodeRegistry,
            mainThreadExecutor = AndroidMainThreadExecutor()
        ).nodeDetail(
            nodeID = nodeRegistry.nodeID(view),
            cancellationToken = RuntimeCancellationToken { false }
        )
    }

    private fun RuntimeNodeDetailPayload.attribute(identifier: String): RuntimeAttributeValue? =
        sections
            .flatMap { section -> section.attributes }
            .firstOrNull { attribute -> attribute.identifier.rawValue == identifier }
            ?.value

    private fun RuntimeNodeDetailPayload.boolean(identifier: String): Boolean =
        (attribute(identifier) as RuntimeAttributeValue.BooleanValue).value

    private fun RuntimeNodeDetailPayload.string(identifier: String): String =
        (attribute(identifier) as RuntimeAttributeValue.StringValue).value

    private fun RuntimeNodeDetailPayload.measurement(identifier: String): Double =
        (attribute(identifier) as RuntimeAttributeValue.Measurement).value.value

    private fun assertColor(expected: Int, value: RuntimeAttributeValue?) {
        val color = (value as RuntimeAttributeValue.Color).value
        assertEquals(Color.red(expected) / 255.0, color.red, 0.0001)
        assertEquals(Color.green(expected) / 255.0, color.green, 0.0001)
        assertEquals(Color.blue(expected) / 255.0, color.blue, 0.0001)
        assertEquals(Color.alpha(expected) / 255.0, color.alpha, 0.0001)
    }

    private fun assertCornerSize(
        payload: RuntimeNodeDetailPayload,
        corner: String,
        expectedWidth: Double,
        expectedHeight: Double
    ) {
        val size = (payload.attribute("android.render.background.cornerRadii.$corner") as
            RuntimeAttributeValue.Size).value
        assertEquals(expectedWidth, size.width, 0.0001)
        assertEquals(expectedHeight, size.height, 0.0001)
        assertEquals(RuntimeMeasurementUnit.logical, size.unit)
    }
}
