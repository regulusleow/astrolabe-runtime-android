//
//  AndroidViewInspectionComponent.kt
//  astrolabe-runtime-android
//
//  Created by 轩辕十四 on 2026/7/20.
//

package dev.astrolabe.runtime.view

import android.content.Context
import android.view.View
import dev.astrolabe.protocol.RuntimeError
import dev.astrolabe.protocol.RuntimeErrorCode
import dev.astrolabe.protocol.RuntimeOpaqueIdentifier
import dev.astrolabe.runtime.core.RuntimeAttributePatchProviding
import dev.astrolabe.runtime.core.RuntimeAttributePatchService
import dev.astrolabe.runtime.core.RuntimeHierarchyProvider
import dev.astrolabe.runtime.core.RuntimeNodeDetailProvider
import dev.astrolabe.runtime.core.RuntimeNodeRegistry
import dev.astrolabe.runtime.core.RuntimeProviderFailure
import java.io.Closeable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** View inspection services and resources assembled for one Runtime process. */
public class AndroidViewInspectionComponent private constructor(
    /** Hierarchy provider registered with the Runtime request router. */
    public val hierarchyProvider: RuntimeHierarchyProvider,
    /** Node-detail provider sharing identifiers with hierarchy capture. */
    public val nodeDetailProvider: RuntimeNodeDetailProvider,
    /** Temporary attribute patch provider sharing identifiers with hierarchy capture. */
    public val attributePatchProvider: RuntimeAttributePatchProviding,
    /** Display provider shared with application information mapping. */
    public val displayEnvironmentProvider: AndroidDisplayEnvironmentProvider,
    private val mainThreadExecutor: AndroidMainThreadExecuting,
    private val rootProvider: AndroidWindowRootProvider
) : Closeable {
    private val closeLock = Any()
    private var closed = false

    override fun close() {
        synchronized(closeLock) {
            if (closed) {
                return
            }
            var closeFailure: Exception? = null
            try {
                attributePatchProvider.clearAttributePatches()
            } catch (error: Exception) {
                closeFailure = error
            }
            try {
                mainThreadExecutor.execute(rootProvider::close)
            } catch (error: Exception) {
                if (closeFailure == null) {
                    closeFailure = error
                }
            }
            closed = true
            closeFailure?.let { error -> throw error }
        }
    }

    public companion object {
        /** Creates process-scoped View inspection services for [targetIdentifier]. */
        public fun create(
            context: Context,
            targetIdentifier: RuntimeOpaqueIdentifier
        ): AndroidViewInspectionComponent {
            val mainThreadExecutor = AndroidMainThreadExecutor()
            val rootProvider = mainThreadExecutor.execute {
                AndroidWindowRootProviderFactory.create(context)
            }
            val displayEnvironmentProvider = AndroidDisplayEnvironmentProvider(context)
            val nodeRegistry = RuntimeNodeRegistry<View>()
            val textPrivacyPolicy = AndroidViewTextPrivacyPolicy()
            val attributeCollectorRegistry = AndroidViewAttributeCollectorRegistry(
                nodeRegistry = nodeRegistry,
                textPrivacyPolicy = textPrivacyPolicy
            )
            val attributePatchProvider = RuntimeAttributePatchService(
                AndroidViewAttributeMutator(
                    nodeRegistry = nodeRegistry,
                    mainThreadExecutor = mainThreadExecutor,
                    textPrivacyPolicy = textPrivacyPolicy
                )
            )
            val collector = AndroidViewHierarchyCollector(
                targetIdentifier = targetIdentifier,
                nodeRegistry = nodeRegistry,
                rootProvider = rootProvider,
                semanticMapper = AndroidViewSemanticMapper(
                    textPrivacyPolicy = textPrivacyPolicy,
                    attributeCollectorRegistry = attributeCollectorRegistry
                )
            )
            return AndroidViewInspectionComponent(
                hierarchyProvider = RuntimeHierarchyProvider { cancellationToken ->
                    try {
                        mainThreadExecutor.execute {
                            collector.capture(
                                environment = displayEnvironmentProvider.capture(),
                                cancellationToken = cancellationToken
                            )
                        }
                    } catch (error: AndroidHierarchyLimitExceededException) {
                        throw RuntimeProviderFailure(
                            RuntimeError(
                                code = RuntimeErrorCode("android.hierarchyLimitExceeded"),
                                message = error.message
                                    ?: "Android hierarchy exceeded a capture limit",
                                recoverySuggestion = "Reduce the active View hierarchy and retry",
                                details = buildJsonObject {
                                    put("kind", error.kind.wireValue)
                                    put("limit", error.limit)
                                }
                            )
                        )
                    }
                },
                nodeDetailProvider = AndroidViewNodeDetailProvider(
                    nodeRegistry = nodeRegistry,
                    mainThreadExecutor = mainThreadExecutor,
                    collectorRegistry = attributeCollectorRegistry
                ),
                attributePatchProvider = attributePatchProvider,
                displayEnvironmentProvider = displayEnvironmentProvider,
                mainThreadExecutor = mainThreadExecutor,
                rootProvider = rootProvider
            )
        }
    }
}
