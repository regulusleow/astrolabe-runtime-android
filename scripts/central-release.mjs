#!/usr/bin/env node

import { spawnSync } from "node:child_process";
import { readFile, rm } from "node:fs/promises";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

import { prepareCentralBundle } from "./central-bundle.mjs";
import { publishCentralBundle } from "./central-publisher.mjs";

const scriptPath = fileURLToPath(import.meta.url);
const defaultProjectRoot = resolve(dirname(scriptPath), "..");

export async function runCentralRelease({
  projectRoot = defaultProjectRoot,
  environment = process.env,
  commandRunner = runCommand,
  bundleBuilder = prepareCentralBundle,
  publisher = publishCentralBundle
} = {}) {
  const version = await repositoryVersion(projectRoot);
  const releaseTag = environment.GITHUB_REF_NAME ?? environment.RELEASE_TAG;
  if (!releaseTag) {
    throw new Error("GITHUB_REF_NAME or RELEASE_TAG is required");
  }
  if (releaseTag !== version) {
    throw new Error(
      `Release tag ${releaseTag} does not match Runtime version ${version}`
    );
  }
  const bundleOnly = environment.CENTRAL_BUNDLE_ONLY === "true";
  const localOverrideNames = [
    "ORG_GRADLE_PROJECT_astrolabeUseMavenLocal",
    "ORG_GRADLE_PROJECT_astrolabeProtocolPath"
  ];
  if (!bundleOnly && localOverrideNames.some((name) => environment[name])) {
    throw new Error(
      "Local dependency overrides are forbidden during Central publication"
    );
  }
  const username = environment.CENTRAL_TOKEN_USERNAME;
  const password = environment.CENTRAL_TOKEN_PASSWORD;
  const signingKeyId = environment.MAVEN_SIGNING_KEY_ID;
  if (!bundleOnly && (!username || !password)) {
    throw new Error(
      "CENTRAL_TOKEN_USERNAME and CENTRAL_TOKEN_PASSWORD are required"
    );
  }
  if (!bundleOnly && !signingKeyId) {
    throw new Error("MAVEN_SIGNING_KEY_ID is required");
  }

  const stagingRepository = join(projectRoot, "build", "central-staging");
  const centralOutputDirectory = join(projectRoot, "build", "central");
  const bundlePath = join(
    centralOutputDirectory,
    `astrolabe-runtime-android-${version}.zip`
  );
  await rm(stagingRepository, { force: true, recursive: true });
  await commandRunner(
    "./gradlew",
    [
      "--no-configuration-cache",
      ":astrolabe-runtime-distribution:publishMavenPublicationToCentralStagingRepository",
      ":astrolabe-runtime-distribution:verifyDistributionArtifact"
    ],
    { cwd: projectRoot }
  );

  const bundle = await bundleBuilder({
    repositoryRoot: stagingRepository,
    outputPath: bundlePath,
    groupId: "io.github.regulusleow",
    artifactId: "astrolabe-runtime-android",
    version,
    signingKeyId,
    signingPassword: environment.MAVEN_SIGNING_PASSWORD
  });
  if (bundleOnly) {
    return {
      version,
      bundlePath: bundle.outputPath,
      state: "BUNDLE_READY"
    };
  }
  const publication = await publisher({
    bundlePath: bundle.outputPath,
    deploymentName: `Astrolabe Runtime Android ${version}`,
    username,
    password
  });
  return {
    version,
    bundlePath: bundle.outputPath,
    deploymentId: publication.deploymentId,
    state: publication.state
  };
}

async function repositoryVersion(projectRoot) {
  const properties = await readFile(join(projectRoot, "gradle.properties"), "utf8");
  const versionLine = properties
    .split(/\r?\n/)
    .find((line) => line.startsWith("astrolabeRuntimeVersion="));
  const version = versionLine?.slice("astrolabeRuntimeVersion=".length).trim();
  if (!version || !/^\d+\.\d+\.\d+$/.test(version)) {
    throw new Error("gradle.properties does not contain a stable Runtime version");
  }
  return version;
}

async function runCommand(command, args, options) {
  const result = spawnSync(command, args, {
    cwd: options.cwd,
    encoding: "utf8",
    stdio: "inherit"
  });
  if (result.error || result.status !== 0) {
    throw new Error(
      `Command failed: ${command} ${args.join(" ")}: ` +
        (result.error?.message ?? `exit ${result.status}`)
    );
  }
}

if (resolve(process.argv[1] ?? "") === scriptPath) {
  try {
    const result = await runCentralRelease();
    process.stdout.write(`${JSON.stringify(result)}\n`);
  } catch (error) {
    process.stderr.write(
      `Release failed: ${error instanceof Error ? error.message : String(error)}\n`
    );
    process.exitCode = 1;
  }
}
