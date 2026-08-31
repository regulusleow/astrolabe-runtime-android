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
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.util.StateSet
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.astrolabe.protocol.RuntimeAttribute
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
import org.junit.Assume.assumeTrue
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
    fun stateListDrawableProjectsOnlyCurrentDrawable() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val drawable = StateListDrawable().apply {
                addState(
                    intArrayOf(android.R.attr.state_pressed),
                    ColorDrawable(Color.RED)
                )
                addState(StateSet.WILD_CARD, ColorDrawable(Color.BLUE))
            }
            val view = View(instrumentation.targetContext).apply {
                background = drawable
                isPressed = true
            }
            val payload = nodeDetail(view)

            assertTrue(payload.boolean("android.render.background.current.present"))
            assertEquals(
                ColorDrawable::class.java.name,
                payload.string("android.render.background.current.type")
            )
            assertColor(
                Color.RED,
                payload.attribute("android.render.background.current.color")
            )
            assertNull(payload.attribute("android.render.background.states"))
        }
    }

    @Test
    fun gradientDrawableExposesShapeColorsAndOrientation() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
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
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
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
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
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
    fun gradientDrawableWithoutExplicitCornersFallsBackToUniformRadius() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val view = View(instrumentation.targetContext).apply {
                background = GradientDrawable()
            }
            val payload = nodeDetail(view)

            assertEquals("rectangle", payload.string("android.render.background.shape"))
            assertEquals(
                0.0,
                payload.measurement("android.render.background.cornerRadius"),
                0.0001
            )
        }
    }

    @Test
    fun gradientDrawableDetailedFactsAreOmittedBeforeApi24() {
        assumeTrue(Build.VERSION.SDK_INT < Build.VERSION_CODES.N)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val view = View(instrumentation.targetContext).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(Color.RED, Color.BLUE)
                ).apply {
                    cornerRadius = 12f
                }
            }
            val payload = nodeDetail(view)

            assertEquals(
                GradientDrawable::class.java.name,
                payload.string("android.render.background.type")
            )
            assertNull(payload.attribute("android.render.background.shape"))
            assertNull(payload.attribute("android.render.background.color"))
            assertNull(payload.attribute("android.render.background.cornerRadius"))
            assertNull(payload.attribute("android.render.background.gradient.colors"))
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
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                assertNull(payload.attribute("android.render.outline.bounds"))
                assertNull(payload.attribute("android.render.outline.cornerRadius"))
                return@runOnMainSync
            }
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
    fun insetDrawableProjectsContentAndEffectiveInsets() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val drawable = InsetDrawable(ColorDrawable(Color.RED), 2, 3, 4, 5).apply {
                bounds = Rect(0, 0, 100, 80)
            }
            val view = View(instrumentation.targetContext).apply {
                background = drawable
                layout(0, 0, 100, 80)
            }
            val payload = nodeDetail(view)
            val density = view.resources.displayMetrics.density.toDouble()

            assertEquals(
                InsetDrawable::class.java.name,
                payload.string("android.render.background.type")
            )
            assertEquals(
                ColorDrawable::class.java.name,
                payload.string("android.render.background.content.type")
            )
            assertColor(
                Color.RED,
                payload.attribute("android.render.background.content.color")
            )
            assertEquals(
                2.0 / density,
                payload.measurement("android.render.background.insets.left"),
                0.0001
            )
            assertEquals(
                3.0 / density,
                payload.measurement("android.render.background.insets.top"),
                0.0001
            )
            assertEquals(
                4.0 / density,
                payload.measurement("android.render.background.insets.right"),
                0.0001
            )
            assertEquals(
                5.0 / density,
                payload.measurement("android.render.background.insets.bottom"),
                0.0001
            )
        }
    }

    @Test
    fun layerDrawableProjectsBoundedLayerFacts() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val drawable = LayerDrawable(
                arrayOf(
                    ColorDrawable(Color.RED),
                    ColorDrawable(Color.BLUE)
                )
            ).apply {
                setId(0, 101)
                setId(1, 202)
                setLayerInset(0, 2, 3, 4, 5)
                bounds = Rect(0, 0, 100, 80)
            }
            val view = View(instrumentation.targetContext).apply {
                background = drawable
            }
            val payload = nodeDetail(view)
            val density = view.resources.displayMetrics.density.toDouble()
            val bounds = (payload.attribute("android.render.background.layers.layer0.bounds") as
                RuntimeAttributeValue.Rect).value

            assertEquals(2L, payload.integer("android.render.background.layerCount"))
            assertEquals(2L, payload.integer("android.render.background.projectedLayerCount"))
            assertFalse(payload.boolean("android.render.background.layersTruncated"))
            assertEquals(101L, payload.integer("android.render.background.layers.layer0.id"))
            assertEquals(2.0 / density, bounds.x, 0.0001)
            assertEquals(3.0 / density, bounds.y, 0.0001)
            assertEquals(94.0 / density, bounds.width, 0.0001)
            assertEquals(72.0 / density, bounds.height, 0.0001)
            assertEquals(
                2.0 / density,
                payload.measurement("android.render.background.layers.layer0.insets.left"),
                0.0001
            )
            assertEquals(
                ColorDrawable::class.java.name,
                payload.string("android.render.background.layers.layer0.drawable.type")
            )
            assertColor(
                Color.RED,
                payload.attribute("android.render.background.layers.layer0.drawable.color")
            )
        }
    }

    @Test
    fun layerDrawableStopsAtMaximumLayerCount() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val drawable = LayerDrawable(
                Array(20) { index -> ColorDrawable(Color.rgb(index, 0, 0)) }
            ).apply {
                bounds = Rect(0, 0, 100, 100)
            }
            val view = View(instrumentation.targetContext).apply {
                background = drawable
            }
            val payload = nodeDetail(view)

            assertEquals(20L, payload.integer("android.render.background.layerCount"))
            assertEquals(16L, payload.integer("android.render.background.projectedLayerCount"))
            assertTrue(payload.boolean("android.render.background.layersTruncated"))
            assertEquals(
                ColorDrawable::class.java.name,
                payload.string("android.render.background.layers.layer15.drawable.type")
            )
            assertNull(payload.attribute("android.render.background.layers.layer16.drawable.type"))
        }
    }

    @Test
    fun layerDrawableOmitsInvalidCurrentBounds() {
        val child = ColorDrawable(Color.RED)
        val drawable = LayerDrawable(arrayOf(child))
        child.bounds = Rect(10, 10, 0, 0)
        val attributes = AndroidDrawableAttributeProjectorRegistry().attributes(
            drawable = drawable,
            prefix = "test.drawable",
            density = 1.0,
            drawableState = intArrayOf()
        )

        assertEquals(
            1L,
            (attributes.attribute("test.drawable.layerCount") as
                RuntimeAttributeValue.Integer).value
        )
        assertNull(attributes.attribute("test.drawable.layers.layer0.bounds"))
        assertColor(Color.RED, attributes.attribute("test.drawable.layers.layer0.drawable.color"))
    }

    @Test
    fun rippleDrawableProjectsCurrentEffectColorContentAndMask() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val effectColors = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_pressed), StateSet.WILD_CARD),
                intArrayOf(Color.GREEN, Color.YELLOW)
            )
            val drawable = RippleDrawable(
                ColorStateList.valueOf(Color.BLUE),
                ColorDrawable(Color.RED),
                ColorDrawable(Color.BLACK)
            ).apply {
                setEffectColor(effectColors)
                bounds = Rect(0, 0, 100, 80)
            }
            val view = View(instrumentation.targetContext).apply {
                background = drawable
                isPressed = true
            }
            val payload = nodeDetail(view)

            assertColor(Color.GREEN, payload.attribute("android.render.background.effectColor"))
            assertNull(payload.attribute("android.render.background.color"))
            assertEquals(1L, payload.integer("android.render.background.contentLayerCount"))
            assertEquals(
                1L,
                payload.integer("android.render.background.projectedContentLayerCount")
            )
            assertFalse(payload.boolean("android.render.background.contentLayersTruncated"))
            assertEquals(
                ColorDrawable::class.java.name,
                payload.string("android.render.background.contents.content0.drawable.type")
            )
            assertColor(
                Color.RED,
                payload.attribute("android.render.background.contents.content0.drawable.color")
            )
            assertTrue(payload.boolean("android.render.background.mask.present"))
            assertEquals(
                ColorDrawable::class.java.name,
                payload.string("android.render.background.mask.drawable.type")
            )
            assertColor(
                Color.BLACK,
                payload.attribute("android.render.background.mask.drawable.color")
            )
        }
    }

    @Test
    fun rippleDrawableStopsAtMaximumContentLayerCount() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val drawable = RippleDrawable(
                ColorStateList.valueOf(Color.BLUE),
                ColorDrawable(Color.RED),
                ColorDrawable(Color.BLACK)
            ).apply {
                repeat(19) { index ->
                    addLayer(ColorDrawable(Color.rgb(index, 0, 0)))
                }
                bounds = Rect(0, 0, 100, 100)
            }
            val view = View(instrumentation.targetContext).apply {
                background = drawable
            }
            val payload = nodeDetail(view)

            assertEquals(20L, payload.integer("android.render.background.contentLayerCount"))
            assertEquals(
                16L,
                payload.integer("android.render.background.projectedContentLayerCount")
            )
            assertTrue(payload.boolean("android.render.background.contentLayersTruncated"))
            assertEquals(
                ColorDrawable::class.java.name,
                payload.string("android.render.background.contents.content15.drawable.type")
            )
            assertNull(
                payload.attribute("android.render.background.contents.content16.drawable.type")
            )
            assertTrue(payload.boolean("android.render.background.mask.present"))
        }
    }

    @Test
    fun drawableProjectionStopsAtMaximumDepth() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            var drawable: Drawable = ColorDrawable(Color.RED)
            repeat(10) {
                drawable = InsetDrawable(drawable, 1)
            }
            val view = View(instrumentation.targetContext).apply {
                background = drawable
                layout(0, 0, 100, 100)
            }
            val payload = nodeDetail(view)

            assertEquals(
                InsetDrawable::class.java.name,
                payload.string(
                    "android.render.background.content.content.content.content.content.content" +
                        ".content.content.type"
                )
            )
            assertNull(
                payload.attribute(
                    "android.render.background.content.content.content.content.content.content" +
                        ".content.content.content.type"
                )
            )
        }
    }

    @Test
    fun drawableProjectionStopsAtIdentityCycle() {
        val cyclicDrawable = object : InsetDrawable(ColorDrawable(Color.RED), 0) {
            override fun getDrawable(): Drawable = this
        }
        val attributes = AndroidDrawableAttributeProjectorRegistry().attributes(
            drawable = cyclicDrawable,
            prefix = "test.drawable",
            density = 1.0,
            drawableState = intArrayOf()
        )

        assertTrue(
            (attributes.attribute("test.drawable.content.present") as
                RuntimeAttributeValue.BooleanValue).value
        )
        assertNull(attributes.attribute("test.drawable.content.type"))
    }

    @Test
    fun drawableProjectionFailsClosedWhenProjectorThrows() {
        val throwingProjector = object : AndroidDrawableAttributeProjecting {
            override fun supports(drawable: Drawable): Boolean = drawable is ColorDrawable

            override fun projection(
                drawable: Drawable,
                prefix: String,
                density: Double,
                drawableState: IntArray
            ): AndroidDrawableProjection = error("测试投影器异常")
        }

        val attributes = AndroidDrawableAttributeProjectorRegistry(
            projectors = listOf(throwingProjector)
        ).attributes(
            drawable = ColorDrawable(Color.RED),
            prefix = "test.drawable",
            density = 1.0,
            drawableState = intArrayOf()
        )

        assertTrue(attributes.isEmpty())
    }

    @Test
    fun centerImageExposesRenderedBoundsOutsideView() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val imageView = ImageView(instrumentation.targetContext).apply {
                setImageDrawable(GradientDrawable().apply { setSize(56, 56) })
                scaleType = ImageView.ScaleType.CENTER
                layout(0, 0, 28, 28)
            }
            val payload = nodeDetail(imageView)
            val density = imageView.resources.displayMetrics.density.toDouble()
            val renderedBounds = (payload.attribute("android.image.renderedBounds") as
                RuntimeAttributeValue.Rect).value

            assertEquals(-14.0 / density, renderedBounds.x, 0.0001)
            assertEquals(-14.0 / density, renderedBounds.y, 0.0001)
            assertEquals(56.0 / density, renderedBounds.width, 0.0001)
            assertEquals(56.0 / density, renderedBounds.height, 0.0001)
            assertEquals(RuntimeCoordinateSpace.local, renderedBounds.coordinateSpace)
            assertEquals(RuntimeMeasurementUnit.logical, renderedBounds.unit)
            assertTrue(payload.boolean("android.image.overflowsViewBounds"))
        }
    }

    @Test
    fun overflowingImageExposesVisibleBoundsWithinView() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val imageView = ImageView(instrumentation.targetContext).apply {
                setImageDrawable(GradientDrawable().apply { setSize(56, 56) })
                scaleType = ImageView.ScaleType.CENTER
                layout(0, 0, 28, 28)
            }
            val payload = nodeDetail(imageView)
            val density = imageView.resources.displayMetrics.density.toDouble()
            val visibleBounds = (payload.attribute("android.image.visibleBoundsInView") as
                RuntimeAttributeValue.Rect).value

            assertEquals(0.0, visibleBounds.x, 0.0001)
            assertEquals(0.0, visibleBounds.y, 0.0001)
            assertEquals(28.0 / density, visibleBounds.width, 0.0001)
            assertEquals(28.0 / density, visibleBounds.height, 0.0001)
            assertEquals(RuntimeCoordinateSpace.local, visibleBounds.coordinateSpace)
            assertEquals(RuntimeMeasurementUnit.logical, visibleBounds.unit)
        }
    }

    @Test
    fun cropToPaddingRestrictsVisibleImageBounds() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val imageView = ImageView(instrumentation.targetContext).apply {
                setPadding(5, 5, 5, 5)
                setImageDrawable(GradientDrawable().apply { setSize(40, 40) })
                scaleType = ImageView.ScaleType.CENTER
                cropToPadding = true
                layout(0, 0, 40, 40)
            }
            val payload = nodeDetail(imageView)
            val density = imageView.resources.displayMetrics.density.toDouble()
            val visibleBounds = (payload.attribute("android.image.visibleBoundsInView") as
                RuntimeAttributeValue.Rect).value

            assertTrue(payload.boolean("android.image.cropToPadding"))
            assertEquals(5.0 / density, visibleBounds.x, 0.0001)
            assertEquals(5.0 / density, visibleBounds.y, 0.0001)
            assertEquals(30.0 / density, visibleBounds.width, 0.0001)
            assertEquals(30.0 / density, visibleBounds.height, 0.0001)
        }
    }

    @Test
    fun clipBoundsRestrictVisibleImageBounds() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val imageView = ImageView(instrumentation.targetContext).apply {
                setImageDrawable(GradientDrawable().apply { setSize(40, 40) })
                scaleType = ImageView.ScaleType.FIT_XY
                clipBounds = Rect(4, 6, 30, 32)
                layout(0, 0, 40, 40)
            }
            val payload = nodeDetail(imageView)
            val density = imageView.resources.displayMetrics.density.toDouble()
            val visibleBounds = (payload.attribute("android.image.visibleBoundsInView") as
                RuntimeAttributeValue.Rect).value

            assertEquals(4.0 / density, visibleBounds.x, 0.0001)
            assertEquals(6.0 / density, visibleBounds.y, 0.0001)
            assertEquals(26.0 / density, visibleBounds.width, 0.0001)
            assertEquals(26.0 / density, visibleBounds.height, 0.0001)
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

    private fun List<RuntimeAttribute>.attribute(identifier: String): RuntimeAttributeValue? =
        firstOrNull { attribute -> attribute.identifier.rawValue == identifier }?.value

    private fun RuntimeNodeDetailPayload.boolean(identifier: String): Boolean =
        (attribute(identifier) as RuntimeAttributeValue.BooleanValue).value

    private fun RuntimeNodeDetailPayload.string(identifier: String): String =
        (attribute(identifier) as RuntimeAttributeValue.StringValue).value

    private fun RuntimeNodeDetailPayload.measurement(identifier: String): Double =
        (attribute(identifier) as RuntimeAttributeValue.Measurement).value.value

    private fun RuntimeNodeDetailPayload.integer(identifier: String): Long =
        (attribute(identifier) as RuntimeAttributeValue.Integer).value

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
