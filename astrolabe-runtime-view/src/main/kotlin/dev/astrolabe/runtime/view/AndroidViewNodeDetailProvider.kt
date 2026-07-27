//
//  AndroidViewNodeDetailProvider.kt
//  astrolabe-runtime-android
//
//  Created by 轩辕十四 on 2026/7/22.
//

package dev.astrolabe.runtime.view

import android.view.View
import dev.astrolabe.protocol.RuntimeError
import dev.astrolabe.protocol.RuntimeErrorCode
import dev.astrolabe.protocol.RuntimeNodeDetailPayload
import dev.astrolabe.protocol.RuntimeOpaqueIdentifier
import dev.astrolabe.runtime.core.RuntimeCancellationToken
import dev.astrolabe.runtime.core.RuntimeNodeDetailProvider
import dev.astrolabe.runtime.core.RuntimeNodeRegistry
import dev.astrolabe.runtime.core.RuntimeProviderFailure
import java.util.concurrent.CancellationException

/** Resolves hierarchy node identifiers and collects View details on the main thread. */
internal class AndroidViewNodeDetailProvider(
    private val nodeRegistry: RuntimeNodeRegistry<View>,
    private val mainThreadExecutor: AndroidMainThreadExecuting,
    private val collectorRegistry: AndroidViewAttributeCollectorRegistry =
        AndroidViewAttributeCollectorRegistry()
) : RuntimeNodeDetailProvider {
    override fun nodeDetail(
        nodeID: RuntimeOpaqueIdentifier,
        cancellationToken: RuntimeCancellationToken
    ): RuntimeNodeDetailPayload = mainThreadExecutor.execute {
        if (cancellationToken.isCancellationRequested()) {
            throw CancellationException("Android node detail request was cancelled")
        }
        val view = nodeRegistry.objectFor(nodeID) ?: throw RuntimeProviderFailure(
            RuntimeError(
                code = RuntimeErrorCode.nodeNotFound,
                message = "The requested Android View is no longer available",
                recoverySuggestion = "Capture a new hierarchy and retry with its node ID"
            )
        )
        RuntimeNodeDetailPayload(
            nodeID = nodeID,
            sections = collectorRegistry.sections(view)
        )
    }
}
