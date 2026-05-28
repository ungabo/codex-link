import { execFileSync, spawn, type ChildProcessWithoutNullStreams } from "node:child_process";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { createServer } from "node:net";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import WebSocket from "ws";

type JsonObject = Record<string, unknown>;

const __dirname = dirname(fileURLToPath(import.meta.url));
const rootDir = resolve(__dirname, "..");
const logsDir = join(rootDir, "logs");

class RpcClient {
  private nextId = 1;
  private pending = new Map<
    string,
    {
      method: string;
      resolve: (value: unknown) => void;
      reject: (error: Error) => void;
      timeout: NodeJS.Timeout;
    }
  >();

  constructor(
    private readonly socket: WebSocket,
    private readonly logPath: string,
  ) {
    socket.on("message", (data) => this.handleMessage(data.toString()));
  }

  request(method: string, params: unknown, timeoutMs = 30000): Promise<unknown> {
    const id = String(this.nextId++);
    const message = { id, method, params };
    this.writeLog("out", message);
    this.socket.send(JSON.stringify(message));

    return new Promise((resolve, reject) => {
      const timeout = setTimeout(() => {
        this.pending.delete(id);
        reject(new Error(`${method} timed out after ${timeoutMs}ms`));
      }, timeoutMs);

      this.pending.set(id, { method, resolve, reject, timeout });
    });
  }

  notify(method: string): void {
    const message = { method };
    this.writeLog("out", message);
    this.socket.send(JSON.stringify(message));
  }

  respond(id: unknown, result: unknown): void {
    const message = { id, result };
    this.writeLog("out", message);
    this.socket.send(JSON.stringify(message));
  }

  private handleMessage(raw: string): void {
    let message: JsonObject;
    try {
      message = JSON.parse(raw) as JsonObject;
    } catch {
      this.writeLog("in-parse-error", { raw });
      return;
    }

    this.writeLog("in", message);

    const id = typeof message.id === "string" ? message.id : undefined;
    if (id && this.pending.has(id)) {
      const pending = this.pending.get(id)!;
      this.pending.delete(id);
      clearTimeout(pending.timeout);
      if (message.error) {
        pending.reject(new Error(`${pending.method} failed: ${JSON.stringify(message.error)}`));
      } else {
        pending.resolve(message.result);
      }
      return;
    }

    if (message.id && message.method) {
      const method = String(message.method);
      if (method === "item/commandExecution/requestApproval") {
        this.respond(message.id, { decision: "decline" });
      } else if (method === "item/fileChange/requestApproval") {
        this.respond(message.id, { decision: "decline" });
      } else if (method === "applyPatchApproval" || method === "execCommandApproval") {
        this.respond(message.id, { decision: "denied" });
      } else {
        this.respond(message.id, { error: `start-thread client does not handle ${method}` });
      }
    }
  }

  private writeLog(direction: string, message: unknown): void {
    writeFileSync(
      this.logPath,
      JSON.stringify({ at: new Date().toISOString(), direction, message }) + "\n",
      { flag: "a", encoding: "utf8" },
    );
  }
}

async function main(): Promise<void> {
  mkdirSync(logsDir, { recursive: true });

  const config = loadConfig();
  const args = process.argv.slice(2);
  const emitStarted = args.includes("--emit-started");
  const positional = args.filter((arg) => arg !== "--emit-started");
  const cwd = (positional[0] ?? config.testProjectDir).trim();
  const prompt = positional.slice(1).join(" ").trim();
  if (!cwd) {
    throw new Error("Usage: npm run start-thread -- <cwd> [initial prompt]");
  }

  const timestamp = new Date().toISOString().replace(/[:.]/g, "-");
  const rawLogPath = join(logsDir, `${timestamp}-phone-start-thread-jsonrpc.jsonl`);
  const serverLogPath = join(logsDir, `${timestamp}-phone-start-thread-server.log`);
  const notifications: JsonObject[] = [];
  let appServerProcess: ChildProcessWithoutNullStreams | undefined;
  let agentText = "";
  let turnId = "";
  let threadId = "";

  try {
    const version = execFileSync(config.codexPath, ["--version"], { encoding: "utf8" }).trim();
    const appServerUrl = await appServerUrlForTurn(config.appServerUrl);
    appServerProcess = spawn(config.codexPath, ["app-server", "--listen", appServerUrl], {
      cwd,
      env: process.env,
    });
    appServerProcess.stdout.on("data", (chunk) => append(serverLogPath, chunk.toString()));
    appServerProcess.stderr.on("data", (chunk) => append(serverLogPath, chunk.toString()));

    await waitForServer(appServerUrl, 15000);
    const socket = await openSocket(appServerUrl);
    const rpc = new RpcClient(socket, rawLogPath);

    socket.on("message", (data) => {
      const message = JSON.parse(data.toString()) as JsonObject;
      if (message.method && !message.id) {
        notifications.push(message);
        if (message.method === "item/agentMessage/delta") {
          const params = message.params as JsonObject;
          agentText += String(params.delta ?? "");
        }
        if (message.method === "turn/started") {
          const params = message.params as JsonObject;
          const turn = params.turn as JsonObject | undefined;
          turnId = String(turn?.id ?? turnId);
        }
        if (message.method === "thread/started") {
          const params = message.params as JsonObject;
          const thread = params.thread as JsonObject | undefined;
          threadId = String(thread?.id ?? threadId);
        }
      }
    });

    await rpc.request("initialize", {
      clientInfo: { name: "codex-link-phone-start-thread", version: "0.1.0" },
      capabilities: { experimentalApi: true, requestAttestation: false },
    });
    rpc.notify("initialized");

    const start = (await rpc.request(
      "thread/start",
      {
        cwd,
        runtimeWorkspaceRoots: [cwd],
        approvalPolicy: "never",
        sandbox: "workspace-write",
        threadSource: "user",
        experimentalRawEvents: false,
        persistExtendedHistory: false,
      },
      60000,
    )) as JsonObject;
    const thread = start.thread as JsonObject | undefined;
    threadId = String(thread?.id ?? start.threadId ?? start.id ?? threadId);

    if (prompt) {
      const turn = await rpc.request(
        "turn/start",
        {
          threadId,
          input: [{ type: "text", text: prompt, text_elements: [] }],
          cwd,
          runtimeWorkspaceRoots: [cwd],
          approvalPolicy: "never",
          sandboxPolicy: sandboxPolicyForCwd(cwd),
        },
        60000,
      );
      turnId = String(((turn as JsonObject).turn as JsonObject | undefined)?.id ?? turnId);
      if (emitStarted) {
        console.log(
          JSON.stringify({
            ok: true,
            event: "started",
            accepted: true,
            completed: false,
            status: "processing",
            codexVersion: version,
            threadId,
            cwd,
            turnId,
            rawLogPath,
            serverLogPath,
          }),
        );
      }
      await waitForTurnCompleted(notifications, threadId, turnId, config.turnTimeoutMs);
    }

    socket.close();

    console.log(
      JSON.stringify({
        ok: true,
        codexVersion: version,
        threadId,
        cwd,
        turnId,
        agentText: agentText.trim(),
        notificationCounts: summarizeNotifications(notifications),
        rawLogPath,
        serverLogPath,
      }),
    );
  } finally {
    if (appServerProcess && !appServerProcess.killed) {
      appServerProcess.kill();
    }
  }
}

function sandboxPolicyForCwd(cwd: string): JsonObject {
  return {
    type: "workspaceWrite",
    writableRoots: [cwd],
    networkAccess: false,
    excludeTmpdirEnvVar: false,
    excludeSlashTmp: false,
  };
}

async function appServerUrlForTurn(configUrl: string): Promise<string> {
  const url = new URL(configUrl);
  if (url.protocol !== "ws:" && url.protocol !== "wss:") {
    return configUrl;
  }
  const host = loopbackHost(url.hostname);
  const port = await freeTcpPort(host);
  return `${url.protocol}//${host}:${port}`;
}

function loopbackHost(hostname: string): string {
  if (!hostname || hostname === "localhost" || hostname === "::1" || hostname === "[::1]") {
    return "127.0.0.1";
  }
  return hostname;
}

function freeTcpPort(host: string): Promise<number> {
  return new Promise((resolve, reject) => {
    const server = createServer();
    server.unref();
    server.on("error", reject);
    server.listen(0, host, () => {
      const address = server.address();
      const port = typeof address === "object" && address ? address.port : 0;
      server.close((error) => {
        if (error) {
          reject(error);
        } else if (port > 0) {
          resolve(port);
        } else {
          reject(new Error("Could not allocate a free app-server port"));
        }
      });
    });
  });
}

function loadConfig(): {
  codexPath: string;
  appServerUrl: string;
  testProjectDir: string;
  turnTimeoutMs: number;
} {
  const example = JSON.parse(readFileSync(join(rootDir, "config.example.json"), "utf8")) as {
    codexPath: string;
    appServerUrl: string;
    testProjectDir: string;
    turnTimeoutMs: number;
  };

  let local: Partial<typeof example> = {};
  try {
    local = JSON.parse(readFileSync(join(rootDir, "config.json"), "utf8")) as typeof local;
  } catch {
    local = {};
  }

  return {
    ...example,
    ...local,
    codexPath: process.env.CODEX_CLI_PATH ?? local.codexPath ?? example.codexPath,
  };
}

async function waitForServer(url: string, timeoutMs: number): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  let lastError = "";
  while (Date.now() < deadline) {
    try {
      const socket = await openSocket(url);
      socket.close();
      return;
    } catch (error) {
      lastError = error instanceof Error ? error.message : String(error);
      await sleep(250);
    }
  }
  throw new Error(`app-server did not accept websocket before timeout: ${lastError}`);
}

function openSocket(url: string): Promise<WebSocket> {
  return new Promise((resolve, reject) => {
    const socket = new WebSocket(url);
    socket.once("open", () => resolve(socket));
    socket.once("error", reject);
  });
}

function waitForTurnCompleted(
  notifications: JsonObject[],
  threadId: string,
  turnId: string,
  timeoutMs: number,
): Promise<void> {
  const startedAt = Date.now();
  return new Promise((resolve, reject) => {
    const interval = setInterval(() => {
      const completed = notifications.find((message) => {
        if (message.method !== "turn/completed") {
          return false;
        }
        const params = message.params as JsonObject | undefined;
        const turn = params?.turn as JsonObject | undefined;
        return params?.threadId === threadId && (!turnId || turn?.id === turnId);
      });

      if (completed) {
        clearInterval(interval);
        resolve();
        return;
      }

      if (Date.now() - startedAt > timeoutMs) {
        clearInterval(interval);
        reject(new Error(`turn did not complete before ${timeoutMs}ms timeout`));
      }
    }, 250);
  });
}

function append(path: string, text: string): void {
  writeFileSync(path, text, { flag: "a", encoding: "utf8" });
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function summarizeNotifications(notifications: JsonObject[]): Record<string, number> {
  const counts: Record<string, number> = {};
  for (const notification of notifications) {
    const method = String(notification.method ?? "unknown");
    counts[method] = (counts[method] ?? 0) + 1;
  }
  return counts;
}

main().catch((error) => {
  console.error(
    JSON.stringify({
      ok: false,
      error: error instanceof Error ? error.message : String(error),
    }),
  );
  process.exitCode = 1;
});
