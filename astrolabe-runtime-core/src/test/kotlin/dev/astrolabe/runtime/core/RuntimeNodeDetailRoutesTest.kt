//
//  RuntimeNodeDetailRoutesTest.kt
//  astrolabe-runtime-android
//
//  Created by 轩辕十四 on 2026/7/22.
//

package dev.astrolabe.runtime.core

import dev.astrolabe.protocol.RuntimeAttributeCategory
import dev.astrolabe.protocol.RuntimeAttributeSection
import dev.astrolabe.protocol.RuntimeCapability
import dev.astrolabe.protocol.RuntimeClientDescriptor
import dev.astrolabe.protocol.RuntimeError
import dev.astrolabe.protocol.RuntimeErrorCode
import dev.astrolabe.protocol.RuntimeHandshakeParameters
import dev.astrolabe.protocol.RuntimeMessageCodec
import dev.astrolabe.protocol.RuntimeNamespacedIdentifier
import dev.astrolabe.protocol.RuntimeNodeDetailParameters
import dev.astrolabe.protocol.RuntimeNodeDetailPayload
import dev.astrolabe.protocol.RuntimeOpaqueIdentifier
import dev.astrolabe.protocol.RuntimeProtocolRange
import dev.astrolabe.protocol.RuntimeResponseOutcome
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeNodeDetailRoutesTest {
    private val messageCodec = RuntimeMessageCodec()
    private val nodeID = RuntimeOpaqueIdentifier("node:test")

    @Test
    fun nodeDetailRouteReturnsTheRequestedNodeAfterNegotiation() {
        var receivedNodeID: RuntimeOpaqueIdentifier? = null
        var receivedCancellationToken: RuntimeCancellationToken? = null
        val expectedPayload = RuntimeNodeDetailPayload(
            nodeID = nodeID,
            sections = listOf(
                RuntimeAttributeSection(
                    category = RuntimeAttributeCategory("android.common"),
                    attributes = emptyList()
                )
            )
        )
        val session = RuntimeConnectionSession()
        val router = router(
            capabilities = setOf(RuntimeCapability.nodeDetail),
            provider = RuntimeNodeDetailProvider { requestedNodeID, cancellationToken ->
                receivedNodeID = requestedNodeID
                receivedCancellationToken = cancellationToken
                expectedPayload
            }
        )

        router.route(handshakeRequest(), session)
        val response = router.route(nodeDetailRequest(), session)
        val payload = messageCodec.decodeSuccessPayload(
            response,
            RuntimeNodeDetailPayload.contract
        )

        assertEquals(nodeID, receivedNodeID)
        assertTrue(receivedCancellationToken != null)
        assertEquals(expectedPayload, payload)
    }

    @Test
    fun nodeDetailRouteRejectsRequestsWithoutTheNegotiatedCapability() {
        val session = RuntimeConnectionSession()
        val router = router(
            capabilities = emptySet(),
            provider = RuntimeNodeDetailProvider { _, _ ->
                throw AssertionError("Provider must not run without capability negotiation")
            }
        )

        router.route(handshakeRequest(), session)
        val response = router.route(nodeDetailRequest(), session)

        val failure = response.outcome as RuntimeResponseOutcome.Failure
        assertEquals(RuntimeErrorCode.capabilityUnavailable, failure.error.code)
    }

    @Test
    fun nodeDetailRoutePreservesStructuredProviderFailures() {
        val session = RuntimeConnectionSession()
        val router = router(
            capabilities = setOf(RuntimeCapability.nodeDetail),
            provider = RuntimeNodeDetailProvider { _, _ ->
                throw RuntimeProviderFailure(
                    RuntimeError(
                        code = RuntimeErrorCode.nodeNotFound,
                        message = "The requested node is unavailable",
                        recoverySuggestion = "Capture a new hierarchy and retry"
                    )
                )
            }
        )

        router.route(handshakeRequest(), session)
        val response = router.route(nodeDetailRequest(), session)

        val failure = response.outcome as RuntimeResponseOutcome.Failure
        assertEquals(RuntimeErrorCode.nodeNotFound, failure.error.code)
    }

    private fun router(
        capabilities: Set<RuntimeCapability>,
        provider: RuntimeNodeDetailProvider
    ): RuntimeRequestRouter = RuntimeRequestRouter(
        RuntimeCoreRoutes.create(
            configuration = RuntimeEndpointConfiguration(
                runtimeIdentifier = RuntimeNamespacedIdentifier("astrolabe.runtime.android"),
                runtimeVersion = "0.1.0",
                runtimeInstanceIdentifier = RuntimeOpaqueIdentifier("runtime:test:100"),
                platform = "android",
                capabilities = capabilities
            ),
            applicationInfoProvider = RuntimeApplicationInfoProvider {
                throw UnsupportedOperationException("Application info is not used by this test")
            },
            messageCodec = messageCodec
        ) + RuntimeNodeDetailRoutes.create(
            nodeDetailProvider = provider,
            messageCodec = messageCodec
        )
    )

    private fun handshakeRequest() = messageCodec.decodeRequest(
        messageCodec.encodeRequest(
            requestID = UUID.randomUUID().toString(),
            contract = RuntimeHandshakeParameters.contract,
            parameters = RuntimeHandshakeParameters(
                client = RuntimeClientDescriptor("test-host", "1.0.0"),
                supportedProtocolRange = RuntimeProtocolRange.V2
            )
        )
    )

    private fun nodeDetailRequest() = messageCodec.decodeRequest(
        messageCodec.encodeRequest(
            requestID = UUID.randomUUID().toString(),
            contract = RuntimeNodeDetailParameters.contract,
            parameters = RuntimeNodeDetailParameters(nodeID)
        )
    )
}
