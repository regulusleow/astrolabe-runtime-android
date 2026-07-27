//
//  RuntimeEndpointConfiguration.kt
//  astrolabe-runtime-android
//
//  Created by 轩辕十四 on 2026/7/20.
//

package dev.astrolabe.runtime.core

import dev.astrolabe.protocol.RuntimeCapability
import dev.astrolabe.protocol.RuntimeNamespacedIdentifier
import dev.astrolabe.protocol.RuntimeOpaqueIdentifier

/** Stable runtime identity and capabilities advertised during handshake. */
public class RuntimeEndpointConfiguration(
    runtimeIdentifier: RuntimeNamespacedIdentifier,
    runtimeVersion: String,
    runtimeInstanceIdentifier: RuntimeOpaqueIdentifier,
    platform: String,
    capabilities: Set<RuntimeCapability>
) {
    /** Namespaced identifier for the Android Runtime implementation. */
    public val runtimeIdentifier: RuntimeNamespacedIdentifier = runtimeIdentifier

    /** Android Runtime release version. */
    public val runtimeVersion: String = runtimeVersion

    /** Opaque identifier for the current app process instance. */
    public val runtimeInstanceIdentifier: RuntimeOpaqueIdentifier = runtimeInstanceIdentifier

    /** Platform identifier exposed on the wire. */
    public val platform: String = platform

    /** Operations implemented by this Runtime instance. */
    public val capabilities: Set<RuntimeCapability> = capabilities.toSet()

    init {
        require(runtimeVersion.isNotBlank()) { "Runtime version cannot be blank" }
        require(platform.isNotBlank()) { "Runtime platform cannot be blank" }
    }
}
