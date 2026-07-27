import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import {
  mkdtemp,
  mkdir,
  readFile,
  readdir,
  writeFile
} from "node:fs/promises";
import { tmpdir } from "node:os";
import { basename, join } from "node:path";
import test from "node:test";

import { prepareCentralBundle } from "../scripts/central-bundle.mjs";

const coordinate = {
  groupId: "io.github.regulusleow",
  artifactId: "astrolabe-runtime-android",
  version: "1.0.0"
};

test("prepareCentralBundle signs and checksums every publication file", async () => {
  const root = await mkdtemp(join(tmpdir(), "astrolabe-central-"));
  const repositoryRoot = join(root, "repository");
  const versionDirectory = join(
    repositoryRoot,
    "io/github/regulusleow/astrolabe-runtime-android/1.0.0"
  );
  await mkdir(versionDirectory, { recursive: true });
  const primaryFiles = [
    "astrolabe-runtime-android-1.0.0.aar",
    "astrolabe-runtime-android-1.0.0.pom",
    "astrolabe-runtime-android-1.0.0.module",
    "astrolabe-runtime-android-1.0.0-sources.jar",
    "astrolabe-runtime-android-1.0.0-javadoc.jar"
  ];
  for (const fileName of primaryFiles) {
    await writeFile(join(versionDirectory, fileName), `content:${fileName}`);
  }
  await writeFile(
    join(repositoryRoot, "io/github/regulusleow/astrolabe-runtime-android/maven-metadata.xml"),
    "must not be bundled"
  );

  const commands = [];
  const outputPath = join(root, "astrolabe-runtime-android-1.0.0.zip");
  const commandRunner = async (command, args, options) => {
    commands.push({ command, args, options });
    if (command === "jar") {
      return [
        "dev/astrolabe/runtime/AstrolabeRuntime.kt",
        "dev/astrolabe/runtime/core/RuntimeServer.kt",
        "dev/astrolabe/runtime/view/AndroidViewHierarchyCollector.kt"
      ].join("\n");
    } else if (command === "gpg") {
      const targetPath = args.at(-1);
      await writeFile(`${targetPath}.asc`, `signature:${basename(targetPath)}`);
    } else if (command === "zip") {
      await writeFile(outputPath, "zip");
    }
  };

  const result = await prepareCentralBundle({
    repositoryRoot,
    outputPath,
    ...coordinate,
    signingPassword: "signing-password",
    commandRunner
  });

  assert.equal(result.outputPath, outputPath);
  assert.equal(commands.filter(({ command }) => command === "gpg").length, 5);
  assert.equal(commands.filter(({ command }) => command === "zip").length, 1);
  const signingCommands = commands.filter(({ command }) => command === "gpg");
  assert.equal(
    signingCommands.every(({ args }) => args.includes("--passphrase-fd")),
    true
  );
  assert.equal(
    signingCommands.every(({ args }) => !args.includes("signing-password")),
    true
  );
  assert.equal(
    signingCommands.every(({ options }) => options.input === "signing-password\n"),
    true
  );

  const bundledDirectory = join(
    result.bundleRoot,
    "io/github/regulusleow/astrolabe-runtime-android/1.0.0"
  );
  const bundledFiles = (await readdir(bundledDirectory)).sort();
  assert.equal(bundledFiles.length, 20);
  assert.equal(
    bundledFiles.includes("astrolabe-runtime-android-1.0.0.aar.asc"),
    true
  );
  assert.equal(
    bundledFiles.includes("astrolabe-runtime-android-1.0.0.aar.md5"),
    true
  );
  assert.equal(
    bundledFiles.includes("astrolabe-runtime-android-1.0.0.aar.sha1"),
    true
  );

  const aarContents = await readFile(
    join(versionDirectory, "astrolabe-runtime-android-1.0.0.aar")
  );
  assert.equal(
    await readFile(
      join(bundledDirectory, "astrolabe-runtime-android-1.0.0.aar.sha1"),
      "utf8"
    ),
    createHash("sha1").update(aarContents).digest("hex")
  );
  assert.equal(
    commands.some(({ args }) => args.includes("maven-metadata.xml")),
    false
  );
});

test("prepareCentralBundle rejects incomplete publications", async () => {
  const root = await mkdtemp(join(tmpdir(), "astrolabe-central-"));
  const repositoryRoot = join(root, "repository");
  const versionDirectory = join(
    repositoryRoot,
    "io/github/regulusleow/astrolabe-runtime-android/1.0.0"
  );
  await mkdir(versionDirectory, { recursive: true });
  await writeFile(
    join(versionDirectory, "astrolabe-runtime-android-1.0.0.aar"),
    "aar"
  );

  await assert.rejects(
    prepareCentralBundle({
      repositoryRoot,
      outputPath: join(root, "bundle.zip"),
      ...coordinate,
      commandRunner: async () => {}
    }),
    /Publication is missing required files/
  );
});

test("prepareCentralBundle rejects an empty published source JAR", async () => {
  const root = await mkdtemp(join(tmpdir(), "astrolabe-central-"));
  const repositoryRoot = join(root, "repository");
  const versionDirectory = join(
    repositoryRoot,
    "io/github/regulusleow/astrolabe-runtime-android/1.0.0"
  );
  await mkdir(versionDirectory, { recursive: true });
  const prefix = "astrolabe-runtime-android-1.0.0";
  for (const suffix of [
    ".aar",
    ".pom",
    ".module",
    "-sources.jar",
    "-javadoc.jar"
  ]) {
    await writeFile(join(versionDirectory, `${prefix}${suffix}`), suffix);
  }

  await assert.rejects(
    prepareCentralBundle({
      repositoryRoot,
      outputPath: join(root, "bundle.zip"),
      ...coordinate,
      commandRunner: async (command) => command === "jar" ? "" : undefined
    }),
    /Published source JAR is missing Runtime sources/
  );
});
