import { execFileSync } from "node:child_process";
import { spawn, type ChildProcessWithoutNullStreams } from "node:child_process";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import WebSocket from "ws";

type JsonObject = Record<string, unknown>;

type Config = {
  codexPath: string;
  appServerUrl: string;
  startServer: boolean;
  testProjectDir: string;
  turnTimeoutMs: number;
  prompt: string;
};

type Operation = {
  name: string;
  ok: boolean;
  detail: string;
};

type PendingRequest = {
  method: string;
  resolve: (value: unknown) => void;
  reject: (error: Error) => void;
  startedAt: number;
};

const __dirname = dirname(fileURLToPath(import.meta.url));
const rootDir = resolve(__dirname, "..");
const logsDir = join(rootDir, "logs");
const reportsDir = join(rootDir, "reports");
const sandboxDir = join(rootDir, "sandbox");
const allSourceKinds = [
  "cli",
  "vscode",
  "exec",
  "appServer",
  "subAgent",
  "subAgentReview",
  "subAgentCompact",
  "subAgentThreadSpawn",
  "subAgentOther",
  "unknown",
];

function loadConfig(): Config {
  const example = JSON.parse(readFileSync(join(rootDir, "config.example.json"), "utf8")) as Config;
  const configPath = join(rootDir, "config.json");
  let local: Partial<Config> = {};
  try {
    local = JSON.parse(readFileSync(configPath, "utf8")) as Partial<Config>;
  } catch {
    local = {};
  }

  return {
    ...example,
    ...local,
    codexPath: process.env.CODEX_CLI_PATH ?? local.codexPath ?? example.codexPath,
  };
}

class RpcClient {
  private nextId = 1;
  private pending = new Map<string, PendingRequest>();

  constructor(
    private readonly socket: WebSocket,
    private readonly logPath: string,
    private readonly serverRequestHandler: (message: JsonObject) => void,
  ) {
    socket.on("message", (data) => this.handleMessage(data.toString()));
  }

  request(method: string, params: unknown, timeoutMs = 30000): Promise<unknown> {
    const id = String(this.nextId++);
    const message: JsonObject = { id, method, params };
    this.writeLog("out", message);
    this.socket.send(JSON.stringify(message));

    return new Promise((resolve, reject) => {
      const timeout = setTimeout(() => {
        this.pending.delete(id);
        reject(new Error(`${method} timed out after ${timeoutMs}ms`));
      }, timeoutMs);

      this.pending.set(id, {
        method,
        startedAt: Date.now(),
        resolve: (value) => {
          clearTimeout(timeout);
          resolve(value);
        },
        reject: (error) => {
          clearTimeout(timeout);
          reject(error);
        },
      });
    });
  }

  notify(method: string, params?: unknown): void {
    const message = params === undefined ? { method } : { method, params };
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
      if (message.error) {
        pending.reject(new Error(`${pending.method} failed: ${JSON.stringify(message.error)}`));
      } else {
        pending.resolve(message.result);
      }
      return;
    }

    if (message.id && message.method) {
      this.serverRequestHandler(message);
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
  mkdirSync(reportsDir, { recursive: true });
  mkdirSync(sandboxDir, { recursive: true });

  const config = loadConfig();
  const timestamp = new Date().toISOString().replace(/[:.]/g, "-");
  const rawLogPath = join(logsDir, `${timestamp}-raw-jsonrpc.jsonl`);
  const serverLogPath = join(logsDir, `${timestamp}-server.log`);
  const reportPath = join(reportsDir, `${timestamp}-phase0-report.md`);
  const operations: Operation[] = [];
  const notifications: JsonObject[] = [];
  let appServerProcess: ChildProcessWithoutNullStreams | undefined;
  let createdThreadId: string | undefined;
  let createdTurnId: string | undefined;
  let agentText = "";

  const record = (name: string, ok: boolean, detail: string) => {
    operations.push({ name, ok, detail });
    console.log(`${ok ? "PASS" : "FAIL"} ${name}: ${detail}`);
  };

  try {
    const version = execFileSync(config.codexPath, ["--version"], { encoding: "utf8" }).trim();
    record("codex version", true, version);

    if (config.startServer) {
      appServerProcess = spawn(config.codexPath, ["app-server", "--listen", config.appServerUrl], {
        cwd: config.testProjectDir,
        env: process.env,
      });
      appServerProcess.stdout.on("data", (chunk) => append(serverLogPath, chunk.toString()));
      appServerProcess.stderr.on("data", (chunk) => append(serverLogPath, chunk.toString()));
      appServerProcess.on("exit", (code, signal) =>
        append(serverLogPath, `\n[process exited code=${code} signal=${signal}]\n`),
      );
      record("start app-server", true, `${config.appServerUrl}`);
      await waitForServer(config.appServerUrl, 15000);
    }

    const socket = await openSocket(config.appServerUrl);
    const rpc = new RpcClient(socket, rawLogPath, (message) => {
      const method = String(message.method ?? "");
      if (method === "item/commandExecution/requestApproval") {
        rpc.respond(message.id, { decision: "decline" });
      } else if (method === "item/fileChange/requestApproval") {
        rpc.respond(message.id, { decision: "decline" });
      } else if (method === "applyPatchApproval" || method === "execCommandApproval") {
        rpc.respond(message.id, { decision: "denied" });
      } else {
        rpc.respond(message.id, { error: `Validation client does not handle ${method}` });
      }
    });

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
          createdTurnId = String(turn?.id ?? createdTurnId ?? "");
        }
      }
    });

    const init = await rpc.request("initialize", {
      clientInfo: { name: "codex-link-phase0-validation", version: "0.1.0" },
      capabilities: { experimentalApi: true, requestAttestation: false },
    });
    record("initialize", true, summarize(init));
    rpc.notify("initialized");

    const threadList = await rpc.request("thread/list", {
      limit: 10,
      sortKey: "updated_at",
      sortDirection: "desc",
      sourceKinds: allSourceKinds,
      useStateDbOnly: true,
    });
    const listData = arrayAt(threadList, "data");
    record("thread/list", true, `${listData.length} threads returned in first page`);

    if (listData.length > 0) {
      const firstThread = listData[0] as JsonObject;
      const threadId = String(firstThread.id);
      const read = await rpc.request("thread/read", { threadId, includeTurns: true }, 60000);
      record("thread/read existing", true, `read ${threadTitle(read)} (${threadId})`);
    } else {
      record("thread/read existing", false, "thread/list returned no existing threads");
    }

    const start = await rpc.request("thread/start", {
      cwd: config.testProjectDir,
      approvalPolicy: "never",
      sandbox: "read-only",
      threadSource: "user",
      experimentalRawEvents: false,
      persistExtendedHistory: false,
    });
    createdThreadId = String(
      ((start as JsonObject).thread as JsonObject | undefined)?.id ??
        (start as JsonObject).threadId ??
        (start as JsonObject).id ??
        "",
    );
    if (!createdThreadId) {
      const threadStarted = notifications.find((item) => item.method === "thread/started");
      const params = threadStarted?.params as JsonObject | undefined;
      const thread = params?.thread as JsonObject | undefined;
      createdThreadId = String(thread?.id ?? "");
    }
    record("thread/start", Boolean(createdThreadId), createdThreadId || summarize(start));

    const turn = await rpc.request("turn/start", {
      threadId: createdThreadId,
      input: [{ type: "text", text: config.prompt, text_elements: [] }],
      approvalPolicy: "never",
      sandboxPolicy: { type: "readOnly", networkAccess: false },
    });
    createdTurnId = String(((turn as JsonObject).turn as JsonObject | undefined)?.id ?? createdTurnId ?? "");
    record("turn/start", Boolean(createdTurnId), createdTurnId || summarize(turn));

    await waitForTurnCompleted(notifications, createdThreadId, createdTurnId, config.turnTimeoutMs);
    record("stream events", true, `${notifications.length} notifications, final text length ${agentText.length}`);

    const readCreated = await rpc.request(
      "thread/read",
      { threadId: createdThreadId, includeTurns: true },
      60000,
    );
    record("thread/read created", true, threadTitle(readCreated));

    const resume = await rpc.request(
      "thread/resume",
      { threadId: createdThreadId, excludeTurns: true, persistExtendedHistory: false },
      60000,
    );
    record("thread/resume created", true, summarize(resume));

    socket.close();
  } catch (error) {
    record("validation run", false, error instanceof Error ? error.message : String(error));
  } finally {
    if (appServerProcess && !appServerProcess.killed) {
      appServerProcess.kill();
    }

    writeFileSync(
      reportPath,
      renderReport({
        config,
        operations,
        rawLogPath,
        serverLogPath,
        createdThreadId,
        createdTurnId,
        agentText,
        notifications,
      }),
      "utf8",
    );
    console.log(`Report: ${reportPath}`);
  }
}

function append(path: string, text: string): void {
  writeFileSync(path, text, { flag: "a", encoding: "utf8" });
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
  threadId: string | undefined,
  turnId: string | undefined,
  timeoutMs: number,
): Promise<void> {
  const startedAt = Date.now();
  return new Promise((resolve, reject) => {
    const interval = setInterval(() => {
      const found = notifications.find((message) => {
        if (message.method !== "turn/completed") {
          return false;
        }
        const params = message.params as JsonObject | undefined;
        const turn = params?.turn as JsonObject | undefined;
        return params?.threadId === threadId && (!turnId || turn?.id === turnId);
      });

      if (found) {
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

function arrayAt(value: unknown, key: string): unknown[] {
  if (!value || typeof value !== "object") {
    return [];
  }
  const candidate = (value as JsonObject)[key];
  return Array.isArray(candidate) ? candidate : [];
}

function summarize(value: unknown): string {
  return JSON.stringify(value).slice(0, 500);
}

function threadTitle(value: unknown): string {
  const thread = (value as JsonObject).thread as JsonObject | undefined;
  return String(thread?.name ?? thread?.preview ?? "thread");
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function renderReport(input: {
  config: Config;
  operations: Operation[];
  rawLogPath: string;
  serverLogPath: string;
  createdThreadId?: string;
  createdTurnId?: string;
  agentText: string;
  notifications: JsonObject[];
}): string {
  const lines = [
    "# Phase 0 Codex App-Server Validation Report",
    "",
    `Generated: ${new Date().toISOString()}`,
    "",
    "## Configuration",
    "",
    `- Codex binary: ${input.config.codexPath}`,
    `- App-server URL: ${input.config.appServerUrl}`,
    `- Test project dir: ${input.config.testProjectDir}`,
    `- Raw JSON-RPC log: ${input.rawLogPath}`,
    `- Server log: ${input.serverLogPath}`,
    "",
    "## Results",
    "",
    ...input.operations.map((op) => `- ${op.ok ? "PASS" : "FAIL"} ${op.name}: ${op.detail}`),
    "",
    "## Created Test Thread",
    "",
    `- Thread id: ${input.createdThreadId ?? "not created"}`,
    `- Turn id: ${input.createdTurnId ?? "not created"}`,
    `- Agent text: ${input.agentText.trim() || "(no streamed agent text captured)"}`,
    "",
    "## Notifications",
    "",
    ...summarizeNotifications(input.notifications).map(([method, count]) => `- ${method}: ${count}`),
    "",
    "## Windows Desktop Sync",
    "",
    "Not automatically tested against an existing desktop-created throwaway thread. This script lists and reads existing threads, but it does not append turns to arbitrary existing desktop chats.",
    "",
  ];
  return lines.join("\n");
}

function summarizeNotifications(notifications: JsonObject[]): [string, number][] {
  const counts = new Map<string, number>();
  for (const notification of notifications) {
    const method = String(notification.method ?? "unknown");
    counts.set(method, (counts.get(method) ?? 0) + 1);
  }
  return [...counts.entries()].sort(([a], [b]) => a.localeCompare(b));
}

void main();
