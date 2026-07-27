//
//  RuntimeResourceLifecycleTest.kt
//  astrolabe-runtime-android
//
//  Created by 轩辕十四 on 2026/7/20.
//

package dev.astrolabe.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeResourceLifecycleTest {
    @Test
    fun closeAttemptsInspectionCleanupWhenServerStopFails() {
        var inspectionClosed = false
        val serverFailure = IllegalStateException("server failure")

        val thrown = assertThrows(IllegalStateException::class.java) {
            closeRuntimeResources(
                stopServer = { throw serverFailure },
                closeInspection = { inspectionClosed = true }
            )
        }

        assertSame(serverFailure, thrown)
        assertTrue(inspectionClosed)
    }

    @Test
    fun closePreservesBothResourceFailures() {
        val serverFailure = IllegalStateException("server failure")
        val inspectionFailure = IllegalArgumentException("inspection failure")

        val thrown = assertThrows(IllegalStateException::class.java) {
            closeRuntimeResources(
                stopServer = { throw serverFailure },
                closeInspection = { throw inspectionFailure }
            )
        }

        assertSame(serverFailure, thrown)
        assertEquals(listOf(inspectionFailure), thrown.suppressed.toList())
    }
}
