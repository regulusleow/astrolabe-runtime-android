//
//  RuntimeAttributePatchRoutes.kt
//  astrolabe-runtime-android
//
//  Created by 轩辕十四 on 2026/7/22.
//

package dev.astrolabe.runtime.core

import dev.astrolabe.protocol.RuntimeApplyAttributePatchParameters
import dev.astrolabe.protocol.RuntimeAttributePatch
import dev.astrolabe.protocol.RuntimeAttributePatchListPayload
import dev.astrolabe.protocol.RuntimeCapability
import dev.astrolabe.protocol.RuntimeClearAttributePatchesParameters
import dev.astrolabe.protocol.RuntimeClearAttributePatchesPayload
import dev.astrolabe.protocol.RuntimeListAttributePatchesParameters
import dev.astrolabe.protocol.RuntimeMessageCodec
import dev.astrolabe.protocol.RuntimeMethod
import dev.astrolabe.protocol.RuntimePatchableAttributesParameters
import dev.astrolabe.protocol.RuntimePatchableAttributesPayload
import dev.astrolabe.protocol.RuntimeRequestEnvelope
import dev.astrolabe.protocol.RuntimeResponseOutcome
import dev.astrolabe.protocol.RuntimeRevertAttributePatchParameters
import dev.astrolabe.protocol.RuntimeRevertAttributePatchPayload
import kotlinx.serialization.KSerializer

/** Creates temporary attribute patch routes independently from platform UI implementations. */
public object RuntimeAttributePatchRoutes {
    /** Creates discovery and lifecycle routes backed by [provider]. */
    public fun create(
        provider: RuntimeAttributePatchProviding,
        messageCodec: RuntimeMessageCodec = RuntimeMessageCodec()
    ): List<RuntimeRequestRoute> = listOf(
        RuntimeRequestRoute(
            method = RuntimeMethod.patchableAttributes,
            requiredCapability = RuntimeCapability.attributePatchDiscovery,
            requiresHandshake = true,
            handler = PatchableAttributesRequestHandler(provider, messageCodec)
        ),
        RuntimeRequestRoute(
            method = RuntimeMethod.applyAttributePatch,
            requiredCapability = RuntimeCapability.attributePatching,
            requiresHandshake = true,
            handler = ApplyAttributePatchRequestHandler(provider, messageCodec)
        ),
        RuntimeRequestRoute(
            method = RuntimeMethod.listAttributePatches,
            requiredCapability = RuntimeCapability.attributePatching,
            requiresHandshake = true,
            handler = ListAttributePatchesRequestHandler(provider, messageCodec)
        ),
        RuntimeRequestRoute(
            method = RuntimeMethod.revertAttributePatch,
            requiredCapability = RuntimeCapability.attributePatching,
            requiresHandshake = true,
            handler = RevertAttributePatchRequestHandler(provider, messageCodec)
        ),
        RuntimeRequestRoute(
            method = RuntimeMethod.clearAttributePatches,
            requiredCapability = RuntimeCapability.attributePatching,
            requiresHandshake = true,
            handler = ClearAttributePatchesRequestHandler(provider, messageCodec)
        )
    )
}

private class PatchableAttributesRequestHandler(
    private val provider: RuntimeAttributePatchProviding,
    private val messageCodec: RuntimeMessageCodec
) : RuntimeRequestHandler {
    override fun handle(
        request: RuntimeRequestEnvelope,
        context: RuntimeRequestContext
    ): RuntimeResponseOutcome {
        messageCodec.decodeRequestParameters(
            request,
            RuntimePatchableAttributesParameters.contract
        )
        return success(
            provider.patchableAttributes(),
            RuntimePatchableAttributesPayload.contract.serializer,
            messageCodec
        )
    }
}

private class ApplyAttributePatchRequestHandler(
    private val provider: RuntimeAttributePatchProviding,
    private val messageCodec: RuntimeMessageCodec
) : RuntimeRequestHandler {
    override fun handle(
        request: RuntimeRequestEnvelope,
        context: RuntimeRequestContext
    ): RuntimeResponseOutcome {
        val parameters = messageCodec.decodeRequestParameters(
            request,
            RuntimeApplyAttributePatchParameters.contract
        )
        return success(
            provider.applyAttributePatch(parameters),
            RuntimeAttributePatch.applyContract.serializer,
            messageCodec
        )
    }
}

private class ListAttributePatchesRequestHandler(
    private val provider: RuntimeAttributePatchProviding,
    private val messageCodec: RuntimeMessageCodec
) : RuntimeRequestHandler {
    override fun handle(
        request: RuntimeRequestEnvelope,
        context: RuntimeRequestContext
    ): RuntimeResponseOutcome {
        messageCodec.decodeRequestParameters(
            request,
            RuntimeListAttributePatchesParameters.contract
        )
        return success(
            provider.activeAttributePatches(),
            RuntimeAttributePatchListPayload.contract.serializer,
            messageCodec
        )
    }
}

private class RevertAttributePatchRequestHandler(
    private val provider: RuntimeAttributePatchProviding,
    private val messageCodec: RuntimeMessageCodec
) : RuntimeRequestHandler {
    override fun handle(
        request: RuntimeRequestEnvelope,
        context: RuntimeRequestContext
    ): RuntimeResponseOutcome {
        val parameters = messageCodec.decodeRequestParameters(
            request,
            RuntimeRevertAttributePatchParameters.contract
        )
        return success(
            provider.revertAttributePatch(parameters),
            RuntimeRevertAttributePatchPayload.contract.serializer,
            messageCodec
        )
    }
}

private class ClearAttributePatchesRequestHandler(
    private val provider: RuntimeAttributePatchProviding,
    private val messageCodec: RuntimeMessageCodec
) : RuntimeRequestHandler {
    override fun handle(
        request: RuntimeRequestEnvelope,
        context: RuntimeRequestContext
    ): RuntimeResponseOutcome {
        messageCodec.decodeRequestParameters(
            request,
            RuntimeClearAttributePatchesParameters.contract
        )
        return success(
            provider.clearAttributePatches(),
            RuntimeClearAttributePatchesPayload.contract.serializer,
            messageCodec
        )
    }
}

private fun <Payload> success(
    payload: Payload,
    serializer: KSerializer<Payload>,
    messageCodec: RuntimeMessageCodec
): RuntimeResponseOutcome.Success = RuntimeResponseOutcome.Success(
    messageCodec.decodeDocument(messageCodec.encodeValue(payload, serializer))
)
