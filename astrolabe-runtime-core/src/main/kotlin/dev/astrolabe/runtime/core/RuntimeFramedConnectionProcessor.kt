//
//  RuntimeFramedConnectionProcessor.kt
//  astrolabe-runtime-android
//
//  Created by 轩辕十四 on 2026/7/20.
//

package dev.astrolabe.runtime.core

import dev.astrolabe.protocol.RuntimeError
import dev.astrolabe.protocol.RuntimeErrorCode
import dev.astrolabe.protocol.RuntimeFrameCodec
import dev.astrolabe.protocol.RuntimeFrameException
import dev.astrolabe.protocol.RuntimeMessageCodec
import dev.astrolabe.protocol.RuntimeResponseEnvelope
import dev.astrolabe.protocol.RuntimeResponseOutcome
import java.io.EOFException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicReference

/** Bridges framed transport bytes to the transport-independent request router. */
public class RuntimeFramedConnectionProcessor(
    private val frameCodec: RuntimeFrameCodec,
    private val messageCodec: RuntimeMessageCodec,
    private val router: RuntimeRequestRouter,
    private val requestExecutionConfiguration: RuntimeRequestExecutionConfiguration =
        RuntimeRequestExecutionConfiguration(),
    readBufferSize: Int = DEFAULT_READ_BUFFER_SIZE
) : RuntimeConnectionProcessor {
    private val readBufferSize: Int = readBufferSize.also {
        require(it > 0) { "Read buffer size must be greater than zero" }
    }

    override fun process(connection: RuntimeConnection) {
        connection.use {
            val session = RuntimeConnectionSession()
            val streamDecoder = frameCodec.makeStreamDecoder()
            val readBuffer = ByteArray(readBufferSize)
            val writeFailure = AtomicReference<Exception?>()
            val writeLock = Any()
            val requestExecutor = RuntimeRequestExecutor(
                configuration = requestExecutionConfiguration,
                session = session,
                router = router,
                responseWriter = RuntimeResponseWriter { response ->
                    synchronized(writeLock) {
                        try {
                            connection.outputStream.write(encodedResponseFrame(response))
                            connection.outputStream.flush()
                        } catch (error: Exception) {
                            writeFailure.compareAndSet(null, error)
                            throw error
                        }
                    }
                }
            )
            try {
                while (true) {
                    val byteCount = connection.inputStream.read(readBuffer)
                    if (byteCount < 0) {
                        break
                    }
                    if (byteCount == 0) {
                        continue
                    }
                    val payloads = streamDecoder.append(readBuffer.copyOf(byteCount))
                    payloads.forEach { payload ->
                        requestExecutor.submit(messageCodec.decodeRequest(payload))
                    }
                }
                if (streamDecoder.pendingByteCount != 0) {
                    throw EOFException("Connection ended with an incomplete Runtime frame")
                }
                if (!requestExecutor.awaitCompletion()) {
                    throw SocketTimeoutException("Runtime requests did not finish before connection shutdown")
                }
                writeFailure.get()?.let { throw it }
            } finally {
                requestExecutor.close()
            }
        }
    }

    private fun encodedResponseFrame(response: RuntimeResponseEnvelope): ByteArray {
        val responsePayload = messageCodec.encodeResponse(response)
        return try {
            frameCodec.encode(responsePayload)
        } catch (error: RuntimeFrameException.PayloadTooLarge) {
            val compactFailure = response.copy(
                outcome = RuntimeResponseOutcome.Failure(
                    RuntimeError(
                        code = RuntimeErrorCode.frameTooLarge,
                        message = "Runtime response exceeds the frame size limit",
                        recoverySuggestion = null
                    )
                )
            )
            frameCodec.encode(messageCodec.encodeResponse(compactFailure))
        }
    }

    private companion object {
        const val DEFAULT_READ_BUFFER_SIZE: Int = 8 * 1024
    }
}
