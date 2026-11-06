#!/bin/bash
set -Eeuo pipefail
export DEBIAN_FRONTEND=noninteractive
mkdir -p /etc/ssl/certs
if [[ ! -s /etc/ssl/certs/ca-certificates.crt ]]; then
  cp /data/data/com.termux/files/usr/etc/tls/cert.pem /etc/ssl/certs/ca-certificates.crt
fi
if [[ -f /etc/apt/sources.list.d/ubuntu.sources ]]; then
  sed -i 's|http://ports.ubuntu.com/ubuntu-ports|https://mirrors.ustc.edu.cn/ubuntu-ports|g' /etc/apt/sources.list.d/ubuntu.sources
fi
if ! python3 -c 'import aiohttp, venv' >/dev/null 2>&1 || ! command -v pip3 >/dev/null 2>&1 || ! command -v git >/dev/null 2>&1 || ! command -v xz >/dev/null 2>&1; then
  apt-get -o Acquire::Retries=1 -o Acquire::https::Timeout=30 update
  apt-get install -y ca-certificates curl python3 python3-aiohttp python3-pip python3-venv git xz-utils libstdc++6 build-essential
fi
mkdir -p "$HOME/.local/bin"
export PATH="$HOME/.local/bin:$PATH"
if ! command -v devin >/dev/null 2>&1; then
  task_installer="$(mktemp)"
  trap 'rm -f -- "$task_installer"' EXIT
  curl --fail --show-error --location --proto '=https' --proto-redir '=https' https://cli.devin.ai/install.sh -o "$task_installer"
  bash "$task_installer"
fi
python3 - <<'PY'
import hashlib
import json
from pathlib import Path
import re
import tarfile
import urllib.request
import subprocess
import os
import shutil

root = Path("/mnt/devin-local")
node = Path.home() / ".local/share/devin-local-node"
binary = node / "bin/node"
needs_node = True
if binary.exists():
    needs_node = not subprocess.check_output([str(binary), "--version"], text=True).startswith("v24.")
if needs_node:
    transfers = root / "transfers"
    transfers.mkdir(exist_ok=True)
    archive = transfers / "node-arm64.tar.xz"
    metadata = json.loads((Path(os.environ["DEVIN_ANDROID_SOURCE"]) / "node-release.json").read_text())
    expected, filename = metadata["sha256"], metadata["filename"]
    if not re.fullmatch(r"node-v24\.\d+\.\d+-linux-arm64\.tar\.xz", filename):
        raise RuntimeError("Invalid bundled Node release")
    if not archive.exists() or hashlib.sha256(archive.read_bytes()).hexdigest() != expected:
        version = filename.removesuffix("-linux-arm64.tar.xz").removeprefix("node-")
        urls = [f"https://nodejs.org/dist/{version}/{filename}",
                f"https://mirrors.ustc.edu.cn/node/{version}/{filename}"]
        for url in urls:
            try:
                with urllib.request.urlopen(url, timeout=40) as response, archive.open("wb") as output:
                    shutil.copyfileobj(response, output)
                if hashlib.sha256(archive.read_bytes()).hexdigest() == expected:
                    break
            except OSError:
                pass
        else:
            archive.unlink(missing_ok=True)
            raise RuntimeError("Node archive could not be downloaded and verified")
    if hashlib.sha256(archive.read_bytes()).hexdigest() != expected:
        raise RuntimeError("Node official archive checksum mismatch")
    node.parent.mkdir(parents=True, exist_ok=True)
    staging = Path(__import__("tempfile").mkdtemp(prefix="node-", dir=node.parent))
    with tarfile.open(archive) as source:
        for member in source.getmembers():
            if not (staging / member.name).resolve().is_relative_to(staging.resolve()):
                raise RuntimeError("Archive escaped runtime directory")
        source.extractall(staging, filter="data")
    extracted = next(x for x in staging.iterdir() if x.is_dir() and x.name.startswith("node-v24."))
    if node.exists():
        # This managed directory contains only a previously installed Node distribution.
        shutil.rmtree(node)
    extracted.rename(node)
    staging.rmdir()
for name, target in (("node", node / "bin/node"), ("npm", node / "bin/npm"), ("npx", node / "bin/npx")):
    link = Path.home() / ".local/bin" / name
    if link.is_symlink() or link.exists():
        link.unlink()
    link.symlink_to(target)
rules = Path.home() / ".config/devin/AGENTS.md"
rules.parent.mkdir(parents=True, exist_ok=True)
text = rules.read_text() if rules.exists() else ""
if "<!-- devin-local-android -->" not in text:
    rules.write_text(text + "\n<!-- devin-local-android -->\n默认用简体中文解释任务和结果，保留代码、文件名与命令的原意。"
        "开发环境在当前手机本机；不将执行任务转移到 Devin Cloud 或使用云端开发虚拟机。"
        "文件修改和命令执行遵守现有官方账号与组织权限规则。\n<!-- /devin-local-android -->\n")
PY
