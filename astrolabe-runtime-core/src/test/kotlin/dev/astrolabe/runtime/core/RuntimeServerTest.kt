//
//  RuntimeServerTest.kt
//  astrolabe-runtime-android
//
//  Created by 轩辕十四 on 2026/7/20.
//

package dev.astrolabe.runtime.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeServerTest {
    @Test
    fun serverStartStopAreIdempotentAndReleaseConnections() {
        val acceptor = QueueRuntimeConnectionAcceptor()
        val factory = RecordingRuntimeConnectionAcceptorFactory(acceptor)
        val processed = CountDownLatch(1)
        val connection = BlockingRuntimeConnection()
        val server = RuntimeServer(
            configuration = RuntimeServerConfiguration(
                socketName = "astrolabe_100",
                maximumConcurrentConnections = 2
            ),
            acceptorFactory = factory,
            connectionProcessor = RuntimeConnectionProcessor {
                processed.countDown()
                it.inputStream.read()
            }
        )

        assertTrue(server.start())
        assertFalse(server.start())
        acceptor.enqueue(connection)
        assertTrue(processed.await(2, TimeUnit.SECONDS))
        assertTrue(server.stop())
        assertFalse(server.stop())

        assertEquals(1, factory.openCount)
        assertTrue(acceptor.closed)
        assertTrue(connection.awaitClosed())
    }

    @Test
    fun unexpectedAcceptFailureStopsTheServerAndAllowsRestart() {
        val secondAcceptor = QueueRuntimeConnectionAcceptor()
        val factory = SequencedRuntimeConnectionAcceptorFactory(
            listOf(FailingRuntimeConnectionAcceptor(), secondAcceptor)
        )
        val failureReported = CountDownLatch(1)
        val server = RuntimeServer(
            configuration = RuntimeServerConfiguration(
                socketName = "astrolabe_100",
                maximumConcurrentConnections = 1
            ),
            acceptorFactory = factory,
            connectionProcessor = RuntimeConnectionProcessor {},
            failureListener = RuntimeServerFailureListener { failureReported.countDown() }
        )

        assertTrue(server.start())
        assertTrue(failureReported.await(1, TimeUnit.SECONDS))
        assertTrue(awaitCondition { !server.isRunning })
        assertTrue(server.start())
        assertTrue(server.stop())
        assertTrue(secondAcceptor.closed)
    }

    @Test
    fun serverProcessesConnectionsConcurrentlyAndRejectsExcessCapacity() {
        val acceptor = QueueRuntimeConnectionAcceptor()
        val processingStarted = CountDownLatch(2)
        val releaseProcessing = CountDownLatch(1)
        val capacityFailure = CountDownLatch(1)
        val firstConnection = BlockingRuntimeConnection()
        val secondConnection = BlockingRuntimeConnection()
        val rejectedConnection = BlockingRuntimeConnection()
        val server = RuntimeServer(
            configuration = RuntimeServerConfiguration(
                socketName = "astrolabe_100",
                maximumConcurrentConnections = 2
            ),
            acceptorFactory = RuntimeConnectionAcceptorFactory { acceptor },
            connectionProcessor = RuntimeConnectionProcessor {
                processingStarted.countDown()
                releaseProcessing.await()
            },
            failureListener = RuntimeServerFailureListener { error ->
                if (error is java.util.concurrent.RejectedExecutionException) {
                    capacityFailure.countDown()
                }
            }
        )

        assertTrue(server.start())
        acceptor.enqueue(firstConnection)
        acceptor.enqueue(secondConnection)
        assertTrue(processingStarted.await(1, TimeUnit.SECONDS))
        acceptor.enqueue(rejectedConnection)
        assertTrue(capacityFailure.await(1, TimeUnit.SECONDS))
        assertTrue(rejectedConnection.awaitClosed())
        releaseProcessing.countDown()
        assertTrue(server.stop())
    }

    @Test
    fun serverRejectsRestartUntilEndpointReleaseCompletes() {
        val closeStarted = CountDownLatch(1)
        val allowClose = CountDownLatch(1)
        val stopFinished = CountDownLatch(1)
        val firstAcceptor = BlockingCloseRuntimeConnectionAcceptor(closeStarted, allowClose)
        val secondAcceptor = QueueRuntimeConnectionAcceptor()
        val factory = SequencedRuntimeConnectionAcceptorFactory(
            listOf(firstAcceptor, secondAcceptor)
        )
        val stopResult = AtomicBoolean(false)
        val server = RuntimeServer(
            configuration = RuntimeServerConfiguration(socketName = "astrolabe_100"),
            acceptorFactory = factory,
            connectionProcessor = RuntimeConnectionProcessor {}
        )

        assertTrue(server.start())
        Thread {
            stopResult.set(server.stop())
            stopFinished.countDown()
        }.start()
        assertTrue(closeStarted.await(1, TimeUnit.SECONDS))

        assertFalse(server.start())
        allowClose.countDown()
        assertTrue(stopFinished.await(1, TimeUnit.SECONDS))
        assertTrue(stopResult.get())
        assertTrue(server.start())
        assertTrue(server.stop())
    }

    @Test
    fun serverStopWaitsForTheAcceptLoopToExit() {
        val acceptStarted = CountDownLatch(1)
        val closeStarted = CountDownLatch(1)
        val allowAcceptExit = CountDownLatch(1)
        val stopFinished = CountDownLatch(1)
        val acceptor = DelayedAcceptExitRuntimeConnectionAcceptor(
            acceptStarted = acceptStarted,
            closeStarted = closeStarted,
            allowAcceptExit = allowAcceptExit
        )
        val server = RuntimeServer(
            configuration = RuntimeServerConfiguration(socketName = "astrolabe_100"),
            acceptorFactory = RuntimeConnectionAcceptorFactory { acceptor },
            connectionProcessor = RuntimeConnectionProcessor {}
        )

        assertTrue(server.start())
        assertTrue(acceptStarted.await(1, TimeUnit.SECONDS))
        Thread {
            server.stop()
            stopFinished.countDown()
        }.start()
        assertTrue(closeStarted.await(1, TimeUnit.SECONDS))
        assertFalse(stopFinished.await(100, TimeUnit.MILLISECONDS))

        allowAcceptExit.countDown()
        assertTrue(stopFinished.await(1, TimeUnit.SECONDS))
    }
}

private fun awaitCondition(condition: () -> Boolean): Boolean {
    repeat(100) {
        if (condition()) {
            return true
        }
        Thread.sleep(10)
    }
    return condition()
}

private class RecordingRuntimeConnectionAcceptorFactory(
    private val acceptor: RuntimeConnectionAcceptor
) : RuntimeConnectionAcceptorFactory {
    var openCount: Int = 0
        private set

    override fun open(socketName: String): RuntimeConnectionAcceptor {
        openCount += 1
        return acceptor
    }
}

private class QueueRuntimeConnectionAcceptor : RuntimeConnectionAcceptor {
    private val events = LinkedBlockingQueue<AcceptorEvent>()
    var closed: Boolean = false
        private set

    fun enqueue(connection: RuntimeConnection) {
        events.put(AcceptorEvent.Connection(connection))
    }

    override fun accept(): RuntimeConnection = when (val event = events.take()) {
        is AcceptorEvent.Connection -> event.connection
        AcceptorEvent.Closed -> throw IllegalStateException("Acceptor is closed")
    }

    override fun close() {
        closed = true
        events.offer(AcceptorEvent.Closed)
    }
}

private class FailingRuntimeConnectionAcceptor : RuntimeConnectionAcceptor {
    override fun accept(): RuntimeConnection = throw IllegalStateException("Accept failed")

    override fun close() = Unit
}

private class BlockingCloseRuntimeConnectionAcceptor(
    private val closeStarted: CountDownLatch,
    private val allowClose: CountDownLatch
) : RuntimeConnectionAcceptor {
    private val closed = CountDownLatch(1)

    override fun accept(): RuntimeConnection {
        closed.await()
        throw IllegalStateException("Acceptor is closed")
    }

    override fun close() {
        closeStarted.countDown()
        allowClose.await()
        closed.countDown()
    }
}

private class DelayedAcceptExitRuntimeConnectionAcceptor(
    private val acceptStarted: CountDownLatch,
    private val closeStarted: CountDownLatch,
    private val allowAcceptExit: CountDownLatch
) : RuntimeConnectionAcceptor {
    override fun accept(): RuntimeConnection {
        acceptStarted.countDown()
        while (true) {
            try {
                allowAcceptExit.await()
                throw IllegalStateException("Acceptor is closed")
            } catch (error: InterruptedException) {
                continue
            }
        }
    }

    override fun close() {
        closeStarted.countDown()
    }
}

private class SequencedRuntimeConnectionAcceptorFactory(
    private val acceptors: List<RuntimeConnectionAcceptor>
) : RuntimeConnectionAcceptorFactory {
    private val nextIndex = AtomicInteger(0)

    override fun open(socketName: String): RuntimeConnectionAcceptor =
        acceptors.getOrElse(nextIndex.getAndIncrement()) {
            throw IllegalStateException("No Runtime acceptor remains")
        }
}

private sealed interface AcceptorEvent {
    data class Connection(
        /** Accepted in-memory connection. */
        val connection: RuntimeConnection
    ) : AcceptorEvent

    data object Closed : AcceptorEvent
}

private class BlockingRuntimeConnection : RuntimeConnection {
    private val closedLatch = CountDownLatch(1)

    override val inputStream: InputStream = object : InputStream() {
        override fun read(): Int {
            closedLatch.await()
            return -1
        }
    }
    override val outputStream: OutputStream = ByteArrayOutputStream()

    fun awaitClosed(): Boolean = closedLatch.await(2, TimeUnit.SECONDS)

    override fun close() {
        closedLatch.countDown()
    }
}
