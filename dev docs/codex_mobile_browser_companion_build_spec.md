# Codex Mobile/Browser Companion Build Spec

## Purpose

Build a private Android/browser-accessible companion interface that can communicate with Codex running on a Windows machine or Linux server, so the user can start, view, continue, and manage Codex development sessions from a phone or browser.

Primary desired workflow:

```text
Android phone / browser
-> private companion app UI
-> local Windows background service or Linux server service
-> Codex app-server
-> Codex threads + project folders
-> streamed output, diffs, approvals, build/test/preview results
```

## Key Direction

The realistic v1 path is:

```text
Custom mobile/browser client
-> custom backend service
-> codex app-server
```

The v1 path is not:

```text
Custom mobile/browser client
-> official Codex Windows app UI
```

Do not automate or scrape the visible Codex Windows desktop UI unless no cleaner alternative exists. The target is to communicate with Codex app-server/thread APIs.

## Current Known Resources

- Codex app-server docs: https://developers.openai.com/codex/app-server
- Codex CLI reference: https://developers.openai.com/codex/cli/reference
- Codex SDK docs: https://developers.openai.com/codex/sdk
- OpenAI Codex GitHub repo: https://github.com/openai/codex
- Codex app-server README: https://github.com/openai/codex/blob/main/codex-rs/app-server/README.md
- Codex app-server test client: https://github.com/openai/codex/blob/main/codex-rs/app-server-test-client/README.md

## Important Reality Checks

- `codex app-server` is experimental and can break across Codex versions.
- Windows desktop app live-sync should not be assumed.
- The companion is effectively a remote coding-agent controller; keep it private, authenticated, and restricted.
- Do not expose raw app-server directly to LAN or internet.

## Target v1

Build a responsive local/private web app first. It should work from Android through the phone browser. Build a native Android app later only if the web version proves useful.

V1 should support:

1. Connect to Codex app-server.
2. List available Codex threads if supported.
3. Read a selected thread if supported.
4. Start a new thread.
5. Resume or continue an existing thread.
6. Send a follow-up prompt.
7. Stream Codex output/events.
8. Show task status.
9. Show changed files/diffs if available.
10. Approve/reject actions if exposed by the protocol.
11. Manage project folders.
12. Open browser-preview URLs for web games.

## Validation Phase

Before building the full app, create a command-line validation script that determines whether a custom client can:

1. Start Codex app-server.
2. Initialize a JSON-RPC session.
3. List threads.
4. Read a thread.
5. Start a new thread.
6. Resume an existing thread.
7. Send a turn.
8. Receive streamed events.
9. Detect approval requests.
10. Detect changed files/diffs if exposed.

## Current Local Phase 0 Result

The project now includes `phase0-validation/`, a Node/TypeScript validation spike against the locally installed Codex CLI.

First verified run:

- Codex CLI: `codex-cli 0.133.0-alpha.1`
- Transport: `ws://127.0.0.1:4500`
- `initialize`: passed
- `thread/list`: passed, existing local Codex threads were returned
- `thread/read`: passed for an existing Codex thread
- `thread/start`: passed
- `turn/start`: passed
- streamed output/events: passed
- `thread/resume`: passed for the created validation thread

Not yet tested: appending to a dedicated desktop-created throwaway thread and checking whether the official Codex Windows desktop app live-refreshes or later displays that appended turn.

## Decision Rules After Phase 0

- If existing Windows app threads are accessible and sync acceptably, proceed with a companion client that can list/resume the same thread IDs.
- If existing Windows app threads are not safely resumable, build the companion as a separate Codex app-server client with its own managed threads.
- If app-server is unreliable, fall back to controlled `codex exec` tasks with diff/checkpoint handling.

## What Not To Do

- Do not build native Android first.
- Do not scrape the Windows Codex app UI.
- Do not use AutoHotkey or UI automation as the main plan.
- Do not expose app-server directly to the LAN or internet.
- Do not store API keys in frontend code.
- Do not allow arbitrary filesystem paths.
- Do not skip Git checkpoints.
