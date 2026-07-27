//
//  RuntimeHierarchyRoutes.kt
//  astrolabe-runtime-android
//
//  Created by 轩辕十四 on 2026/7/20.
//

package dev.astrolabe.runtime.core

import dev.astrolabe.protocol.RuntimeCapability
import dev.astrolabe.protocol.RuntimeHierarchySnapshotParameters
import dev.astrolabe.protocol.RuntimeHierarchySnapshotPayload
import dev.astrolabe.protocol.RuntimeMessageCodec
import dev.astrolabe.protocol.RuntimeMethod
import dev.astrolabe.protocol.RuntimeRequestEnvelope
import dev.astrolabe.protocol.RuntimeResponseOutcome

/** Captures the current platform hierarchy without exposing platform UI types. */
public fun interface RuntimeHierarchyProvider {
    /** Captures one immutable hierarchy payload. */
    public fun captureHierarchy(
        cancellationToken: RuntimeCancellationToken
    ): RuntimeHierarchySnapshotPayload
}

/** Creates hierarchy routes independently from transport and UI implementation details. */
public object RuntimeHierarchyRoutes {
    /** Creates the hierarchy-snapshot route backed by [hierarchyProvider]. */
    public fun create(
        hierarchyProvider: RuntimeHierarchyProvider,
        messageCodec: RuntimeMessageCodec = RuntimeMessageCodec()
    ): List<RuntimeRequestRoute> = listOf(
        RuntimeRequestRoute(
            method = RuntimeMethod.hierarchySnapshot,
            requiredCapability = RuntimeCapability.hierarchySnapshot,
            requiresHandshake = true,
            handler = HierarchySnapshotRequestHandler(hierarchyProvider, messageCodec)
        )
    )
}

private class HierarchySnapshotRequestHandler(
    private val hierarchyProvider: RuntimeHierarchyProvider,
    private val messageCodec: RuntimeMessageCodec
) : RuntimeRequestHandler {
    override fun handle(
        request: RuntimeRequestEnvelope,
        context: RuntimeRequestContext
    ): RuntimeResponseOutcome {
        messageCodec.decodeRequestParameters(request, RuntimeHierarchySnapshotParameters.contract)
        val payload = hierarchyProvider.captureHierarchy(context.cancellationToken)
        return RuntimeResponseOutcome.Success(
            messageCodec.decodeDocument(
                messageCodec.encodeValue(payload, RuntimeHierarchySnapshotPayload.contract.serializer)
            )
        )
    }
}
