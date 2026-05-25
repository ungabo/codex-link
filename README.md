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
- shows an in-app Status dialog with app version, Local/Web mode, endpoint, host health, catalog cache, current chat, and queued-message counts
- shows a Queue overview for phone messages queued across chats
- loads the newest transcript window first instead of the whole chat
- loads older messages with `Older` and can load a full transcript with `Full`
- has compact fixed `Chats`, `Refresh`, `Files`, and `Actions` controls, with secondary chat actions inside the accordion
- uses full-width transcript text instead of nested message cards
- merges consecutive messages from the same role into paragraph-separated blocks
- replaces image references with lazy placeholders
- detects active/running chats and queues phone messages instead of interleaving turns
- shows explicit idle/processing/sending/queued status in the open chat composer
- pumps queued messages as soon as the opened chat refreshes back to idle
- keeps queued messages collapsed by default so they do not consume the chat view
- offers a per-message "Try now" control for a queued message; backend conflicts still keep the message queued
- runs queued messages through a foreground queue worker so backgrounding, rotation, or switching chats does not strand them
- lets queued phone messages be edited or deleted before they run
- exposes queued messages across chats so a hidden queue is not stranded inside one thread
- polls an active opened chat and refreshes new transcript output at the bottom
- attaches phone images to chat submissions
- previews or clears a selected image before sending
- sends follow-up prompts from the phone through `codex app-server` with workspace-write access limited to the mapped project root
- tracks the Codex chat/transcript folder separately from the writable project folder and can infer real projects from transcript-looking folders when they contain project markers
- clears orphaned/stale processing locks when a transcript has no recent activity
- persists queued phone messages per chat across app restarts
- can start a new Codex chat in the currently opened chat's project folder
- exposes phone controls for Git status, diff preview, checkpoint, and revert to last checkpoint
- confirms Stop, Checkpoint, and Revert actions with the affected chat/project before sending them
- exposes a project file picker for recently changed/generated files, including direct APK downloads and in-app text previews
- attempts to interrupt an active turn when the app-server exposes the active turn id
- opens directly into a denser chat list, keeps connection settings collapsed, and keeps the composer visible above the keyboard
- collapses the expanded Actions panel when the composer receives focus, so typing does not leave a huge toolbar over the chat
- preserves the open chat, draft composer text, attachment state, and scroll position through screen rotation
- restores the last open chat and draft after app switching or activity recreation
- requests Android notification permission and shows a phone notification after the APK is replaced
- anchors the visible transcript while you read; active output only auto-follows when the bottom of the chat is already visible
- includes `qa\android_smoke_test.ps1` for repeatable install/launch/catalog/thread/action/keyboard checks

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

The LAN shortcut starts the local bridge on `0.0.0.0:18765` so the phone can reach it over Wi-Fi. The web tunnel starts or uses a local bridge and then polls the PHP relay uploaded to `https://www.sitesindevelopment.com/codex-link/`.

It serves:

- `GET /health`
- `GET /catalog`
- `GET /threads/:threadId?limit=40`
- `GET /threads/:threadId?limit=40&before=<rangeStart>`
- `GET /threads/:threadId?full=1`
- `GET /threads/:threadId/project-status`
- `GET /threads/:threadId/diff`
- `GET /threads/:threadId/files`
- `GET /threads/:threadId/files/download?path=<project-relative-path>`
- `POST /threads`
- `POST /threads/:threadId/turns`
- `POST /threads/:threadId/interrupt`
- `POST /threads/:threadId/checkpoint`
- `POST /threads/:threadId/revert`

`POST /threads/:threadId/turns` refuses to start a new turn while the desktop chat is still processing. The phone queues those turns locally and submits them in order when the opened chat becomes idle.

Phone-originated Codex turns currently use `approvalPolicy: never`, network disabled, and a `workspaceWrite` sandbox limited to the explicit mapped project root when one exists. The bridge blocks Codex transcript/workspace folders such as `C:\Users\Gabe\Documents\Codex\...` as writable roots unless they are mapped to a real project. Before a phone-originated turn, the bridge records a best-effort Git checkpoint for Git-backed projects. Approval UI is still future hardening work.

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
RZCX625807M
SM_A546U
```

Verified on the phone:

- local Windows mode refreshes the live desktop catalog through `http://10.0.0.211:18765/link`
- Web Link mode was verified through the relay while the Windows tunnel was running
- chats open and mirror recent transcript content
- Status shows app version, endpoint, host health, and queue counts
- Queue shows whether phone messages are waiting across chats
- fixed jump controls remain available as floating up/down buttons
- `Older`, `Top`, `Full`, and compact chat search are exposed inside the Actions accordion
- the fixed top composer stays visible above the keyboard
- tapping the composer collapses expanded Actions controls before the keyboard comes up
- the composer sends to the current thread and refreshes with the Codex reply
- active chats show `Queue` instead of `Send`
- queued messages render with `Edit` and `Delete`; delete was verified on-device
- image sharing into the app attaches an image and exposes `View`/`Clear`
- project file listing works, and `README.md` was opened in the in-app preview overlay
- Stop opens a confirmation dialog before interrupting the open chat
- `Status` reports the bridge online
- scroll anchoring keeps the same visible message at the same bounds through a poll interval while reading away from the bottom
- automated smoke test passed with `qa\android_smoke_test.ps1 -Serial RZCX625807M -SkipInstall`

Latest successful phone-originated smoke reply:

```text
Android send path OK
```
