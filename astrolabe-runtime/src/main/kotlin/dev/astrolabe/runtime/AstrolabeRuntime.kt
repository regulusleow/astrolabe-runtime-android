//
//  AstrolabeRuntime.kt
//  astrolabe-runtime-android
//
//  Created by 轩辕十四 on 2026/7/20.
//

package dev.astrolabe.runtime

import android.content.Context
import android.os.Process
import dev.astrolabe.protocol.RuntimeCapability
import dev.astrolabe.protocol.RuntimeFrameCodec
import dev.astrolabe.protocol.RuntimeMessageCodec
import dev.astrolabe.protocol.RuntimeNamespacedIdentifier
import dev.astrolabe.protocol.RuntimeOpaqueIdentifier
import dev.astrolabe.runtime.core.AndroidLocalSocketAcceptorFactory
import dev.astrolabe.runtime.core.RuntimeAttributePatchRoutes
import dev.astrolabe.runtime.core.RuntimeCoreRoutes
import dev.astrolabe.runtime.core.RuntimeEndpointConfiguration
import dev.astrolabe.runtime.core.RuntimeFramedConnectionProcessor
import dev.astrolabe.runtime.core.RuntimeHierarchyRoutes
import dev.astrolabe.runtime.core.RuntimeNodeDetailRoutes
import dev.astrolabe.runtime.core.RuntimeRequestRouter
import dev.astrolabe.runtime.core.RuntimeServer
import dev.astrolabe.runtime.core.RuntimeServerConfiguration
import dev.astrolabe.runtime.view.AndroidViewInspectionComponent
import java.util.UUID

/** Result of attempting to start the Android Runtime. */
public sealed interface AstrolabeRuntimeStartResult {
    /** Runtime started a new server. */
    public data class Started(
        /** Abstract local socket name owned by the Runtime. */
        public val socketName: String
    ) : AstrolabeRuntimeStartResult

    /** Runtime was already active. */
    public data class AlreadyRunning(
        /** Existing abstract local socket name. */
        public val socketName: String
    ) : AstrolabeRuntimeStartResult

    /** Runtime could not start. */
    public data class Failed(
        /** Failure raised while opening or starting the server. */
        public val error: Exception
    ) : AstrolabeRuntimeStartResult
}

/** Public composition root and lifecycle facade for Astrolabe Android Runtime. */
public object AstrolabeRuntime {
    private val lock = Any()
    private var activeRuntime: ActiveRuntime? = null

    /** Current abstract local socket name, or null while stopped. */
    @JvmStatic
    public val socketName: String?
        get() = synchronized(lock) {
            activeRuntime?.takeIf { it.server.isRunning }?.socketName
        }

    /** Starts one process-scoped Runtime server. */
    @JvmStatic
    public fun start(context: Context): AstrolabeRuntimeStartResult = synchronized(lock) {
        activeRuntime?.let { runtime ->
            if (runtime.server.isRunning) {
                return AstrolabeRuntimeStartResult.AlreadyRunning(runtime.socketName)
            }
            try {
                runtime.close()
            } catch (error: Exception) {
                return AstrolabeRuntimeStartResult.Failed(error)
            }
            activeRuntime = null
        }

        var runtime: ActiveRuntime? = null
        var server: RuntimeServer? = null
        var viewInspectionComponent: AndroidViewInspectionComponent? = null
        return try {
            val processIdentifier = Process.myPid().toString()
            val instanceIdentifier = RuntimeOpaqueIdentifier(
                "runtime:android:$processIdentifier:${UUID.randomUUID()}"
            )
            val targetIdentifier = instanceIdentifier
            val socketName = "astrolabe_$processIdentifier"
            val messageCodec = RuntimeMessageCodec()
            val inspectionComponent = AndroidViewInspectionComponent.create(
                context = context,
                targetIdentifier = targetIdentifier
            )
            viewInspectionComponent = inspectionComponent
            val endpointConfiguration = androidRuntimeEndpointConfiguration(instanceIdentifier)
            val router = RuntimeRequestRouter(
                RuntimeCoreRoutes.create(
                    configuration = endpointConfiguration,
                    applicationInfoProvider = AndroidRuntimeApplicationInfoProvider(
                        context = context,
                        targetIdentifier = targetIdentifier,
                        displayEnvironmentProvider = inspectionComponent
                            .displayEnvironmentProvider
                    ),
                    messageCodec = messageCodec
                ) + RuntimeHierarchyRoutes.create(
                    hierarchyProvider = inspectionComponent.hierarchyProvider,
                    messageCodec = messageCodec
                ) + RuntimeNodeDetailRoutes.create(
                    nodeDetailProvider = inspectionComponent.nodeDetailProvider,
                    messageCodec = messageCodec
                ) + RuntimeAttributePatchRoutes.create(
                    provider = inspectionComponent.attributePatchProvider,
                    messageCodec = messageCodec
                )
            )
            val runtimeServer = RuntimeServer(
                configuration = RuntimeServerConfiguration(socketName = socketName),
                acceptorFactory = AndroidLocalSocketAcceptorFactory,
                connectionProcessor = RuntimeFramedConnectionProcessor(
                    frameCodec = RuntimeFrameCodec(),
                    messageCodec = messageCodec,
                    router = router
                )
            )
            server = runtimeServer
            check(runtimeServer.start()) { "Runtime server did not enter the active state" }
            runtime = ActiveRuntime(
                socketName = socketName,
                server = runtimeServer,
                viewInspectionComponent = inspectionComponent
            )
            activeRuntime = runtime
            AstrolabeRuntimeStartResult.Started(socketName)
        } catch (error: Exception) {
            val cleanupFailure = try {
                val active = runtime
                if (active != null) {
                    active.close()
                } else {
                    closeRuntimeResources(
                        stopServer = { server?.stop() ?: false },
                        closeInspection = { viewInspectionComponent?.close() }
                    )
                }
                null
            } catch (cleanupError: Exception) {
                cleanupError
            }
            if (cleanupFailure != null) {
                error.addSuppressed(cleanupFailure)
            }
            AstrolabeRuntimeStartResult.Failed(error)
        }
    }

    /** Stops the process-scoped Runtime server. */
    @JvmStatic
    public fun stop(): Boolean = synchronized(lock) {
        val runtime = activeRuntime
            ?: return false
        val stopped = runtime.close()
        activeRuntime = null
        stopped
    }
}

internal fun androidRuntimeEndpointConfiguration(
    instanceIdentifier: RuntimeOpaqueIdentifier
): RuntimeEndpointConfiguration = RuntimeEndpointConfiguration(
    runtimeIdentifier = RuntimeNamespacedIdentifier("astrolabe.runtime.android"),
    runtimeVersion = BuildConfig.ASTROLABE_RUNTIME_VERSION,
    runtimeInstanceIdentifier = instanceIdentifier,
    platform = "android",
    capabilities = setOf(
        RuntimeCapability.applicationInfo,
        RuntimeCapability.hierarchySnapshot,
        RuntimeCapability.nodeDetail,
        RuntimeCapability.attributePatchDiscovery,
        RuntimeCapability.attributePatching,
        RuntimeCapability.requestCancellation,
        RuntimeCapability.uiGraphRelations
    )
)

private data class ActiveRuntime(
    /** Abstract local socket name owned by this Runtime instance. */
    val socketName: String,
    /** Server that owns transport resources for this Runtime instance. */
    val server: RuntimeServer,
    /** Process-scoped Android View inspection services. */
    val viewInspectionComponent: AndroidViewInspectionComponent
) {
    fun close(): Boolean = closeRuntimeResources(
        stopServer = server::stop,
        closeInspection = viewInspectionComponent::close
    )
}

internal fun closeRuntimeResources(
    stopServer: () -> Boolean,
    closeInspection: () -> Unit
): Boolean {
    var stopped = false
    var primaryFailure: Exception? = null
    try {
        stopped = stopServer()
    } catch (error: Exception) {
        primaryFailure = error
    }
    try {
        closeInspection()
    } catch (error: Exception) {
        val existingFailure = primaryFailure
        if (existingFailure == null) {
            primaryFailure = error
        } else {
            existingFailure.addSuppressed(error)
        }
    }
    primaryFailure?.let { error -> throw error }
    return stopped
}
