//
//  RuntimeRequestExecutor.kt
//  astrolabe-runtime-android
//
//  Created by 轩辕十四 on 2026/7/20.
//

package dev.astrolabe.runtime.core

import dev.astrolabe.protocol.RuntimeError
import dev.astrolabe.protocol.RuntimeErrorCode
import dev.astrolabe.protocol.RuntimeMethod
import dev.astrolabe.protocol.RuntimeRequestEnvelope
import dev.astrolabe.protocol.RuntimeResponseEnvelope
import dev.astrolabe.protocol.RuntimeResponseOutcome
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Per-connection execution limits for Runtime requests. */
public data class RuntimeRequestExecutionConfiguration(
    /** Maximum number of requests that may execute concurrently. */
    public val maximumConcurrentRequests: Int = DEFAULT_MAXIMUM_CONCURRENT_REQUESTS,
    /** Maximum execution time for one request. */
    public val requestTimeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MILLIS
) {
    init {
        require(maximumConcurrentRequests > 0) {
            "Maximum concurrent requests must be greater than zero"
        }
        require(requestTimeoutMillis > 0) { "Request timeout must be greater than zero" }
    }

    private companion object {
        const val DEFAULT_MAXIMUM_CONCURRENT_REQUESTS: Int = 4
        const val DEFAULT_REQUEST_TIMEOUT_MILLIS: Long = 5_000
    }
}

/** Writes one completed response to the owning connection. */
internal fun interface RuntimeResponseWriter {
    /** Writes [response] exactly once. */
    public fun write(response: RuntimeResponseEnvelope)
}

/** Executes decoded requests with bounded concurrency, deadlines, and cancellation. */
internal class RuntimeRequestExecutor(
    private val configuration: RuntimeRequestExecutionConfiguration,
    private val session: RuntimeConnectionSession,
    private val router: RuntimeRequestRouter,
    private val responseWriter: RuntimeResponseWriter
) : Closeable, RuntimeRequestCancellationController {
    private val closed = AtomicBoolean(false)
    private val activeRequests = ConcurrentHashMap<String, ActiveRuntimeRequest>()
    private val requestExecutor = ThreadPoolExecutor(
        configuration.maximumConcurrentRequests,
        configuration.maximumConcurrentRequests,
        0L,
        TimeUnit.MILLISECONDS,
        SynchronousQueue(),
        RuntimeThreadFactory("astrolabe-request")
    )
    private val timeoutExecutor = ScheduledThreadPoolExecutor(
        1,
        RuntimeThreadFactory("astrolabe-timeout")
    ).apply {
        removeOnCancelPolicy = true
    }

    /** Submits one request or immediately writes a capacity failure. */
    public fun submit(request: RuntimeRequestEnvelope) {
        if (closed.get()) {
            return
        }
        if (request.method == RuntimeMethod.cancelRequest) {
            writeCancellationResponse(request)
            return
        }

        val activeRequest = ActiveRuntimeRequest(
            request = request,
            responseWriter = responseWriter,
            onCompletion = activeRequests::remove
        )
        if (activeRequests.putIfAbsent(request.requestID, activeRequest) != null) {
            responseWriter.write(
                failureResponse(
                    request,
                    RuntimeErrorCode.invalidParameters,
                    "Request identifier is already active"
                )
            )
            return
        }

        val operation = FutureTask {
            val response = router.route(
                request = request,
                context = RuntimeRequestContext(
                    session = session,
                    cancellationToken = activeRequest,
                    cancellationController = this
                )
            )
            activeRequest.complete(response)
        }
        activeRequest.operation = operation
        try {
            requestExecutor.execute(operation)
        } catch (error: RejectedExecutionException) {
            activeRequest.complete(
                failureResponse(
                    request,
                    RuntimeErrorCode.tooManyRequests,
                    "Connection request limit was reached"
                )
            )
            return
        }

        val timeout = timeoutExecutor.schedule(
            {
                activeRequest.fail(
                    failureResponse(
                        request,
                        RuntimeErrorCode.requestTimedOut,
                        "Runtime request exceeded its deadline"
                    )
                )
            },
            configuration.requestTimeoutMillis,
            TimeUnit.MILLISECONDS
        )
        activeRequest.registerTimeout(timeout)
    }

    override fun cancel(requestID: String): Boolean {
        val activeRequest = activeRequests[requestID] ?: return false
        return activeRequest.fail(
            failureResponse(
                activeRequest.request,
                RuntimeErrorCode.requestCancelled,
                "Runtime request was cancelled"
            )
        )
    }

    /** Stops accepting work and waits for active requests to finish or time out. */
    public fun awaitCompletion(): Boolean {
        requestExecutor.shutdown()
        return try {
            requestExecutor.awaitTermination(
                configuration.requestTimeoutMillis + COMPLETION_GRACE_MILLIS,
                TimeUnit.MILLISECONDS
            )
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        activeRequests.values.forEach(ActiveRuntimeRequest::abandon)
        activeRequests.clear()
        requestExecutor.shutdownNow()
        timeoutExecutor.shutdownNow()
    }

    private fun writeCancellationResponse(request: RuntimeRequestEnvelope) {
        val response = router.route(
            request = request,
            context = RuntimeRequestContext(
                session = session,
                cancellationToken = RuntimeCancellationToken { false },
                cancellationController = this
            )
        )
        responseWriter.write(response)
    }

    private fun failureResponse(
        request: RuntimeRequestEnvelope,
        code: RuntimeErrorCode,
        message: String
    ): RuntimeResponseEnvelope = RuntimeResponseEnvelope(
        requestID = request.requestID,
        protocolVersion = request.protocolVersion,
        method = request.method,
        outcome = RuntimeResponseOutcome.Failure(
            RuntimeError(
                code = code,
                message = message,
                recoverySuggestion = null
            )
        )
    )

    private companion object {
        const val COMPLETION_GRACE_MILLIS: Long = 1_000
    }
}

private class ActiveRuntimeRequest(
    /** Request represented by this execution state. */
    val request: RuntimeRequestEnvelope,
    private val responseWriter: RuntimeResponseWriter,
    private val onCompletion: (String) -> Unit
) : RuntimeCancellationToken {
    private val completed = AtomicBoolean(false)
    private val cancellationRequested = AtomicBoolean(false)

    @Volatile
    var operation: Future<*>? = null

    @Volatile
    private var timeout: ScheduledFuture<*>? = null

    override fun isCancellationRequested(): Boolean = cancellationRequested.get()

    fun complete(response: RuntimeResponseEnvelope): Boolean = finish(response, cancelTask = false)

    fun registerTimeout(scheduledTimeout: ScheduledFuture<*>) {
        timeout = scheduledTimeout
        if (completed.get()) {
            scheduledTimeout.cancel(false)
        }
    }

    fun fail(response: RuntimeResponseEnvelope): Boolean {
        cancellationRequested.set(true)
        return finish(response, cancelTask = true)
    }

    fun abandon() {
        if (completed.compareAndSet(false, true)) {
            cancellationRequested.set(true)
            timeout?.cancel(false)
            operation?.cancel(true)
            onCompletion(request.requestID)
        }
    }

    private fun finish(response: RuntimeResponseEnvelope, cancelTask: Boolean): Boolean {
        if (!completed.compareAndSet(false, true)) {
            return false
        }
        timeout?.cancel(false)
        if (cancelTask) {
            operation?.cancel(true)
        }
        onCompletion(request.requestID)
        responseWriter.write(response)
        return true
    }
}
