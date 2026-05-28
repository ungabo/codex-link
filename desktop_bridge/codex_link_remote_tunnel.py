from __future__ import annotations

import base64
import json
import socket
import sys
import time
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen


CONFIG_PATH = Path(__file__).with_name("remote_tunnel_config.json")


def log(message: str, *, error: bool = False) -> None:
    print(message, file=sys.stderr if error else sys.stdout, flush=True)


def load_config() -> dict[str, Any]:
    if not CONFIG_PATH.exists():
        raise RuntimeError(f"Remote tunnel config not found: {CONFIG_PATH}")
    return json.loads(CONFIG_PATH.read_text(encoding="utf-8-sig"))


def request_bytes(
    url: str,
    method: str = "GET",
    headers: dict[str, str] | None = None,
    body: bytes | None = None,
    timeout: float = 30,
) -> tuple[int, dict[str, str], bytes]:
    request = Request(url, data=body, method=method.upper())
    for key, value in (headers or {}).items():
        if value:
            request.add_header(key, value)
    try:
        with urlopen(request, timeout=timeout) as response:
            return response.status, dict(response.headers.items()), response.read()
    except HTTPError as error:
        return error.code, dict(error.headers.items()), error.read()


def worker_url(base: str, action: str, token: str) -> str:
    return base + "?" + urlencode({"action": action, "token": token})


def next_job(config: dict[str, Any]) -> dict[str, Any] | None:
    status, _headers, body = request_bytes(
        worker_url(str(config["relayWorkerUrl"]), "next", str(config["workerToken"])),
        timeout=20,
    )
    if status == 204:
        return None
    if status < 200 or status >= 300:
        raise RuntimeError(f"relay next returned HTTP {status}: {body[:500].decode('utf-8', 'replace')}")
    payload = json.loads(body.decode("utf-8"))
    if not payload.get("ok"):
        raise RuntimeError(str(payload.get("error") or "relay next failed"))
    job = payload.get("job")
    return job if isinstance(job, dict) else None


def complete_job(config: dict[str, Any], result: dict[str, Any]) -> None:
    body = json.dumps(result).encode("utf-8")
    status, _headers, response_body = request_bytes(
        worker_url(str(config["relayWorkerUrl"]), "complete", str(config["workerToken"])),
        method="POST",
        headers={"Content-Type": "application/json; charset=utf-8"},
        body=body,
        timeout=30,
    )
    if status < 200 or status >= 300:
        raise RuntimeError(f"relay complete returned HTTP {status}: {response_body[:500].decode('utf-8', 'replace')}")


def complete_job_with_retry(config: dict[str, Any], result: dict[str, Any]) -> None:
    last_error: Exception | None = None
    for attempt in range(1, 4):
        try:
            complete_job(config, result)
            return
        except Exception as error:
            last_error = error
            log(f"Relay complete attempt {attempt} failed for {result.get('id')}: {error}", error=True)
            time.sleep(attempt)
    if last_error is not None:
        raise last_error


def local_url_for(config: dict[str, Any], job: dict[str, Any]) -> str:
    base = str(config.get("localBaseUrl") or "http://127.0.0.1:18765").rstrip("/")
    route = str(job.get("route") or "/")
    if not route.startswith("/"):
        route = "/" + route
    query = str(job.get("query") or "")
    if query:
        return base + route + "?" + query
    return base + route


def forwarded_headers(headers: dict[str, str]) -> dict[str, str]:
    allowed = {}
    for key in ("Content-Disposition", "content-disposition"):
        value = headers.get(key)
        if value:
            allowed["Content-Disposition"] = value
            break
    return allowed


def route_timeout_seconds(config: dict[str, Any], job: dict[str, Any]) -> float:
    method = str(job.get("method") or "GET").upper()
    route = str(job.get("route") or "/").lower()
    if method == "GET":
        if route.endswith("/status") or route in {"/host/status", "/server-health", "/health", "/threads"}:
            return float(config.get("statusTimeoutSeconds") or 20)
        if "/files/" in route or route.endswith("/download") or route.endswith("/apk"):
            return float(config.get("downloadTimeoutSeconds") or 90)
        return float(config.get("getTimeoutSeconds") or 30)
    if method == "POST" and (route == "/threads" or route.endswith("/turns")):
        return float(config.get("turnTimeoutSeconds") or 120)
    return float(config.get("defaultLocalTimeoutSeconds") or config.get("localTimeoutSeconds") or 60)


def handle_job(config: dict[str, Any], job: dict[str, Any]) -> dict[str, Any]:
    job_id = str(job.get("id") or "")
    local_timeout = route_timeout_seconds(config, job)
    body = base64.b64decode(str(job.get("bodyBase64") or ""))
    headers = {
        "Accept": str(job.get("accept") or "application/json"),
        "Content-Type": str(job.get("contentType") or "application/json; charset=utf-8"),
    }
    local_token = str(config.get("localToken") or "")
    if local_token:
        headers["Authorization"] = "Bearer " + local_token

    try:
        status, response_headers, response_body = request_bytes(
            local_url_for(config, job),
            method=str(job.get("method") or "GET"),
            headers=headers,
            body=body if body else None,
            timeout=local_timeout,
        )
        return {
            "id": job_id,
            "status": status,
            "contentType": response_headers.get("Content-Type", "application/json; charset=utf-8"),
            "headers": forwarded_headers(response_headers),
            "bodyBase64": base64.b64encode(response_body).decode("ascii"),
        }
    except (OSError, URLError, TimeoutError, socket.timeout) as error:
        error_body = json.dumps({"ok": False, "error": f"Windows tunnel failed: {error}"}).encode("utf-8")
        return {
            "id": job_id,
            "status": 502,
            "contentType": "application/json; charset=utf-8",
            "bodyBase64": base64.b64encode(error_body).decode("ascii"),
        }


def main() -> int:
    config = load_config()
    poll_seconds = float(config.get("pollSeconds") or 1.0)
    log("Codex Link remote tunnel started.")
    log(f"Relay worker: {config['relayWorkerUrl']}")
    log(f"Local bridge: {config.get('localBaseUrl', 'http://127.0.0.1:18765')}")
    while True:
        try:
            job = next_job(config)
            if job is None:
                time.sleep(poll_seconds)
                continue
            started = time.monotonic()
            timeout = route_timeout_seconds(config, job)
            log(f"Forwarding relay job {job.get('id')} {job.get('method')} {job.get('route')} timeout={timeout:.0f}s")
            result = handle_job(config, job)
            complete_job_with_retry(config, result)
            elapsed = time.monotonic() - started
            log(f"Completed relay job {job.get('id')} HTTP {result.get('status')} in {elapsed:.1f}s")
        except KeyboardInterrupt:
            log("Stopping remote tunnel.")
            return 0
        except Exception as error:
            log(f"Remote tunnel error: {error}", error=True)
            time.sleep(max(2.0, poll_seconds))


if __name__ == "__main__":
    raise SystemExit(main())
