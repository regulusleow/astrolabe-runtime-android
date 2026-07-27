//
//  RuntimeAttributePatchServiceTest.kt
//  astrolabe-runtime-android
//
//  Created by 轩辕十四 on 2026/7/22.
//

package dev.astrolabe.runtime.core

import dev.astrolabe.protocol.RuntimeAttributeIdentifier
import dev.astrolabe.protocol.RuntimeAttributeValue
import dev.astrolabe.protocol.RuntimeError
import dev.astrolabe.protocol.RuntimeErrorCode
import dev.astrolabe.protocol.RuntimeExtensionMap
import dev.astrolabe.protocol.RuntimeOpaqueIdentifier
import dev.astrolabe.protocol.RuntimePatchValueType
import dev.astrolabe.protocol.RuntimePatchableAttribute
import dev.astrolabe.protocol.RuntimePatchableAttributesPayload
import dev.astrolabe.protocol.RuntimeRevertAttributePatchParameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeAttributePatchServiceTest {
    private val nodeID = RuntimeOpaqueIdentifier("node:1")
    private val text = RuntimeAttributeIdentifier("android.text.text")
    private val color = RuntimeAttributeIdentifier("android.text.color")

    @Test
    fun repeatedPatchRetainsIdentityAndFirstOriginalValue() {
        val mutator = FakeRuntimeAttributeMutator(
            initialValues = mapOf(text to string("Original"))
        )
        val service = RuntimeAttributePatchService(mutator)

        val first = service.applyAttributePatch(request(text, string("A")))
        val second = service.applyAttributePatch(request(text, string("B")))

        assertEquals(first.patchID, second.patchID)
        assertEquals(string("Original"), second.originalValue)
        assertEquals(string("B"), second.requestedValue)
        assertEquals(string("B"), second.actualValue)
        assertEquals(string("B"), mutator.value(text))
        assertEquals(listOf(second), service.activeAttributePatches().patches)
    }

    @Test
    fun updatingOnePatchReplaysOtherPatchesInTheSameDomain() {
        val mutator = FakeRuntimeAttributeMutator(
            initialValues = mapOf(
                text to string("Original"),
                color to string("black")
            )
        )
        val service = RuntimeAttributePatchService(mutator)

        val textPatch = service.applyAttributePatch(request(text, string("A")))
        val colorPatch = service.applyAttributePatch(request(color, string("red")))
        val updatedTextPatch = service.applyAttributePatch(request(text, string("B")))

        assertEquals(textPatch.patchID, updatedTextPatch.patchID)
        assertNotEquals(textPatch.patchID, colorPatch.patchID)
        assertEquals(string("B"), mutator.value(text))
        assertEquals(string("red"), mutator.value(color))
        assertEquals(
            listOf(updatedTextPatch.patchID, colorPatch.patchID),
            service.activeAttributePatches().patches.map { patch -> patch.patchID }
        )
    }

    @Test
    fun overlappingEffectsAreRejectedWithoutChangingActiveState() {
        val mutator = FakeRuntimeAttributeMutator(
            initialValues = mapOf(
                text to string("Original"),
                color to string("black")
            ),
            effects = mapOf(
                text to setOf("text.presentation"),
                color to setOf("text.presentation")
            )
        )
        val service = RuntimeAttributePatchService(mutator)
        val activePatch = service.applyAttributePatch(request(text, string("A")))

        val failure = runCatching {
            service.applyAttributePatch(request(color, string("red")))
        }.exceptionOrNull()

        assertTrue(failure is RuntimeProviderFailure)
        assertEquals(
            RuntimeErrorCode.patchConflict,
            (failure as RuntimeProviderFailure).error.code
        )
        assertEquals(string("A"), mutator.value(text))
        assertEquals(string("black"), mutator.value(color))
        assertEquals(listOf(activePatch), service.activeAttributePatches().patches)
    }

    @Test
    fun failedReplacementRestoresThePreviousPatchSet() {
        val mutator = FakeRuntimeAttributeMutator(
            initialValues = mapOf(text to string("Original")),
            rejectedValues = setOf(string("Rejected"))
        )
        val service = RuntimeAttributePatchService(mutator)
        val activePatch = service.applyAttributePatch(request(text, string("A")))

        val failure = runCatching {
            service.applyAttributePatch(request(text, string("Rejected")))
        }.exceptionOrNull()

        assertTrue(failure is RuntimeProviderFailure)
        assertEquals(
            RuntimeErrorCode.invalidAttributeValue,
            (failure as RuntimeProviderFailure).error.code
        )
        assertEquals(string("A"), mutator.value(text))
        assertEquals(listOf(activePatch), service.activeAttributePatches().patches)
    }

    @Test
    fun revertRestoresTheOriginalValueAndKeepsOtherPatches() {
        val mutator = FakeRuntimeAttributeMutator(
            initialValues = mapOf(
                text to string("Original"),
                color to string("black")
            )
        )
        val service = RuntimeAttributePatchService(mutator)
        val textPatch = service.applyAttributePatch(request(text, string("A")))
        val colorPatch = service.applyAttributePatch(request(color, string("red")))

        val result = service.revertAttributePatch(
            RuntimeRevertAttributePatchParameters(textPatch.patchID)
        )

        assertEquals(textPatch.patchID, result.revertedPatchID)
        assertEquals(string("Original"), result.restoredValue)
        assertEquals(1, result.remainingPatchCount)
        assertEquals(string("Original"), mutator.value(text))
        assertEquals(string("red"), mutator.value(color))
        assertEquals(listOf(colorPatch.patchID), service.activeAttributePatches().patches.map { it.patchID })
    }

    @Test
    fun clearRestoresAllValuesInReversePatchOrder() {
        val mutator = FakeRuntimeAttributeMutator(
            initialValues = mapOf(
                text to string("Original"),
                color to string("black")
            )
        )
        val service = RuntimeAttributePatchService(mutator)
        val textPatch = service.applyAttributePatch(request(text, string("A")))
        val colorPatch = service.applyAttributePatch(request(color, string("red")))

        val result = service.clearAttributePatches()

        assertEquals(listOf(colorPatch.patchID, textPatch.patchID), result.revertedPatchIDs)
        assertEquals(0, result.remainingPatchCount)
        assertEquals(string("Original"), mutator.value(text))
        assertEquals(string("black"), mutator.value(color))
        assertTrue(service.activeAttributePatches().patches.isEmpty())
    }

    @Test
    fun restorationFailureLeavesThePatchActive() {
        val mutator = FakeRuntimeAttributeMutator(
            initialValues = mapOf(text to string("Original"))
        )
        val service = RuntimeAttributePatchService(mutator)
        val activePatch = service.applyAttributePatch(request(text, string("A")))
        mutator.failRestoration = true

        val failure = runCatching {
            service.revertAttributePatch(
                RuntimeRevertAttributePatchParameters(activePatch.patchID)
            )
        }.exceptionOrNull()

        assertTrue(failure is RuntimeProviderFailure)
        assertEquals(
            RuntimeErrorCode.patchRestorationFailed,
            (failure as RuntimeProviderFailure).error.code
        )
        assertEquals(string("A"), mutator.value(text))
        assertEquals(listOf(activePatch.patchID), service.activeAttributePatches().patches.map { it.patchID })
    }

    @Test
    fun catalogIsOwnedByTheMutator() {
        val mutator = FakeRuntimeAttributeMutator(
            initialValues = mapOf(text to string("Original"))
        )
        val service = RuntimeAttributePatchService(mutator)

        assertSame(mutator.patchableAttributeCatalog, service.patchableAttributes())
    }

    private fun request(
        identifier: RuntimeAttributeIdentifier,
        value: RuntimeAttributeValue
    ) = dev.astrolabe.protocol.RuntimeApplyAttributePatchParameters(
        nodeID = nodeID,
        attributeIdentifier = identifier,
        value = value
    )

    private fun string(value: String): RuntimeAttributeValue =
        RuntimeAttributeValue.StringValue(value)
}

private class FakeRuntimeAttributeMutator(
    initialValues: Map<RuntimeAttributeIdentifier, RuntimeAttributeValue>,
    private val effects: Map<RuntimeAttributeIdentifier, Set<String>> = emptyMap(),
    private val rejectedValues: Set<RuntimeAttributeValue> = emptySet()
) : RuntimeAttributeMutating {
    private val values = initialValues.toMutableMap()

    /** Whether restore commands should simulate an unavailable runtime object. */
    var failRestoration: Boolean = false

    override val patchableAttributeCatalog = RuntimePatchableAttributesPayload(
        attributes = initialValues.keys.map { identifier ->
            RuntimePatchableAttribute(
                attributePattern = identifier.rawValue,
                valueType = RuntimePatchValueType("string"),
                targetRoles = emptyList(),
                valueConstraints = null,
                extensions = RuntimeExtensionMap(emptyMap())
            )
        }
    )

    override fun mutationDescriptor(
        nodeID: RuntimeOpaqueIdentifier,
        attributeIdentifier: RuntimeAttributeIdentifier
    ): RuntimeAttributeMutationDescriptor = RuntimeAttributeMutationDescriptor(
        domain = RuntimeAttributeMutationDomain(nodeID, "fake.presentation"),
        effectIdentifiers = effects[attributeIdentifier] ?: setOf(attributeIdentifier.rawValue)
    )

    override fun apply(
        nodeID: RuntimeOpaqueIdentifier,
        attributeIdentifier: RuntimeAttributeIdentifier,
        value: RuntimeAttributeValue
    ): RuntimeAttributeMutation {
        if (value in rejectedValues) {
            throw RuntimeProviderFailure(
                RuntimeError(
                    code = RuntimeErrorCode.invalidAttributeValue,
                    message = "The fake mutator rejected the requested value",
                    recoverySuggestion = null
                )
            )
        }
        val original = values.getValue(attributeIdentifier)
        values[attributeIdentifier] = value
        return RuntimeAttributeMutation(
            originalValue = original,
            actualValue = value,
            restore = {
                if (failRestoration) {
                    throw IllegalStateException("The fake runtime object is unavailable")
                }
                values[attributeIdentifier] = original
                original
            }
        )
    }

    fun value(identifier: RuntimeAttributeIdentifier): RuntimeAttributeValue =
        values.getValue(identifier)
}
