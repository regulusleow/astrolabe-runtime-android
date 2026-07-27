//
//  RuntimeAttributePatchRoutesTest.kt
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
import dev.astrolabe.protocol.RuntimeCapability
import dev.astrolabe.protocol.RuntimeClearAttributePatchesParameters
import dev.astrolabe.protocol.RuntimeClearAttributePatchesPayload
import dev.astrolabe.protocol.RuntimeClientDescriptor
import dev.astrolabe.protocol.RuntimeError
import dev.astrolabe.protocol.RuntimeErrorCode
import dev.astrolabe.protocol.RuntimeHandshakeParameters
import dev.astrolabe.protocol.RuntimeListAttributePatchesParameters
import dev.astrolabe.protocol.RuntimeMessageCodec
import dev.astrolabe.protocol.RuntimeNamespacedIdentifier
import dev.astrolabe.protocol.RuntimeOpaqueIdentifier
import dev.astrolabe.protocol.RuntimePatchableAttributesParameters
import dev.astrolabe.protocol.RuntimePatchableAttributesPayload
import dev.astrolabe.protocol.RuntimeProtocolRange
import dev.astrolabe.protocol.RuntimeRequestEnvelope
import dev.astrolabe.protocol.RuntimeResponseOutcome
import dev.astrolabe.protocol.RuntimeRevertAttributePatchParameters
import dev.astrolabe.protocol.RuntimeRevertAttributePatchPayload
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeAttributePatchRoutesTest {
    private val messageCodec = RuntimeMessageCodec()
    private val nodeID = RuntimeOpaqueIdentifier("node:1")
    private val patchID = RuntimeOpaqueIdentifier("patch:1")
    private val attributeIdentifier = RuntimeAttributeIdentifier("android.text.text")
    private val requestedValue = RuntimeAttributeValue.StringValue("Updated")
    private val patch = RuntimeAttributePatch(
        patchID = patchID,
        nodeID = nodeID,
        attributeIdentifier = attributeIdentifier,
        originalValue = RuntimeAttributeValue.StringValue("Original"),
        requestedValue = requestedValue,
        actualValue = requestedValue,
        appliedAtUnixTime = 1.0
    )

    @Test
    fun patchRoutesExposeTheCompleteLifecycleAfterNegotiation() {
        val provider = RecordingPatchProvider(patch)
        val session = RuntimeConnectionSession()
        val router = router(
            capabilities = setOf(
                RuntimeCapability.attributePatchDiscovery,
                RuntimeCapability.attributePatching
            ),
            provider = provider
        )
        router.route(handshakeRequest(), session)

        val catalog = messageCodec.decodeSuccessPayload(
            router.route(request(RuntimePatchableAttributesParameters.contract, RuntimePatchableAttributesParameters), session),
            RuntimePatchableAttributesPayload.contract
        )
        val applied = messageCodec.decodeSuccessPayload(
            router.route(
                request(
                    RuntimeApplyAttributePatchParameters.contract,
                    RuntimeApplyAttributePatchParameters(nodeID, attributeIdentifier, requestedValue)
                ),
                session
            ),
            RuntimeAttributePatch.applyContract
        )
        val listed = messageCodec.decodeSuccessPayload(
            router.route(request(RuntimeListAttributePatchesParameters.contract, RuntimeListAttributePatchesParameters), session),
            RuntimeAttributePatchListPayload.contract
        )
        val reverted = messageCodec.decodeSuccessPayload(
            router.route(
                request(
                    RuntimeRevertAttributePatchParameters.contract,
                    RuntimeRevertAttributePatchParameters(patchID)
                ),
                session
            ),
            RuntimeRevertAttributePatchPayload.contract
        )
        val cleared = messageCodec.decodeSuccessPayload(
            router.route(request(RuntimeClearAttributePatchesParameters.contract, RuntimeClearAttributePatchesParameters), session),
            RuntimeClearAttributePatchesPayload.contract
        )

        assertEquals(provider.catalog, catalog)
        assertEquals(patch, applied)
        assertEquals(RuntimeAttributePatchListPayload(listOf(patch)), listed)
        assertEquals(RuntimeRevertAttributePatchPayload(patchID, patch.originalValue, 0), reverted)
        assertEquals(RuntimeClearAttributePatchesPayload(emptyList(), 0), cleared)
        assertEquals(1, provider.applyCount)
        assertEquals(1, provider.revertCount)
        assertEquals(1, provider.clearCount)
    }

    @Test
    fun mutationRoutesRequireThePatchingCapability() {
        val session = RuntimeConnectionSession()
        val router = router(
            capabilities = setOf(RuntimeCapability.attributePatchDiscovery),
            provider = RecordingPatchProvider(patch)
        )
        router.route(handshakeRequest(), session)

        val response = router.route(
            request(
                RuntimeApplyAttributePatchParameters.contract,
                RuntimeApplyAttributePatchParameters(nodeID, attributeIdentifier, requestedValue)
            ),
            session
        )

        val failure = response.outcome as RuntimeResponseOutcome.Failure
        assertEquals(RuntimeErrorCode.capabilityUnavailable, failure.error.code)
    }

    @Test
    fun patchRoutesPreserveStructuredProviderFailures() {
        val session = RuntimeConnectionSession()
        val router = router(
            capabilities = setOf(
                RuntimeCapability.attributePatchDiscovery,
                RuntimeCapability.attributePatching
            ),
            provider = object : RecordingPatchProvider(patch) {
                override fun clearAttributePatches(): RuntimeClearAttributePatchesPayload {
                    throw RuntimeProviderFailure(
                        RuntimeError(
                            code = RuntimeErrorCode.patchRestorationFailed,
                            message = "The fake patch could not be restored",
                            recoverySuggestion = null
                        )
                    )
                }
            }
        )
        router.route(handshakeRequest(), session)

        val response = router.route(
            request(RuntimeClearAttributePatchesParameters.contract, RuntimeClearAttributePatchesParameters),
            session
        )

        val failure = response.outcome as RuntimeResponseOutcome.Failure
        assertEquals(RuntimeErrorCode.patchRestorationFailed, failure.error.code)
    }

    private fun router(
        capabilities: Set<RuntimeCapability>,
        provider: RuntimeAttributePatchProviding
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
        ) + RuntimeAttributePatchRoutes.create(provider, messageCodec)
    )

    private fun handshakeRequest(): RuntimeRequestEnvelope = request(
        RuntimeHandshakeParameters.contract,
        RuntimeHandshakeParameters(
            client = RuntimeClientDescriptor("test-host", "1.0.0"),
            supportedProtocolRange = RuntimeProtocolRange.V2
        )
    )

    private fun <Parameters> request(
        contract: dev.astrolabe.protocol.RuntimeMethodContract<Parameters>,
        parameters: Parameters
    ): RuntimeRequestEnvelope = messageCodec.decodeRequest(
        messageCodec.encodeRequest(
            requestID = UUID.randomUUID().toString(),
            contract = contract,
            parameters = parameters
        )
    )
}

private open class RecordingPatchProvider(
    private val patch: RuntimeAttributePatch
) : RuntimeAttributePatchProviding {
    /** Empty catalog returned by discovery requests. */
    val catalog = RuntimePatchableAttributesPayload(emptyList())

    /** Number of apply requests received by this fake. */
    var applyCount: Int = 0

    /** Number of revert requests received by this fake. */
    var revertCount: Int = 0

    /** Number of clear requests received by this fake. */
    var clearCount: Int = 0

    override fun patchableAttributes(): RuntimePatchableAttributesPayload = catalog

    override fun applyAttributePatch(
        parameters: RuntimeApplyAttributePatchParameters
    ): RuntimeAttributePatch {
        applyCount += 1
        return patch
    }

    override fun activeAttributePatches(): RuntimeAttributePatchListPayload =
        RuntimeAttributePatchListPayload(listOf(patch))

    override fun revertAttributePatch(
        parameters: RuntimeRevertAttributePatchParameters
    ): RuntimeRevertAttributePatchPayload {
        revertCount += 1
        return RuntimeRevertAttributePatchPayload(parameters.patchID, patch.originalValue, 0)
    }

    override fun clearAttributePatches(): RuntimeClearAttributePatchesPayload {
        clearCount += 1
        return RuntimeClearAttributePatchesPayload(emptyList(), 0)
    }
}
