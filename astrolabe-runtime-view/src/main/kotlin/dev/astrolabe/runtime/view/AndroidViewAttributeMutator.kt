//
//  AndroidViewAttributeMutator.kt
//  astrolabe-runtime-android
//
//  Created by 轩辕十四 on 2026/7/22.
//

package dev.astrolabe.runtime.view

import android.view.View
import dev.astrolabe.protocol.RuntimeAttributeIdentifier
import dev.astrolabe.protocol.RuntimeAttributeValue
import dev.astrolabe.protocol.RuntimeError
import dev.astrolabe.protocol.RuntimeErrorCode
import dev.astrolabe.protocol.RuntimeOpaqueIdentifier
import dev.astrolabe.protocol.RuntimePatchableAttribute
import dev.astrolabe.protocol.RuntimePatchableAttributesPayload
import dev.astrolabe.runtime.core.RuntimeAttributeMutating
import dev.astrolabe.runtime.core.RuntimeAttributeMutation
import dev.astrolabe.runtime.core.RuntimeAttributeMutationDescriptor
import dev.astrolabe.runtime.core.RuntimeAttributeMutationDomain
import dev.astrolabe.runtime.core.RuntimeNodeRegistry
import dev.astrolabe.runtime.core.RuntimeProviderFailure

/** Strategy for one independently patchable Android View attribute. */
internal interface AndroidViewAttributeMutationStrategy {
    val patchableAttribute: RuntimePatchableAttribute

    val domainIdentifier: String

    val effectIdentifiers: Set<String>

    fun supports(view: View): Boolean

    fun apply(view: View, value: RuntimeAttributeValue): RuntimeAttributeMutation
}

/** Resolves Views and dispatches typed mutations through a stable Strategy registry. */
internal class AndroidViewAttributeMutator(
    private val nodeRegistry: RuntimeNodeRegistry<View>,
    private val mainThreadExecutor: AndroidMainThreadExecuting,
    strategies: List<AndroidViewAttributeMutationStrategy> = defaultStrategies
) : RuntimeAttributeMutating {
    private val strategiesByIdentifier = strategies.associateBy { strategy ->
        RuntimeAttributeIdentifier(strategy.patchableAttribute.attributePattern)
    }.also { indexedStrategies ->
        require(indexedStrategies.size == strategies.size) {
            "Android View mutation strategies must use unique attributes"
        }
    }

    override val patchableAttributeCatalog = RuntimePatchableAttributesPayload(
        strategies.map(AndroidViewAttributeMutationStrategy::patchableAttribute)
    )

    override val invalidValueRecoverySuggestion: String =
        "Discover the patch catalog and use a value accepted by the target Android View"

    override fun mutationDescriptor(
        nodeID: RuntimeOpaqueIdentifier,
        attributeIdentifier: RuntimeAttributeIdentifier
    ): RuntimeAttributeMutationDescriptor = mainThreadExecutor.execute {
        val view = resolvedView(nodeID)
        val strategy = strategy(attributeIdentifier, view)
        RuntimeAttributeMutationDescriptor(
            domain = RuntimeAttributeMutationDomain(nodeID, strategy.domainIdentifier),
            effectIdentifiers = strategy.effectIdentifiers
        )
    }

    override fun apply(
        nodeID: RuntimeOpaqueIdentifier,
        attributeIdentifier: RuntimeAttributeIdentifier,
        value: RuntimeAttributeValue
    ): RuntimeAttributeMutation {
        val mutation = mainThreadExecutor.execute {
            val view = resolvedView(nodeID)
            strategy(attributeIdentifier, view).apply(view, value)
        }
        return RuntimeAttributeMutation(
            originalValue = mutation.originalValue,
            actualValue = mutation.actualValue,
            comparisonValue = mutation.comparisonValue,
            restore = {
                mainThreadExecutor.execute(mutation.restore)
            }
        )
    }

    private fun strategy(
        attributeIdentifier: RuntimeAttributeIdentifier,
        view: View
    ): AndroidViewAttributeMutationStrategy {
        val strategy = strategiesByIdentifier[attributeIdentifier]
            ?: throw unsupportedAttribute(attributeIdentifier)
        if (!strategy.supports(view)) {
            throw unsupportedAttribute(attributeIdentifier)
        }
        return strategy
    }

    private fun resolvedView(nodeID: RuntimeOpaqueIdentifier): View =
        nodeRegistry.objectFor(nodeID) ?: throw RuntimeProviderFailure(
            RuntimeError(
                code = RuntimeErrorCode.nodeNotFound,
                message = "The requested Android View is no longer available",
                recoverySuggestion = "Capture a new hierarchy and retry with its node ID"
            )
        )

    private fun unsupportedAttribute(
        attributeIdentifier: RuntimeAttributeIdentifier
    ): RuntimeProviderFailure = RuntimeProviderFailure(
        RuntimeError(
            code = RuntimeErrorCode.unsupportedAttribute,
            message = "The requested attribute is not patchable for this Android View",
            recoverySuggestion = "Inspect the patch catalog and target a compatible View"
        )
    )

    private companion object {
        val defaultStrategies: List<AndroidViewAttributeMutationStrategy> = listOf(
            AndroidTextMutationStrategy(),
            AndroidFontSizeMutationStrategy(),
            AndroidTextColorMutationStrategy(),
            AndroidAlphaMutationStrategy(),
            AndroidVisibilityMutationStrategy(),
            AndroidBackgroundColorMutationStrategy(),
            AndroidImageScaleTypeMutationStrategy()
        )
    }
}
