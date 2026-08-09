//
//  AstrolabeRuntimeEndpointConfigurationTest.kt
//  astrolabe-runtime-android
//
//  Created by 轩辕十四 on 2026/8/5.
//

package dev.astrolabe.runtime

import dev.astrolabe.protocol.RuntimeCapability
import dev.astrolabe.protocol.RuntimeClientDescriptor
import dev.astrolabe.protocol.RuntimeHandshakeParameters
import dev.astrolabe.protocol.RuntimeHandshakePayload
import dev.astrolabe.protocol.RuntimeMessageCodec
import dev.astrolabe.protocol.RuntimeOpaqueIdentifier
import dev.astrolabe.protocol.RuntimeProtocolRange
import dev.astrolabe.runtime.core.RuntimeApplicationInfoProvider
import dev.astrolabe.runtime.core.RuntimeConnectionSession
import dev.astrolabe.runtime.core.RuntimeCoreRoutes
import dev.astrolabe.runtime.core.RuntimeRequestRouter
import java.util.UUID
import org.junit.Assert.assertTrue
import org.junit.Test

class AstrolabeRuntimeEndpointConfigurationTest {
    @Test
    fun handshakeAdvertisesUIGraphRelationsCapability() {
        val messageCodec = RuntimeMessageCodec()
        val router = RuntimeRequestRouter(
            RuntimeCoreRoutes.create(
                configuration = androidRuntimeEndpointConfiguration(
                    RuntimeOpaqueIdentifier("runtime:test")
                ),
                applicationInfoProvider = RuntimeApplicationInfoProvider {
                    error("Application info is not used by this test")
                },
                messageCodec = messageCodec
            )
        )
        val request = messageCodec.decodeRequest(
            messageCodec.encodeRequest(
                requestID = UUID.randomUUID().toString(),
                contract = RuntimeHandshakeParameters.contract,
                parameters = RuntimeHandshakeParameters(
                    client = RuntimeClientDescriptor("test-host", "1.0.0"),
                    supportedProtocolRange = RuntimeProtocolRange.V2
                )
            )
        )

        val response = router.route(request, RuntimeConnectionSession())
        val handshake = messageCodec.decodeSuccessPayload(
            response,
            RuntimeHandshakePayload.contract
        )

        assertTrue(RuntimeCapability.uiGraphRelations in handshake.capabilities)
    }
}
