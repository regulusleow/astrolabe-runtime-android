//
//  RuntimeRequestExecutorTest.kt
//  astrolabe-runtime-android
//
//  Created by 轩辕十四 on 2026/7/20.
//

package dev.astrolabe.runtime.core

import dev.astrolabe.protocol.RuntimeCancelRequestParameters
import dev.astrolabe.protocol.RuntimeCancelRequestPayload
import dev.astrolabe.protocol.RuntimeCapability
import dev.astrolabe.protocol.RuntimeMessageCodec
import dev.astrolabe.protocol.RuntimeMethod
import dev.astrolabe.protocol.RuntimeNamespacedIdentifier
import dev.astrolabe.protocol.RuntimeOpaqueIdentifier
import dev.astrolabe.protocol.RuntimeProtocolVersion
import dev.astrolabe.protocol.RuntimeRequestEnvelope
import dev.astrolabe.protocol.RuntimeResponseEnvelope
import dev.astrolabe.protocol.RuntimeResponseOutcome
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeRequestExecutorTest {
    private val messageCodec = RuntimeMessageCodec()
    private val blockingMethod = RuntimeMethod("test.blocking")

    @Test
    fun executorTimesOutRequestsAndWritesOneResponse() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val responses = LinkedBlockingQueue<RuntimeResponseEnvelope>()
        val executor = makeExecutor(
            started = started,
            release = release,
            responses = responses,
            maximumConcurrentRequests = 1,
            requestTimeoutMillis = 50
        )
        val request = request(blockingMethod)

        executor.submit(request)
        assertTrue(started.await(1, TimeUnit.SECONDS))
        val response = responses.poll(2, TimeUnit.SECONDS)
        release.countDown()
        executor.close()

        assertNotNull(response)
        val failure = response?.outcome as RuntimeResponseOutcome.Failure
        assertEquals("requestTimedOut", failure.error.code.rawValue)
        assertEquals(0, responses.size)
    }

    @Test
    fun executorRejectsWorkBeyondTheConnectionLimit() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val responses = LinkedBlockingQueue<RuntimeResponseEnvelope>()
        val executor = makeExecutor(
            started = started,
            release = release,
            responses = responses,
            maximumConcurrentRequests = 1,
            requestTimeoutMillis = 2_000
        )
        val firstRequest = request(blockingMethod)
        val secondRequest = request(blockingMethod)

        executor.submit(firstRequest)
        assertTrue(started.await(1, TimeUnit.SECONDS))
        executor.submit(secondRequest)
        val rejectedResponse = responses.poll(1, TimeUnit.SECONDS)
        release.countDown()
        executor.close()

        assertEquals(secondRequest.requestID, rejectedResponse?.requestID)
        val failure = rejectedResponse?.outcome as RuntimeResponseOutcome.Failure
        assertEquals("tooManyRequests", failure.error.code.rawValue)
    }

    @Test
    fun cancellationCompletesTargetAndCancellationRequests() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val responses = LinkedBlockingQueue<RuntimeResponseEnvelope>()
        val executor = makeExecutor(
            started = started,
            release = release,
            responses = responses,
            maximumConcurrentRequests = 1,
            requestTimeoutMillis = 2_000
        )
        val targetRequest = request(blockingMethod)
        val cancellationRequest = messageCodec.decodeRequest(
            messageCodec.encodeRequest(
                requestID = UUID.randomUUID().toString(),
                contract = RuntimeCancelRequestParameters.contract,
                parameters = RuntimeCancelRequestParameters(targetRequest.requestID)
            )
        )

        executor.submit(targetRequest)
        assertTrue(started.await(1, TimeUnit.SECONDS))
        executor.submit(cancellationRequest)
        val firstResponse = responses.poll(1, TimeUnit.SECONDS)
        val secondResponse = responses.poll(1, TimeUnit.SECONDS)
        release.countDown()
        executor.close()

        val responsesByRequest = listOfNotNull(firstResponse, secondResponse)
            .associateBy(RuntimeResponseEnvelope::requestID)
        val targetFailure = responsesByRequest[targetRequest.requestID]?.outcome
            as RuntimeResponseOutcome.Failure
        assertEquals("requestCancelled", targetFailure.error.code.rawValue)
        val cancellationResponse = responsesByRequest[cancellationRequest.requestID]
        assertNotNull(cancellationResponse)
        val payload = messageCodec.decodeSuccessPayload(
            cancellationResponse ?: error("Missing cancellation response"),
            RuntimeCancelRequestPayload.contract
        )
        assertTrue(payload.cancellationAccepted)
    }

    private fun makeExecutor(
        started: CountDownLatch,
        release: CountDownLatch,
        responses: LinkedBlockingQueue<RuntimeResponseEnvelope>,
        maximumConcurrentRequests: Int,
        requestTimeoutMillis: Long
    ): RuntimeRequestExecutor {
        val session = RuntimeConnectionSession()
        session.completeHandshake(
            RuntimeProtocolVersion.V2,
            setOf(RuntimeCapability.requestCancellation)
        )
        val blockingRoute = RuntimeRequestRoute(
            method = blockingMethod,
            requiredCapability = null,
            requiresHandshake = true,
            handler = RuntimeRequestHandler { _, _ ->
                started.countDown()
                release.await()
                RuntimeResponseOutcome.Success(JsonPrimitive("done"))
            }
        )
        val coreRoutes = RuntimeCoreRoutes.create(
            configuration = testEndpointConfiguration(
                setOf(RuntimeCapability.applicationInfo, RuntimeCapability.requestCancellation)
            ),
            applicationInfoProvider = RuntimeApplicationInfoProvider {
                throw UnsupportedOperationException("Application info is not used by this test")
            },
            messageCodec = messageCodec
        )
        return RuntimeRequestExecutor(
            configuration = RuntimeRequestExecutionConfiguration(
                maximumConcurrentRequests = maximumConcurrentRequests,
                requestTimeoutMillis = requestTimeoutMillis
            ),
            session = session,
            router = RuntimeRequestRouter(coreRoutes + blockingRoute),
            responseWriter = RuntimeResponseWriter(responses::add)
        )
    }

    private fun request(method: RuntimeMethod): RuntimeRequestEnvelope = RuntimeRequestEnvelope(
        requestID = UUID.randomUUID().toString(),
        protocolVersion = RuntimeProtocolVersion.V2,
        method = method,
        parameters = buildJsonObject {}
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
