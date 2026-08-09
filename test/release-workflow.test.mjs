import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

test("release workflow publishes tagged versions through Central Portal", async () => {
  const workflow = await readFile(".github/workflows/release.yml", "utf8");

  assert.match(workflow, /tags:/);
  assert.match(workflow, /npm ci/);
  assert.match(workflow, /npm test/);
  assert.match(workflow, /MAVEN_GPG_PRIVATE_KEY_BASE64/);
  assert.match(workflow, /gpg --batch --import/);
  assert.match(workflow, /GNUPGHOME/);
  assert.match(workflow, /Verify public Protocol dependency/);
  assert.match(workflow, /Clean signing home/);
  assert.match(workflow, /test -n "\$\{GNUPGHOME:-\}"/);
  assert.match(workflow, /rm -rf -- "\$GNUPGHOME"/);
  assert.match(workflow, /CENTRAL_TOKEN_USERNAME/);
  assert.match(workflow, /CENTRAL_TOKEN_PASSWORD/);
  assert.match(workflow, /npm run release:central/);
  assert.match(workflow, /environment: maven-central/);
  assert.match(workflow, /git merge-base --is-ancestor/);
  assert.doesNotMatch(
    workflow,
    /uses:\s+[^@\s]+@v\d+/
  );
  assert.doesNotMatch(workflow, /astrolabeUseMavenLocal/);
  assert.doesNotMatch(workflow, /astrolabeProtocolPath/);
});

test("CI validates release tools and the final fused publication", async () => {
  const workflow = await readFile(".github/workflows/ci.yml", "utf8");

  assert.match(workflow, /npm ci/);
  assert.match(workflow, /npm test/);
  assert.match(
    workflow,
    /:AstrolabeProtocolKotlin:publishToMavenLocal/
  );
  assert.match(
    workflow,
    /ASTROLABE_PROTOCOL_VERSION: "\d+\.\d+\.\d+"/
  );
  assert.match(
    workflow,
    /ref: \$\{\{ env\.ASTROLABE_PROTOCOL_VERSION \}\}/
  );
  assert.doesNotMatch(workflow, /-PastrolabeVersion=/);
  assert.match(workflow, /astrolabeUseMavenLocal/);
  assert.match(
    workflow,
    /:astrolabe-runtime-distribution:verifyDistributionArtifact/
  );
  assert.doesNotMatch(
    workflow,
    /uses:\s+[^@\s]+@v\d+/
  );
  assert.doesNotMatch(workflow, /astrolabeProtocolPath/);
});
