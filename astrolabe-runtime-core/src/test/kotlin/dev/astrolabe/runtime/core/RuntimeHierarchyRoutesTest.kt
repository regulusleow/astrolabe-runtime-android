//
//  RuntimeHierarchyRoutesTest.kt
//  astrolabe-runtime-android
//
//  Created by 轩辕十四 on 2026/7/20.
//

package dev.astrolabe.runtime.core

import dev.astrolabe.protocol.RuntimeCapability
import dev.astrolabe.protocol.RuntimeClientDescriptor
import dev.astrolabe.protocol.RuntimeCoordinateRect
import dev.astrolabe.protocol.RuntimeCoordinateSpace
import dev.astrolabe.protocol.RuntimeDisplayInfo
import dev.astrolabe.protocol.RuntimeError
import dev.astrolabe.protocol.RuntimeErrorCode
import dev.astrolabe.protocol.RuntimeHandshakeParameters
import dev.astrolabe.protocol.RuntimeHierarchySnapshotParameters
import dev.astrolabe.protocol.RuntimeHierarchySnapshotPayload
import dev.astrolabe.protocol.RuntimeMeasuredSize
import dev.astrolabe.protocol.RuntimeMeasurementUnit
import dev.astrolabe.protocol.RuntimeMessageCodec
import dev.astrolabe.protocol.RuntimeNamespacedIdentifier
import dev.astrolabe.protocol.RuntimeOpaqueIdentifier
import dev.astrolabe.protocol.RuntimeProtocolRange
import dev.astrolabe.protocol.RuntimeResponseOutcome
import dev.astrolabe.protocol.RuntimeScale
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeHierarchyRoutesTest {
    private val messageCodec = RuntimeMessageCodec()

    @Test
    fun hierarchyRouteReturnsTheProviderSnapshotAfterNegotiation() {
        val expectedPayload = hierarchyPayload()
        val session = RuntimeConnectionSession()
        val router = RuntimeRequestRouter(
            RuntimeCoreRoutes.create(
                configuration = testEndpointConfiguration(
                    setOf(RuntimeCapability.hierarchySnapshot)
                ),
                applicationInfoProvider = RuntimeApplicationInfoProvider {
                    throw UnsupportedOperationException("Application info is not used by this test")
                },
                messageCodec = messageCodec
            ) + RuntimeHierarchyRoutes.create(
                hierarchyProvider = RuntimeHierarchyProvider { _ -> expectedPayload },
                messageCodec = messageCodec
            )
        )

        router.route(handshakeRequest(), session)
        val response = router.route(hierarchyRequest(), session)
        val payload = messageCodec.decodeSuccessPayload(
            response,
            RuntimeHierarchySnapshotPayload.contract
        )

        assertEquals(expectedPayload, payload)
    }

    @Test
    fun hierarchyRouteRejectsRequestsWithoutTheNegotiatedCapability() {
        val session = RuntimeConnectionSession()
        val router = RuntimeRequestRouter(
            RuntimeCoreRoutes.create(
                configuration = testEndpointConfiguration(emptySet()),
                applicationInfoProvider = RuntimeApplicationInfoProvider {
                    throw UnsupportedOperationException("Application info is not used by this test")
                },
                messageCodec = messageCodec
            ) + RuntimeHierarchyRoutes.create(
                hierarchyProvider = RuntimeHierarchyProvider { _ -> hierarchyPayload() },
                messageCodec = messageCodec
            )
        )

        router.route(handshakeRequest(), session)
        val response = router.route(hierarchyRequest(), session)

        val failure = response.outcome as RuntimeResponseOutcome.Failure
        assertEquals("capabilityUnavailable", failure.error.code.rawValue)
    }

    @Test
    fun hierarchyRoutePreservesStructuredProviderFailures() {
        val session = RuntimeConnectionSession()
        val providerErrorCode = RuntimeErrorCode("android.hierarchyLimitExceeded")
        val router = RuntimeRequestRouter(
            RuntimeCoreRoutes.create(
                configuration = testEndpointConfiguration(
                    setOf(RuntimeCapability.hierarchySnapshot)
                ),
                applicationInfoProvider = RuntimeApplicationInfoProvider {
                    throw UnsupportedOperationException("Application info is not used by this test")
                },
                messageCodec = messageCodec
            ) + RuntimeHierarchyRoutes.create(
                hierarchyProvider = RuntimeHierarchyProvider {
                    throw RuntimeProviderFailure(
                        RuntimeError(
                            code = providerErrorCode,
                            message = "Hierarchy limit reached",
                            recoverySuggestion = null
                        )
                    )
                },
                messageCodec = messageCodec
            )
        )

        router.route(handshakeRequest(), session)
        val response = router.route(hierarchyRequest(), session)

        val failure = response.outcome as RuntimeResponseOutcome.Failure
        assertEquals(providerErrorCode, failure.error.code)
    }

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

    private fun hierarchyRequest() = messageCodec.decodeRequest(
        messageCodec.encodeRequest(
            requestID = UUID.randomUUID().toString(),
            contract = RuntimeHierarchySnapshotParameters.contract,
            parameters = RuntimeHierarchySnapshotParameters
        )
    )

    private fun hierarchyPayload(): RuntimeHierarchySnapshotPayload =
        RuntimeHierarchySnapshotPayload(
            snapshotID = RuntimeOpaqueIdentifier("snapshot:test"),
            capturedAtUnixTime = 1.0,
            targetIdentifier = RuntimeOpaqueIdentifier("target:test"),
            orientation = "portrait",
            display = RuntimeDisplayInfo(
                logicalSize = RuntimeMeasuredSize(400.0, 800.0, RuntimeMeasurementUnit.logical),
                pixelSize = RuntimeMeasuredSize(1200.0, 2400.0, RuntimeMeasurementUnit.pixel),
                logicalToPixelScale = RuntimeScale(3.0, 3.0),
                maximumRefreshRate = 60.0
            ),
            viewport = RuntimeCoordinateRect(
                x = 0.0,
                y = 0.0,
                width = 400.0,
                height = 800.0,
                coordinateSpace = RuntimeCoordinateSpace.screen,
                unit = RuntimeMeasurementUnit.logical
            ),
            roots = emptyList()
        )

    private fun testEndpointConfiguration(
        capabilities: Set<RuntimeCapability>
    ): RuntimeEndpointConfiguration = RuntimeEndpointConfiguration(
        runtimeIdentifier = RuntimeNamespacedIdentifier("astrolabe.runtime.android"),
        runtimeVersion = "0.1.0",
        runtimeInstanceIdentifier = RuntimeOpaqueIdentifier("runtime:test:100"),
        platform = "android",
        capabilities = capabilities
    )
}
