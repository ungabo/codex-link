from __future__ import annotations

from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import argparse
import base64
import binascii
import hashlib
import json
import mimetypes
import os
from pathlib import Path
import re
import shutil
import sqlite3
import subprocess
import tempfile
import threading
from typing import Any
from urllib.parse import parse_qs, quote, unquote, urlparse


DEFAULT_CODEX_HOME = Path.home() / ".codex"
PROJECT_ROOT = Path(__file__).resolve().parents[1]
PHASE0_DIR = PROJECT_ROOT / "phase0-validation"
DEFAULT_THREAD_LIMIT = 40
MAX_THREAD_LIMIT = 250
IMAGE_EXTENSIONS = {".png", ".jpg", ".jpeg", ".webp", ".gif"}
MAX_UPLOAD_BYTES = 8 * 1024 * 1024
MAX_UPLOAD_IMAGES = 4
ACTIVE_TURN_STALE_SECONDS = 4 * 60 * 60
ACTIVITY_TAIL_BYTES = 512 * 1024
UPLOAD_ROOT = PROJECT_ROOT / "desktop_bridge" / "uploaded_images"
CHECKPOINTS_PATH = PROJECT_ROOT / "desktop_bridge" / "checkpoints.json"
MAX_DIFF_CHARS = 80_000
IMAGE_REF_RE = re.compile(
    r"!\[[^\]]*\]\(<?([^>)]+?)(?:>|\))|"
    r"\[[^\]]+\]\(<([^>]+\.(?:png|jpe?g|webp|gif))>\)|"
    r"([A-Za-z]:[\\/][^\r\n<>|?*\"]+\.(?:png|jpe?g|webp|gif))",
    re.IGNORECASE,
)
IMAGE_PLACEHOLDER_RE = re.compile(r"<image\s+name=\[?([^\]\n>]+)\]?>", re.IGNORECASE)
TURN_LOCK = threading.Lock()


def clean_windows_path(value: str | None) -> str:
    if not value:
        return ""
    value = value.replace("\\\\?\\", "")
    return value.rstrip("\\/")


def path_key(value: str | None) -> str:
    return clean_windows_path(value).replace("/", "\\").lower()


def is_under(child: str, parent: str) -> bool:
    child_key = path_key(child)
    parent_key = path_key(parent)
    return child_key == parent_key or child_key.startswith(parent_key + "\\")


def truncate_text(value: str, limit: int = MAX_DIFF_CHARS) -> tuple[str, bool]:
    if len(value) <= limit:
        return value, False
    return value[:limit].rstrip() + "\n\n... truncated ...", True


def iso_from_ms(value: int | None) -> str | None:
    if not value:
        return None
    return datetime.fromtimestamp(value / 1000, tz=timezone.utc).isoformat().replace("+00:00", "Z")


def iso_from_seconds(value: Any) -> str | None:
    try:
        seconds = float(value)
    except (TypeError, ValueError):
        return None
    if seconds <= 0:
        return None
    return datetime.fromtimestamp(seconds, tz=timezone.utc).isoformat().replace("+00:00", "Z")


def load_global_state(codex_home: Path) -> dict[str, Any]:
    path = codex_home / ".codex-global-state.json"
    if not path.exists():
        return {}
    return json.loads(path.read_text(encoding="utf-8"))


def load_session_index_names(codex_home: Path) -> dict[str, str]:
    path = codex_home / "session_index.jsonl"
    names: dict[str, str] = {}
    if not path.exists():
        return names
    with path.open("r", encoding="utf-8", errors="replace") as handle:
        for line in handle:
            try:
                item = json.loads(line)
            except json.JSONDecodeError:
                continue
            thread_id = str(item.get("id") or "")
            name = str(item.get("thread_name") or "").strip()
            if thread_id and name:
                names[thread_id] = name
    return names


def is_subagent_thread(row: sqlite3.Row) -> bool:
    thread_source = str(row["thread_source"] or "").lower()
    source = str(row["source"] or "").lower()
    return (
        thread_source == "subagent"
        or bool(row["agent_nickname"])
        or bool(row["agent_role"])
        or '"subagent"' in source
    )


def iter_recent_jsonl(path: Path, tail_bytes: int = ACTIVITY_TAIL_BYTES):
    size = path.stat().st_size
    with path.open("rb") as handle:
        if size > tail_bytes:
            handle.seek(size - tail_bytes)
            handle.readline()
        for raw_line in handle:
            yield raw_line.decode("utf-8", errors="replace")


def thread_activity_for_path(rollout_path: Path, *, tail_only: bool = True) -> dict[str, Any]:
    active_turns: dict[str, dict[str, Any]] = {}
    last_event_at = ""

    if not rollout_path.exists():
        return {"status": "idle", "active": False, "lastEventAt": last_event_at}

    lines = iter_recent_jsonl(rollout_path) if tail_only else rollout_path.open("r", encoding="utf-8", errors="replace")
    try:
        for line in lines:
            try:
                event = json.loads(line)
            except json.JSONDecodeError:
                continue

            timestamp = str(event.get("timestamp") or "")
            if timestamp:
                last_event_at = timestamp

            if event.get("type") != "event_msg":
                continue
            payload = event.get("payload")
            if not isinstance(payload, dict):
                continue

            event_type = payload.get("type")
            turn_id = str(payload.get("turn_id") or "")
            if not turn_id:
                continue

            if event_type == "task_started":
                try:
                    started_seconds = float(payload.get("started_at") or 0)
                except (TypeError, ValueError):
                    started_seconds = 0
                active_turns[turn_id] = {
                    "turnId": turn_id,
                    "startedAt": iso_from_seconds(payload.get("started_at")) or timestamp,
                    "startedSeconds": started_seconds,
                }
            elif event_type in {"task_complete", "task_failed", "task_cancelled", "task_canceled"}:
                active_turns.pop(turn_id, None)
    finally:
        close = getattr(lines, "close", None)
        if callable(close):
            close()

    if not active_turns:
        return {"status": "idle", "active": False, "lastEventAt": last_event_at}

    active = next(reversed(active_turns.values()))
    started_seconds = float(active.get("startedSeconds") or 0)
    now_seconds = datetime.now(tz=timezone.utc).timestamp()
    if started_seconds and now_seconds - started_seconds > ACTIVE_TURN_STALE_SECONDS:
        return {
            "status": "idle",
            "active": False,
            "staleActive": True,
            "activeTurnId": active.get("turnId", ""),
            "activeStartedAt": active.get("startedAt", ""),
            "lastEventAt": last_event_at,
        }

    return {
        "status": "running",
        "active": True,
        "activeTurnId": active.get("turnId", ""),
        "activeStartedAt": active.get("startedAt", ""),
        "activeCount": len(active_turns),
        "lastEventAt": last_event_at,
    }


def load_threads(codex_home: Path, global_state: dict[str, Any]) -> tuple[list[dict[str, Any]], int]:
    db_path = codex_home / "state_5.sqlite"
    if not db_path.exists():
        return [], 0

    session_names = load_session_index_names(codex_home)
    pinned_thread_ids = set(global_state.get("pinned-thread-ids") or [])
    rows: list[dict[str, Any]] = []
    hidden_subagents = 0
    connection = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)
    connection.row_factory = sqlite3.Row
    try:
        for row in connection.execute(
            """
            select id, title, cwd, updated_at_ms, updated_at, archived,
                   thread_source, source, preview, agent_nickname, agent_role,
                   rollout_path
            from threads
            order by coalesce(updated_at_ms, updated_at * 1000) desc
            """
        ):
            if bool(row["archived"]):
                continue
            if is_subagent_thread(row):
                hidden_subagents += 1
                continue

            updated_ms = row["updated_at_ms"] or (row["updated_at"] * 1000 if row["updated_at"] else None)
            original_title = row["title"] or "Untitled chat"
            display_title = session_names.get(row["id"]) or original_title
            rollout_path = Path(clean_windows_path(row["rollout_path"]))
            activity = thread_activity_for_path(rollout_path)
            rows.append(
                {
                    "id": row["id"],
                    "title": display_title,
                    "displayTitle": display_title,
                    "originalTitle": original_title,
                    "cwd": clean_windows_path(row["cwd"]),
                    "updatedAt": iso_from_ms(updated_ms),
                    "updatedAtMs": updated_ms,
                    "archived": bool(row["archived"]),
                    "threadSource": row["thread_source"] or "user",
                    "source": row["source"],
                    "preview": row["preview"] or "",
                    "pinned": row["id"] in pinned_thread_ids,
                    "status": activity["status"],
                    "active": activity["active"],
                    "activeTurnId": activity.get("activeTurnId", ""),
                    "activeStartedAt": activity.get("activeStartedAt", ""),
                }
            )
    finally:
        connection.close()
    rows.sort(key=lambda thread: (not thread.get("pinned", False), -(thread.get("updatedAtMs") or 0)))
    return rows, hidden_subagents


def collect_message_text(content: Any) -> str:
    if not isinstance(content, list):
        return ""

    parts: list[str] = []
    for item in content:
        if not isinstance(item, dict):
            continue
        if item.get("type") in {"input_text", "output_text"}:
            text = item.get("text")
            if isinstance(text, str) and text.strip():
                parts.append(text.strip())
    return "\n\n".join(parts).strip()


def media_id_for(path: str) -> str:
    return hashlib.sha256(path_key(path).encode("utf-8")).hexdigest()[:24]


def extract_media(text: str, thread_id: str) -> list[dict[str, Any]]:
    media: list[dict[str, Any]] = []
    seen: set[str] = set()

    for match in IMAGE_REF_RE.finditer(text):
        raw_path = next((group for group in match.groups() if group), "")
        clean_path = clean_windows_path(raw_path.strip())
        suffix = Path(clean_path).suffix.lower()
        if suffix not in IMAGE_EXTENSIONS:
            continue
        media_id = media_id_for(clean_path)
        if media_id in seen:
            continue
        seen.add(media_id)
        media.append(
            {
                "id": media_id,
                "kind": "image",
                "label": Path(clean_path).name,
                "path": clean_path,
                "available": Path(clean_path).exists(),
                "loadUrl": f"/threads/{quote(thread_id)}/media/{media_id}",
            }
        )

    for index, match in enumerate(IMAGE_PLACEHOLDER_RE.finditer(text), start=1):
        placeholder_id = f"placeholder-{index}"
        if placeholder_id in seen:
            continue
        seen.add(placeholder_id)
        media.append(
            {
                "id": placeholder_id,
                "kind": "image",
                "label": match.group(1).strip() or "Image",
                "available": False,
                "loadUrl": None,
            }
        )

    return media


def strip_heavy_media_markup(text: str) -> str:
    text = IMAGE_REF_RE.sub("[Image placeholder]", text)
    text = IMAGE_PLACEHOLDER_RE.sub(lambda match: f"[Image: {match.group(1).strip() or 'Image'}]", text)
    return text


def parse_positive_int(value: str | None, default: int, max_value: int) -> int:
    if not value:
        return default
    try:
        parsed = int(value)
    except ValueError:
        return default
    if parsed < 1:
        return default
    return min(parsed, max_value)


def load_thread_row(codex_home: Path, thread_id: str) -> sqlite3.Row | None:
    db_path = codex_home / "state_5.sqlite"
    if not db_path.exists():
        return None

    connection = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)
    connection.row_factory = sqlite3.Row
    try:
        row = connection.execute(
            """
            select id, title, cwd, updated_at_ms, updated_at, archived,
                   thread_source, source, preview, rollout_path
            from threads
            where id = ?
            """,
            (thread_id,),
        ).fetchone()
    finally:
        connection.close()

    return row


def collect_thread_messages(rollout_path: Path, thread_id: str) -> list[dict[str, Any]]:
    messages: list[dict[str, Any]] = []

    if rollout_path.exists():
        with rollout_path.open("r", encoding="utf-8", errors="replace") as handle:
            for line in handle:
                try:
                    event = json.loads(line)
                except json.JSONDecodeError:
                    continue

                payload = event.get("payload")
                if not isinstance(payload, dict):
                    continue
                if event.get("type") != "response_item" or payload.get("type") != "message":
                    continue

                role = payload.get("role")
                if role not in {"user", "assistant"}:
                    continue

                text = collect_message_text(payload.get("content"))
                if not text or text.startswith("<environment_context>"):
                    continue

                media = extract_media(text, thread_id)
                text = strip_heavy_media_markup(text)
                messages.append(
                    {
                        "index": len(messages),
                        "role": role,
                        "text": text,
                        "phase": payload.get("phase"),
                        "timestamp": event.get("timestamp"),
                        "media": media,
                    }
                )

    return messages


def read_thread(
    codex_home: Path,
    thread_id: str,
    *,
    limit: int = DEFAULT_THREAD_LIMIT,
    before: int | None = None,
    full: bool = False,
) -> dict[str, Any] | None:
    row = load_thread_row(codex_home, thread_id)
    if row is None:
        return None

    updated_ms = row["updated_at_ms"] or (row["updated_at"] * 1000 if row["updated_at"] else None)
    rollout_path = Path(clean_windows_path(row["rollout_path"]))
    activity = thread_activity_for_path(rollout_path, tail_only=False)
    messages = collect_thread_messages(rollout_path, thread_id)
    total_count = len(messages)
    session_name = load_session_index_names(codex_home).get(thread_id)
    title = session_name or row["title"] or "Untitled chat"

    if full:
        range_start = 0
        range_end = total_count
    else:
        range_end = total_count if before is None else max(0, min(before, total_count))
        range_start = max(0, range_end - limit)
    selected_messages = messages[range_start:range_end]

    return {
        "source": "codex-rollout",
        "generatedAt": datetime.now(tz=timezone.utc).isoformat().replace("+00:00", "Z"),
        "thread": {
            "id": row["id"],
            "title": title,
            "displayTitle": title,
            "originalTitle": row["title"] or "Untitled chat",
            "cwd": clean_windows_path(row["cwd"]),
            "updatedAt": iso_from_ms(updated_ms),
            "updatedAtMs": updated_ms,
            "archived": bool(row["archived"]),
            "threadSource": row["thread_source"] or "user",
            "source": row["source"],
            "preview": row["preview"] or "",
            "status": activity["status"],
            "active": activity["active"],
            "activeTurnId": activity.get("activeTurnId", ""),
            "activeStartedAt": activity.get("activeStartedAt", ""),
        },
        "activity": activity,
        "messageCount": len(selected_messages),
        "totalMessageCount": total_count,
        "rangeStart": range_start,
        "rangeEnd": range_end,
        "hasMoreBefore": range_start > 0,
        "messages": selected_messages,
    }


def find_thread_media(codex_home: Path, thread_id: str, media_id: str) -> Path | None:
    row = load_thread_row(codex_home, thread_id)
    if row is None:
        return None

    rollout_path = Path(clean_windows_path(row["rollout_path"]))
    for message in collect_thread_messages(rollout_path, thread_id):
        for item in message.get("media", []):
            if item.get("id") != media_id or not item.get("available"):
                continue
            path = Path(clean_windows_path(item.get("path")))
            if path.exists() and path.suffix.lower() in IMAGE_EXTENSIONS:
                return path
    return None


def safe_upload_name(value: str, fallback: str) -> str:
    name = Path(value or fallback).name
    name = re.sub(r"[^A-Za-z0-9._-]+", "_", name).strip("._")
    return name or fallback


def extension_for_upload(name: str, mime_type: str) -> str:
    suffix = Path(name).suffix.lower()
    if suffix in IMAGE_EXTENSIONS:
        return suffix
    guessed = mimetypes.guess_extension(mime_type or "")
    if guessed and guessed.lower() in IMAGE_EXTENSIONS:
        return guessed.lower()
    if mime_type == "image/png":
        return ".png"
    if mime_type in {"image/jpeg", "image/jpg"}:
        return ".jpg"
    if mime_type == "image/webp":
        return ".webp"
    return ".png"


def save_turn_images(thread_id: str, images: Any) -> list[Path]:
    if images is None:
        return []
    if not isinstance(images, list):
        raise ValueError("images must be an array")
    if len(images) > MAX_UPLOAD_IMAGES:
        raise ValueError(f"at most {MAX_UPLOAD_IMAGES} images can be sent at once")

    saved_paths: list[Path] = []
    timestamp = datetime.now(tz=timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    upload_dir = UPLOAD_ROOT / safe_upload_name(thread_id, "thread") / timestamp
    upload_dir.mkdir(parents=True, exist_ok=True)

    for index, item in enumerate(images, start=1):
        if not isinstance(item, dict):
            raise ValueError("each image must be an object")
        encoded = str(item.get("base64") or "")
        if not encoded:
            raise ValueError("image is missing base64 data")
        try:
            data = base64.b64decode(encoded, validate=True)
        except binascii.Error as error:
            raise ValueError("image base64 data is invalid") from error
        if len(data) > MAX_UPLOAD_BYTES:
            raise ValueError("image is larger than 8 MB")

        mime_type = str(item.get("mimeType") or "image/png")
        original_name = safe_upload_name(str(item.get("name") or f"phone-image-{index}"), f"phone-image-{index}")
        extension = extension_for_upload(original_name, mime_type)
        stem = Path(original_name).stem or f"phone-image-{index}"
        output_path = upload_dir / f"{index:02d}-{stem}{extension}"
        output_path.write_bytes(data)
        saved_paths.append(output_path)

    return saved_paths


def build_projects(global_state: dict[str, Any], threads: list[dict[str, Any]]) -> list[dict[str, Any]]:
    labels = global_state.get("electron-workspace-root-labels") or {}
    pinned = set(global_state.get("pinned-project-ids") or [])
    ordered_roots = list(global_state.get("project-order") or [])

    for root in global_state.get("electron-saved-workspace-roots") or []:
        if root not in ordered_roots:
            ordered_roots.append(root)

    projects = []
    for root in ordered_roots:
        clean_root = clean_windows_path(root)
        thread_count = sum(1 for thread in threads if is_under(thread.get("cwd", ""), clean_root))
        projects.append(
            {
                "id": clean_root,
                "label": labels.get(root) or Path(clean_root).name or clean_root,
                "path": clean_root,
                "pinned": root in pinned or clean_root in pinned,
                "threadCount": thread_count,
            }
        )
    return projects


def attach_project_paths(
    threads: list[dict[str, Any]], projects: list[dict[str, Any]]
) -> list[dict[str, Any]]:
    ordered_projects = sorted(projects, key=lambda project: len(project["path"]), reverse=True)
    for thread in threads:
        thread["projectPath"] = None
        thread["projectLabel"] = None
        for project in ordered_projects:
            if is_under(thread.get("cwd", ""), project["path"]):
                thread["projectPath"] = project["path"]
                thread["projectLabel"] = project["label"]
                break
    return threads


def load_catalog(codex_home: Path) -> dict[str, Any]:
    global_state = load_global_state(codex_home)
    threads, hidden_subagents = load_threads(codex_home, global_state)
    projects = build_projects(global_state, threads)
    threads = attach_project_paths(threads, projects)
    return {
        "source": "codex-desktop-state",
        "generatedAt": datetime.now(tz=timezone.utc).isoformat().replace("+00:00", "Z"),
        "codexHome": str(codex_home),
        "counts": {
            "projects": len(projects),
            "chats": len(threads),
            "hiddenSubagentChats": hidden_subagents,
        },
        "projects": projects,
        "chats": threads,
    }


def run_process(command: list[str], *, cwd: Path | str | None = None, timeout: int = 30) -> dict[str, Any]:
    completed = subprocess.run(
        command,
        cwd=cwd,
        text=True,
        capture_output=True,
        timeout=timeout,
    )
    return {
        "exitCode": completed.returncode,
        "stdout": completed.stdout.strip(),
        "stderr": completed.stderr.strip(),
    }


def git_binary() -> str:
    git = shutil.which("git")
    if not git:
        raise RuntimeError("git was not found on PATH")
    return git


def git_output(args: list[str], cwd: str | Path, *, timeout: int = 30, allow_error: bool = False) -> str:
    result = run_process([git_binary(), "-C", str(cwd), *args], timeout=timeout)
    if result["exitCode"] != 0 and not allow_error:
        detail = result["stderr"] or result["stdout"] or f"git exited {result['exitCode']}"
        raise RuntimeError(detail)
    return str(result["stdout"])


def git_project_status(cwd: str) -> dict[str, Any]:
    clean_cwd = clean_windows_path(cwd)
    if not clean_cwd or not Path(clean_cwd).exists():
        return {"ok": False, "cwd": clean_cwd, "isGitRepo": False, "error": "project path not found"}

    try:
        root = clean_windows_path(git_output(["rev-parse", "--show-toplevel"], clean_cwd))
    except RuntimeError as error:
        return {
            "ok": True,
            "cwd": clean_cwd,
            "isGitRepo": False,
            "error": str(error),
            "dirty": False,
            "changedFiles": [],
            "statusText": "",
        }

    branch = git_output(["branch", "--show-current"], root, allow_error=True) or "(detached)"
    head = git_output(["rev-parse", "--short", "HEAD"], root, allow_error=True)
    status_text = git_output(["status", "--short"], root, allow_error=True)
    changed_files = []
    for line in status_text.splitlines():
        if len(line) >= 3:
            changed_files.append({"status": line[:2].strip(), "path": line[3:]})

    return {
        "ok": True,
        "cwd": clean_cwd,
        "isGitRepo": True,
        "root": root,
        "branch": branch,
        "head": head,
        "dirty": bool(status_text.strip()),
        "changedFiles": changed_files,
        "statusText": status_text,
    }


def git_project_diff(cwd: str) -> dict[str, Any]:
    status = git_project_status(cwd)
    if not status.get("isGitRepo"):
        return status

    root = str(status["root"])
    stat = git_output(["diff", "--stat"], root, timeout=60, allow_error=True)
    staged_stat = git_output(["diff", "--cached", "--stat"], root, timeout=60, allow_error=True)
    working_diff = git_output(["diff", "--"], root, timeout=60, allow_error=True)
    staged_diff = git_output(["diff", "--cached", "--"], root, timeout=60, allow_error=True)

    chunks = []
    if staged_diff.strip():
        chunks.append("Staged changes\n\n" + staged_diff)
    if working_diff.strip():
        chunks.append("Working tree changes\n\n" + working_diff)
    diff_text, truncated = truncate_text("\n\n".join(chunks) or "No tracked diff.", MAX_DIFF_CHARS)

    return {
        **status,
        "stat": "\n".join(part for part in [staged_stat, stat] if part.strip()),
        "diff": diff_text,
        "truncated": truncated,
    }


def load_checkpoints() -> dict[str, Any]:
    if not CHECKPOINTS_PATH.exists():
        return {"entries": []}
    try:
        data = json.loads(CHECKPOINTS_PATH.read_text(encoding="utf-8"))
    except json.JSONDecodeError:
        return {"entries": []}
    if not isinstance(data, dict):
        return {"entries": []}
    entries = data.get("entries")
    if not isinstance(entries, list):
        data["entries"] = []
    return data


def save_checkpoints(data: dict[str, Any]) -> None:
    CHECKPOINTS_PATH.parent.mkdir(parents=True, exist_ok=True)
    CHECKPOINTS_PATH.write_text(json.dumps(data, indent=2), encoding="utf-8")


def record_checkpoint(cwd: str, thread_id: str | None, checkpoint_hash: str, label: str) -> dict[str, Any]:
    entry = {
        "cwd": clean_windows_path(cwd),
        "threadId": thread_id or "",
        "hash": checkpoint_hash,
        "label": label,
        "createdAt": datetime.now(tz=timezone.utc).isoformat().replace("+00:00", "Z"),
    }
    data = load_checkpoints()
    data.setdefault("entries", []).append(entry)
    save_checkpoints(data)
    return entry


def last_checkpoint(cwd: str, thread_id: str | None) -> dict[str, Any] | None:
    target_cwd = path_key(cwd)
    target_thread = thread_id or ""
    entries = load_checkpoints().get("entries") or []
    for entry in reversed(entries):
        if not isinstance(entry, dict):
            continue
        if path_key(str(entry.get("cwd") or "")) != target_cwd:
            continue
        if target_thread and str(entry.get("threadId") or "") not in {target_thread, ""}:
            continue
        if entry.get("hash"):
            return entry
    return None


def git_checkpoint(cwd: str, thread_id: str | None = None, label: str = "manual") -> dict[str, Any]:
    status = git_project_status(cwd)
    if not status.get("isGitRepo"):
        return {**status, "ok": False, "error": "project is not a Git repository"}

    root = str(status["root"])
    created_commit = False
    commit_output = ""
    if status.get("dirty"):
        git_output(["add", "-A"], root, timeout=60)
        stamp = datetime.now(tz=timezone.utc).strftime("%Y-%m-%d %H:%M:%S UTC")
        safe_label = re.sub(r"[^A-Za-z0-9 ._-]+", " ", label).strip() or "manual"
        commit_output = git_output(
            [
                "-c",
                "user.name=Codex Link",
                "-c",
                "user.email=codex-link@local",
                "commit",
                "-m",
                f"Codex Link checkpoint ({safe_label}) {stamp}",
            ],
            root,
            timeout=90,
        )
        created_commit = True

    checkpoint_hash = git_output(["rev-parse", "HEAD"], root)
    short_hash = git_output(["rev-parse", "--short", "HEAD"], root)
    entry = record_checkpoint(root, thread_id, checkpoint_hash, label)
    return {
        "ok": True,
        "isGitRepo": True,
        "root": root,
        "checkpointHash": checkpoint_hash,
        "checkpointShortHash": short_hash,
        "createdCommit": created_commit,
        "commitOutput": commit_output,
        "checkpoint": entry,
    }


def git_revert_last(cwd: str, thread_id: str | None = None) -> dict[str, Any]:
    status = git_project_status(cwd)
    if not status.get("isGitRepo"):
        return {**status, "ok": False, "error": "project is not a Git repository"}

    root = str(status["root"])
    checkpoint = last_checkpoint(root, thread_id)
    if checkpoint is None:
        return {"ok": False, "isGitRepo": True, "root": root, "error": "no checkpoint recorded for this project"}

    reset_output = git_output(["reset", "--hard", str(checkpoint["hash"])], root, timeout=90)
    return {
        "ok": True,
        "isGitRepo": True,
        "root": root,
        "resetOutput": reset_output,
        "checkpoint": checkpoint,
        "status": git_project_status(root),
    }


def approved_project_roots(codex_home: Path) -> list[str]:
    global_state = load_global_state(codex_home)
    roots: list[str] = []
    for key in ("project-order", "electron-saved-workspace-roots"):
        for root in global_state.get(key) or []:
            roots.append(clean_windows_path(str(root)))

    threads, _hidden = load_threads(codex_home, global_state)
    for thread in threads:
        cwd = clean_windows_path(str(thread.get("cwd") or ""))
        if cwd:
            roots.append(cwd)

    unique: list[str] = []
    seen: set[str] = set()
    for root in roots:
        if not root or path_key(root) in seen or not Path(root).exists():
            continue
        seen.add(path_key(root))
        unique.append(root)
    return unique


def validate_cwd(codex_home: Path, cwd: str) -> str:
    clean_cwd = clean_windows_path(cwd)
    if not clean_cwd:
        raise ValueError("missing project path")
    path = Path(clean_cwd)
    if not path.exists() or not path.is_dir():
        raise ValueError("project path does not exist")
    resolved = clean_windows_path(str(path.resolve()))
    roots = approved_project_roots(codex_home)
    if not any(is_under(resolved, root) or is_under(clean_cwd, root) for root in roots):
        raise ValueError("project path is not under an approved Codex workspace root")
    return resolved


def default_cwd(codex_home: Path) -> str:
    global_state = load_global_state(codex_home)
    threads, _hidden = load_threads(codex_home, global_state)
    for thread in threads:
        cwd = clean_windows_path(str(thread.get("cwd") or ""))
        if cwd and Path(cwd).exists():
            return cwd
    roots = approved_project_roots(codex_home)
    return roots[0] if roots else ""


class CodexLinkHandler(BaseHTTPRequestHandler):
    codex_home: Path
    token: str | None
    link_log_path: Path

    def do_GET(self) -> None:
        if not self.authorized():
            self.write_json({"error": "unauthorized"}, status=401)
            return

        parsed_url = urlparse(self.path)
        path = parsed_url.path
        query = parse_qs(parsed_url.query)
        if path in {"/health", "/codex/health"}:
            self.write_json(
                {
                    "ok": True,
                    "source": "codex-link-bridge",
                    "generatedAt": datetime.now(tz=timezone.utc).isoformat().replace("+00:00", "Z"),
                }
            )
            return

        if path in {"/catalog", "/codex/catalog"}:
            self.write_json(load_catalog(self.codex_home))
            return

        thread_prefixes = ("/threads/", "/codex/threads/")
        matching_prefix = next((prefix for prefix in thread_prefixes if path.startswith(prefix)), None)
        if matching_prefix is not None:
            thread_tail = unquote(path[len(matching_prefix) :])
            action_markers = {
                "/project-status": "project-status",
                "/diff": "diff",
            }
            for marker, action in action_markers.items():
                if thread_tail.endswith(marker):
                    thread_id = thread_tail[: -len(marker)]
                    row = load_thread_row(self.codex_home, thread_id)
                    if row is None:
                        self.write_json({"error": "thread not found"}, status=404)
                        return
                    cwd = clean_windows_path(row["cwd"])
                    try:
                        if action == "project-status":
                            self.write_json(git_project_status(cwd))
                        else:
                            self.write_json(git_project_diff(cwd))
                    except RuntimeError as error:
                        self.write_json({"ok": False, "error": str(error)}, status=500)
                    return

            media_marker = "/media/"
            if media_marker in thread_tail:
                thread_id, media_id = thread_tail.split(media_marker, 1)
                media_path = find_thread_media(self.codex_home, thread_id, media_id)
                if media_path is None:
                    self.write_json({"error": "media not found"}, status=404)
                    return
                self.write_file(media_path)
                return

            thread_id = thread_tail
            if not thread_id:
                self.write_json({"error": "missing thread id"}, status=400)
                return
            limit = parse_positive_int(
                query.get("limit", [None])[0],
                DEFAULT_THREAD_LIMIT,
                MAX_THREAD_LIMIT,
            )
            before_raw = query.get("before", [None])[0]
            before = None
            if before_raw:
                try:
                    before = int(before_raw)
                except ValueError:
                    before = None
            full = query.get("full", ["0"])[0] in {"1", "true", "yes"}
            thread = read_thread(self.codex_home, thread_id, limit=limit, before=before, full=full)
            if thread is None:
                self.write_json({"error": "thread not found"}, status=404)
                return
            self.write_json(thread)
            return

        if path not in {"/catalog", "/codex/catalog"}:
            self.write_json({"error": "not found"}, status=404)
            return

    def do_POST(self) -> None:
        if not self.authorized():
            self.write_json({"error": "unauthorized"}, status=401)
            return

        parsed_url = urlparse(self.path)
        path = parsed_url.path

        if path in {"/threads", "/codex/threads"}:
            try:
                payload = self.read_json_body()
                requested_cwd = str(payload.get("cwd") or "").strip() or default_cwd(self.codex_home)
                cwd = validate_cwd(self.codex_home, requested_cwd)
            except ValueError as error:
                self.write_json({"error": str(error)}, status=400)
                return

            prompt = str(payload.get("prompt") or "").strip()
            if not TURN_LOCK.acquire(blocking=False):
                self.write_json({"error": "another Codex operation is already running"}, status=409)
                return

            try:
                result = run_codex_start_thread(cwd, prompt)
            except RuntimeError as error:
                self.write_json({"error": str(error)}, status=500)
                return
            finally:
                TURN_LOCK.release()

            self.write_json(result)
            return

        thread_prefixes = ("/threads/", "/codex/threads/")
        matching_prefix = next((prefix for prefix in thread_prefixes if path.startswith(prefix)), None)
        if matching_prefix is not None:
            thread_tail = unquote(path[len(matching_prefix) :])
            post_action_markers = {
                "/interrupt": "interrupt",
                "/checkpoint": "checkpoint",
                "/revert": "revert",
            }
            for marker, action in post_action_markers.items():
                if thread_tail.endswith(marker):
                    thread_id = thread_tail[: -len(marker)]
                    if not thread_id:
                        self.write_json({"error": "missing thread id"}, status=400)
                        return
                    row = load_thread_row(self.codex_home, thread_id)
                    if row is None:
                        self.write_json({"error": "thread not found"}, status=404)
                        return
                    try:
                        payload = self.read_json_body()
                    except ValueError as error:
                        self.write_json({"error": str(error)}, status=400)
                        return

                    cwd = clean_windows_path(row["cwd"])
                    if action == "interrupt":
                        rollout_path = Path(clean_windows_path(row["rollout_path"]))
                        activity = thread_activity_for_path(rollout_path, tail_only=False)
                        turn_id = str(payload.get("turnId") or activity.get("activeTurnId") or "")
                        if not turn_id:
                            self.write_json({"error": "no active turn id found for this chat"}, status=400)
                            return
                        try:
                            self.write_json(run_codex_interrupt(thread_id, turn_id))
                        except RuntimeError as error:
                            self.write_json({"error": str(error)}, status=500)
                        return

                    if action == "checkpoint":
                        label = str(payload.get("label") or "phone")
                        try:
                            self.write_json(git_checkpoint(cwd, thread_id, label))
                        except RuntimeError as error:
                            self.write_json({"ok": False, "error": str(error)}, status=500)
                        return

                    if action == "revert":
                        try:
                            result = git_revert_last(cwd, thread_id)
                        except RuntimeError as error:
                            self.write_json({"ok": False, "error": str(error)}, status=500)
                            return
                        self.write_json(result, status=200 if result.get("ok") else 400)
                        return

            turns_marker = "/turns"
            if not thread_tail.endswith(turns_marker):
                self.write_json({"error": "not found"}, status=404)
                return

            thread_id = thread_tail[: -len(turns_marker)]
            if not thread_id:
                self.write_json({"error": "missing thread id"}, status=400)
                return

            try:
                payload = self.read_json_body()
            except ValueError as error:
                self.write_json({"error": str(error)}, status=400)
                return

            prompt = str(payload.get("prompt") or "").strip()
            images = payload.get("images")
            if not prompt and not images:
                self.write_json({"error": "missing prompt or image"}, status=400)
                return
            if not prompt and images:
                prompt = "Please review the attached image."

            row = load_thread_row(self.codex_home, thread_id)
            if row is None:
                self.write_json({"error": "thread not found"}, status=404)
                return
            try:
                turn_cwd = validate_cwd(self.codex_home, clean_windows_path(row["cwd"]))
            except ValueError as error:
                self.write_json(
                    {
                        "error": (
                            "Cannot send from Android because this chat does not have a valid writable "
                            f"project folder: {error}"
                        ),
                        "kind": "invalid-cwd",
                    },
                    status=400,
                )
                return

            rollout_path = Path(clean_windows_path(row["rollout_path"]))
            activity = thread_activity_for_path(rollout_path, tail_only=False)
            if activity["active"]:
                self.write_json(
                    {
                        "error": (
                            "This chat is still processing in Codex desktop. "
                            "Wait for the current turn to finish, then send again."
                        ),
                        "status": activity["status"],
                        "activeTurnId": activity.get("activeTurnId", ""),
                        "activeStartedAt": activity.get("activeStartedAt", ""),
                    },
                    status=409,
                )
                return

            try:
                image_paths = save_turn_images(thread_id, images)
            except ValueError as error:
                self.write_json({"error": str(error)}, status=400)
                return

            if not TURN_LOCK.acquire(blocking=False):
                self.write_json({"error": "another Codex turn is already running"}, status=409)
                return

            checkpoint_result: dict[str, Any] | None = None
            try:
                try:
                    checkpoint_result = git_checkpoint(turn_cwd, thread_id, "before-phone-turn")
                except RuntimeError:
                    checkpoint_result = None
                result = run_codex_turn(thread_id, prompt, image_paths, turn_cwd)
            except RuntimeError as error:
                self.write_json({"error": str(error)}, status=500)
                return
            finally:
                TURN_LOCK.release()

            if checkpoint_result is not None:
                result["checkpoint"] = checkpoint_result
            self.write_json(result)
            return

        if path not in {"/link", "/codex/link"}:
            self.write_json({"error": "not found"}, status=404)
            return

        length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(length).decode("utf-8", errors="replace")
        record = {
            "receivedAt": datetime.now(tz=timezone.utc).isoformat().replace("+00:00", "Z"),
            "headers": dict(self.headers.items()),
            "body": body,
        }
        self.link_log_path.parent.mkdir(parents=True, exist_ok=True)
        with self.link_log_path.open("a", encoding="utf-8") as handle:
            handle.write(json.dumps(record, ensure_ascii=False) + "\n")
        self.write_json({"ok": True})

    def read_json_body(self) -> dict[str, Any]:
        length = int(self.headers.get("Content-Length", "0"))
        if length <= 0:
            return {}
        body = self.rfile.read(length).decode("utf-8", errors="replace")
        try:
            payload = json.loads(body)
        except json.JSONDecodeError as error:
            raise ValueError(f"invalid JSON body: {error}") from error
        if not isinstance(payload, dict):
            raise ValueError("JSON body must be an object")
        return payload

    def authorized(self) -> bool:
        if not self.token:
            return True
        return self.headers.get("Authorization") == f"Bearer {self.token}"

    def write_json(self, value: dict[str, Any], status: int = 200) -> None:
        body = json.dumps(value, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def write_file(self, path: Path) -> None:
        content_type = mimetypes.guess_type(str(path))[0] or "application/octet-stream"
        body = path.read_bytes()
        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format: str, *args: Any) -> None:
        return


def run_codex_turn(
    thread_id: str,
    prompt: str,
    image_paths: list[Path] | None = None,
    cwd: str | None = None,
) -> dict[str, Any]:
    npm = shutil.which("npm.cmd") or shutil.which("npm")
    if not npm:
        raise RuntimeError("npm was not found on PATH")
    if not PHASE0_DIR.exists():
        raise RuntimeError(f"phase0-validation folder not found: {PHASE0_DIR}")

    payload_path: Path | None = None
    try:
        tmp_dir = PROJECT_ROOT / "desktop_bridge" / "tmp"
        tmp_dir.mkdir(parents=True, exist_ok=True)
        with tempfile.NamedTemporaryFile("w", encoding="utf-8", suffix=".json", delete=False, dir=tmp_dir) as handle:
            payload_path = Path(handle.name)
            json.dump(
                {
                    "threadId": thread_id,
                    "prompt": prompt,
                    "cwd": clean_windows_path(cwd or ""),
                    "images": [{"path": str(path), "detail": "high"} for path in image_paths or []],
                },
                handle,
            )
        command = [npm, "run", "send-turn", "--", "--payload-file", str(payload_path)]

        completed = subprocess.run(
            command,
            cwd=PHASE0_DIR,
            text=True,
            capture_output=True,
            timeout=240,
        )
    finally:
        if payload_path is not None:
            try:
                payload_path.unlink()
            except OSError:
                pass

    stdout = completed.stdout.strip()
    stderr = completed.stderr.strip()
    json_line = next(
        (line for line in reversed(stdout.splitlines()) if line.strip().startswith("{")),
        "",
    )
    if completed.returncode != 0:
        detail = stderr or stdout or f"exit code {completed.returncode}"
        raise RuntimeError(f"Codex turn failed: {detail}")
    if not json_line:
        raise RuntimeError("Codex turn did not return JSON")

    try:
        result = json.loads(json_line)
    except json.JSONDecodeError as error:
        raise RuntimeError(f"Codex turn returned invalid JSON: {json_line}") from error

    if not isinstance(result, dict) or not result.get("ok"):
        raise RuntimeError(f"Codex turn failed: {json_line}")

    return {
        "ok": True,
        "source": "codex-app-server",
        "threadId": result.get("threadId"),
        "turnId": result.get("turnId"),
        "agentText": result.get("agentText") or "",
        "notificationCounts": result.get("notificationCounts") or {},
    }


def phase0_npm() -> str:
    npm = shutil.which("npm.cmd") or shutil.which("npm")
    if not npm:
        raise RuntimeError("npm was not found on PATH")
    if not PHASE0_DIR.exists():
        raise RuntimeError(f"phase0-validation folder not found: {PHASE0_DIR}")
    return npm


def parse_phase0_json(completed: subprocess.CompletedProcess[str], operation: str) -> dict[str, Any]:
    stdout = completed.stdout.strip()
    stderr = completed.stderr.strip()
    json_line = next(
        (line for line in reversed(stdout.splitlines()) if line.strip().startswith("{")),
        "",
    )
    if completed.returncode != 0:
        detail = stderr or stdout or f"exit code {completed.returncode}"
        raise RuntimeError(f"{operation} failed: {detail}")
    if not json_line:
        raise RuntimeError(f"{operation} did not return JSON")

    try:
        result = json.loads(json_line)
    except json.JSONDecodeError as error:
        raise RuntimeError(f"{operation} returned invalid JSON: {json_line}") from error

    if not isinstance(result, dict) or not result.get("ok"):
        raise RuntimeError(f"{operation} failed: {json_line}")
    return result


def run_codex_start_thread(cwd: str, prompt: str = "") -> dict[str, Any]:
    command = [phase0_npm(), "run", "start-thread", "--", cwd]
    if prompt:
        command.append(prompt)
    completed = subprocess.run(
        command,
        cwd=PHASE0_DIR,
        text=True,
        capture_output=True,
        timeout=240,
    )
    result = parse_phase0_json(completed, "Codex thread start")
    return {
        "ok": True,
        "source": "codex-app-server",
        "threadId": result.get("threadId"),
        "cwd": result.get("cwd") or cwd,
        "turnId": result.get("turnId") or "",
        "agentText": result.get("agentText") or "",
        "notificationCounts": result.get("notificationCounts") or {},
    }


def run_codex_interrupt(thread_id: str, turn_id: str) -> dict[str, Any]:
    completed = subprocess.run(
        [phase0_npm(), "run", "interrupt-turn", "--", thread_id, turn_id],
        cwd=PHASE0_DIR,
        text=True,
        capture_output=True,
        timeout=60,
    )
    result = parse_phase0_json(completed, "Codex turn interrupt")
    return {
        "ok": True,
        "source": "codex-app-server",
        "threadId": result.get("threadId") or thread_id,
        "turnId": result.get("turnId") or turn_id,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Expose local Codex desktop metadata to Codex Link.")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=18765)
    parser.add_argument("--codex-home", type=Path, default=DEFAULT_CODEX_HOME)
    parser.add_argument(
        "--link-log",
        type=Path,
        default=Path(__file__).with_name("received_links.jsonl"),
    )
    parser.add_argument("--token", default=os.environ.get("CODEX_LINK_TOKEN"))
    args = parser.parse_args()

    CodexLinkHandler.codex_home = args.codex_home
    CodexLinkHandler.token = args.token
    CodexLinkHandler.link_log_path = args.link_log

    server = ThreadingHTTPServer((args.host, args.port), CodexLinkHandler)
    print(f"Codex Link bridge listening on http://{args.host}:{args.port}")
    server.serve_forever()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
