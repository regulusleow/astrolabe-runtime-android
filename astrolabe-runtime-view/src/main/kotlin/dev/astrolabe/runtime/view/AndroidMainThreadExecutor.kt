//
//  AndroidMainThreadExecutor.kt
//  astrolabe-runtime-android
//
//  Created by 轩辕十四 on 2026/7/20.
//

package dev.astrolabe.runtime.view

import android.os.Handler
import android.os.Looper
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask

/** Executes all Android UI access through one explicit main-thread boundary. */
internal interface AndroidMainThreadExecuting {
    fun <T> execute(operation: () -> T): T
}

internal class AndroidMainThreadExecutor(
    private val handler: Handler = Handler(Looper.getMainLooper())
) : AndroidMainThreadExecuting {
    override fun <T> execute(operation: () -> T): T {
        if (Looper.myLooper() === handler.looper) {
            return operation()
        }

        val task = FutureTask(operation)
        check(handler.post(task)) { "Android main thread rejected Runtime work" }
        return try {
            task.get()
        } catch (error: InterruptedException) {
            handler.removeCallbacks(task)
            task.cancel(false)
            Thread.currentThread().interrupt()
            throw error
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        }
    }
}
