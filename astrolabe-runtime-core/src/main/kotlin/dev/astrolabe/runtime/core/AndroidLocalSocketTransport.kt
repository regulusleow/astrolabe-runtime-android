//
//  AndroidLocalSocketTransport.kt
//  astrolabe-runtime-android
//
//  Created by 轩辕十四 on 2026/7/20.
//

package dev.astrolabe.runtime.core

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/** Opens Android abstract local sockets for Runtime transport. */
public object AndroidLocalSocketAcceptorFactory : RuntimeConnectionAcceptorFactory {
    override fun open(socketName: String): RuntimeConnectionAcceptor =
        AndroidLocalSocketAcceptor(
            socketName = socketName,
            serverSocket = LocalServerSocket(socketName)
        )
}

private class AndroidLocalSocketAcceptor(
    private val socketName: String,
    private val serverSocket: LocalServerSocket
) : RuntimeConnectionAcceptor {
    private val closed = AtomicBoolean(false)

    override fun accept(): RuntimeConnection = AndroidLocalSocketConnection(serverSocket.accept())

    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        LocalSocket().use { wakeSocket ->
            try {
                wakeSocket.connect(
                    LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT)
                )
            } finally {
                serverSocket.close()
            }
        }
    }
}

private class AndroidLocalSocketConnection(
    private val socket: LocalSocket
) : RuntimeConnection {
    override val inputStream: InputStream
        get() = socket.inputStream

    override val outputStream: OutputStream
        get() = socket.outputStream

    override fun close() {
        socket.close()
    }
}
