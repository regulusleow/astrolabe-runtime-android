import { readFile } from "node:fs/promises";
import { basename } from "node:path";

const centralBaseURL = "https://central.sonatype.com";
const terminalStates = new Set(["PUBLISHED", "FAILED"]);

export async function publishCentralBundle({
  bundlePath,
  deploymentName,
  username,
  password,
  fetchImpl = fetch,
  sleep = delay,
  pollIntervalMilliseconds = 5_000,
  timeoutMilliseconds = 60 * 60 * 1_000,
  now = Date.now
}) {
  if (!username || !password) {
    throw new Error("Central Portal token username and password are required");
  }
  if (!deploymentName?.trim()) {
    throw new Error("Central deployment name is required");
  }

  const deadline = now() + timeoutMilliseconds;
  const authorization = `Bearer ${Buffer.from(`${username}:${password}`).toString("base64")}`;
  const bundleContents = await readFile(bundlePath);
  const form = new FormData();
  form.append(
    "bundle",
    new Blob([bundleContents], { type: "application/octet-stream" }),
    basename(bundlePath)
  );
  const uploadURL = new URL("/api/v1/publisher/upload", centralBaseURL);
  uploadURL.searchParams.set("name", deploymentName);
  uploadURL.searchParams.set("publishingType", "AUTOMATIC");
  const deploymentId = await requestBeforeDeadline({
    fetchImpl,
    url: uploadURL,
    options: {
      method: "POST",
      headers: { Authorization: authorization },
      body: form
    },
    deadline,
    now,
    timeoutMessage: `Central upload timed out after ${timeoutMilliseconds} ms`,
    readResponse: async (response) => {
      if (!response?.ok) {
        throw new Error(
          `Central upload failed (${response?.status ?? "unknown"}): ` +
            await responseBody(response)
        );
      }
      return (await response.text()).trim();
    }
  });
  if (!deploymentId) {
    throw new Error("Central upload returned an empty deployment ID");
  }

  while (now() <= deadline) {
    const statusURL = new URL("/api/v1/publisher/status", centralBaseURL);
    statusURL.searchParams.set("id", deploymentId);
    const status = await requestBeforeDeadline({
      fetchImpl,
      url: statusURL,
      options: {
        method: "POST",
        headers: { Authorization: authorization }
      },
      deadline,
      now,
      timeoutMessage:
        `Central status request timed out after ${timeoutMilliseconds} ms: ${deploymentId}`,
      readResponse: async (response) => {
        if (!response?.ok) {
          throw new Error(
            `Central status request failed (${response?.status ?? "unknown"}): ` +
              await responseBody(response)
          );
        }
        return response.json();
      }
    });
    const state = status.deploymentState;
    if (terminalStates.has(state)) {
      if (state === "FAILED") {
        throw new Error(
          `Central deployment failed: ${JSON.stringify(status.errors ?? status)}`
        );
      }
      return { deploymentId, state };
    }
    await sleep(pollIntervalMilliseconds);
  }

  throw new Error(
    `Central deployment timed out after ${timeoutMilliseconds} ms: ${deploymentId}`
  );
}

async function requestBeforeDeadline({
  fetchImpl,
  url,
  options,
  deadline,
  now,
  timeoutMessage,
  readResponse
}) {
  const remainingMilliseconds = deadline - now();
  if (remainingMilliseconds <= 0) {
    throw new Error(timeoutMessage);
  }

  const controller = new AbortController();
  let timeout;
  const timeoutResult = new Promise((_, reject) => {
    timeout = setTimeout(() => {
      controller.abort();
      reject(new Error(timeoutMessage));
    }, remainingMilliseconds);
  });
  const requestResult = (async () => {
    const response = await fetchImpl(url, {
      ...options,
      signal: controller.signal
    });
    return readResponse(response);
  })();
  try {
    return await Promise.race([requestResult, timeoutResult]);
  } catch (error) {
    if (controller.signal.aborted) {
      throw new Error(timeoutMessage, { cause: error });
    }
    throw error;
  } finally {
    clearTimeout(timeout);
  }
}

async function responseBody(response) {
  if (!response) {
    return "No response";
  }
  return (await response.text()).trim();
}

function delay(milliseconds) {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}
