//
//  AndroidWindowRootProvider.kt
//  astrolabe-runtime-android
//
//  Created by 轩辕十四 on 2026/7/20.
//

package dev.astrolabe.runtime.view

import android.annotation.TargetApi
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.inspector.WindowInspector
import java.io.Closeable
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.IdentityHashMap

/** Root enumeration coverage available on the current Android version. */
internal enum class AndroidWindowRootCoverage(
    /** Stable wire value describing this coverage level. */
    val wireValue: String
) {
    global("global"),
    activityOnly("activityOnly")
}

/** Ordered roots and the coverage level used to discover them. */
internal data class AndroidWindowRootSnapshot(
    /** Current process roots in the order returned by the active provider. */
    val roots: List<View>,
    /** Root discovery coverage available for this snapshot. */
    val coverage: AndroidWindowRootCoverage
)

/** Enumerates inspectable roots without exposing discovery details to the collector. */
internal interface AndroidWindowRootProvider : Closeable {
    fun rootSnapshot(): AndroidWindowRootSnapshot
}

internal object AndroidWindowRootProviderFactory {
    fun create(context: Context): AndroidWindowRootProvider =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            GlobalAndroidWindowRootProvider()
        } else {
            ActivityAndroidWindowRootProvider(
                application = context.applicationContext as? Application
                    ?: throw IllegalStateException("Android Application context is unavailable"),
                initialActivity = context.findActivity()
            )
        }
}

@TargetApi(Build.VERSION_CODES.Q)
private class GlobalAndroidWindowRootProvider : AndroidWindowRootProvider {
    override fun rootSnapshot(): AndroidWindowRootSnapshot = AndroidWindowRootSnapshot(
        roots = identityDistinct(WindowInspector.getGlobalWindowViews())
            .filter(View::isAttachedToWindow),
        coverage = AndroidWindowRootCoverage.global
    )

    override fun close() = Unit
}

private class ActivityAndroidWindowRootProvider(
    private val application: Application,
    initialActivity: Activity?
) : AndroidWindowRootProvider, Application.ActivityLifecycleCallbacks {
    private val activities = mutableListOf<WeakReference<Activity>>()

    init {
        initialActivity?.let(::track)
        application.registerActivityLifecycleCallbacks(this)
    }

    override fun rootSnapshot(): AndroidWindowRootSnapshot {
        pruneActivities()
        return AndroidWindowRootSnapshot(
            roots = identityDistinct(
                activities.mapNotNull(WeakReference<Activity>::get)
                    .map { activity -> activity.window.decorView }
                    .filter(View::isAttachedToWindow)
            ),
            coverage = AndroidWindowRootCoverage.activityOnly
        )
    }

    override fun close() {
        application.unregisterActivityLifecycleCallbacks(this)
        activities.clear()
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        track(activity)
    }

    override fun onActivityDestroyed(activity: Activity) {
        activities.removeAll { reference ->
            val trackedActivity = reference.get()
            trackedActivity == null || trackedActivity === activity
        }
    }

    override fun onActivityStarted(activity: Activity) = Unit

    override fun onActivityResumed(activity: Activity) = Unit

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    private fun track(activity: Activity) {
        pruneActivities()
        if (activities.none { reference -> reference.get() === activity }) {
            activities += WeakReference(activity)
        }
    }

    private fun pruneActivities() {
        activities.removeAll { reference -> reference.get() == null }
    }
}

private fun Context.findActivity(): Activity? {
    var currentContext: Context? = this
    val visitedContexts = Collections.newSetFromMap(IdentityHashMap<Context, Boolean>())
    while (currentContext != null && visitedContexts.add(currentContext)) {
        if (currentContext is Activity) {
            return currentContext
        }
        currentContext = (currentContext as? ContextWrapper)?.baseContext
    }
    return null
}

private fun identityDistinct(views: List<View>): List<View> {
    val seen = Collections.newSetFromMap(IdentityHashMap<View, Boolean>())
    return views.filter(seen::add)
}
