//
//  AndroidViewHierarchyCollector.kt
//  astrolabe-runtime-android
//
//  Created by 轩辕十四 on 2026/7/20.
//

package dev.astrolabe.runtime.view

import android.graphics.RectF
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import dev.astrolabe.protocol.RuntimeExtensionMap
import dev.astrolabe.protocol.RuntimeHierarchySnapshotPayload
import dev.astrolabe.protocol.RuntimeNode
import dev.astrolabe.protocol.RuntimeOpaqueIdentifier
import dev.astrolabe.runtime.core.RuntimeCancellationToken
import dev.astrolabe.runtime.core.RuntimeNodeRegistry
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlinx.serialization.json.JsonPrimitive

/** Traversal limits applied to one hierarchy capture. */
internal data class AndroidHierarchyCaptureConfiguration(
    /** Maximum number of View nodes accepted in one capture. */
    val maximumNodeCount: Int = DEFAULT_MAXIMUM_NODE_COUNT,
    /** Maximum nested View depth accepted by recursive wire serialization. */
    val maximumDepth: Int = DEFAULT_MAXIMUM_DEPTH,
    /** Maximum main-thread traversal duration. */
    val maximumDurationMillis: Long = DEFAULT_MAXIMUM_DURATION_MILLIS
) {
    init {
        require(maximumNodeCount > 0) { "Maximum hierarchy node count must be greater than zero" }
        require(maximumDepth >= 0) { "Maximum hierarchy depth cannot be negative" }
        require(maximumDurationMillis > 0) {
            "Maximum hierarchy duration must be greater than zero"
        }
    }

    private companion object {
        const val DEFAULT_MAXIMUM_NODE_COUNT: Int = 10_000
        const val DEFAULT_MAXIMUM_DEPTH: Int = 256
        const val DEFAULT_MAXIMUM_DURATION_MILLIS: Long = 1_000L
    }
}

internal fun interface AndroidHierarchyClock {
    fun elapsedRealtimeNanos(): Long
}

/** Captures ordered View roots through an iterative depth-first traversal. */
internal class AndroidViewHierarchyCollector(
    private val targetIdentifier: RuntimeOpaqueIdentifier,
    private val nodeRegistry: RuntimeNodeRegistry<View>,
    private val rootProvider: AndroidWindowRootProvider,
    private val semanticMapper: AndroidViewSemanticMapper = AndroidViewSemanticMapper(),
    private val configuration: AndroidHierarchyCaptureConfiguration =
        AndroidHierarchyCaptureConfiguration(),
    private val clock: AndroidHierarchyClock = AndroidHierarchyClock(System::nanoTime)
) {
    fun capture(
        environment: AndroidDisplayEnvironmentSnapshot,
        cancellationToken: RuntimeCancellationToken
    ): RuntimeHierarchySnapshotPayload {
        check(Looper.myLooper() === Looper.getMainLooper()) {
            "Android hierarchy capture must execute on the main thread"
        }
        val capturedAtUnixTime = System.currentTimeMillis().toDouble() / MILLIS_PER_SECOND
        nodeRegistry.pruneReleasedObjects()
        val rootSnapshot = rootProvider.rootSnapshot()
        val budget = AndroidHierarchyCaptureBudget(configuration, clock)
        val geometryMapper = AndroidViewGeometryMapper(
            density = environment.density,
            viewport = environment.viewport
        )
        val viewportPixels = RectF(
            (environment.viewport.x * environment.density).toFloat(),
            (environment.viewport.y * environment.density).toFloat(),
            ((environment.viewport.x + environment.viewport.width) * environment.density).toFloat(),
            ((environment.viewport.y + environment.viewport.height) * environment.density).toFloat()
        )
        val roots = rootSnapshot.roots.mapIndexed { index, root ->
            captureSubtree(
                root = root,
                rootIndex = index,
                viewportInScreenPixels = viewportPixels,
                geometryMapper = geometryMapper,
                budget = budget,
                cancellationToken = cancellationToken
            )
        }
        return RuntimeHierarchySnapshotPayload(
            snapshotID = RuntimeOpaqueIdentifier("snapshot:android:${UUID.randomUUID()}"),
            capturedAtUnixTime = capturedAtUnixTime,
            targetIdentifier = targetIdentifier,
            orientation = environment.orientation,
            display = environment.display,
            viewport = environment.viewport,
            roots = roots,
            extensions = RuntimeExtensionMap(
                mapOf(
                    "android.rootCoverage" to JsonPrimitive(rootSnapshot.coverage.wireValue),
                    "android.nodeCount" to JsonPrimitive(budget.capturedNodeCount)
                )
            )
        )
    }

    private fun captureSubtree(
        root: View,
        rootIndex: Int,
        viewportInScreenPixels: RectF,
        geometryMapper: AndroidViewGeometryMapper,
        budget: AndroidHierarchyCaptureBudget,
        cancellationToken: RuntimeCancellationToken
    ): RuntimeNode {
        val stack = ArrayDeque<AndroidTraversalFrame>()
        stack.addLast(
            AndroidTraversalFrame(
                view = root,
                parentID = null,
                siblingIndex = rootIndex,
                depth = 0,
                ancestorHidden = false,
                ancestorOpacity = 1.0,
                ancestorClipInScreenPixels = viewportInScreenPixels
            )
        )
        while (stack.isNotEmpty()) {
            val frame = stack.last()
            if (frame.preparedNode == null) {
                budget.consumeNode(frame.depth, cancellationToken)
                val nodeID = nodeRegistry.nodeID(frame.view)
                frame.preparedNode = AndroidPreparedViewNode(
                    nodeID = nodeID,
                    mappedGeometry = geometryMapper.map(
                        view = frame.view,
                        isRoot = frame.parentID == null,
                        ancestorHidden = frame.ancestorHidden,
                        ancestorOpacity = frame.ancestorOpacity,
                        ancestorClipInScreenPixels = frame.ancestorClipInScreenPixels
                    )
                )
            }

            val preparedNode = frame.preparedNode
                ?: throw IllegalStateException("Runtime traversal node was not prepared")
            val viewGroup = frame.view as? ViewGroup
            if (viewGroup != null && frame.nextChildIndex < viewGroup.childCount) {
                val childIndex = frame.nextChildIndex
                frame.nextChildIndex += 1
                stack.addLast(
                    AndroidTraversalFrame(
                        view = viewGroup.getChildAt(childIndex),
                        parentID = preparedNode.nodeID,
                        siblingIndex = childIndex,
                        depth = frame.depth + 1,
                        ancestorHidden = preparedNode.mappedGeometry.descendantHidden,
                        ancestorOpacity = preparedNode.mappedGeometry.descendantOpacity,
                        ancestorClipInScreenPixels = preparedNode
                            .mappedGeometry
                            .descendantClipInScreenPixels
                    )
                )
                continue
            }

            val completedNode = RuntimeNode(
                nodeID = preparedNode.nodeID,
                parentID = frame.parentID,
                siblingIndex = frame.siblingIndex,
                role = semanticMapper.role(frame.view, frame.parentID == null),
                runtimeType = semanticMapper.runtimeType(frame.view),
                geometry = preparedNode.mappedGeometry.geometry,
                visibility = preparedNode.mappedGeometry.visibility,
                clipsContent = preparedNode.mappedGeometry.clipsContent,
                backgroundColor = semanticMapper.backgroundColor(frame.view),
                text = semanticMapper.textPreview(frame.view),
                accessibility = semanticMapper.accessibility(frame.view),
                interaction = semanticMapper.interaction(frame.view),
                availableDetailCategories = semanticMapper.detailCategories(frame.view),
                extensions = semanticMapper.extensions(frame.view),
                children = frame.children.toList()
            )
            stack.removeLast()
            val parentFrame = stack.lastOrNull()
            if (parentFrame == null) {
                return completedNode
            }
            parentFrame.children += completedNode
        }
        throw IllegalStateException("Runtime traversal completed without a root node")
    }

    private companion object {
        const val MILLIS_PER_SECOND: Double = 1_000.0
    }
}

private class AndroidHierarchyCaptureBudget(
    private val configuration: AndroidHierarchyCaptureConfiguration,
    private val clock: AndroidHierarchyClock
) {
    private val startedAtNanos = clock.elapsedRealtimeNanos()

    /** Number of nodes accepted by this capture budget. */
    var capturedNodeCount: Int = 0
        private set

    fun consumeNode(depth: Int, cancellationToken: RuntimeCancellationToken) {
        if (cancellationToken.isCancellationRequested()) {
            throw CancellationException("Android hierarchy capture was cancelled")
        }
        if (capturedNodeCount >= configuration.maximumNodeCount) {
            throw AndroidHierarchyLimitExceededException(
                kind = AndroidHierarchyLimitKind.nodeCount,
                limit = configuration.maximumNodeCount.toLong()
            )
        }
        if (depth > configuration.maximumDepth) {
            throw AndroidHierarchyLimitExceededException(
                kind = AndroidHierarchyLimitKind.depth,
                limit = configuration.maximumDepth.toLong()
            )
        }
        val elapsedNanos = clock.elapsedRealtimeNanos() - startedAtNanos
        if (elapsedNanos > configuration.maximumDurationMillis * NANOS_PER_MILLISECOND) {
            throw AndroidHierarchyLimitExceededException(
                kind = AndroidHierarchyLimitKind.durationMillis,
                limit = configuration.maximumDurationMillis
            )
        }
        capturedNodeCount += 1
    }

    private companion object {
        const val NANOS_PER_MILLISECOND: Long = 1_000_000L
    }
}

internal enum class AndroidHierarchyLimitKind(
    /** Stable diagnostic value sent to the Host. */
    val wireValue: String
) {
    nodeCount("nodeCount"),
    depth("depth"),
    durationMillis("durationMillis")
}

internal class AndroidHierarchyLimitExceededException(
    /** Limit category reached by the capture. */
    val kind: AndroidHierarchyLimitKind,
    /** Configured maximum for the reached category. */
    val limit: Long
) : IllegalStateException("Android hierarchy exceeded the configured ${kind.wireValue} limit")

private data class AndroidPreparedViewNode(
    /** Stable identifier assigned before child traversal. */
    val nodeID: RuntimeOpaqueIdentifier,
    /** Geometry and inherited state captured for this View. */
    val mappedGeometry: AndroidMappedViewGeometry
)

private data class AndroidTraversalFrame(
    /** View represented by this traversal frame. */
    val view: View,
    /** Parent identifier, or null for a root. */
    val parentID: RuntimeOpaqueIdentifier?,
    /** Ordered position in the parent or root collection. */
    val siblingIndex: Int,
    /** Zero-based depth below the root collection. */
    val depth: Int,
    /** Hidden state inherited from ancestors. */
    val ancestorHidden: Boolean,
    /** Effective opacity inherited from ancestors. */
    val ancestorOpacity: Double,
    /** Screen-pixel clipping inherited from ancestors. */
    val ancestorClipInScreenPixels: RectF?,
    /** Prepared node facts retained while children are traversed. */
    var preparedNode: AndroidPreparedViewNode? = null,
    /** Next child index to visit. */
    var nextChildIndex: Int = 0,
    /** Ordered child payloads completed for this View. */
    val children: MutableList<RuntimeNode> = mutableListOf()
)
