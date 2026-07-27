//
//  RuntimeRequestRouterTest.kt
//  astrolabe-runtime-android
//
//  Created by 轩辕十四 on 2026/7/20.
//

package dev.astrolabe.runtime.core

import dev.astrolabe.protocol.RuntimeApplication
import dev.astrolabe.protocol.RuntimeApplicationInfoParameters
import dev.astrolabe.protocol.RuntimeApplicationInfoPayload
import dev.astrolabe.protocol.RuntimeCapability
import dev.astrolabe.protocol.RuntimeClientDescriptor
import dev.astrolabe.protocol.RuntimeDisplayInfo
import dev.astrolabe.protocol.RuntimeEnvironment
import dev.astrolabe.protocol.RuntimeHandshakeParameters
import dev.astrolabe.protocol.RuntimeHandshakePayload
import dev.astrolabe.protocol.RuntimeLayoutDirection
import dev.astrolabe.protocol.RuntimeMeasuredSize
import dev.astrolabe.protocol.RuntimeMeasurementUnit
import dev.astrolabe.protocol.RuntimeMessageCodec
import dev.astrolabe.protocol.RuntimeMethod
import dev.astrolabe.protocol.RuntimeNamespacedIdentifier
import dev.astrolabe.protocol.RuntimeOpaqueIdentifier
import dev.astrolabe.protocol.RuntimeProtocolRange
import dev.astrolabe.protocol.RuntimeProtocolVersion
import dev.astrolabe.protocol.RuntimeRequestEnvelope
import dev.astrolabe.protocol.RuntimeResponseOutcome
import dev.astrolabe.protocol.RuntimeScale
import dev.astrolabe.protocol.RuntimeTarget
import java.util.UUID
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeRequestRouterTest {
    private val messageCodec = RuntimeMessageCodec()
    private val applicationInfo = makeApplicationInfo()

    @Test
    fun applicationInfoRequiresHandshake() {
        val fixture = makeFixture(setOf(RuntimeCapability.applicationInfo))

        val response = fixture.router.route(applicationInfoRequest(), fixture.session)

        val failure = response.outcome as RuntimeResponseOutcome.Failure
        assertEquals("handshakeRequired", failure.error.code.rawValue)
        assertFalse(fixture.session.isHandshakeComplete)
    }

    @Test
    fun handshakeNegotiatesCapabilitiesAndEnablesApplicationInfo() {
        val fixture = makeFixture(setOf(RuntimeCapability.applicationInfo))

        val handshakeResponse = fixture.router.route(handshakeRequest(), fixture.session)
        val handshakePayload = messageCodec.decodeSuccessPayload(
            handshakeResponse,
            RuntimeHandshakePayload.contract
        )
        val applicationResponse = fixture.router.route(applicationInfoRequest(), fixture.session)
        val applicationPayload = messageCodec.decodeSuccessPayload(
            applicationResponse,
            RuntimeApplicationInfoPayload.contract
        )

        assertTrue(fixture.session.isHandshakeComplete)
        assertEquals(RuntimeProtocolVersion.V2, fixture.session.negotiatedProtocolVersion)
        assertEquals(listOf(RuntimeCapability.applicationInfo), handshakePayload.capabilities)
        assertEquals(applicationInfo, applicationPayload)
    }

    @Test
    fun handshakeRejectsProtocolRangesWithoutVersionTwo() {
        val fixture = makeFixture(setOf(RuntimeCapability.applicationInfo))
        val unsupportedRange = RuntimeProtocolRange(
            minimum = RuntimeProtocolVersion(major = 3, minor = 0),
            maximum = RuntimeProtocolVersion(major = 3, minor = 1)
        )

        val response = fixture.router.route(handshakeRequest(unsupportedRange), fixture.session)

        val failure = response.outcome as RuntimeResponseOutcome.Failure
        assertEquals("unsupportedProtocolVersion", failure.error.code.rawValue)
        assertFalse(fixture.session.isHandshakeComplete)
    }

    @Test
    fun routerRejectsMethodsWhoseCapabilityWasNotNegotiated() {
        val fixture = makeFixture(emptySet())
        fixture.router.route(handshakeRequest(), fixture.session)

        val response = fixture.router.route(applicationInfoRequest(), fixture.session)

        val failure = response.outcome as RuntimeResponseOutcome.Failure
        assertEquals("capabilityUnavailable", failure.error.code.rawValue)
    }

    @Test
    fun routerRejectsUnknownMethodsAfterHandshake() {
        val fixture = makeFixture(setOf(RuntimeCapability.applicationInfo))
        fixture.router.route(handshakeRequest(), fixture.session)
        val request = RuntimeRequestEnvelope(
            requestID = UUID.randomUUID().toString(),
            protocolVersion = RuntimeProtocolVersion.V2,
            method = RuntimeMethod("vendor.example"),
            parameters = buildJsonObject {}
        )

        val response = fixture.router.route(request, fixture.session)

        val failure = response.outcome as RuntimeResponseOutcome.Failure
        assertEquals("unsupportedMethod", failure.error.code.rawValue)
    }

    @Test
    fun endpointConfigurationCopiesDeclaredCapabilities() {
        val declaredCapabilities = mutableSetOf(RuntimeCapability.applicationInfo)
        val configuration = RuntimeEndpointConfiguration(
            runtimeIdentifier = RuntimeNamespacedIdentifier("astrolabe.runtime.android"),
            runtimeVersion = "0.1.0",
            runtimeInstanceIdentifier = RuntimeOpaqueIdentifier("runtime:test:100"),
            platform = "android",
            capabilities = declaredCapabilities
        )

        declaredCapabilities.clear()

        assertEquals(setOf(RuntimeCapability.applicationInfo), configuration.capabilities)
    }

    private fun makeFixture(capabilities: Set<RuntimeCapability>): RouterFixture {
        val configuration = RuntimeEndpointConfiguration(
            runtimeIdentifier = RuntimeNamespacedIdentifier("astrolabe.runtime.android"),
            runtimeVersion = "0.1.0",
            runtimeInstanceIdentifier = RuntimeOpaqueIdentifier("runtime:test:100"),
            platform = "android",
            capabilities = capabilities
        )
        return RouterFixture(
            router = RuntimeRequestRouter(
                routes = RuntimeCoreRoutes.create(
                    configuration = configuration,
                    applicationInfoProvider = RuntimeApplicationInfoProvider { applicationInfo },
                    messageCodec = messageCodec
                )
            ),
            session = RuntimeConnectionSession()
        )
    }

    private fun handshakeRequest(
        range: RuntimeProtocolRange = RuntimeProtocolRange.V2
    ): RuntimeRequestEnvelope = messageCodec.decodeRequest(
        messageCodec.encodeRequest(
            requestID = UUID.randomUUID().toString(),
            contract = RuntimeHandshakeParameters.contract,
            parameters = RuntimeHandshakeParameters(
                client = RuntimeClientDescriptor(name = "test-host", version = "1.0.0"),
                supportedProtocolRange = range
            )
        )
    )

    private fun applicationInfoRequest(): RuntimeRequestEnvelope = messageCodec.decodeRequest(
        messageCodec.encodeRequest(
            requestID = UUID.randomUUID().toString(),
            contract = RuntimeApplicationInfoParameters.contract,
            parameters = RuntimeApplicationInfoParameters
        )
    )
}

private data class RouterFixture(
    /** Router under test. */
    val router: RuntimeRequestRouter,
    /** Connection-scoped session under test. */
    val session: RuntimeConnectionSession
)

private fun makeApplicationInfo(): RuntimeApplicationInfoPayload = RuntimeApplicationInfoPayload(
    application = RuntimeApplication(
        identifier = "dev.astrolabe.test",
        displayName = "Astrolabe Test",
        version = "1.0",
        buildVersion = "1"
    ),
    target = RuntimeTarget(
        identifier = RuntimeOpaqueIdentifier("target:test:100"),
        processIdentifier = "100",
        kind = "application",
        primary = true
    ),
    environment = RuntimeEnvironment(
        platform = "android",
        operatingSystemVersion = "37",
        deviceCategory = "phone",
        deviceName = "Test Device",
        deviceModel = "Test Model",
        virtualDevice = true,
        locale = "en-US",
        layoutDirection = RuntimeLayoutDirection.leftToRight,
        display = RuntimeDisplayInfo(
            logicalSize = RuntimeMeasuredSize(400.0, 800.0, RuntimeMeasurementUnit.logical),
            pixelSize = RuntimeMeasuredSize(1200.0, 2400.0, RuntimeMeasurementUnit.pixel),
            logicalToPixelScale = RuntimeScale(3.0, 3.0),
            maximumRefreshRate = 60.0
        )
    )
)
