//
//  AndroidViewNodeDetailEncodingTest.kt
//  astrolabe-runtime-android
//
//  Created by 轩辕十四 on 2026/7/22.
//

package dev.astrolabe.runtime.view

import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import androidx.constraintlayout.widget.ConstraintLayout
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidViewNodeDetailEncodingTest {
    @Test
    fun passwordInputVariationsAreRedactedAndMarkedSecure() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val inputTypes = listOf(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            )

            inputTypes.forEach { inputType ->
                val input = EditText(instrumentation.targetContext).apply {
                    this.inputType = inputType
                    setText("private-password")
                    setSelection(text.length)
                }
                val payload = nodeDetail(input)

                assertNull(payload.attribute("android.text.text"))
                assertNull(payload.attribute("android.textInput.selectionStart"))
                assertNull(payload.attribute("android.textInput.selectionEnd"))
                assertEquals(
                    true,
                    (payload.attribute("android.textInput.secure") as?
                        RuntimeAttributeValue.BooleanValue)?.value
                )
            }
        }
    }

    @Test
    fun passwordTransformationMethodMarksInputSecure() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val input = EditText(instrumentation.targetContext).apply {
                inputType = InputType.TYPE_CLASS_TEXT
                transformationMethod = PasswordTransformationMethod.getInstance()
                setText("private-password")
            }
            val payload = nodeDetail(input)

            assertNull(payload.attribute("android.text.text"))
            assertEquals(
                true,
                (payload.attribute("android.textInput.secure") as?
                    RuntimeAttributeValue.BooleanValue)?.value
            )
        }
    }

    @Test
    fun ordinaryTextInputRemainsVisibleAndIsMarkedNonSecure() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val input = EditText(instrumentation.targetContext).apply {
                inputType = InputType.TYPE_CLASS_TEXT
                setText("visible-text")
                setSelection(2, 6)
            }
            val payload = nodeDetail(input)

            assertEquals(
                "visible-text",
                (payload.attribute("android.text.text") as?
                    RuntimeAttributeValue.StringValue)?.value
            )
            assertFalse(
                (payload.attribute("android.textInput.secure") as
                    RuntimeAttributeValue.BooleanValue).value
            )
            assertEquals(
                2L,
                (payload.attribute("android.textInput.selectionStart") as
                    RuntimeAttributeValue.Integer).value
            )
            assertEquals(
                6L,
                (payload.attribute("android.textInput.selectionEnd") as
                    RuntimeAttributeValue.Integer).value
            )
        }
    }

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
    fun constraintLayoutPercentDimensionsBecomeParentRelations() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val nodeRegistry = RuntimeNodeRegistry<View>()
            val parent = ConstraintLayout(instrumentation.targetContext).apply {
                setPadding(10, 12, 14, 16)
            }
            val source = View(instrumentation.targetContext).apply {
                id = View.generateViewId()
            }
            parent.addView(
                source,
                ConstraintLayout.LayoutParams(
                    ConstraintLayout.LayoutParams.MATCH_CONSTRAINT,
                    ConstraintLayout.LayoutParams.MATCH_CONSTRAINT
                ).apply {
                    matchConstraintDefaultWidth =
                        ConstraintLayout.LayoutParams.MATCH_CONSTRAINT_PERCENT
                    matchConstraintDefaultHeight =
                        ConstraintLayout.LayoutParams.MATCH_CONSTRAINT_PERCENT
                    matchConstraintPercentWidth = 0.4f
                    matchConstraintPercentHeight = 0.25f
                }
            )
            val sourceNodeID = nodeRegistry.nodeID(source)
            val parentNodeID = nodeRegistry.nodeID(parent)

            val relations = layoutRelations(
                AndroidViewNodeDetailProvider(
                    nodeRegistry = nodeRegistry,
                    mainThreadExecutor = AndroidMainThreadExecutor()
                ).nodeDetail(
                    nodeID = sourceNodeID,
                    cancellationToken = RuntimeCancellationToken { false }
                )
            )

            assertEquals(listOf("width", "height"), relations.map { it.source.anchor })
            assertTrue(relations.all { relation -> relation.source.nodeID == sourceNodeID })
            assertEquals(listOf("width", "height"), relations.map { it.target?.anchor })
            assertTrue(relations.all { relation -> relation.target?.nodeID == parentNodeID })
            assertEquals(0.4, relations[0].multiplier, 0.0001)
            assertEquals(0.25, relations[1].multiplier, 0.0001)
            val density = source.resources.displayMetrics.density.toDouble()
            assertEquals(-(10.0 + 14.0) * 0.4 / density, relations[0].offset.value, 0.0001)
            assertEquals(-(12.0 + 16.0) * 0.25 / density, relations[1].offset.value, 0.0001)
        }
    }

    @Test
    fun constraintLayoutExplicitDimensionRatiosBecomeSameNodeRelations() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val cases = listOf(
                Triple("W,16:9", "width" to "height", 16.0 / 9.0),
                Triple("H,16:9", "height" to "width", 9.0 / 16.0)
            )

            cases.forEach { (ratio, anchors, multiplier) ->
                val nodeRegistry = RuntimeNodeRegistry<View>()
                val parent = ConstraintLayout(instrumentation.targetContext)
                val source = View(instrumentation.targetContext).apply {
                    id = View.generateViewId()
                }
                parent.addView(
                    source,
                    ConstraintLayout.LayoutParams(
                        ConstraintLayout.LayoutParams.MATCH_CONSTRAINT,
                        ConstraintLayout.LayoutParams.MATCH_CONSTRAINT
                    ).apply {
                        dimensionRatio = ratio
                    }
                )
                val sourceNodeID = nodeRegistry.nodeID(source)

                val relation = layoutRelations(
                    AndroidViewNodeDetailProvider(
                        nodeRegistry = nodeRegistry,
                        mainThreadExecutor = AndroidMainThreadExecutor()
                    ).nodeDetail(
                        nodeID = sourceNodeID,
                        cancellationToken = RuntimeCancellationToken { false }
                    )
                ).single()

                assertEquals(anchors.first, relation.source.anchor)
                assertEquals(sourceNodeID, relation.source.nodeID)
                assertEquals(anchors.second, relation.target?.anchor)
                assertEquals(sourceNodeID, relation.target?.nodeID)
                assertEquals(multiplier, relation.multiplier, 0.0001)
                assertEquals(0.0, relation.offset.value, 0.0001)
                assertEquals(RuntimeLayoutRelationKind.equal, relation.relation)
            }
        }
    }

    @Test
    fun constraintLayoutMatchConstraintBoundsBecomeInequalityRelations() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val nodeRegistry = RuntimeNodeRegistry<View>()
            val parent = ConstraintLayout(instrumentation.targetContext)
            val source = View(instrumentation.targetContext).apply {
                id = View.generateViewId()
            }
            parent.addView(
                source,
                ConstraintLayout.LayoutParams(
                    ConstraintLayout.LayoutParams.MATCH_CONSTRAINT,
                    ConstraintLayout.LayoutParams.MATCH_CONSTRAINT
                ).apply {
                    matchConstraintMinWidth = 20
                    matchConstraintMaxWidth = 80
                    matchConstraintMinHeight = 10
                    matchConstraintMaxHeight = 60
                }
            )

            val relations = layoutRelations(
                AndroidViewNodeDetailProvider(
                    nodeRegistry = nodeRegistry,
                    mainThreadExecutor = AndroidMainThreadExecutor()
                ).nodeDetail(
                    nodeID = nodeRegistry.nodeID(source),
                    cancellationToken = RuntimeCancellationToken { false }
                )
            )
            val density = source.resources.displayMetrics.density.toDouble()

            assertEquals(
                listOf("width", "width", "height", "height"),
                relations.map { relation -> relation.source.anchor }
            )
            assertEquals(
                listOf(
                    RuntimeLayoutRelationKind.greaterThanOrEqual,
                    RuntimeLayoutRelationKind.lessThanOrEqual,
                    RuntimeLayoutRelationKind.greaterThanOrEqual,
                    RuntimeLayoutRelationKind.lessThanOrEqual
                ),
                relations.map { relation -> relation.relation }
            )
            assertTrue(relations.all { relation -> relation.target == null })
            assertEquals(
                listOf(20.0, 80.0, 10.0, 60.0).map { value -> value / density },
                relations.map { relation -> relation.offset.value }
            )
        }
    }

    @Test
    fun ambiguousOrInvalidDimensionRatiosAreOmitted() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val ratios = listOf("16:9", "W,0:9", "H,16:0", "W,invalid")

            ratios.forEach { ratio ->
                val parent = ConstraintLayout(instrumentation.targetContext)
                val source = View(instrumentation.targetContext).apply {
                    id = View.generateViewId()
                }
                parent.addView(
                    source,
                    ConstraintLayout.LayoutParams(
                        ConstraintLayout.LayoutParams.MATCH_CONSTRAINT,
                        ConstraintLayout.LayoutParams.MATCH_CONSTRAINT
                    ).apply {
                        dimensionRatio = ratio
                    }
                )

                assertTrue(layoutRelations(nodeDetail(source)).isEmpty())
            }
        }
    }

    @Test
    fun constraintLayoutInvalidPercentDimensionsAreOmitted() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val nodeRegistry = RuntimeNodeRegistry<View>()
            val parent = ConstraintLayout(instrumentation.targetContext)
            val source = View(instrumentation.targetContext).apply {
                id = View.generateViewId()
            }
            parent.addView(
                source,
                ConstraintLayout.LayoutParams(
                    ConstraintLayout.LayoutParams.MATCH_CONSTRAINT,
                    ConstraintLayout.LayoutParams.MATCH_CONSTRAINT
                ).apply {
                    matchConstraintDefaultWidth =
                        ConstraintLayout.LayoutParams.MATCH_CONSTRAINT_SPREAD
                    matchConstraintDefaultHeight =
                        ConstraintLayout.LayoutParams.MATCH_CONSTRAINT_PERCENT
                    matchConstraintPercentWidth = 0.4f
                    matchConstraintPercentHeight = Float.NaN
                }
            )

            val relations = layoutRelations(
                AndroidViewNodeDetailProvider(
                    nodeRegistry = nodeRegistry,
                    mainThreadExecutor = AndroidMainThreadExecutor()
                ).nodeDetail(
                    nodeID = nodeRegistry.nodeID(source),
                    cancellationToken = RuntimeCancellationToken { false }
                )
            )

            assertTrue(relations.isEmpty())
        }
    }

    @Test
    fun constraintLayoutOutOfRangePercentDimensionsAreOmitted() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val nodeRegistry = RuntimeNodeRegistry<View>()
            val parent = ConstraintLayout(instrumentation.targetContext)
            val source = View(instrumentation.targetContext).apply {
                id = View.generateViewId()
            }
            parent.addView(
                source,
                ConstraintLayout.LayoutParams(
                    ConstraintLayout.LayoutParams.MATCH_CONSTRAINT,
                    ConstraintLayout.LayoutParams.MATCH_CONSTRAINT
                ).apply {
                    matchConstraintDefaultWidth =
                        ConstraintLayout.LayoutParams.MATCH_CONSTRAINT_PERCENT
                    matchConstraintDefaultHeight =
                        ConstraintLayout.LayoutParams.MATCH_CONSTRAINT_PERCENT
                    matchConstraintPercentWidth = -0.1f
                    matchConstraintPercentHeight = 1.1f
                }
            )

            val relations = layoutRelations(
                AndroidViewNodeDetailProvider(
                    nodeRegistry = nodeRegistry,
                    mainThreadExecutor = AndroidMainThreadExecutor()
                ).nodeDetail(
                    nodeID = nodeRegistry.nodeID(source),
                    cancellationToken = RuntimeCancellationToken { false }
                )
            )

            assertTrue(relations.isEmpty())
        }
    }

    @Test
    fun constraintLayoutSiblingAnchorsBecomeLogicalRelations() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val nodeRegistry = RuntimeNodeRegistry<View>()
            val parent = ConstraintLayout(instrumentation.targetContext)
            val target = View(instrumentation.targetContext).apply {
                id = View.generateViewId()
            }
            val source = View(instrumentation.targetContext).apply {
                id = View.generateViewId()
            }
            parent.addView(target, ConstraintLayout.LayoutParams(40, 20))
            parent.addView(
                source,
                ConstraintLayout.LayoutParams(54, 27).apply {
                    startToEnd = target.id
                    topToBottom = target.id
                    marginStart = 12
                    topMargin = 8
                }
            )
            val sourceNodeID = nodeRegistry.nodeID(source)
            val targetNodeID = nodeRegistry.nodeID(target)

            val relations = layoutRelations(
                AndroidViewNodeDetailProvider(
                    nodeRegistry = nodeRegistry,
                    mainThreadExecutor = AndroidMainThreadExecutor()
                ).nodeDetail(
                    nodeID = sourceNodeID,
                    cancellationToken = RuntimeCancellationToken { false }
                )
            ).filter { relation -> relation.target != null }

            val density = source.resources.displayMetrics.density.toDouble()
            assertEquals(listOf("start", "top"), relations.map { it.source.anchor })
            assertEquals(listOf("end", "bottom"), relations.map { it.target?.anchor })
            assertTrue(relations.all { relation -> relation.source.nodeID == sourceNodeID })
            assertTrue(relations.all { relation -> relation.target?.nodeID == targetNodeID })
            assertEquals(12.0 / density, relations[0].offset.value, 0.0001)
            assertEquals(8.0 / density, relations[1].offset.value, 0.0001)
        }
    }

    @Test
    fun constraintLayoutParentEndAnchorsUseNegativeMargins() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val nodeRegistry = RuntimeNodeRegistry<View>()
            val parent = ConstraintLayout(instrumentation.targetContext)
            val source = View(instrumentation.targetContext).apply {
                id = View.generateViewId()
            }
            parent.addView(
                source,
                ConstraintLayout.LayoutParams(54, 27).apply {
                    endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                    bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                    marginEnd = 6
                    bottomMargin = 4
                }
            )
            val sourceNodeID = nodeRegistry.nodeID(source)
            val parentNodeID = nodeRegistry.nodeID(parent)

            val relations = layoutRelations(
                AndroidViewNodeDetailProvider(
                    nodeRegistry = nodeRegistry,
                    mainThreadExecutor = AndroidMainThreadExecutor()
                ).nodeDetail(
                    nodeID = sourceNodeID,
                    cancellationToken = RuntimeCancellationToken { false }
                )
            ).filter { relation -> relation.target != null }

            val density = source.resources.displayMetrics.density.toDouble()
            assertEquals(listOf("end", "bottom"), relations.map { it.source.anchor })
            assertTrue(relations.all { relation -> relation.target?.nodeID == parentNodeID })
            assertEquals(-6.0 / density, relations[0].offset.value, 0.0001)
            assertEquals(-4.0 / density, relations[1].offset.value, 0.0001)
        }
    }

    @Test
    fun constraintLayoutGoneTargetUsesGoneMargin() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val nodeRegistry = RuntimeNodeRegistry<View>()
            val parent = ConstraintLayout(instrumentation.targetContext)
            val target = View(instrumentation.targetContext).apply {
                id = View.generateViewId()
                visibility = View.GONE
            }
            val source = View(instrumentation.targetContext).apply {
                id = View.generateViewId()
            }
            parent.addView(target, ConstraintLayout.LayoutParams(40, 20))
            parent.addView(
                source,
                ConstraintLayout.LayoutParams(54, 27).apply {
                    startToEnd = target.id
                    marginStart = 12
                    goneStartMargin = 20
                }
            )

            val relation = layoutRelations(
                AndroidViewNodeDetailProvider(
                    nodeRegistry = nodeRegistry,
                    mainThreadExecutor = AndroidMainThreadExecutor()
                ).nodeDetail(
                    nodeID = nodeRegistry.nodeID(source),
                    cancellationToken = RuntimeCancellationToken { false }
                )
            ).single { item -> item.target != null }

            val density = source.resources.displayMetrics.density.toDouble()
            assertEquals(20.0 / density, relation.offset.value, 0.0001)
        }
    }

    @Test
    fun constraintLayoutAbsoluteAndBaselineAnchorsRemainDistinct() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val nodeRegistry = RuntimeNodeRegistry<View>()
            val parent = ConstraintLayout(instrumentation.targetContext)
            val target = View(instrumentation.targetContext).apply {
                id = View.generateViewId()
            }
            val source = View(instrumentation.targetContext).apply {
                id = View.generateViewId()
            }
            parent.addView(target, ConstraintLayout.LayoutParams(40, 20))
            parent.addView(
                source,
                ConstraintLayout.LayoutParams(54, 27).apply {
                    leftToRight = target.id
                    rightToRight = ConstraintLayout.LayoutParams.PARENT_ID
                    baselineToBaseline = target.id
                    leftMargin = 10
                    rightMargin = 6
                    baselineMargin = 3
                }
            )
            val sourceNodeID = nodeRegistry.nodeID(source)
            val targetNodeID = nodeRegistry.nodeID(target)
            val parentNodeID = nodeRegistry.nodeID(parent)

            val relations = layoutRelations(
                AndroidViewNodeDetailProvider(
                    nodeRegistry = nodeRegistry,
                    mainThreadExecutor = AndroidMainThreadExecutor()
                ).nodeDetail(
                    nodeID = sourceNodeID,
                    cancellationToken = RuntimeCancellationToken { false }
                )
            ).filter { relation -> relation.target != null }

            val density = source.resources.displayMetrics.density.toDouble()
            assertEquals(
                listOf("left", "right", "baseline"),
                relations.map { relation -> relation.source.anchor }
            )
            assertEquals(
                listOf("right", "right", "baseline"),
                relations.map { relation -> relation.target?.anchor }
            )
            assertEquals(
                listOf(targetNodeID, parentNodeID, targetNodeID),
                relations.map { relation -> relation.target?.nodeID }
            )
            assertEquals(10.0 / density, relations[0].offset.value, 0.0001)
            assertEquals(-6.0 / density, relations[1].offset.value, 0.0001)
            assertEquals(3.0 / density, relations[2].offset.value, 0.0001)
        }
    }

    @Test
    fun constraintLayoutUnresolvedTargetsAreOmitted() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val nodeRegistry = RuntimeNodeRegistry<View>()
            val parent = ConstraintLayout(instrumentation.targetContext)
            val source = View(instrumentation.targetContext).apply {
                id = View.generateViewId()
            }
            parent.addView(
                source,
                ConstraintLayout.LayoutParams(54, 27).apply {
                    startToStart = View.generateViewId()
                }
            )

            val relations = layoutRelations(
                AndroidViewNodeDetailProvider(
                    nodeRegistry = nodeRegistry,
                    mainThreadExecutor = AndroidMainThreadExecutor()
                ).nodeDetail(
                    nodeID = nodeRegistry.nodeID(source),
                    cancellationToken = RuntimeCancellationToken { false }
                )
            )

            assertTrue(relations.none { relation -> relation.target != null })
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
}
