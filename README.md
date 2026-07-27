# Astrolabe Runtime for Android

English | [简体中文](README.zh-CN.md)

Astrolabe Runtime for Android exposes Android View hierarchy and presentation
data to the Astrolabe Host during development. Applications integrate one
debug-only dependency; the Runtime starts automatically and remains absent from
Release builds.

Current release: `2.0.0`.

## Requirements

- Android API 23 or later
- An Android View-based interface
- Astrolabe Host tools

Jetpack Compose inspection is not supported yet.

## Installation

Add Maven Central to dependency resolution, then add the Runtime only to the
debug variant:

```kotlin
dependencies {
    debugImplementation(
        "io.github.regulusleow:astrolabe-runtime-android:2.0.0"
    )
}
```

No Application, Activity, View, manifest, startup, or registration code is
required. Do not use `implementation`, because that would package the Runtime
in Release builds.

## Capabilities

- Emulator and USB device Runtime discovery through ADB.
- Android View hierarchy collection with stable process-scoped node IDs.
- Logical screen geometry, visibility, accessibility, text, typography,
  colors, images, control state, scrolling, and resource metadata.
- On-demand node-detail lookup.
- Allowlisted, in-memory presentation patches with replacement and rollback.
- Host-side screenshots, frozen snapshots, Baseline comparison, and Visual
  Diff workflows.

Temporary patches never modify source code, application models, binaries, or
persistent storage. They disappear when the Runtime stops or the App process
exits.

## Architecture

| Module | Responsibility |
| --- | --- |
| `astrolabe-runtime-core` | Framing, transport, sessions, request routing, cancellation, node registry, and patch coordination. |
| `astrolabe-runtime-view` | Android View roots, traversal, geometry, semantic attributes, node details, and allowlisted mutations. |
| `astrolabe-runtime` | Public lifecycle facade, application metadata, and composition root. |
| `astrolabe-runtime-distribution` | AGP Fused Library that publishes the internal modules as one AAR. |

The Runtime implements the platform-neutral Wire Protocol from
`astrolabe-protocol`. Android-specific lifecycle, collection, mapping, local
socket transport, and patch execution stay in this repository.

Only the `dev.astrolabe.runtime.AstrolabeRuntime` facade is supported as public
source API. Core and View packages are fused into the AAR as implementation
details and are not compatibility commitments.

## Development

Publish Protocol Kotlin to Maven Local, then run the Runtime checks:

```bash
cd ../astrolabe-protocol
./gradlew :AstrolabeProtocolKotlin:publishToMavenLocal

cd ../astrolabe-runtime-android
./gradlew \
  -PastrolabeUseMavenLocal=true \
  test \
  lint \
  verifyModuleBoundaries \
  :astrolabe-runtime-distribution:verifyDistributionArtifact
```

Instrumentation tests require an emulator or physical device:

```bash
./gradlew \
  -PastrolabeUseMavenLocal=true \
  :astrolabe-runtime-view:connectedDebugAndroidTest
```

Release maintainers should follow [docs/releasing.md](docs/releasing.md).

## Security

The Runtime exposes a process-scoped abstract local socket reachable through
ADB forwarding. It does not provide arbitrary method invocation or business
actions. Temporary mutation is restricted to the advertised patch catalog.

Use `debugImplementation` and verify the Release dependency graph before
shipping.

## License

Astrolabe Runtime for Android is available under the
[Apache License 2.0](LICENSE).
