# Phase 0 Validation

This folder contains the first proof-of-capability spike from the companion build spec.

It connects to `codex app-server` over a localhost WebSocket, performs the required handshake, lists existing threads, reads one existing thread, starts a new read-only validation thread, sends one harmless prompt, streams events, and resumes the created thread.

## Run

```powershell
npm install
npm run validate
```

Optional local overrides can be placed in `config.json`; see `config.example.json`.

## Outputs

- `generated/` contains TypeScript protocol bindings generated from the installed Codex binary.
- `logs/` contains raw inbound/outbound JSON-RPC and app-server logs.
- `reports/` contains the Markdown validation report for each run.

## Latest Local Result

The first run against `codex-cli 0.133.0-alpha.1` passed:

- app-server started at `ws://127.0.0.1:4500`
- `initialize` succeeded
- `thread/list` returned existing local Codex threads
- `thread/read` read the current desktop-visible thread
- `thread/start`, `turn/start`, streaming notifications, and `thread/resume` worked for a new test thread

The script intentionally did not append to an arbitrary existing desktop-created chat. That live-sync test should use a dedicated throwaway desktop-created thread.

## Current Thread Sync Test

After the user explicitly approved using the active thread, `npm run sync-test -- 019e57e2-e6b1-75c0-be43-00019887c9bf ...` passed.

Observed result:

- target thread was listed by `thread/list`
- `thread/read` succeeded before and after
- `thread/resume` succeeded
- `turn/start` created turn `019e5801-051d-7901-a927-2d0d732b26a0`
- streamed assistant text: `Mobile companion sync test received.`
- turn count changed from 4 to 5

Report: `reports/2026-05-24T03-22-01-245Z-current-thread-sync-report.md`
