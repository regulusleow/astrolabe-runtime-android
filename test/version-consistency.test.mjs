import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

test("release metadata is consistent and Protocol uses the same major version", async () => {
  const [
    gradleProperties,
    versionCatalog,
    packageMetadata,
    readme,
    chineseReadme,
    changelog,
    releaseGuide
  ] = await Promise.all([
    readFile("gradle.properties", "utf8"),
    readFile("gradle/libs.versions.toml", "utf8"),
    readFile("package.json", "utf8").then(JSON.parse),
    readFile("README.md", "utf8"),
    readFile("README.zh-CN.md", "utf8"),
    readFile("CHANGELOG.md", "utf8"),
    readFile("docs/releasing.md", "utf8")
  ]);

  const runtimeVersion = requiredCapture(
    gradleProperties,
    /^astrolabeRuntimeVersion=(\d+\.\d+\.\d+)$/m,
    "Runtime version"
  );
  const protocolVersion = requiredCapture(
    versionCatalog,
    /^astrolabe-protocol = "(\d+\.\d+\.\d+)"$/m,
    "Protocol version"
  );

  assert.equal(packageMetadata.version, runtimeVersion);
  assert.equal(
    semverMajor(protocolVersion),
    semverMajor(runtimeVersion),
    "Runtime and Protocol must use the same major version"
  );
  assert.match(readme, new RegExp(`Current release: \\\`${runtimeVersion}\\\``));
  assert.match(
    readme,
    new RegExp(`astrolabe-runtime-android:${runtimeVersion}`)
  );
  assert.match(
    chineseReadme,
    new RegExp(`astrolabe-runtime-android:${runtimeVersion}`)
  );
  assert.match(changelog, new RegExp(`^## ${runtimeVersion}$`, "m"));
  assert.match(
    releaseGuide,
    new RegExp(`^RELEASE_TAG=${runtimeVersion} \\\\$`, "m")
  );
});

function requiredCapture(source, pattern, label) {
  const value = source.match(pattern)?.[1];
  assert.ok(value, `${label} is missing`);
  return value;
}

function semverMajor(version) {
  return version.split(".", 1)[0];
}
