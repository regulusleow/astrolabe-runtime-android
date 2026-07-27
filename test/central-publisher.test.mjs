import assert from "node:assert/strict";
import { mkdtemp, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";

import { publishCentralBundle } from "../scripts/central-publisher.mjs";

test("publishCentralBundle uploads with bearer authentication and waits for publication", async () => {
  const root = await mkdtemp(join(tmpdir(), "astrolabe-publisher-"));
  const bundlePath = join(root, "bundle.zip");
  await writeFile(bundlePath, "bundle");
  const requests = [];
  const responses = [
    new Response("deployment-id", { status: 201 }),
    Response.json({ deploymentState: "VALIDATING" }),
    Response.json({ deploymentState: "PUBLISHED" })
  ];
  const fetchImpl = async (url, options) => {
    requests.push({ url: String(url), options });
    return responses.shift();
  };

  const result = await publishCentralBundle({
    bundlePath,
    deploymentName: "Astrolabe Runtime Android 1.0.0",
    username: "token-user",
    password: "token-password",
    fetchImpl,
    sleep: async () => {},
    pollIntervalMilliseconds: 1,
    timeoutMilliseconds: 1_000
  });

  assert.equal(result.deploymentId, "deployment-id");
  assert.equal(result.state, "PUBLISHED");
  assert.equal(requests.length, 3);
  assert.equal(
    requests[0].options.headers.Authorization,
    `Bearer ${Buffer.from("token-user:token-password").toString("base64")}`
  );
  assert.match(requests[0].url, /publishingType=AUTOMATIC/);
  assert.match(requests[1].url, /\/api\/v1\/publisher\/status\?id=deployment-id/);
});

test("publishCentralBundle reports failed deployment validation", async () => {
  const root = await mkdtemp(join(tmpdir(), "astrolabe-publisher-"));
  const bundlePath = join(root, "bundle.zip");
  await writeFile(bundlePath, "bundle");
  const responses = [
    new Response("deployment-id", { status: 201 }),
    Response.json({
      deploymentState: "FAILED",
      errors: { signature: ["Missing signature"] }
    })
  ];

  await assert.rejects(
    publishCentralBundle({
      bundlePath,
      deploymentName: "Astrolabe Runtime Android 1.0.0",
      username: "token-user",
      password: "token-password",
      fetchImpl: async () => responses.shift(),
      sleep: async () => {}
    }),
    /Central deployment failed.*Missing signature/
  );
});

test("publishCentralBundle aborts a hung upload at the release deadline", async () => {
  const root = await mkdtemp(join(tmpdir(), "astrolabe-publisher-"));
  const bundlePath = join(root, "bundle.zip");
  await writeFile(bundlePath, "bundle");

  await assert.rejects(
    publishCentralBundle({
      bundlePath,
      deploymentName: "Astrolabe Runtime Android 1.0.0",
      username: "token-user",
      password: "token-password",
      fetchImpl: async (_url, options) => new Promise((_resolve, reject) => {
        options.signal.addEventListener("abort", () => {
          reject(new DOMException("Aborted", "AbortError"));
        });
      }),
      timeoutMilliseconds: 10
    }),
    /Central upload timed out/
  );
});

test("publishCentralBundle times out while reading a stalled upload response", async () => {
  const root = await mkdtemp(join(tmpdir(), "astrolabe-publisher-"));
  const bundlePath = join(root, "bundle.zip");
  await writeFile(bundlePath, "bundle");
  const stalledBody = new ReadableStream({
    start() {}
  });

  await assert.rejects(
    publishCentralBundle({
      bundlePath,
      deploymentName: "Astrolabe Runtime Android 1.0.0",
      username: "token-user",
      password: "token-password",
      fetchImpl: async () => new Response(stalledBody, { status: 201 }),
      timeoutMilliseconds: 10
    }),
    /Central upload timed out/
  );
});
