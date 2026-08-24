//
//  AndroidViewTextPrivacyPolicy.kt
//  astrolabe-runtime-android
//
//  Created by 轩辕十四 on 2026/8/24.
//

package dev.astrolabe.runtime.view

import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.widget.TextView

/** Owns the single fail-closed policy for text exposed by Android View inspection. */
internal class AndroidViewTextPrivacyPolicy {
    fun exposedText(view: TextView): String? = if (isSensitive(view)) {
        null
    } else {
        nonempty(view.text)
    }

    fun isSensitive(view: TextView): Boolean {
        if (view.transformationMethod is PasswordTransformationMethod) {
            return true
        }
        val inputClass = view.inputType and InputType.TYPE_MASK_CLASS
        val variation = view.inputType and InputType.TYPE_MASK_VARIATION
        return when (inputClass) {
            InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }
}
