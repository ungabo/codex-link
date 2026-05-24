import { execFileSync, spawn, type ChildProcessWithoutNullStreams } from "node:child_process";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import WebSocket from "ws";

type JsonObject = Record<string, unknown>;

const __dirname = dirname(fileURLToPath(import.meta.url));
const rootDir = resolve(__dirname, "..");
const logsDir = join(rootDir, "logs");
const reportsDir = join(rootDir, "reports");

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
        this.respond(message.id, {
          error: `Sync test client does not handle ${method}`,
        });
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
  mkdirSync(reportsDir, { recursive: true });

  const config = loadConfig();
  const threadId = process.argv[2] ?? config.currentThreadId;
  if (!threadId) {
    throw new Error("Pass the target thread id as the first argument or set currentThreadId in config.json.");
  }

  const prompt =
    process.argv.slice(3).join(" ") ||
    `Benign sync test from the Codex mobile/browser companion validation script at ${new Date().toISOString()}. Please reply with exactly: "Mobile companion sync test received."`;

  const timestamp = new Date().toISOString().replace(/[:.]/g, "-");
  const rawLogPath = join(logsDir, `${timestamp}-current-thread-sync-jsonrpc.jsonl`);
  const serverLogPath = join(logsDir, `${timestamp}-current-thread-sync-server.log`);
  const reportPath = join(reportsDir, `${timestamp}-current-thread-sync-report.md`);
  const notifications: JsonObject[] = [];
  let appServerProcess: ChildProcessWithoutNullStreams | undefined;
  let agentText = "";
  let turnId = "";
  let outcome = "not completed";

  try {
    const version = execFileSync(config.codexPath, ["--version"], { encoding: "utf8" }).trim();
    appServerProcess = spawn(config.codexPath, ["app-server", "--listen", config.appServerUrl], {
      cwd: config.testProjectDir,
      env: process.env,
    });
    appServerProcess.stdout.on("data", (chunk) => append(serverLogPath, chunk.toString()));
    appServerProcess.stderr.on("data", (chunk) => append(serverLogPath, chunk.toString()));

    await waitForServer(config.appServerUrl, 15000);
    const socket = await openSocket(config.appServerUrl);
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
      }
    });

    await rpc.request("initialize", {
      clientInfo: { name: "codex-link-current-thread-sync-test", version: "0.1.0" },
      capabilities: { experimentalApi: true, requestAttestation: false },
    });
    rpc.notify("initialized");

    const listed = await rpc.request("thread/list", {
      limit: 20,
      sortKey: "updated_at",
      sortDirection: "desc",
      sourceKinds: allSourceKinds,
      useStateDbOnly: true,
    });
    const targetListed = arrayAt(listed, "data").some((thread) => (thread as JsonObject).id === threadId);

    const before = await rpc.request("thread/read", { threadId, includeTurns: true }, 60000);
    await rpc.request(
      "thread/resume",
      { threadId, excludeTurns: true, persistExtendedHistory: false },
      60000,
    );

    const turn = await rpc.request(
      "turn/start",
      {
        threadId,
        input: [{ type: "text", text: prompt, text_elements: [] }],
        approvalPolicy: "never",
        sandboxPolicy: { type: "readOnly", networkAccess: false },
      },
      60000,
    );
    turnId = String(((turn as JsonObject).turn as JsonObject | undefined)?.id ?? turnId);

    await waitForTurnCompleted(notifications, threadId, turnId, config.turnTimeoutMs);
    const after = await rpc.request("thread/read", { threadId, includeTurns: true }, 60000);
    outcome = "completed";

    writeFileSync(
      reportPath,
      renderReport({
        version,
        threadId,
        turnId,
        prompt,
        targetListed,
        before,
        after,
        agentText,
        notifications,
        rawLogPath,
        serverLogPath,
        outcome,
      }),
      "utf8",
    );

    console.log(`PASS current-thread sync test: ${agentText.trim()}`);
    console.log(`Report: ${reportPath}`);
    socket.close();
  } catch (error) {
    outcome = error instanceof Error ? error.message : String(error);
    writeFileSync(
      reportPath,
      renderReport({
        version: "unknown",
        threadId,
        turnId,
        prompt,
        targetListed: false,
        before: null,
        after: null,
        agentText,
        notifications,
        rawLogPath,
        serverLogPath,
        outcome,
      }),
      "utf8",
    );
    console.error(`FAIL current-thread sync test: ${outcome}`);
    console.error(`Report: ${reportPath}`);
    process.exitCode = 1;
  } finally {
    if (appServerProcess && !appServerProcess.killed) {
      appServerProcess.kill();
    }
  }
}

function loadConfig(): {
  codexPath: string;
  appServerUrl: string;
  testProjectDir: string;
  turnTimeoutMs: number;
  currentThreadId?: string;
} {
  const example = JSON.parse(readFileSync(join(rootDir, "config.example.json"), "utf8")) as {
    codexPath: string;
    appServerUrl: string;
    testProjectDir: string;
    turnTimeoutMs: number;
  };

  let local: Partial<typeof example & { currentThreadId: string }> = {};
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

function arrayAt(value: unknown, key: string): unknown[] {
  if (!value || typeof value !== "object") {
    return [];
  }
  const candidate = (value as JsonObject)[key];
  return Array.isArray(candidate) ? candidate : [];
}

function append(path: string, text: string): void {
  writeFileSync(path, text, { flag: "a", encoding: "utf8" });
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function renderReport(input: {
  version: string;
  threadId: string;
  turnId: string;
  prompt: string;
  targetListed: boolean;
  before: unknown;
  after: unknown;
  agentText: string;
  notifications: JsonObject[];
  rawLogPath: string;
  serverLogPath: string;
  outcome: string;
}): string {
  return [
    "# Current Thread Sync Test",
    "",
    `Generated: ${new Date().toISOString()}`,
    `Outcome: ${input.outcome}`,
    `Codex version: ${input.version}`,
    `Thread id: ${input.threadId}`,
    `Turn id: ${input.turnId || "not created"}`,
    `Listed in thread/list page: ${input.targetListed}`,
    `Prompt: ${input.prompt}`,
    `Agent text: ${input.agentText.trim() || "(none)"}`,
    `Raw JSON-RPC log: ${input.rawLogPath}`,
    `Server log: ${input.serverLogPath}`,
    "",
    "## Notification Counts",
    "",
    ...summarizeNotifications(input.notifications).map(([method, count]) => `- ${method}: ${count}`),
    "",
    "## Before",
    "",
    "```json",
    JSON.stringify(summarizeThread(input.before), null, 2),
    "```",
    "",
    "## After",
    "",
    "```json",
    JSON.stringify(summarizeThread(input.after), null, 2),
    "```",
    "",
  ].join("\n");
}

function summarizeThread(value: unknown): unknown {
  if (!value || typeof value !== "object") {
    return value;
  }
  const thread = (value as JsonObject).thread as JsonObject | undefined;
  if (!thread) {
    return value;
  }
  return {
    id: thread.id,
    name: thread.name,
    preview: thread.preview,
    status: thread.status,
    updatedAt: thread.updatedAt,
    turns: Array.isArray(thread.turns) ? thread.turns.length : 0,
  };
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
