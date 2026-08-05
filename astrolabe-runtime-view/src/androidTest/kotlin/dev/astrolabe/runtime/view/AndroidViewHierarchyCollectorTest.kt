//
//  AndroidViewHierarchyCollectorTest.kt
//  astrolabe-runtime-android
//
//  Created by 轩辕十四 on 2026/7/20.
//

package dev.astrolabe.runtime.view

import android.graphics.RectF
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.astrolabe.protocol.RuntimeCoordinateRect
import dev.astrolabe.protocol.RuntimeCoordinateSpace
import dev.astrolabe.protocol.RuntimeFrameCodec
import dev.astrolabe.protocol.RuntimeHierarchySnapshotPayload
import dev.astrolabe.protocol.RuntimeMessageCodec
import dev.astrolabe.protocol.RuntimeMeasurementUnit
import dev.astrolabe.protocol.RuntimeOpaqueIdentifier
import dev.astrolabe.runtime.core.RuntimeCancellationToken
import dev.astrolabe.runtime.core.RuntimeNodeRegistry
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidViewHierarchyCollectorTest {
    @Test
    fun collectorEmitsAnEmptyUIGraphRelationList() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val payload = collector(
                root = View(context),
                configuration = AndroidHierarchyCaptureConfiguration()
            ).capture(
                environment = AndroidDisplayEnvironmentProvider(context).capture(),
                cancellationToken = RuntimeCancellationToken { false }
            )

            assertEquals(0, payload.relations?.size)
        }
    }

    @Test
    fun collectorRejectsTreesBeyondTheNodeBudget() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val root = LinearLayout(context).apply {
                repeat(3) { index ->
                    addView(TextView(context).apply { text = "Node $index" })
                }
            }
            val collector = collector(
                root = root,
                configuration = AndroidHierarchyCaptureConfiguration(maximumNodeCount = 2)
            )

            assertThrows(IllegalStateException::class.java) {
                collector.capture(
                    environment = AndroidDisplayEnvironmentProvider(context).capture(),
                    cancellationToken = RuntimeCancellationToken { false }
                )
            }
        }
    }

    @Test
    fun collectorRejectsTraversalBeyondTheTimeBudget() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val elapsedTimes = ArrayDeque(listOf(0L, 2_000_000L))
            val collector = collector(
                root = View(context),
                configuration = AndroidHierarchyCaptureConfiguration(maximumDurationMillis = 1L),
                clock = AndroidHierarchyClock { elapsedTimes.removeFirst() }
            )

            assertThrows(IllegalStateException::class.java) {
                collector.capture(
                    environment = AndroidDisplayEnvironmentProvider(context).capture(),
                    cancellationToken = RuntimeCancellationToken { false }
                )
            }
        }
    }

    @Test
    fun collectorRejectsTreesBeyondTheDepthBudget() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val root = LinearLayout(context)
            val child = LinearLayout(context)
            child.addView(TextView(context))
            root.addView(child)
            val collector = collector(
                root = root,
                configuration = AndroidHierarchyCaptureConfiguration(maximumDepth = 1)
            )

            val error = assertThrows(AndroidHierarchyLimitExceededException::class.java) {
                collector.capture(
                    environment = AndroidDisplayEnvironmentProvider(context).capture(),
                    cancellationToken = RuntimeCancellationToken { false }
                )
            }

            assertEquals(AndroidHierarchyLimitKind.depth, error.kind)
        }
    }

    @Test
    fun collectorStopsWhenTheRequestIsCancelled() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val collector = collector(
                root = View(context),
                configuration = AndroidHierarchyCaptureConfiguration()
            )

            assertThrows(CancellationException::class.java) {
                collector.capture(
                    environment = AndroidDisplayEnvironmentProvider(context).capture(),
                    cancellationToken = RuntimeCancellationToken { true }
                )
            }
        }
    }

    @Test
    fun repeatedCapturePreservesNodeIdentityAndReflectsDynamicContent() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val label = TextView(context).apply { text = "Before" }
            val root = LinearLayout(context).apply { addView(label) }
            val collector = collector(
                root = root,
                configuration = AndroidHierarchyCaptureConfiguration()
            )
            val environment = AndroidDisplayEnvironmentProvider(context).capture()

            val first = collector.capture(
                environment = environment,
                cancellationToken = RuntimeCancellationToken { false }
            )
            label.text = "After"
            val second = collector.capture(
                environment = environment,
                cancellationToken = RuntimeCancellationToken { false }
            )

            assertEquals(first.roots.single().children.single().nodeID, second.roots.single().children.single().nodeID)
            assertEquals("Before", first.roots.single().children.single().text)
            assertEquals("After", second.roots.single().children.single().text)
        }
    }

    @Test
    fun repeatedCaptureDropsViewsRemovedFromTheHierarchy() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val label = TextView(context)
            val root = LinearLayout(context).apply { addView(label) }
            val collector = collector(
                root = root,
                configuration = AndroidHierarchyCaptureConfiguration()
            )
            val environment = AndroidDisplayEnvironmentProvider(context).capture()

            val first = collector.capture(
                environment = environment,
                cancellationToken = RuntimeCancellationToken { false }
            )
            root.removeView(label)
            val second = collector.capture(
                environment = environment,
                cancellationToken = RuntimeCancellationToken { false }
            )

            assertEquals(1, first.roots.single().children.size)
            assertTrue(second.roots.single().children.isEmpty())
        }
    }

    @Test
    fun largeHierarchyFitsTheWireFrameBudget() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val root = LinearLayout(context).apply {
                repeat(LARGE_HIERARCHY_CHILD_COUNT) { index ->
                    addView(TextView(context).apply { text = "Node $index" })
                }
            }
            val payload = collector(
                root = root,
                configuration = AndroidHierarchyCaptureConfiguration(
                    maximumNodeCount = LARGE_HIERARCHY_CHILD_COUNT + 1,
                    maximumDurationMillis = LARGE_HIERARCHY_DURATION_MILLIS
                )
            ).capture(
                environment = AndroidDisplayEnvironmentProvider(context).capture(),
                cancellationToken = RuntimeCancellationToken { false }
            )

            val encoded = RuntimeMessageCodec().encodeValue(
                payload,
                RuntimeHierarchySnapshotPayload.contract.serializer
            )

            assertEquals(LARGE_HIERARCHY_CHILD_COUNT, payload.roots.single().children.size)
            assertTrue(encoded.size < RuntimeFrameCodec.DEFAULT_MAXIMUM_PAYLOAD_SIZE)
        }
    }

    @Test
    fun clippedParentPropagatesAnEmptyClipToDescendants() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val parent = LinearLayout(instrumentation.targetContext).apply {
                layout(0, 0, 100, 100)
                clipChildren = true
            }
            val mappedGeometry = AndroidViewGeometryMapper(
                density = 1.0,
                viewport = RuntimeCoordinateRect(
                    x = 0.0,
                    y = 0.0,
                    width = 500.0,
                    height = 500.0,
                    coordinateSpace = RuntimeCoordinateSpace.screen,
                    unit = RuntimeMeasurementUnit.logical
                )
            ).map(
                view = parent,
                isRoot = false,
                ancestorHidden = false,
                ancestorOpacity = 1.0,
                ancestorClipInScreenPixels = RectF(200F, 200F, 300F, 300F)
            )

            val descendantClip = mappedGeometry.descendantClipInScreenPixels
                ?: error("A bounded empty clip cannot become unbounded")
            assertTrue(descendantClip.isEmpty)
        }
    }

    private fun collector(
        root: View,
        configuration: AndroidHierarchyCaptureConfiguration,
        clock: AndroidHierarchyClock = AndroidHierarchyClock(System::nanoTime)
    ): AndroidViewHierarchyCollector = AndroidViewHierarchyCollector(
        targetIdentifier = RuntimeOpaqueIdentifier("target:test"),
        nodeRegistry = RuntimeNodeRegistry(),
        rootProvider = FixedAndroidWindowRootProvider(root),
        configuration = configuration,
        clock = clock
    )

    private companion object {
        const val LARGE_HIERARCHY_CHILD_COUNT: Int = 1_000
        const val LARGE_HIERARCHY_DURATION_MILLIS: Long = 30_000L
    }
}

private class FixedAndroidWindowRootProvider(
    private val root: View
) : AndroidWindowRootProvider {
    override fun rootSnapshot(): AndroidWindowRootSnapshot = AndroidWindowRootSnapshot(
        roots = listOf(root),
        coverage = AndroidWindowRootCoverage.global
    )

    override fun close() = Unit
}
