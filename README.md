# Codex Mobile/Browser Companion

This project is a private Codex mobile/browser companion.

The current priority is the build spec's Phase 0 validation path:

- custom client
- local backend/service
- `codex app-server`
- Codex threads and project folders

Do not treat the native Android prototype as v1. The spec calls for proving the browser/backend workflow first, then deciding whether Android should be a PWA wrapper, Trusted Web Activity, or native app later.

## Current State

Phase 0 proved that `codex app-server` works on this machine:

- initialize/list/read/resume/start turn all work
- an existing desktop-visible Codex thread can receive a benign app-server turn
- streamed agent text is captured from JSON-RPC notifications

The native Android prototype is now useful as a private phone companion MVP:

- connects to the Windows bridge locally or through the Web Link relay
- local Windows endpoint example: `http://10.0.0.211:18765/link`
- web endpoint: `https://www.sitesindevelopment.com/codex-link/index.php/link`
- lists Codex desktop chats/projects from local Codex state
- opens any listed chat, including this current chat
- loads the newest transcript window first instead of the whole chat
- loads older messages with `Older`
- has compact fixed `Refresh`, `End`, and `Actions` controls, with secondary chat actions inside the accordion
- uses full-width transcript text instead of nested message cards
- merges consecutive messages from the same role into paragraph-separated blocks
- replaces image references with lazy placeholders
- detects active/running chats and queues phone messages instead of interleaving turns
- lets queued phone messages be edited or deleted before they run
- polls an active opened chat and refreshes new transcript output at the bottom
- attaches phone images to chat submissions
- sends follow-up prompts from the phone through `codex app-server` with workspace-write access limited to the resumed chat cwd
- persists queued phone messages per chat across app restarts
- can start a new Codex chat in the currently opened chat's project folder
- exposes phone controls for Git status, diff preview, checkpoint, and revert to last checkpoint
- attempts to interrupt an active turn when the app-server exposes the active turn id

## APK Download

A debug APK is committed for quick side-load testing:

```text
artifacts/codex-link-debug.apk
```

Public test APKs may include a short-lived Web Link test token for convenience. The relay enforces test-token expiry server-side, and these tokens should be rotated after testing.

For durable private Web Link access, use the phone endpoint shown above and enter the private `phoneToken` from:

```text
desktop_bridge\remote_tunnel_config.json
```

That private config file is git-ignored. A safe template is available at:

```text
desktop_bridge\remote_tunnel_config.sample.json
```

## Desktop Bridge

The bridge lives in:

```text
desktop_bridge\
```

Start it after a reboot with the desktop shortcut:

```text
C:\Users\Gabe\Desktop\Start Codex Link Bridge.lnk
```

For public Web Link access, use:

```text
C:\Users\Gabe\Desktop\Codex Link Web Tunnel.lnk
```

The web tunnel starts the local bridge on `127.0.0.1:18765` if needed, then polls the PHP relay uploaded to `https://www.sitesindevelopment.com/codex-link/`.

It serves:

- `GET /health`
- `GET /catalog`
- `GET /threads/:threadId?limit=40`
- `GET /threads/:threadId?limit=40&before=<rangeStart>`
- `GET /threads/:threadId?full=1`
- `GET /threads/:threadId/project-status`
- `GET /threads/:threadId/diff`
- `POST /threads`
- `POST /threads/:threadId/turns`
- `POST /threads/:threadId/interrupt`
- `POST /threads/:threadId/checkpoint`
- `POST /threads/:threadId/revert`

`POST /threads/:threadId/turns` refuses to start a new turn while the desktop chat is still processing. The phone queues those turns locally and submits them in order when the opened chat becomes idle.

Phone-originated Codex turns currently use `approvalPolicy: never`, network disabled, and a `workspaceWrite` sandbox limited to the resumed thread's cwd. Before a phone-originated turn, the bridge records a best-effort Git checkpoint for Git-backed projects. Approval UI is still future hardening work.

## Phase 0 Validation

The app-server validation scripts live in:

```text
phase0-validation\
```

Run them with:

```powershell
cd .\phase0-validation
npm install
npm run validate
```

Useful scripts:

- `npm run validate`
- `npm run sync-test -- <thread-id> <prompt>`
- `npm run send-turn -- <thread-id> <prompt>`
- `npm run start-thread -- <cwd> [initial prompt]`
- `npm run interrupt-turn -- <thread-id> <turn-id>`

Reports and raw JSON-RPC logs are written under `phase0-validation\reports` and `phase0-validation\logs`.

## Android QA

Physical phone tested:

```text
10.0.0.116:33281
SM_A546U
```

Verified on the phone:

- current chat opens and mirrors recent transcript content
- fixed jump controls remain available
- `Older` expands the loaded range
- `Bottom` stays inside the transcript view
- the fixed top composer stays visible above the keyboard
- the composer sends to the current thread and refreshes with the Codex reply
- active chats show `Queue` instead of `Send`
- queued messages render with `Edit` and `Delete`; delete was verified on-device
- `Host` reports the bridge online

Latest successful phone-originated smoke reply:

```text
Android send path OK
```
