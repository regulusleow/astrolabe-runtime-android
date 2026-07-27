# Releasing

`gradle.properties` is the Runtime version source of truth. Stable releases use
an exact `MAJOR.MINOR.PATCH` version and a matching Git tag.

## Prerequisites

1. Verify that the release is running from the public
   `regulusleow/astrolabe-runtime-android` repository. Do not tag or publish
   from a private development mirror.
2. Install JDK 17, Node.js 22, Android SDK, GnuPG, and `zip`.
3. Publish the exact `astrolabe-protocol-kotlin` dependency declared in the
   generated Runtime POM and verify that Maven Central serves it.
4. Verify the `io.github.regulusleow` namespace in Central Portal.
5. Create a GPG signing key, publish its public key to a supported key server,
   and keep the private key outside the repository.
6. Configure these GitHub Actions secrets:

| Secret | Value |
| --- | --- |
| `CENTRAL_TOKEN_USERNAME` | Central Portal user token name. |
| `CENTRAL_TOKEN_PASSWORD` | Central Portal user token password. |
| `MAVEN_GPG_PRIVATE_KEY_BASE64` | Base64-encoded private GPG key export. |
| `MAVEN_SIGNING_KEY_ID` | Signing key fingerprint or long key ID. |
| `MAVEN_SIGNING_PASSWORD` | Private key passphrase. |

Create a `maven-central` GitHub environment, store the release secrets in that
environment, restrict deployment to protected tags, and require maintainer
approval. The workflow also rejects tagged commits that are not reachable from
`main`. Release jobs use an isolated temporary GnuPG home and remove it even
when publication fails.

## Local Validation

Publish Protocol Kotlin to Maven Local, then create a signed Central-compatible
bundle without uploading it:

```bash
cd ../astrolabe-protocol
./gradlew :AstrolabeProtocolKotlin:publishToMavenLocal

cd ../astrolabe-runtime-android
npm ci
npm test

RELEASE_TAG=2.0.0 \
CENTRAL_BUNDLE_ONLY=true \
ORG_GRADLE_PROJECT_astrolabeUseMavenLocal=true \
MAVEN_SIGNING_KEY_ID=<key-id> \
MAVEN_SIGNING_PASSWORD=<passphrase> \
npm run release:central
```

The command writes
`build/central/astrolabe-runtime-android-<version>.zip`. The bundle contains the
AAR, POM, Gradle module metadata, source JAR, Javadoc JAR, detached GPG
signatures, and MD5/SHA-1 checksums in Maven repository layout.

## Publication

Push a tag equal to `astrolabeRuntimeVersion`. The Release workflow:

1. Tests the release tools.
2. Resolves Protocol from its public Maven Central coordinate and validates the
   final Fused publication without local dependency overrides.
3. Imports the signing key into an isolated temporary GnuPG home.
4. Builds and validates the Fused AAR and publication metadata.
5. Rejects Maven Local and composite-build dependency overrides.
6. Signs and packages the Central Portal bundle.
7. Uploads it with automatic publishing and waits for a terminal deployment
   state.
8. Removes the temporary GnuPG home on success or failure.

Formal publication resolves Protocol from Maven Central. Local dependency
overrides are intentionally limited to development and bundle validation.
