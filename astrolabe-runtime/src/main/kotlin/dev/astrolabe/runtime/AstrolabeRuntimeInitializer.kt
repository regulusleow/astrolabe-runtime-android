//
//  AstrolabeRuntimeInitializer.kt
//  astrolabe-runtime-android
//
//  Created by 轩辕十四 on 2026/7/27.
//

package dev.astrolabe.runtime

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.util.Log

/** Starts Astrolabe Runtime before application components are created. */
internal class AstrolabeRuntimeInitializer : ContentProvider() {
    override fun onCreate(): Boolean {
        val applicationContext = context?.applicationContext
        if (applicationContext == null) {
            Log.e(logTag, "Astrolabe Runtime 自动启动失败：无法获取 Application Context")
            return false
        }

        return when (val result = AstrolabeRuntime.start(applicationContext)) {
            is AstrolabeRuntimeStartResult.Started,
            is AstrolabeRuntimeStartResult.AlreadyRunning -> true

            is AstrolabeRuntimeStartResult.Failed -> {
                Log.e(logTag, "Astrolabe Runtime 自动启动失败", result.error)
                false
            }
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    private companion object {
        const val logTag = "AstrolabeRuntime"
    }
}
