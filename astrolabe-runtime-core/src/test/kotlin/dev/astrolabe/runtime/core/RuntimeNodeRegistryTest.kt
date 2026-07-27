//
//  RuntimeNodeRegistryTest.kt
//  astrolabe-runtime-android
//
//  Created by 轩辕十四 on 2026/7/20.
//

package dev.astrolabe.runtime.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class RuntimeNodeRegistryTest {
    @Test
    fun registryKeepsStableIdentityAndResolvesTheOriginalObject() {
        val registry = RuntimeNodeRegistry<Any>()
        val first = Any()
        val second = Any()

        val firstIdentifier = registry.nodeID(first)
        val repeatedIdentifier = registry.nodeID(first)
        val secondIdentifier = registry.nodeID(second)

        assertEquals(firstIdentifier, repeatedIdentifier)
        assertNotEquals(firstIdentifier, secondIdentifier)
        assertSame(first, registry.objectFor(firstIdentifier))
        assertSame(second, registry.objectFor(secondIdentifier))
    }

    @Test
    fun registryPrunesReleasedObjectsWithoutReusingIdentifiers() {
        val references = mutableListOf<ControllableRuntimeNodeReference<Any>>()
        val registry = RuntimeNodeRegistry<Any> { value ->
            ControllableRuntimeNodeReference(value).also(references::add)
        }
        val released = Any()
        val releasedIdentifier = registry.nodeID(released)

        references.single().clear()
        registry.pruneReleasedObjects()
        val replacementIdentifier = registry.nodeID(Any())

        assertNull(registry.objectFor(releasedIdentifier))
        assertNotEquals(releasedIdentifier, replacementIdentifier)
    }
}

private class ControllableRuntimeNodeReference<T : Any>(value: T) : RuntimeNodeReference<T> {
    private var value: T? = value

    override fun get(): T? = value

    fun clear() {
        value = null
    }
}
