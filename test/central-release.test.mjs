import assert from "node:assert/strict";
import { mkdtemp, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";

import { runCentralRelease } from "../scripts/central-release.mjs";

test("runCentralRelease builds, bundles, and publishes the tagged version", async () => {
  const projectRoot = await mkdtemp(join(tmpdir(), "astrolabe-release-"));
  await writeFile(
    join(projectRoot, "gradle.properties"),
    "astrolabeRuntimeVersion=1.0.0\n"
  );
  const commands = [];
  const bundleRequests = [];
  const publishRequests = [];

  const result = await runCentralRelease({
    projectRoot,
    environment: {
      GITHUB_REF_NAME: "1.0.0",
      CENTRAL_TOKEN_USERNAME: "token-user",
      CENTRAL_TOKEN_PASSWORD: "token-password",
      MAVEN_SIGNING_KEY_ID: "signing-key-id",
      MAVEN_SIGNING_PASSWORD: "signing-password"
    },
    commandRunner: async (command, args, options) => {
      commands.push({ command, args, options });
    },
    bundleBuilder: async (request) => {
      bundleRequests.push(request);
      return { outputPath: join(projectRoot, "build/central/bundle.zip") };
    },
    publisher: async (request) => {
      publishRequests.push(request);
      return { deploymentId: "deployment-id", state: "PUBLISHED" };
    }
  });

  assert.equal(result.version, "1.0.0");
  assert.equal(result.deploymentId, "deployment-id");
  assert.deepEqual(commands[0].args, [
    "--no-configuration-cache",
    ":astrolabe-runtime-distribution:publishMavenPublicationToCentralStagingRepository",
    ":astrolabe-runtime-distribution:verifyDistributionArtifact"
  ]);
  assert.equal(bundleRequests[0].version, "1.0.0");
  assert.equal(bundleRequests[0].signingKeyId, "signing-key-id");
  assert.equal(bundleRequests[0].signingPassword, "signing-password");
  assert.equal(publishRequests[0].deploymentName, "Astrolabe Runtime Android 1.0.0");
});

test("runCentralRelease rejects a tag that does not match the repository version", async () => {
  const projectRoot = await mkdtemp(join(tmpdir(), "astrolabe-release-"));
  await writeFile(
    join(projectRoot, "gradle.properties"),
    "astrolabeRuntimeVersion=1.0.0\n"
  );

  await assert.rejects(
    runCentralRelease({
      projectRoot,
      environment: {
        GITHUB_REF_NAME: "1.0.1",
        CENTRAL_TOKEN_USERNAME: "token-user",
        CENTRAL_TOKEN_PASSWORD: "token-password"
      }
    }),
    /Release tag 1.0.1 does not match Runtime version 1.0.0/
  );
});

test("runCentralRelease supports local bundle validation without Portal credentials", async () => {
  const projectRoot = await mkdtemp(join(tmpdir(), "astrolabe-release-"));
  await writeFile(
    join(projectRoot, "gradle.properties"),
    "astrolabeRuntimeVersion=1.0.0\n"
  );
  let publishCalled = false;

  const result = await runCentralRelease({
    projectRoot,
    environment: {
      RELEASE_TAG: "1.0.0",
      CENTRAL_BUNDLE_ONLY: "true",
      ORG_GRADLE_PROJECT_astrolabeUseMavenLocal: "true"
    },
    commandRunner: async () => {},
    bundleBuilder: async () => ({
      outputPath: join(projectRoot, "build/central/bundle.zip")
    }),
    publisher: async () => {
      publishCalled = true;
    }
  });

  assert.equal(result.state, "BUNDLE_READY");
  assert.equal(publishCalled, false);
});

test("runCentralRelease rejects local dependency overrides during publication", async () => {
  const projectRoot = await mkdtemp(join(tmpdir(), "astrolabe-release-"));
  await writeFile(
    join(projectRoot, "gradle.properties"),
    "astrolabeRuntimeVersion=1.0.0\n"
  );

  await assert.rejects(
    runCentralRelease({
      projectRoot,
      environment: {
        GITHUB_REF_NAME: "1.0.0",
        CENTRAL_TOKEN_USERNAME: "token-user",
        CENTRAL_TOKEN_PASSWORD: "token-password",
        ORG_GRADLE_PROJECT_astrolabeUseMavenLocal: "true"
      }
    }),
    /Local dependency overrides are forbidden during Central publication/
  );
});

test("runCentralRelease requires an explicit signing key during publication", async () => {
  const projectRoot = await mkdtemp(join(tmpdir(), "astrolabe-release-"));
  await writeFile(
    join(projectRoot, "gradle.properties"),
    "astrolabeRuntimeVersion=1.0.0\n"
  );

  await assert.rejects(
    runCentralRelease({
      projectRoot,
      environment: {
        GITHUB_REF_NAME: "1.0.0",
        CENTRAL_TOKEN_USERNAME: "token-user",
        CENTRAL_TOKEN_PASSWORD: "token-password"
      }
    }),
    /MAVEN_SIGNING_KEY_ID is required/
  );
});
