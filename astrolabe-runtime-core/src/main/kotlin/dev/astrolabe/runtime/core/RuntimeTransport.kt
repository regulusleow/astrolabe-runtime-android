//
//  RuntimeTransport.kt
//  astrolabe-runtime-android
//
//  Created by 轩辕十四 on 2026/7/20.
//

package dev.astrolabe.runtime.core

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

/** Bidirectional byte stream accepted by the Runtime server. */
public interface RuntimeConnection : Closeable {
    /** Stream carrying framed Host requests. */
    public val inputStream: InputStream

    /** Stream receiving framed Runtime responses. */
    public val outputStream: OutputStream
}

/** Accepts connections from one transport endpoint. */
public interface RuntimeConnectionAcceptor : Closeable {
    /** Blocks until one connection is accepted or the acceptor closes. */
    public fun accept(): RuntimeConnection
}

/** Opens a transport endpoint for a Runtime socket name. */
public fun interface RuntimeConnectionAcceptorFactory {
    /** Opens an acceptor bound to [socketName]. */
    public fun open(socketName: String): RuntimeConnectionAcceptor
}

/** Processes one accepted connection until EOF, failure, or shutdown. */
public fun interface RuntimeConnectionProcessor {
    /** Processes [connection] and returns after all connection resources are released. */
    public fun process(connection: RuntimeConnection)
}
