//
//  AndroidViewAttributeMutationPrivacyTest.kt
//  astrolabe-runtime-android
//
//  Created by 轩辕十四 on 2026/8/24.
//

package dev.astrolabe.runtime.view

import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.astrolabe.protocol.RuntimeApplyAttributePatchParameters
import dev.astrolabe.protocol.RuntimeAttributeValue
import dev.astrolabe.protocol.RuntimeErrorCode
import dev.astrolabe.runtime.core.RuntimeAttributePatchService
import dev.astrolabe.runtime.core.RuntimeNodeRegistry
import dev.astrolabe.runtime.core.RuntimeProviderFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidViewAttributeMutationPrivacyTest {
    @Test
    fun sensitiveTextPatchIsRejectedBeforeOriginalValueIsCaptured() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val input = EditText(instrumentation.targetContext).apply {
                inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_VARIATION_PASSWORD
                setText("private-password")
            }
            val nodeRegistry = RuntimeNodeRegistry<View>()
            val mutator = AndroidViewAttributeMutator(
                nodeRegistry = nodeRegistry,
                mainThreadExecutor = AndroidMainThreadExecutor(),
                textPrivacyPolicy = AndroidViewTextPrivacyPolicy()
            )
            val service = RuntimeAttributePatchService(mutator)
            val nodeID = nodeRegistry.nodeID(input)

            val error = assertThrows(RuntimeProviderFailure::class.java) {
                service.applyAttributePatch(
                    RuntimeApplyAttributePatchParameters(
                        nodeID = nodeID,
                        attributeIdentifier = AndroidViewPatchCatalog.text,
                        value = RuntimeAttributeValue.StringValue("replacement")
                    )
                )
            }

            val directMutationError = assertThrows(RuntimeProviderFailure::class.java) {
                mutator.apply(
                    nodeID = nodeID,
                    attributeIdentifier = AndroidViewPatchCatalog.text,
                    value = RuntimeAttributeValue.StringValue("replacement")
                )
            }

            assertEquals(RuntimeErrorCode.unsupportedAttribute, error.error.code)
            assertEquals(
                RuntimeErrorCode.unsupportedAttribute,
                directMutationError.error.code
            )
            assertTrue(service.activeAttributePatches().patches.isEmpty())
            assertEquals("private-password", input.text.toString())
        }
    }

    @Test
    fun ordinaryTextPatchStillCapturesAndRestoresItsValue() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val label = TextView(instrumentation.targetContext).apply {
                text = "original"
            }
            val nodeRegistry = RuntimeNodeRegistry<View>()
            val mutator = AndroidViewAttributeMutator(
                nodeRegistry = nodeRegistry,
                mainThreadExecutor = AndroidMainThreadExecutor(),
                textPrivacyPolicy = AndroidViewTextPrivacyPolicy()
            )

            val mutation = mutator.apply(
                nodeID = nodeRegistry.nodeID(label),
                attributeIdentifier = AndroidViewPatchCatalog.text,
                value = RuntimeAttributeValue.StringValue("replacement")
            )

            assertEquals(
                RuntimeAttributeValue.StringValue("original"),
                mutation.originalValue
            )
            assertEquals(
                RuntimeAttributeValue.StringValue("replacement"),
                mutation.actualValue
            )
            assertEquals(
                RuntimeAttributeValue.StringValue("original"),
                mutation.restore()
            )
            assertEquals("original", label.text.toString())
        }
    }
}
