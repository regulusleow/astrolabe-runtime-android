import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import {
  copyFile,
  mkdir,
  readFile,
  readdir,
  rm,
  stat,
  writeFile
} from "node:fs/promises";
import { dirname, join, resolve } from "node:path";

const checksumAlgorithms = ["md5", "sha1"];

export async function prepareCentralBundle({
  repositoryRoot,
  outputPath,
  groupId,
  artifactId,
  version,
  signingKeyId,
  signingPassword,
  commandRunner = runCommand
}) {
  validateCoordinate(groupId, artifactId, version);
  const absoluteRepositoryRoot = resolve(repositoryRoot);
  const absoluteOutputPath = resolve(outputPath);
  const relativeVersionPath = join(
    ...groupId.split("."),
    artifactId,
    version
  );
  const versionDirectory = join(absoluteRepositoryRoot, relativeVersionPath);
  const requiredFileNames = requiredPublicationFileNames(artifactId, version);
  const availableFiles = new Set(await readdir(versionDirectory));
  const missingFiles = requiredFileNames.filter((fileName) => !availableFiles.has(fileName));
  if (missingFiles.length > 0) {
    throw new Error(
      `Publication is missing required files: ${missingFiles.join(", ")}`
    );
  }
  const sourceJarPath = join(
    versionDirectory,
    `${artifactId}-${version}-sources.jar`
  );
  const sourceListing = await commandRunner(
    "jar",
    ["tf", sourceJarPath],
    { cwd: absoluteRepositoryRoot }
  );
  const publishedSources = new Set(
    (sourceListing ?? "")
      .split(/\r?\n/)
      .filter(Boolean)
  );
  const missingSources = requiredRuntimeSources.filter(
    (sourcePath) => !publishedSources.has(sourcePath)
  );
  if (missingSources.length > 0) {
    throw new Error(
      `Published source JAR is missing Runtime sources: ${missingSources.join(", ")}`
    );
  }

  const bundleRoot = join(dirname(absoluteOutputPath), "central-bundle-staging");
  const bundledVersionDirectory = join(bundleRoot, relativeVersionPath);
  await rm(bundleRoot, { force: true, recursive: true });
  await rm(absoluteOutputPath, { force: true });
  await mkdir(bundledVersionDirectory, { recursive: true });

  for (const fileName of requiredFileNames) {
    const sourcePath = join(versionDirectory, fileName);
    const signaturePath = `${sourcePath}.asc`;
    await rm(signaturePath, { force: true });
    const signingArguments = ["--batch", "--yes", "--armor", "--detach-sign"];
    if (signingKeyId) {
      signingArguments.push("--local-user", signingKeyId);
    }
    const commandOptions = {
      cwd: absoluteRepositoryRoot
    };
    if (signingPassword) {
      signingArguments.push(
        "--pinentry-mode",
        "loopback",
        "--passphrase-fd",
        "0"
      );
      commandOptions.input = `${signingPassword}\n`;
    }
    signingArguments.push(sourcePath);
    await commandRunner("gpg", signingArguments, commandOptions);
    await requireFile(signaturePath, `GPG did not create ${signaturePath}`);

    const contents = await readFile(sourcePath);
    const generatedPaths = [sourcePath, signaturePath];
    for (const algorithm of checksumAlgorithms) {
      const checksumPath = `${sourcePath}.${algorithm}`;
      await writeFile(
        checksumPath,
        createHash(algorithm).update(contents).digest("hex")
      );
      generatedPaths.push(checksumPath);
    }
    for (const generatedPath of generatedPaths) {
      await copyFile(
        generatedPath,
        join(bundledVersionDirectory, generatedPath.slice(versionDirectory.length + 1))
      );
    }
  }

  await commandRunner(
    "zip",
    ["-X", "-q", "-r", absoluteOutputPath, "."],
    { cwd: bundleRoot }
  );
  await requireFile(absoluteOutputPath, `ZIP did not create ${absoluteOutputPath}`);
  return {
    bundleRoot,
    outputPath: absoluteOutputPath
  };
}

function requiredPublicationFileNames(artifactId, version) {
  const prefix = `${artifactId}-${version}`;
  return [
    `${prefix}.aar`,
    `${prefix}.pom`,
    `${prefix}.module`,
    `${prefix}-sources.jar`,
    `${prefix}-javadoc.jar`
  ];
}

function validateCoordinate(groupId, artifactId, version) {
  const groupPattern = /^[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+$/;
  const identifierPattern = /^[A-Za-z0-9_.-]+$/;
  if (!groupPattern.test(groupId)) {
    throw new Error(`Invalid Maven group ID: ${groupId}`);
  }
  if (!identifierPattern.test(artifactId)) {
    throw new Error(`Invalid Maven artifact ID: ${artifactId}`);
  }
  if (!identifierPattern.test(version)) {
    throw new Error(`Invalid Maven version: ${version}`);
  }
}

async function requireFile(path, message) {
  const metadata = await stat(path).catch(() => null);
  if (!metadata?.isFile()) {
    throw new Error(message);
  }
}

async function runCommand(command, args, options) {
  const result = spawnSync(command, args, {
    cwd: options.cwd,
    encoding: "utf8",
    input: options.input,
    stdio: [options.input ? "pipe" : "ignore", "pipe", "pipe"]
  });
  if (result.error || result.status !== 0) {
    const detail = result.stderr?.trim() || result.error?.message || "Unknown error";
    throw new Error(`Command failed: ${command} ${args.join(" ")}\n${detail}`);
  }
  return result.stdout;
}

const requiredRuntimeSources = [
  "dev/astrolabe/runtime/AstrolabeRuntime.kt",
  "dev/astrolabe/runtime/core/RuntimeServer.kt",
  "dev/astrolabe/runtime/view/AndroidViewHierarchyCollector.kt"
];
