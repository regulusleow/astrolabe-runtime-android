//
//  AndroidViewPatchCatalog.kt
//  astrolabe-runtime-android
//
//  Created by 轩辕十四 on 2026/7/22.
//

package dev.astrolabe.runtime.view

import dev.astrolabe.protocol.RuntimeAttributeIdentifier
import dev.astrolabe.protocol.RuntimeAttributeValue
import dev.astrolabe.protocol.RuntimeExtensionMap
import dev.astrolabe.protocol.RuntimePatchValueConstraints
import dev.astrolabe.protocol.RuntimePatchValueType
import dev.astrolabe.protocol.RuntimePatchableAttribute

internal object AndroidViewPatchCatalog {
    val text = RuntimeAttributeIdentifier("android.text.text")
    val fontSize = RuntimeAttributeIdentifier("android.text.fontSize")
    val textColor = RuntimeAttributeIdentifier("android.text.color")
    val alpha = RuntimeAttributeIdentifier("android.view.alpha")
    val visibility = RuntimeAttributeIdentifier("android.view.visibility")
    val backgroundColor = RuntimeAttributeIdentifier("android.view.backgroundColor")
    val imageScaleType = RuntimeAttributeIdentifier("android.image.scaleType")

    fun stringAttribute(
        identifier: RuntimeAttributeIdentifier,
        targetRoles: List<String>,
        allowedValues: List<String> = emptyList()
    ): RuntimePatchableAttribute = attribute(
        identifier = identifier,
        valueType = "string",
        targetRoles = targetRoles,
        constraints = allowedValues.takeIf(List<String>::isNotEmpty)?.let { values ->
            RuntimePatchValueConstraints(
                minimum = null,
                maximum = null,
                minimumExclusive = false,
                maximumExclusive = false,
                acceptedFormats = emptyList(),
                allowedValues = values.map(RuntimeAttributeValue::StringValue)
            )
        }
    )

    fun numberAttribute(
        identifier: RuntimeAttributeIdentifier,
        targetRoles: List<String>,
        minimum: Double?,
        maximum: Double?,
        minimumExclusive: Boolean = false
    ): RuntimePatchableAttribute = attribute(
        identifier = identifier,
        valueType = "number",
        targetRoles = targetRoles,
        constraints = RuntimePatchValueConstraints(
            minimum = minimum,
            maximum = maximum,
            minimumExclusive = minimumExclusive,
            maximumExclusive = false,
            acceptedFormats = emptyList(),
            allowedValues = emptyList()
        )
    )

    fun measurementAttribute(
        identifier: RuntimeAttributeIdentifier,
        targetRoles: List<String>,
        minimum: Double?,
        minimumExclusive: Boolean
    ): RuntimePatchableAttribute = attribute(
        identifier = identifier,
        valueType = "measurement",
        targetRoles = targetRoles,
        constraints = RuntimePatchValueConstraints(
            minimum = minimum,
            maximum = null,
            minimumExclusive = minimumExclusive,
            maximumExclusive = false,
            acceptedFormats = listOf("scaledLogical"),
            allowedValues = emptyList()
        )
    )

    fun colorAttribute(
        identifier: RuntimeAttributeIdentifier,
        targetRoles: List<String>
    ): RuntimePatchableAttribute = attribute(
        identifier = identifier,
        valueType = "color",
        targetRoles = targetRoles,
        constraints = null
    )

    private fun attribute(
        identifier: RuntimeAttributeIdentifier,
        valueType: String,
        targetRoles: List<String>,
        constraints: RuntimePatchValueConstraints?
    ): RuntimePatchableAttribute = RuntimePatchableAttribute(
        attributePattern = identifier.rawValue,
        valueType = RuntimePatchValueType(valueType),
        targetRoles = targetRoles,
        valueConstraints = constraints,
        extensions = RuntimeExtensionMap(emptyMap())
    )
}
