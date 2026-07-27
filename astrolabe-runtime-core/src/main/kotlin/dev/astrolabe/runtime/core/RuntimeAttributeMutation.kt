//
//  RuntimeAttributeMutation.kt
//  astrolabe-runtime-android
//
//  Created by 轩辕十四 on 2026/7/22.
//

package dev.astrolabe.runtime.core

import dev.astrolabe.protocol.RuntimeAttributeIdentifier
import dev.astrolabe.protocol.RuntimeAttributeValue
import dev.astrolabe.protocol.RuntimeOpaqueIdentifier
import dev.astrolabe.protocol.RuntimePatchableAttributesPayload

/** Identifies platform state that must be rewound and replayed as one unit. */
public data class RuntimeAttributeMutationDomain(
    /** Stable node identifier owning the mutable state. */
    public val nodeID: RuntimeOpaqueIdentifier,
    /** Platform-owned key grouping related mutations. */
    public val domainIdentifier: String
) {
    init {
        require(domainIdentifier.isNotEmpty()) { "Mutation domain identifier cannot be empty" }
    }
}

/** Describes the state domain and visible effects controlled by one attribute. */
public data class RuntimeAttributeMutationDescriptor(
    /** State domain that must be replayed atomically. */
    public val domain: RuntimeAttributeMutationDomain,
    /** Logical presentation effects controlled by this attribute. */
    public val effectIdentifiers: Set<String>
) {
    init {
        require(effectIdentifiers.isNotEmpty()) { "Mutation effects cannot be empty" }
        require(effectIdentifiers.all(String::isNotEmpty)) { "Mutation effects cannot contain empty identifiers" }
    }
}

/** One applied mutation and the Memento required to restore its previous value. */
public class RuntimeAttributeMutation(
    /** Value observed immediately before the mutation. */
    public val originalValue: RuntimeAttributeValue?,
    /** Value read back immediately after the mutation. */
    public val actualValue: RuntimeAttributeValue?,
    /** Requested value normalized into the readback representation when needed. */
    public val comparisonValue: RuntimeAttributeValue? = null,
    /** Restores the captured value and remains safe to invoke during repeated transaction recovery. */
    public val restore: () -> RuntimeAttributeValue?
)

/** Platform adapter responsible for validating, applying, reading, and restoring attributes. */
public interface RuntimeAttributeMutating {
    /** Runtime-owned catalog exposed to the Host before mutation. */
    public val patchableAttributeCatalog: RuntimePatchableAttributesPayload

    /** Platform-specific recovery guidance used when readback validation fails. */
    public val invalidValueRecoverySuggestion: String?
        get() = null

    /** Resolves the state domain and visible effects for one attribute. */
    public fun mutationDescriptor(
        nodeID: RuntimeOpaqueIdentifier,
        attributeIdentifier: RuntimeAttributeIdentifier
    ): RuntimeAttributeMutationDescriptor

    /** Applies one typed value and returns its restoration Memento. */
    public fun apply(
        nodeID: RuntimeOpaqueIdentifier,
        attributeIdentifier: RuntimeAttributeIdentifier,
        value: RuntimeAttributeValue
    ): RuntimeAttributeMutation
}
