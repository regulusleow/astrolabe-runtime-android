//
//  AndroidViewNodeDetailEncodingTest.kt
//  astrolabe-runtime-android
//
//  Created by 轩辕十四 on 2026/7/22.
//

package dev.astrolabe.runtime.view

import android.widget.Switch
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.astrolabe.protocol.RuntimeMessageCodec
import dev.astrolabe.protocol.RuntimeNodeDetailPayload
import dev.astrolabe.runtime.core.RuntimeCancellationToken
import dev.astrolabe.runtime.core.RuntimeNodeRegistry
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidViewNodeDetailEncodingTest {
    @Test
    fun switchDetailSatisfiesTheWireContract() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val nodeRegistry = RuntimeNodeRegistry<android.view.View>()
            val view = Switch(instrumentation.targetContext).apply {
                text = "Live inspection"
                layout(0, 0, 320, 144)
            }
            val payload = AndroidViewNodeDetailProvider(
                nodeRegistry = nodeRegistry,
                mainThreadExecutor = AndroidMainThreadExecutor()
            ).nodeDetail(
                nodeID = nodeRegistry.nodeID(view),
                cancellationToken = RuntimeCancellationToken { false }
            )

            RuntimeMessageCodec().encodeValue(
                payload,
                RuntimeNodeDetailPayload.contract.serializer
            )
        }
    }
}
