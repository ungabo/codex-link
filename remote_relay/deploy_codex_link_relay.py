from __future__ import annotations

import json
import posixpath
import secrets
import sys
import tempfile
from pathlib import Path

import paramiko


PROJECT_ROOT = Path(__file__).resolve().parents[1]
RELAY_DIR = PROJECT_ROOT / "remote_relay"
BRIDGE_DIR = PROJECT_ROOT / "desktop_bridge"
CONFIG_PATH = BRIDGE_DIR / "remote_tunnel_config.json"
DEFAULT_PUBLIC_BASE_URL = "https://www.sitesindevelopment.com/codex-link"
DEFAULT_PHONE_ENDPOINT = DEFAULT_PUBLIC_BASE_URL + "/index.php/link"


def parse_info(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if ":" in line:
            key, value = line.split(":", 1)
        elif "=" in line:
            key, value = line.split("=", 1)
        else:
            continue
        values[key.strip().lower()] = value.strip()
    return values


def load_or_create_tunnel_config() -> dict[str, object]:
    BRIDGE_DIR.mkdir(parents=True, exist_ok=True)
    if CONFIG_PATH.exists():
        config = json.loads(CONFIG_PATH.read_text(encoding="utf-8-sig"))
    else:
        config = {}
    config.setdefault("publicBaseUrl", DEFAULT_PUBLIC_BASE_URL)
    if config.get("phoneEndpoint") in {None, "", DEFAULT_PUBLIC_BASE_URL + "/link"}:
        config["phoneEndpoint"] = DEFAULT_PHONE_ENDPOINT
    config.setdefault("relayWorkerUrl", DEFAULT_PUBLIC_BASE_URL + "/worker.php")
    config.setdefault("phoneToken", secrets.token_urlsafe(32))
    config.setdefault("workerToken", secrets.token_urlsafe(32))
    config.setdefault("localBaseUrl", "http://127.0.0.1:18765")
    config.setdefault("localToken", "")
    config.setdefault("pollSeconds", 1.0)
    config.setdefault("localTimeoutSeconds", 310)
    CONFIG_PATH.write_text(json.dumps(config, indent=2) + "\n", encoding="utf-8")
    return config


def php_string(value: str) -> str:
    return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"


def write_generated_config(tmp_dir: Path, config: dict[str, object]) -> Path:
    phone_token = str(config["phoneToken"])
    worker_token = str(config["workerToken"])
    config_php = tmp_dir / "config.php"
    config_php.write_text(
        "<?php\n"
        "declare(strict_types=1);\n"
        "const PHONE_TOKEN = " + php_string(phone_token) + ";\n"
        "const WORKER_TOKEN = " + php_string(worker_token) + ";\n"
        "const PHONE_WAIT_SECONDS = 240;\n",
        encoding="utf-8",
    )
    return config_php


def mkdir_p(sftp: paramiko.SFTPClient, remote_path: str) -> None:
    parts = [part for part in remote_path.replace("\\", "/").split("/") if part]
    current = "/" if remote_path.startswith("/") else ""
    for part in parts:
        current = posixpath.join(current, part) if current else part
        try:
            sftp.stat(current)
        except FileNotFoundError:
            sftp.mkdir(current)


def upload_file(sftp: paramiko.SFTPClient, local: Path, remote: str) -> None:
    sftp.put(str(local), remote)


def main() -> int:
    info_path = Path.home() / "Desktop" / "sitesindevelopment-sftp-info.txt"
    if not info_path.exists():
        print(f"Missing SFTP info file: {info_path}", file=sys.stderr)
        return 1

    info = parse_info(info_path)
    host = info.get("host", "")
    port = int(info.get("port", "22"))
    username = info.get("username", "")
    password = info.get("password", "")
    remote_root = info.get("remote folder", info.get("remote_folder", "")).replace("\\", "/").rstrip("/")
    if not all([host, username, password, remote_root]):
        print("SFTP info file is missing host, username, password, or remote folder.", file=sys.stderr)
        return 1

    config = load_or_create_tunnel_config()
    remote_base = posixpath.join(remote_root, "codex-link")
    remote_data = posixpath.join(remote_base, "data")

    transport = paramiko.Transport((host, port))
    transport.connect(username=username, password=password)
    try:
        sftp = paramiko.SFTPClient.from_transport(transport)
        try:
            mkdir_p(sftp, remote_base)
            mkdir_p(sftp, posixpath.join(remote_data, "jobs"))
            mkdir_p(sftp, posixpath.join(remote_data, "processing"))
            mkdir_p(sftp, posixpath.join(remote_data, "results"))

            upload_file(sftp, RELAY_DIR / "index.php", posixpath.join(remote_base, "index.php"))
            upload_file(sftp, RELAY_DIR / "worker.php", posixpath.join(remote_base, "worker.php"))
            upload_file(sftp, RELAY_DIR / ".htaccess", posixpath.join(remote_base, ".htaccess"))
            upload_file(sftp, RELAY_DIR / "data" / ".htaccess", posixpath.join(remote_data, ".htaccess"))
            with tempfile.TemporaryDirectory() as tmp:
                config_php = write_generated_config(Path(tmp), config)
                upload_file(sftp, config_php, posixpath.join(remote_base, "config.php"))
        finally:
            sftp.close()
    finally:
        transport.close()

    print("Uploaded codex-link relay.")
    print(f"Public base URL: {config['publicBaseUrl']}")
    print(f"Phone endpoint: {config['phoneEndpoint']}")
    print(f"Local tunnel config: {CONFIG_PATH}")
    print("Phone and worker tokens were written to the local tunnel config file; they were not printed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
