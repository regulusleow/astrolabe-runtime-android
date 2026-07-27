//
//  RuntimeConnectionSession.kt
//  astrolabe-runtime-android
//
//  Created by 轩辕十四 on 2026/7/20.
//

package dev.astrolabe.runtime.core

import dev.astrolabe.protocol.RuntimeCapability
import dev.astrolabe.protocol.RuntimeProtocolVersion

/** Mutable protocol state owned by exactly one transport connection. */
public class RuntimeConnectionSession {
    private val lock = Any()
    private var protocolVersion: RuntimeProtocolVersion? = null
    private var capabilities: Set<RuntimeCapability> = emptySet()

    /** Whether the connection completed protocol negotiation. */
    public val isHandshakeComplete: Boolean
        get() = synchronized(lock) { protocolVersion != null }

    /** Negotiated wire version, or null before handshake. */
    public val negotiatedProtocolVersion: RuntimeProtocolVersion?
        get() = synchronized(lock) { protocolVersion }

    /** Returns whether the negotiated session includes [capability]. */
    public fun supports(capability: RuntimeCapability): Boolean =
        synchronized(lock) { capability in capabilities }

    internal fun completeHandshake(
        version: RuntimeProtocolVersion,
        negotiatedCapabilities: Set<RuntimeCapability>
    ): Boolean = synchronized(lock) {
        if (protocolVersion != null) {
            false
        } else {
            protocolVersion = version
            capabilities = negotiatedCapabilities.toSet()
            true
        }
    }
}
