//
//  AndroidViewSemanticMapperTest.kt
//  astrolabe-runtime-android
//
//  Created by 轩辕十四 on 2026/7/20.
//

package dev.astrolabe.runtime.view

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidViewSemanticMapperTest {
    @Test
    fun backgroundColorPreservesTheDrawableAlphaOnce() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val view = View(instrumentation.targetContext).apply {
                background = ColorDrawable(Color.argb(128, 10, 20, 30))
            }

            val color = AndroidViewSemanticMapper().backgroundColor(view)

            assertEquals(128.0 / 255.0, color?.alpha ?: -1.0, 0.0001)
        }
    }
}
