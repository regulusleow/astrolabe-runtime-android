//
//  AndroidViewNodeDetailEncodingTest.kt
//  astrolabe-runtime-android
//
//  Created by 轩辕十四 on 2026/7/22.
//

package dev.astrolabe.runtime.view

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Switch
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.astrolabe.protocol.RuntimeAttributeValue
import dev.astrolabe.protocol.RuntimeLayoutRelation
import dev.astrolabe.protocol.RuntimeLayoutRelationKind
import dev.astrolabe.protocol.RuntimeMessageCodec
import dev.astrolabe.protocol.RuntimeMeasurementUnit
import dev.astrolabe.protocol.RuntimeNodeDetailPayload
import dev.astrolabe.runtime.core.RuntimeCancellationToken
import dev.astrolabe.runtime.core.RuntimeNodeRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidViewNodeDetailEncodingTest {
    @Test
    fun exactLayoutParamsBecomeLogicalConstantRelations() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val nodeRegistry = RuntimeNodeRegistry<View>()
            val view = View(instrumentation.targetContext).apply {
                layoutParams = ViewGroup.LayoutParams(54, 27)
            }
            val nodeID = nodeRegistry.nodeID(view)

            val relations = layoutRelations(
                AndroidViewNodeDetailProvider(
                    nodeRegistry = nodeRegistry,
                    mainThreadExecutor = AndroidMainThreadExecutor()
                ).nodeDetail(
                    nodeID = nodeID,
                    cancellationToken = RuntimeCancellationToken { false }
                )
            )

            val density = view.resources.displayMetrics.density.toDouble()
            assertEquals(listOf("width", "height"), relations.map { it.source.anchor })
            assertTrue(relations.all { relation -> relation.source.nodeID == nodeID })
            assertTrue(relations.all { relation -> relation.identifier == null })
            assertTrue(relations.all { relation ->
                relation.relation == RuntimeLayoutRelationKind.equal
            })
            assertTrue(relations.all { relation -> relation.target == null })
            assertTrue(relations.all { relation -> relation.multiplier == 1.0 })
            assertEquals(54.0 / density, relations[0].offset.value, 0.0001)
            assertEquals(27.0 / density, relations[1].offset.value, 0.0001)
            assertTrue(relations.all { relation ->
                relation.offset.unit == RuntimeMeasurementUnit.logical
            })
            assertTrue(relations.all { relation -> relation.strength == null })
            assertTrue(relations.all { relation -> relation.active == null })
            assertTrue(relations.all { relation -> relation.extensions.values.isEmpty() })
        }
    }

    @Test
    fun semanticLayoutParamsAreNotReportedAsConstantRelations() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val nodeRegistry = RuntimeNodeRegistry<View>()
            val unsupportedDimensions = listOf(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                0
            )

            unsupportedDimensions.forEach { dimension ->
                val view = View(instrumentation.targetContext).apply {
                    layoutParams = ViewGroup.LayoutParams(dimension, dimension)
                }
                val relations = layoutRelations(
                    AndroidViewNodeDetailProvider(
                        nodeRegistry = nodeRegistry,
                        mainThreadExecutor = AndroidMainThreadExecutor()
                    ).nodeDetail(
                        nodeID = nodeRegistry.nodeID(view),
                        cancellationToken = RuntimeCancellationToken { false }
                    )
                )

                assertTrue(relations.isEmpty())
            }
        }
    }

    @Test
    fun linearLayoutWeightOmitsTheWeightedAxis() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val nodeRegistry = RuntimeNodeRegistry<View>()
            val parent = LinearLayout(instrumentation.targetContext).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            val view = View(instrumentation.targetContext)
            parent.addView(view, LinearLayout.LayoutParams(54, 27, 1.0f))

            val relations = layoutRelations(
                AndroidViewNodeDetailProvider(
                    nodeRegistry = nodeRegistry,
                    mainThreadExecutor = AndroidMainThreadExecutor()
                ).nodeDetail(
                    nodeID = nodeRegistry.nodeID(view),
                    cancellationToken = RuntimeCancellationToken { false }
                )
            )

            assertEquals(
                listOf("height"),
                relations.map { relation -> relation.source.anchor }
            )
        }
    }

    @Test
    fun switchDetailSatisfiesTheWireContract() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val nodeRegistry = RuntimeNodeRegistry<android.view.View>()
            val view = Switch(instrumentation.targetContext).apply {
                text = "Live inspection"
                layout(0, 0, 320, 144)
            }
            val payload = AndroidViewNodeDetailProvider(
                nodeRegistry = nodeRegistry,
                mainThreadExecutor = AndroidMainThreadExecutor()
            ).nodeDetail(
                nodeID = nodeRegistry.nodeID(view),
                cancellationToken = RuntimeCancellationToken { false }
            )

            RuntimeMessageCodec().encodeValue(
                payload,
                RuntimeNodeDetailPayload.contract.serializer
            )
        }
    }

    private fun layoutRelations(
        payload: RuntimeNodeDetailPayload
    ): List<RuntimeLayoutRelation> {
        val attribute = payload.sections
            .flatMap { section -> section.attributes }
            .single { attribute ->
                attribute.identifier.rawValue == "common.layout.relations"
            }
        return (attribute.value as RuntimeAttributeValue.LayoutRelations).value
    }
}
