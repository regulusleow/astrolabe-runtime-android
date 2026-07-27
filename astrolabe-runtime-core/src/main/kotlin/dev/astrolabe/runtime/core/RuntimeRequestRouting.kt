//
//  RuntimeRequestRouting.kt
//  astrolabe-runtime-android
//
//  Created by 轩辕十四 on 2026/7/20.
//

package dev.astrolabe.runtime.core

import dev.astrolabe.protocol.RuntimeCapability
import dev.astrolabe.protocol.RuntimeError
import dev.astrolabe.protocol.RuntimeErrorCode
import dev.astrolabe.protocol.RuntimeMessageException
import dev.astrolabe.protocol.RuntimeMethod
import dev.astrolabe.protocol.RuntimeRequestEnvelope
import dev.astrolabe.protocol.RuntimeResponseEnvelope
import dev.astrolabe.protocol.RuntimeResponseOutcome

/** Handles one decoded Runtime request. */
public fun interface RuntimeRequestHandler {
    /** Executes [request] in the context of [session]. */
    public fun handle(
        request: RuntimeRequestEnvelope,
        context: RuntimeRequestContext
    ): RuntimeResponseOutcome
}

/** Request-scoped services exposed to protocol handlers. */
public data class RuntimeRequestContext(
    /** Connection session that owns the request. */
    public val session: RuntimeConnectionSession,
    /** Cooperative cancellation state for the current request. */
    public val cancellationToken: RuntimeCancellationToken,
    /** Connection-scoped controller used by the cancellation method. */
    public val cancellationController: RuntimeRequestCancellationController
)

/** Exposes cooperative cancellation state without leaking executor types. */
public fun interface RuntimeCancellationToken {
    /** Returns whether cancellation was requested. */
    public fun isCancellationRequested(): Boolean
}

/** Cancels in-flight requests owned by one connection. */
public fun interface RuntimeRequestCancellationController {
    /** Attempts to cancel [requestID] and returns whether an active request accepted it. */
    public fun cancel(requestID: String): Boolean
}

/** Immutable routing metadata for one wire method. */
public data class RuntimeRequestRoute(
    /** Method accepted by this route. */
    public val method: RuntimeMethod,
    /** Capability required after handshake, or null for unguarded methods. */
    public val requiredCapability: RuntimeCapability?,
    /** Whether this route requires a completed handshake. */
    public val requiresHandshake: Boolean,
    /** Handler invoked after route guards pass. */
    public val handler: RuntimeRequestHandler
)

/** Routes protocol requests without depending on transport or Android UI types. */
public class RuntimeRequestRouter(
    routes: Collection<RuntimeRequestRoute>
) {
    private val routesByMethod: Map<RuntimeMethod, RuntimeRequestRoute> =
        routes.associateBy(RuntimeRequestRoute::method).also { indexedRoutes ->
            require(indexedRoutes.size == routes.size) { "Runtime routes must use unique methods" }
        }

    /** Routes one request and always returns a matching response envelope. */
    public fun route(
        request: RuntimeRequestEnvelope,
        session: RuntimeConnectionSession
    ): RuntimeResponseEnvelope = route(
        request = request,
        context = RuntimeRequestContext(
            session = session,
            cancellationToken = RuntimeCancellationToken { false },
            cancellationController = RuntimeRequestCancellationController { false }
        )
    )

    /** Routes one request with request-scoped execution services. */
    public fun route(
        request: RuntimeRequestEnvelope,
        context: RuntimeRequestContext
    ): RuntimeResponseEnvelope {
        val outcome = routeOutcome(request, context)
        return RuntimeResponseEnvelope(
            requestID = request.requestID,
            protocolVersion = request.protocolVersion,
            method = request.method,
            outcome = outcome
        )
    }

    private fun routeOutcome(
        request: RuntimeRequestEnvelope,
        context: RuntimeRequestContext
    ): RuntimeResponseOutcome {
        val session = context.session
        if (!session.isHandshakeComplete && request.method != RuntimeMethod.handshake) {
            return failure(
                code = RuntimeErrorCode.handshakeRequired,
                message = "Handshake must complete before this request"
            )
        }

        val route = routesByMethod[request.method] ?: return failure(
            code = RuntimeErrorCode.unsupportedMethod,
            message = "Runtime method is not supported"
        )
        if (route.requiresHandshake && !session.isHandshakeComplete) {
            return failure(
                code = RuntimeErrorCode.handshakeRequired,
                message = "Handshake must complete before this request"
            )
        }
        val requiredCapability = route.requiredCapability
        if (requiredCapability != null && !session.supports(requiredCapability)) {
            return failure(
                code = RuntimeErrorCode.capabilityUnavailable,
                message = "Required Runtime capability is unavailable"
            )
        }

        return try {
            route.handler.handle(request, context)
        } catch (failure: RuntimeProviderFailure) {
            RuntimeResponseOutcome.Failure(failure.error)
        } catch (error: RuntimeMessageException) {
            failure(
                code = RuntimeErrorCode.invalidParameters,
                message = "Request parameters do not satisfy the method contract"
            )
        } catch (error: Exception) {
            failure(
                code = RuntimeErrorCode.internalFailure,
                message = "Runtime request failed"
            )
        }
    }

    private fun failure(code: RuntimeErrorCode, message: String): RuntimeResponseOutcome.Failure =
        RuntimeResponseOutcome.Failure(
            RuntimeError(
                code = code,
                message = message,
                recoverySuggestion = null
            )
        )
}

/** Reports a structured provider failure without coupling providers to response encoding. */
public class RuntimeProviderFailure(
    /** Structured failure returned to the Host. */
    public val error: RuntimeError
) : Exception(error.message)
