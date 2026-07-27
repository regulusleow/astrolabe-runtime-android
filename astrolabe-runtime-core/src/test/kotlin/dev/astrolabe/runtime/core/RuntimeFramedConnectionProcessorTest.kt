//
//  RuntimeFramedConnectionProcessorTest.kt
//  astrolabe-runtime-android
//
//  Created by 轩辕十四 on 2026/7/20.
//

package dev.astrolabe.runtime.core

import dev.astrolabe.protocol.RuntimeCapability
import dev.astrolabe.protocol.RuntimeClientDescriptor
import dev.astrolabe.protocol.RuntimeErrorCode
import dev.astrolabe.protocol.RuntimeFrameCodec
import dev.astrolabe.protocol.RuntimeHandshakeParameters
import dev.astrolabe.protocol.RuntimeHandshakePayload
import dev.astrolabe.protocol.RuntimeMessageCodec
import dev.astrolabe.protocol.RuntimeNamespacedIdentifier
import dev.astrolabe.protocol.RuntimeOpaqueIdentifier
import dev.astrolabe.protocol.RuntimeProtocolRange
import dev.astrolabe.protocol.RuntimeResponseOutcome
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeFramedConnectionProcessorTest {
    private val messageCodec = RuntimeMessageCodec()
    private val frameCodec = RuntimeFrameCodec()

    @Test
    fun processorDecodesFragmentedFramesAndWritesResponses() {
        val requestPayload = messageCodec.encodeRequest(
            requestID = UUID.randomUUID().toString(),
            contract = RuntimeHandshakeParameters.contract,
            parameters = RuntimeHandshakeParameters(
                client = RuntimeClientDescriptor("test-host", "1.0.0"),
                supportedProtocolRange = RuntimeProtocolRange.V2
            )
        )
        val connection = MemoryRuntimeConnection(frameCodec.encode(requestPayload))
        val processor = RuntimeFramedConnectionProcessor(
            frameCodec = frameCodec,
            messageCodec = messageCodec,
            router = makeRouter(),
            readBufferSize = 3
        )

        processor.process(connection)

        val responsePayloads = frameCodec.makeStreamDecoder().append(connection.outputBytes())
        assertEquals(1, responsePayloads.size)
        val response = messageCodec.decodeResponse(responsePayloads.single())
        val payload = messageCodec.decodeSuccessPayload(response, RuntimeHandshakePayload.contract)
        assertEquals("android", payload.platform)
        assertTrue(connection.closed)
    }

    @Test
    fun processorReportsIncompleteFramesAndClosesTheConnection() {
        val completeFrame = frameCodec.encode(byteArrayOf(1, 2, 3))
        val connection = MemoryRuntimeConnection(completeFrame.copyOf(completeFrame.size - 1))
        val processor = RuntimeFramedConnectionProcessor(
            frameCodec = frameCodec,
            messageCodec = messageCodec,
            router = makeRouter()
        )

        assertThrows(EOFException::class.java) {
            processor.process(connection)
        }

        assertTrue(connection.closed)
        assertEquals(0, connection.outputBytes().size)
    }

    @Test
    fun processorReturnsACompactFailureWhenTheResponseExceedsTheFrameLimit() {
        val constrainedFrameCodec = RuntimeFrameCodec(maximumPayloadSize = 512)
        val requestPayload = messageCodec.encodeRequest(
            requestID = UUID.randomUUID().toString(),
            contract = RuntimeHandshakeParameters.contract,
            parameters = RuntimeHandshakeParameters(
                client = RuntimeClientDescriptor("test-host", "1.0.0"),
                supportedProtocolRange = RuntimeProtocolRange.V2
            )
        )
        val connection = MemoryRuntimeConnection(constrainedFrameCodec.encode(requestPayload))
        val oversizedCapabilities = (0 until 40)
            .map { index -> RuntimeCapability("vendor.capability$index") }
            .toSet()
        val processor = RuntimeFramedConnectionProcessor(
            frameCodec = constrainedFrameCodec,
            messageCodec = messageCodec,
            router = makeRouter(oversizedCapabilities)
        )

        processor.process(connection)

        val responsePayload = constrainedFrameCodec
            .makeStreamDecoder()
            .append(connection.outputBytes())
            .single()
        val response = messageCodec.decodeResponse(responsePayload)
        val failure = response.outcome as RuntimeResponseOutcome.Failure
        assertEquals(RuntimeErrorCode.frameTooLarge, failure.error.code)
    }

    private fun makeRouter(
        capabilities: Set<RuntimeCapability> = setOf(RuntimeCapability.applicationInfo)
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
        )
    )
}

private class MemoryRuntimeConnection(input: ByteArray) : RuntimeConnection {
    private val output = ByteArrayOutputStream()

    override val inputStream: InputStream = ByteArrayInputStream(input)
    override val outputStream: OutputStream = output
    var closed: Boolean = false
        private set

    fun outputBytes(): ByteArray = output.toByteArray()

    override fun close() {
        closed = true
    }
}
