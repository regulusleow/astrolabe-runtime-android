//
//  RuntimeNodeRegistry.kt
//  astrolabe-runtime-android
//
//  Created by 轩辕十四 on 2026/7/20.
//

package dev.astrolabe.runtime.core

import dev.astrolabe.protocol.RuntimeOpaqueIdentifier
import java.lang.ref.WeakReference

/** Maintains process-scoped stable identifiers without retaining inspected objects. */
public class RuntimeNodeRegistry<T : Any> {
    private val lock = Any()
    private val referenceFactory: RuntimeNodeReferenceFactory<T>
    private val entriesByIdentityHash = mutableMapOf<Int, MutableList<RuntimeNodeEntry<T>>>()
    private val entriesByNodeID = mutableMapOf<RuntimeOpaqueIdentifier, RuntimeNodeEntry<T>>()
    private var nextIdentifier: Long = 1L

    public constructor() : this(
        RuntimeNodeReferenceFactory { value -> JavaRuntimeNodeReference(value) }
    )

    internal constructor(referenceFactory: RuntimeNodeReferenceFactory<T>) {
        this.referenceFactory = referenceFactory
    }

    /** Returns the stable identifier assigned to [value] in this registry. */
    public fun nodeID(value: T): RuntimeOpaqueIdentifier = synchronized(lock) {
        val identityHash = System.identityHashCode(value)
        entriesByIdentityHash[identityHash]
            ?.firstOrNull { entry -> entry.reference.get() === value }
            ?.let { entry -> return@synchronized entry.nodeID }

        check(nextIdentifier < Long.MAX_VALUE) { "Runtime node identifier space is exhausted" }
        val nodeID = RuntimeOpaqueIdentifier(nextIdentifier.toString())
        nextIdentifier += 1L
        val entry = RuntimeNodeEntry(
            identityHash = identityHash,
            nodeID = nodeID,
            reference = referenceFactory.create(value)
        )
        entriesByIdentityHash.getOrPut(identityHash, ::mutableListOf).add(entry)
        entriesByNodeID[nodeID] = entry
        nodeID
    }

    /** Resolves [nodeID], or returns null after the object has been released. */
    public fun objectFor(nodeID: RuntimeOpaqueIdentifier): T? = synchronized(lock) {
        val entry = entriesByNodeID[nodeID] ?: return@synchronized null
        entry.reference.get() ?: run {
            removeEntry(entry)
            null
        }
    }

    /** Removes entries whose inspected objects have been released. */
    public fun pruneReleasedObjects() {
        synchronized(lock) {
            entriesByNodeID.values
                .filter { entry -> entry.reference.get() == null }
                .toList()
                .forEach(::removeEntry)
        }
    }

    private fun removeEntry(entry: RuntimeNodeEntry<T>) {
        entriesByNodeID.remove(entry.nodeID)
        entriesByIdentityHash[entry.identityHash]?.let { entries ->
            entries.remove(entry)
            if (entries.isEmpty()) {
                entriesByIdentityHash.remove(entry.identityHash)
            }
        }
    }
}

internal fun interface RuntimeNodeReference<T : Any> {
    fun get(): T?
}

internal fun interface RuntimeNodeReferenceFactory<T : Any> {
    fun create(value: T): RuntimeNodeReference<T>
}

private class JavaRuntimeNodeReference<T : Any>(value: T) : RuntimeNodeReference<T> {
    private val reference = WeakReference(value)

    override fun get(): T? = reference.get()
}

private data class RuntimeNodeEntry<T : Any>(
    /** Identity hash used to narrow identity comparisons. */
    val identityHash: Int,
    /** Stable wire identifier assigned to the referenced object. */
    val nodeID: RuntimeOpaqueIdentifier,
    /** Weak object reference owned by this entry. */
    val reference: RuntimeNodeReference<T>
)
