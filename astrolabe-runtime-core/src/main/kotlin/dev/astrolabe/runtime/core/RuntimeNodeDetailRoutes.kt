//
//  RuntimeNodeDetailRoutes.kt
//  astrolabe-runtime-android
//
//  Created by 轩辕十四 on 2026/7/22.
//

package dev.astrolabe.runtime.core

import dev.astrolabe.protocol.RuntimeCapability
import dev.astrolabe.protocol.RuntimeMessageCodec
import dev.astrolabe.protocol.RuntimeMethod
import dev.astrolabe.protocol.RuntimeNodeDetailParameters
import dev.astrolabe.protocol.RuntimeNodeDetailPayload
import dev.astrolabe.protocol.RuntimeOpaqueIdentifier
import dev.astrolabe.protocol.RuntimeRequestEnvelope
import dev.astrolabe.protocol.RuntimeResponseOutcome

/** Reads platform-neutral node details without exposing platform UI types. */
public fun interface RuntimeNodeDetailProvider {
    /** Reads the current details for [nodeID]. */
    public fun nodeDetail(
        nodeID: RuntimeOpaqueIdentifier,
        cancellationToken: RuntimeCancellationToken
    ): RuntimeNodeDetailPayload
}

/** Creates node-detail routes independently from transport and UI implementations. */
public object RuntimeNodeDetailRoutes {
    /** Creates the node-detail route backed by [nodeDetailProvider]. */
    public fun create(
        nodeDetailProvider: RuntimeNodeDetailProvider,
        messageCodec: RuntimeMessageCodec = RuntimeMessageCodec()
    ): List<RuntimeRequestRoute> = listOf(
        RuntimeRequestRoute(
            method = RuntimeMethod.nodeDetail,
            requiredCapability = RuntimeCapability.nodeDetail,
            requiresHandshake = true,
            handler = NodeDetailRequestHandler(nodeDetailProvider, messageCodec)
        )
    )
}

private class NodeDetailRequestHandler(
    private val nodeDetailProvider: RuntimeNodeDetailProvider,
    private val messageCodec: RuntimeMessageCodec
) : RuntimeRequestHandler {
    override fun handle(
        request: RuntimeRequestEnvelope,
        context: RuntimeRequestContext
    ): RuntimeResponseOutcome {
        val parameters = messageCodec.decodeRequestParameters(
            request,
            RuntimeNodeDetailParameters.contract
        )
        val payload = nodeDetailProvider.nodeDetail(
            parameters.nodeID,
            context.cancellationToken
        )
        return RuntimeResponseOutcome.Success(
            messageCodec.decodeDocument(
                messageCodec.encodeValue(payload, RuntimeNodeDetailPayload.contract.serializer)
            )
        )
    }
}
