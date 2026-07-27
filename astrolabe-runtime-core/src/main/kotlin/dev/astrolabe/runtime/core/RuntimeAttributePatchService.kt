//
//  RuntimeAttributePatchService.kt
//  astrolabe-runtime-android
//
//  Created by 轩辕十四 on 2026/7/22.
//

package dev.astrolabe.runtime.core

import dev.astrolabe.protocol.RuntimeApplyAttributePatchParameters
import dev.astrolabe.protocol.RuntimeAttributeIdentifier
import dev.astrolabe.protocol.RuntimeAttributePatch
import dev.astrolabe.protocol.RuntimeAttributePatchListPayload
import dev.astrolabe.protocol.RuntimeAttributeValue
import dev.astrolabe.protocol.RuntimeClearAttributePatchesPayload
import dev.astrolabe.protocol.RuntimeError
import dev.astrolabe.protocol.RuntimeErrorCode
import dev.astrolabe.protocol.RuntimeOpaqueIdentifier
import dev.astrolabe.protocol.RuntimePatchableAttributesPayload
import dev.astrolabe.protocol.RuntimeRevertAttributePatchParameters
import dev.astrolabe.protocol.RuntimeRevertAttributePatchPayload
import java.util.UUID

/** Platform-neutral lifecycle used by Runtime patch routes. */
public interface RuntimeAttributePatchProviding {
    /** Returns the Runtime-owned temporary mutation catalog. */
    public fun patchableAttributes(): RuntimePatchableAttributesPayload

    /** Applies or replaces one temporary attribute patch. */
    public fun applyAttributePatch(
        parameters: RuntimeApplyAttributePatchParameters
    ): RuntimeAttributePatch

    /** Returns active patches in first-apply order. */
    public fun activeAttributePatches(): RuntimeAttributePatchListPayload

    /** Reverts one active patch. */
    public fun revertAttributePatch(
        parameters: RuntimeRevertAttributePatchParameters
    ): RuntimeRevertAttributePatchPayload

    /** Reverts all active patches in reverse order. */
    public fun clearAttributePatches(): RuntimeClearAttributePatchesPayload
}

/** Coordinates patch Commands and Mementos without depending on platform UI objects. */
public class RuntimeAttributePatchService private constructor(
    private val mutator: RuntimeAttributeMutating,
    private val patchIDFactory: () -> RuntimeOpaqueIdentifier,
    private val unixTimeProvider: () -> Double
) : RuntimeAttributePatchProviding {
    private data class ActivePatch(
        /** Public protocol record returned to the Host. */
        val record: RuntimeAttributePatch,
        /** Descriptor used to compose and validate related mutations. */
        val descriptor: RuntimeAttributeMutationDescriptor,
        /** Restores state observed immediately before this command. */
        val restore: () -> RuntimeAttributeValue?
    )

    private data class PatchIntent(
        /** Stable identifier retained when the same attribute is updated. */
        val patchID: RuntimeOpaqueIdentifier,
        /** Runtime node resolved by the platform Mutator during replay. */
        val nodeID: RuntimeOpaqueIdentifier,
        /** Semantic attribute requested by the Host. */
        val attributeIdentifier: RuntimeAttributeIdentifier,
        /** Value applied whenever this state domain is replayed. */
        val requestedValue: RuntimeAttributeValue,
        /** Value captured before this attribute was first patched. */
        val originalValue: RuntimeAttributeValue?,
        /** Timestamp of the most recent explicit update. */
        val appliedAtUnixTime: Double,
        /** Platform descriptor resolved for this attribute. */
        val descriptor: RuntimeAttributeMutationDescriptor
    )

    private val lock = Any()
    private val patchesByID = mutableMapOf<RuntimeOpaqueIdentifier, ActivePatch>()
    private val patchIDsByDomain = mutableMapOf<RuntimeAttributeMutationDomain, MutableList<RuntimeOpaqueIdentifier>>()
    private val patchOrder = mutableListOf<RuntimeOpaqueIdentifier>()

    /** Creates a process-scoped patch service backed by [mutator]. */
    public constructor(mutator: RuntimeAttributeMutating) : this(
        mutator = mutator,
        patchIDFactory = { RuntimeOpaqueIdentifier(UUID.randomUUID().toString()) },
        unixTimeProvider = { System.currentTimeMillis().toDouble() / MILLISECONDS_PER_SECOND }
    )

    override fun patchableAttributes(): RuntimePatchableAttributesPayload =
        mutator.patchableAttributeCatalog

    override fun applyAttributePatch(
        parameters: RuntimeApplyAttributePatchParameters
    ): RuntimeAttributePatch = synchronized(lock) {
        val descriptor = mutator.mutationDescriptor(
            parameters.nodeID,
            parameters.attributeIdentifier
        )
        val domain = descriptor.domain
        val currentPatches = activePatches(domain)
        val existingIndex = currentPatches.indexOfFirst { activePatch ->
            activePatch.record.attributeIdentifier == parameters.attributeIdentifier
        }.takeIf { index -> index >= 0 }
        if (existingIndex == null && currentPatches.any { activePatch ->
                activePatch.descriptor.effectIdentifiers.any(descriptor.effectIdentifiers::contains)
            }) {
            throw failure(
                RuntimeErrorCode.patchConflict,
                "The requested attribute overlaps an active patch",
                "Revert the conflicting patch before applying this attribute"
            )
        }

        val patchID = existingIndex?.let { index -> currentPatches[index].record.patchID }
            ?: patchIDFactory()
        val candidateIntents = currentPatches.map(::intent).toMutableList()
        val candidateIntent = PatchIntent(
            patchID = patchID,
            nodeID = parameters.nodeID,
            attributeIdentifier = parameters.attributeIdentifier,
            requestedValue = parameters.value,
            originalValue = existingIndex?.let { index ->
                currentPatches[index].record.originalValue
            },
            appliedAtUnixTime = unixTimeProvider(),
            descriptor = descriptor
        )
        if (existingIndex == null) {
            candidateIntents.add(candidateIntent)
        } else {
            candidateIntents[existingIndex] = candidateIntent
        }

        val rewrittenPatches = rewrite(
            domain = domain,
            currentPatches = currentPatches,
            candidateIntents = candidateIntents,
            newPatchIDs = if (existingIndex == null) setOf(patchID) else emptySet()
        )
        store(rewrittenPatches, domain)
        if (existingIndex == null) {
            patchOrder.add(patchID)
        }
        patchesByID[patchID]?.record ?: throw failure(
            RuntimeErrorCode.internalFailure,
            "The Runtime could not read the patch that was just applied"
        )
    }

    override fun activeAttributePatches(): RuntimeAttributePatchListPayload = synchronized(lock) {
        RuntimeAttributePatchListPayload(
            patchOrder.mapNotNull { patchID -> patchesByID[patchID]?.record }
        )
    }

    override fun revertAttributePatch(
        parameters: RuntimeRevertAttributePatchParameters
    ): RuntimeRevertAttributePatchPayload = synchronized(lock) {
        val activePatch = patchesByID[parameters.patchID] ?: throw failure(
            RuntimeErrorCode.patchNotFound,
            "The requested patch is not active in this Runtime session"
        )
        val domain = activePatch.descriptor.domain
        val currentPatches = activePatches(domain)
        val candidateIntents = currentPatches
            .filter { patch -> patch.record.patchID != parameters.patchID }
            .map(::intent)
        val rewrittenPatches = rewrite(
            domain = domain,
            currentPatches = currentPatches,
            candidateIntents = candidateIntents,
            newPatchIDs = emptySet()
        )
        store(rewrittenPatches, domain)
        patchOrder.removeAll { patchID -> patchID == parameters.patchID }
        RuntimeRevertAttributePatchPayload(
            revertedPatchID = parameters.patchID,
            restoredValue = activePatch.record.originalValue,
            remainingPatchCount = patchesByID.size
        )
    }

    override fun clearAttributePatches(): RuntimeClearAttributePatchesPayload = synchronized(lock) {
        val revertedPatchIDs = restoreAll()
        RuntimeClearAttributePatchesPayload(
            revertedPatchIDs = revertedPatchIDs,
            remainingPatchCount = patchesByID.size
        )
    }

    private fun activePatches(domain: RuntimeAttributeMutationDomain): List<ActivePatch> =
        patchIDsByDomain[domain].orEmpty().mapNotNull(patchesByID::get)

    private fun intent(activePatch: ActivePatch): PatchIntent = PatchIntent(
        patchID = activePatch.record.patchID,
        nodeID = activePatch.record.nodeID,
        attributeIdentifier = activePatch.record.attributeIdentifier,
        requestedValue = activePatch.record.requestedValue,
        originalValue = activePatch.record.originalValue,
        appliedAtUnixTime = activePatch.record.appliedAtUnixTime,
        descriptor = activePatch.descriptor
    )

    private fun rewrite(
        domain: RuntimeAttributeMutationDomain,
        currentPatches: List<ActivePatch>,
        candidateIntents: List<PatchIntent>,
        newPatchIDs: Set<RuntimeOpaqueIdentifier>
    ): List<ActivePatch> {
        rewind(currentPatches, domain)
        return try {
            replay(candidateIntents, newPatchIDs)
        } catch (candidateError: Exception) {
            try {
                val restoredPatches = replay(currentPatches.map(::intent), emptySet())
                store(restoredPatches, domain)
            } catch (restorationError: Exception) {
                throw failure(
                    RuntimeErrorCode.patchRestorationFailed,
                    "The Runtime could not restore the original state after the patch set failed",
                    restorationError.message
                )
            }
            throw candidateError
        }
    }

    private fun rewind(
        activePatches: List<ActivePatch>,
        domain: RuntimeAttributeMutationDomain
    ) {
        try {
            restore(activePatches)
        } catch (rewindError: Exception) {
            try {
                replay(activePatches.map(::intent), emptySet())
                store(activePatches, domain)
            } catch (restorationError: Exception) {
                throw failure(
                    RuntimeErrorCode.patchRestorationFailed,
                    "The Runtime could not restore the pre-call state after rewind failed",
                    restorationError.message
                )
            }
            throw asRestorationFailure(rewindError)
        }
    }

    private fun replay(
        intents: List<PatchIntent>,
        newPatchIDs: Set<RuntimeOpaqueIdentifier>
    ): List<ActivePatch> {
        val replayedPatches = mutableListOf<ActivePatch>()
        try {
            for (intent in intents) {
                val mutation = mutator.apply(
                    intent.nodeID,
                    intent.attributeIdentifier,
                    intent.requestedValue
                )
                validate(mutation, intent.requestedValue)
                replayedPatches.add(
                    ActivePatch(
                        record = RuntimeAttributePatch(
                            patchID = intent.patchID,
                            nodeID = intent.nodeID,
                            attributeIdentifier = intent.attributeIdentifier,
                            originalValue = if (intent.patchID in newPatchIDs) {
                                mutation.originalValue
                            } else {
                                intent.originalValue
                            },
                            requestedValue = intent.requestedValue,
                            actualValue = mutation.actualValue,
                            appliedAtUnixTime = intent.appliedAtUnixTime
                        ),
                        descriptor = intent.descriptor,
                        restore = mutation.restore
                    )
                )
            }
            return replayedPatches
        } catch (replayError: Exception) {
            try {
                restore(replayedPatches)
            } catch (restorationError: Exception) {
                throw failure(
                    RuntimeErrorCode.patchRestorationFailed,
                    "The Runtime could not fully revert the failed patch set",
                    restorationError.message
                )
            }
            throw replayError
        }
    }

    private fun restore(activePatches: List<ActivePatch>) {
        for (activePatch in activePatches.asReversed()) {
            restore(activePatch)
        }
    }

    private fun restore(activePatch: ActivePatch): RuntimeAttributeValue? {
        val restoredValue = try {
            activePatch.restore()
        } catch (error: Exception) {
            throw asRestorationFailure(error)
        }
        if (!optionalValuesMatch(restoredValue, activePatch.record.originalValue)) {
            throw failure(
                RuntimeErrorCode.patchRestorationFailed,
                "The temporary attribute did not return to its original value"
            )
        }
        return restoredValue
    }

    private fun store(
        activePatches: List<ActivePatch>,
        domain: RuntimeAttributeMutationDomain
    ) {
        patchIDsByDomain[domain].orEmpty().forEach(patchesByID::remove)
        if (activePatches.isEmpty()) {
            patchIDsByDomain.remove(domain)
            return
        }
        patchIDsByDomain[domain] = activePatches
            .mapTo(mutableListOf()) { patch -> patch.record.patchID }
        activePatches.forEach { patch -> patchesByID[patch.record.patchID] = patch }
    }

    private fun restoreAll(): List<RuntimeOpaqueIdentifier> {
        val revertedPatchIDs = mutableListOf<RuntimeOpaqueIdentifier>()
        var firstError: Exception? = null
        for (patchID in patchOrder.asReversed().toList()) {
            val activePatch = patchesByID[patchID] ?: continue
            try {
                restore(activePatch)
                remove(activePatch)
                revertedPatchIDs.add(patchID)
            } catch (error: Exception) {
                if (firstError == null) {
                    firstError = error
                }
            }
        }
        firstError?.let { error ->
            throw failure(
                RuntimeErrorCode.patchRestorationFailed,
                "One or more temporary attributes could not be restored",
                error.message
            )
        }
        return revertedPatchIDs
    }

    private fun remove(activePatch: ActivePatch) {
        val patchID = activePatch.record.patchID
        val domain = activePatch.descriptor.domain
        patchesByID.remove(patchID)
        patchIDsByDomain[domain]?.removeAll { candidate -> candidate == patchID }
        if (patchIDsByDomain[domain].isNullOrEmpty()) {
            patchIDsByDomain.remove(domain)
        }
        patchOrder.removeAll { candidate -> candidate == patchID }
    }

    private fun validate(
        mutation: RuntimeAttributeMutation,
        requestedValue: RuntimeAttributeValue
    ) {
        val expectedValue = mutation.comparisonValue ?: requestedValue
        val actualValue = mutation.actualValue
        if (actualValue != null && valuesMatch(actualValue, expectedValue)) {
            return
        }
        val restoredValue = try {
            mutation.restore()
        } catch (error: Exception) {
            throw failure(
                RuntimeErrorCode.patchRestorationFailed,
                "The Runtime could not revert the unapplied temporary attribute",
                error.message
            )
        }
        if (!optionalValuesMatch(restoredValue, mutation.originalValue)) {
            throw failure(
                RuntimeErrorCode.patchRestorationFailed,
                "The Runtime could not restore the original value after validation failed"
            )
        }
        throw failure(
            RuntimeErrorCode.invalidAttributeValue,
            "The target did not accept the requested temporary attribute value",
            mutator.invalidValueRecoverySuggestion
        )
    }

    private fun valuesMatch(
        lhs: RuntimeAttributeValue,
        rhs: RuntimeAttributeValue
    ): Boolean = when {
        lhs is RuntimeAttributeValue.Number && rhs is RuntimeAttributeValue.Number ->
            approximatelyEqual(lhs.value, rhs.value)
        lhs is RuntimeAttributeValue.Measurement && rhs is RuntimeAttributeValue.Measurement ->
            lhs.value.unit == rhs.value.unit && approximatelyEqual(lhs.value.value, rhs.value.value)
        lhs is RuntimeAttributeValue.Color && rhs is RuntimeAttributeValue.Color ->
            colorSpacesMatch(lhs.value.colorSpace, rhs.value.colorSpace) &&
                colorComponentMatches(lhs.value.red, rhs.value.red) &&
                colorComponentMatches(lhs.value.green, rhs.value.green) &&
                colorComponentMatches(lhs.value.blue, rhs.value.blue) &&
                colorComponentMatches(lhs.value.alpha, rhs.value.alpha)
        else -> lhs == rhs
    }

    private fun optionalValuesMatch(
        lhs: RuntimeAttributeValue?,
        rhs: RuntimeAttributeValue?
    ): Boolean = when {
        lhs == null && rhs == null -> true
        lhs != null && rhs != null -> valuesMatch(lhs, rhs)
        else -> false
    }

    private fun approximatelyEqual(lhs: Double, rhs: Double): Boolean =
        kotlin.math.abs(lhs - rhs) <= NUMERIC_TOLERANCE

    private fun colorComponentMatches(lhs: Double, rhs: Double): Boolean =
        kotlin.math.abs(lhs - rhs) <= COLOR_COMPONENT_TOLERANCE

    private fun colorSpacesMatch(lhs: String, rhs: String): Boolean {
        if (lhs.equals(rhs, ignoreCase = true)) {
            return true
        }
        val normalizedSpaces = setOf("srgb", "extended-srgb")
        return lhs.lowercase() in normalizedSpaces && rhs.lowercase() in normalizedSpaces
    }

    private fun asRestorationFailure(error: Exception): RuntimeProviderFailure {
        if (error is RuntimeProviderFailure && error.error.code == RuntimeErrorCode.patchRestorationFailed) {
            return error
        }
        return failure(
            RuntimeErrorCode.patchRestorationFailed,
            "The temporary attribute could not be restored",
            error.message
        )
    }

    private fun failure(
        code: RuntimeErrorCode,
        message: String,
        recoverySuggestion: String? = null
    ): RuntimeProviderFailure = RuntimeProviderFailure(
        RuntimeError(
            code = code,
            message = message,
            recoverySuggestion = recoverySuggestion
        )
    )

    private companion object {
        const val MILLISECONDS_PER_SECOND: Double = 1_000.0
        const val NUMERIC_TOLERANCE: Double = 0.000_001
        const val COLOR_COMPONENT_TOLERANCE: Double = 1.0 / 255.0 + NUMERIC_TOLERANCE
    }
}
