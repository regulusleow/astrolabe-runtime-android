//
//  RuntimeCoreRoutes.kt
//  astrolabe-runtime-android
//
//  Created by 轩辕十四 on 2026/7/20.
//

package dev.astrolabe.runtime.core

import dev.astrolabe.protocol.RuntimeApplicationInfoParameters
import dev.astrolabe.protocol.RuntimeApplicationInfoPayload
import dev.astrolabe.protocol.RuntimeCapability
import dev.astrolabe.protocol.RuntimeCancelRequestParameters
import dev.astrolabe.protocol.RuntimeCancelRequestPayload
import dev.astrolabe.protocol.RuntimeDescriptor
import dev.astrolabe.protocol.RuntimeError
import dev.astrolabe.protocol.RuntimeErrorCode
import dev.astrolabe.protocol.RuntimeHandshakeParameters
import dev.astrolabe.protocol.RuntimeHandshakePayload
import dev.astrolabe.protocol.RuntimeMessageCodec
import dev.astrolabe.protocol.RuntimeMethod
import dev.astrolabe.protocol.RuntimeProtocolRange
import dev.astrolabe.protocol.RuntimeRequestEnvelope
import dev.astrolabe.protocol.RuntimeResponseOutcome

/** Supplies current application and device facts to the Core request handlers. */
public fun interface RuntimeApplicationInfoProvider {
    /** Captures application information for the current process. */
    public fun applicationInfo(): RuntimeApplicationInfoPayload
}

/** Creates the transport-independent routes implemented by Runtime Core. */
public object RuntimeCoreRoutes {
    /** Creates handshake and application-info routes for [configuration]. */
    public fun create(
        configuration: RuntimeEndpointConfiguration,
        applicationInfoProvider: RuntimeApplicationInfoProvider,
        messageCodec: RuntimeMessageCodec = RuntimeMessageCodec()
    ): List<RuntimeRequestRoute> = listOf(
        RuntimeRequestRoute(
            method = RuntimeMethod.handshake,
            requiredCapability = null,
            requiresHandshake = false,
            handler = HandshakeRequestHandler(configuration, messageCodec)
        ),
        RuntimeRequestRoute(
            method = RuntimeMethod.applicationInfo,
            requiredCapability = RuntimeCapability.applicationInfo,
            requiresHandshake = true,
            handler = ApplicationInfoRequestHandler(applicationInfoProvider, messageCodec)
        ),
        RuntimeRequestRoute(
            method = RuntimeMethod.cancelRequest,
            requiredCapability = RuntimeCapability.requestCancellation,
            requiresHandshake = true,
            handler = CancellationRequestHandler(messageCodec)
        )
    )
}

private class HandshakeRequestHandler(
    private val configuration: RuntimeEndpointConfiguration,
    private val messageCodec: RuntimeMessageCodec
) : RuntimeRequestHandler {
    override fun handle(
        request: RuntimeRequestEnvelope,
        context: RuntimeRequestContext
    ): RuntimeResponseOutcome {
        val parameters = messageCodec.decodeRequestParameters(
            request,
            RuntimeHandshakeParameters.contract
        )
        val negotiatedVersion = RuntimeProtocolRange.V2.highestCommonVersion(
            parameters.supportedProtocolRange
        ) ?: throw RuntimeProviderFailure(
            RuntimeError(
                code = RuntimeErrorCode.unsupportedProtocolVersion,
                message = "Host and Runtime do not share a protocol version",
                recoverySuggestion = null
            )
        )
        if (!context.session.completeHandshake(negotiatedVersion, configuration.capabilities)) {
            throw RuntimeProviderFailure(
                RuntimeError(
                    code = RuntimeErrorCode.invalidParameters,
                    message = "Handshake already completed for this connection",
                    recoverySuggestion = null
                )
            )
        }

        val payload = RuntimeHandshakePayload(
            runtime = RuntimeDescriptor(
                identifier = configuration.runtimeIdentifier,
                version = configuration.runtimeVersion,
                instanceID = configuration.runtimeInstanceIdentifier
            ),
            platform = configuration.platform,
            negotiatedProtocolVersion = negotiatedVersion,
            capabilities = configuration.capabilities.sortedBy(RuntimeCapability::rawValue)
        )
        return typedSuccess(payload, RuntimeHandshakePayload.contract.serializer)
    }

    private fun <T> typedSuccess(
        payload: T,
        serializer: kotlinx.serialization.KSerializer<T>
    ): RuntimeResponseOutcome.Success = RuntimeResponseOutcome.Success(
        messageCodec.decodeDocument(messageCodec.encodeValue(payload, serializer))
    )
}

private class ApplicationInfoRequestHandler(
    private val applicationInfoProvider: RuntimeApplicationInfoProvider,
    private val messageCodec: RuntimeMessageCodec
) : RuntimeRequestHandler {
    override fun handle(
        request: RuntimeRequestEnvelope,
        context: RuntimeRequestContext
    ): RuntimeResponseOutcome {
        messageCodec.decodeRequestParameters(request, RuntimeApplicationInfoParameters.contract)
        val payload = applicationInfoProvider.applicationInfo()
        return RuntimeResponseOutcome.Success(
            messageCodec.decodeDocument(
                messageCodec.encodeValue(payload, RuntimeApplicationInfoPayload.contract.serializer)
            )
        )
    }
}

private class CancellationRequestHandler(
    private val messageCodec: RuntimeMessageCodec
) : RuntimeRequestHandler {
    override fun handle(
        request: RuntimeRequestEnvelope,
        context: RuntimeRequestContext
    ): RuntimeResponseOutcome {
        val parameters = messageCodec.decodeRequestParameters(
            request,
            RuntimeCancelRequestParameters.contract
        )
        val payload = RuntimeCancelRequestPayload(
            targetRequestID = parameters.targetRequestID,
            cancellationAccepted = context.cancellationController.cancel(parameters.targetRequestID)
        )
        return RuntimeResponseOutcome.Success(
            messageCodec.decodeDocument(
                messageCodec.encodeValue(payload, RuntimeCancelRequestPayload.contract.serializer)
            )
        )
    }
}
