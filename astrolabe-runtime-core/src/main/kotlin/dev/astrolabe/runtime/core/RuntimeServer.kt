//
//  RuntimeServer.kt
//  astrolabe-runtime-android
//
//  Created by 轩辕十四 on 2026/7/20.
//

package dev.astrolabe.runtime.core

import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Resource limits and endpoint identity for one Runtime server. */
public data class RuntimeServerConfiguration(
    /** Abstract local socket name exposed by the current process. */
    public val socketName: String,
    /** Maximum number of connections processed concurrently. */
    public val maximumConcurrentConnections: Int = DEFAULT_MAXIMUM_CONNECTIONS
) {
    init {
        require(socketName.isNotBlank()) { "Runtime socket name cannot be blank" }
        require(maximumConcurrentConnections > 0) {
            "Maximum concurrent connections must be greater than zero"
        }
    }

    private companion object {
        const val DEFAULT_MAXIMUM_CONNECTIONS: Int = 4
    }
}

/** Receives transport and connection-processing failures. */
public fun interface RuntimeServerFailureListener {
    /** Reports one failure that occurred while the server was active. */
    public fun onFailure(error: Exception)
}

/** Owns the accept loop and bounded connection lifecycle for one Runtime endpoint. */
public class RuntimeServer(
    private val configuration: RuntimeServerConfiguration,
    private val acceptorFactory: RuntimeConnectionAcceptorFactory,
    private val connectionProcessor: RuntimeConnectionProcessor,
    private val failureListener: RuntimeServerFailureListener = RuntimeServerFailureListener {}
) {
    private val lock = Any()
    private var activeState: RuntimeServerState? = null

    /** Whether the server currently owns an open transport endpoint. */
    public val isRunning: Boolean
        get() = synchronized(lock) { activeState != null }

    /** Starts the server once and returns false when it is already running. */
    public fun start(): Boolean {
        val state = synchronized(lock) {
            if (activeState != null) {
                return false
            }

            RuntimeServerState(
                acceptor = acceptorFactory.open(configuration.socketName),
                acceptExecutor = Executors.newSingleThreadExecutor(
                    RuntimeThreadFactory("astrolabe-accept")
                ),
                connectionExecutor = ThreadPoolExecutor(
                    configuration.maximumConcurrentConnections,
                    configuration.maximumConcurrentConnections,
                    0L,
                    TimeUnit.MILLISECONDS,
                    SynchronousQueue(),
                    RuntimeThreadFactory("astrolabe-connection")
                ),
                connections = Collections.synchronizedSet(mutableSetOf())
            ).also { activeState = it }
        }
        try {
            state.acceptExecutor.execute { acceptConnections(state) }
        } catch (error: RejectedExecutionException) {
            deactivate(state)
            throw error
        }
        return true
    }

    /** Stops the active server and returns false when it was already stopped. */
    public fun stop(): Boolean {
        val state = synchronized(lock) {
            activeState ?: return false
        }
        return releaseAndDeactivate(state)
    }

    private fun acceptConnections(state: RuntimeServerState) {
        while (isActive(state)) {
            val connection = try {
                state.acceptor.accept()
            } catch (error: Exception) {
                if (isActive(state)) {
                    reportFailure(error)
                    deactivate(state)
                }
                return
            }

            if (!isActive(state)) {
                closeConnection(connection)
                return
            }
            state.connections.add(connection)
            try {
                state.connectionExecutor.execute {
                    try {
                        connectionProcessor.process(connection)
                    } catch (error: Exception) {
                        if (isActive(state)) {
                            reportFailure(error)
                        }
                    } finally {
                        state.connections.remove(connection)
                        closeConnection(connection)
                    }
                }
            } catch (error: RejectedExecutionException) {
                state.connections.remove(connection)
                closeConnection(connection)
                if (isActive(state)) {
                    reportFailure(error)
                }
            }
        }
    }

    private fun isActive(state: RuntimeServerState): Boolean =
        synchronized(lock) { activeState === state && !state.releasing.get() }

    private fun deactivate(state: RuntimeServerState) {
        releaseAndDeactivate(state)
    }

    private fun releaseAndDeactivate(state: RuntimeServerState): Boolean {
        if (!state.releasing.compareAndSet(false, true)) {
            return false
        }
        try {
            release(state)
        } finally {
            synchronized(lock) {
                if (activeState === state) {
                    activeState = null
                }
            }
        }
        return true
    }

    private fun release(state: RuntimeServerState) {
        closeAcceptor(state.acceptor)
        synchronized(state.connections) {
            state.connections.toList().forEach(::closeConnection)
            state.connections.clear()
        }
        shutdownAndAwaitTermination(state.acceptExecutor, "accept loop")
        shutdownAndAwaitTermination(state.connectionExecutor, "connection workers")
    }

    private fun shutdownAndAwaitTermination(
        executor: ExecutorService,
        resourceName: String
    ) {
        executor.shutdownNow()
        val terminated = try {
            executor.awaitTermination(EXECUTOR_TERMINATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException(
                "Runtime shutdown was interrupted while waiting for $resourceName",
                error
            )
        }
        check(terminated) {
            "Runtime $resourceName did not stop within the release timeout"
        }
    }

    private fun closeAcceptor(acceptor: RuntimeConnectionAcceptor) {
        try {
            acceptor.close()
        } catch (error: Exception) {
            reportFailure(error)
        }
    }

    private fun closeConnection(connection: RuntimeConnection) {
        try {
            connection.close()
        } catch (error: Exception) {
            reportFailure(error)
        }
    }

    private fun reportFailure(error: Exception) {
        runCatching { failureListener.onFailure(error) }
    }

    private companion object {
        const val EXECUTOR_TERMINATION_TIMEOUT_SECONDS: Long = 2L
    }
}

private data class RuntimeServerState(
    /** Acceptor owned by this server run. */
    val acceptor: RuntimeConnectionAcceptor,
    /** Executor owning the blocking accept loop. */
    val acceptExecutor: ExecutorService,
    /** Bounded executor processing accepted connections. */
    val connectionExecutor: ExecutorService,
    /** Connections that must be closed when the server stops. */
    val connections: MutableSet<RuntimeConnection>,
    /** Whether this state has started releasing its endpoint resources. */
    val releasing: AtomicBoolean = AtomicBoolean(false)
)

internal class RuntimeThreadFactory(private val prefix: String) : ThreadFactory {
    private val nextIdentifier = AtomicInteger(1)

    override fun newThread(operation: Runnable): Thread = Thread(
        operation,
        "$prefix-${nextIdentifier.getAndIncrement()}"
    ).apply {
        isDaemon = true
    }
}
