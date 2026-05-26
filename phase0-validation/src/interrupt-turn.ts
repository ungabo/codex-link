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
        this.respond(message.id, { error: `interrupt-turn client does not handle ${method}` });
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
  const threadId = process.argv[2] ?? "";
  const turnId = process.argv[3] ?? "";
  if (!threadId || !turnId) {
    throw new Error("Usage: npm run interrupt-turn -- <thread-id> <turn-id>");
  }

  const timestamp = new Date().toISOString().replace(/[:.]/g, "-");
  const rawLogPath = join(logsDir, `${timestamp}-phone-interrupt-turn-jsonrpc.jsonl`);
  const serverLogPath = join(logsDir, `${timestamp}-phone-interrupt-turn-server.log`);
  let appServerProcess: ChildProcessWithoutNullStreams | undefined;

  try {
    const version = execFileSync(config.codexPath, ["--version"], { encoding: "utf8" }).trim();
    const appServerUrl = await appServerUrlForTurn(config.appServerUrl);
    appServerProcess = spawn(config.codexPath, ["app-server", "--listen", appServerUrl], {
      cwd: config.testProjectDir,
      env: process.env,
    });
    appServerProcess.stdout.on("data", (chunk) => append(serverLogPath, chunk.toString()));
    appServerProcess.stderr.on("data", (chunk) => append(serverLogPath, chunk.toString()));

    await waitForServer(appServerUrl, 15000);
    const socket = await openSocket(appServerUrl);
    const rpc = new RpcClient(socket, rawLogPath);

    await rpc.request("initialize", {
      clientInfo: { name: "codex-link-phone-interrupt-turn", version: "0.1.0" },
      capabilities: { experimentalApi: true, requestAttestation: false },
    });
    rpc.notify("initialized");

    await rpc.request("thread/resume", { threadId, excludeTurns: true, persistExtendedHistory: false }, 60000);
    await rpc.request("turn/interrupt", { threadId, turnId }, 30000);
    socket.close();

    console.log(
      JSON.stringify({
        ok: true,
        codexVersion: version,
        threadId,
        turnId,
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
} {
  const example = JSON.parse(readFileSync(join(rootDir, "config.example.json"), "utf8")) as {
    codexPath: string;
    appServerUrl: string;
    testProjectDir: string;
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

function append(path: string, text: string): void {
  writeFileSync(path, text, { flag: "a", encoding: "utf8" });
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
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
