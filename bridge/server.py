#!/usr/bin/env python3
"""Private, authenticated loopback bridge for the unofficial Android client."""
from __future__ import annotations
import argparse
import asyncio
import base64
from collections import deque
import difflib
import hashlib
import io
import json
import mimetypes
import os
from pathlib import Path
import platform
import re
import secrets
import shutil
import signal
import struct
import tarfile
import tempfile
import time
import uuid
import zipfile
import urllib.request
from urllib.parse import urlsplit, unquote, quote
if os.name == "posix":
    import pty
    import termios
    import fcntl
from aiohttp import web

VERSION = "0.2.0"
MAX_ATTACHMENT = 20 * 1024 * 1024
MAX_DOWNLOAD = 50 * 1024 * 1024
BRIDGE_HASH = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
ROOT = Path(os.environ.get("DEVIN_ANDROID_DATA", "/mnt/devin-local")).resolve()
PORT = int(os.environ.get("DEVIN_ANDROID_PORT", "8765"))
PROJECTS = ROOT / "workspaces"
SESSIONS = ROOT / "sessions"
AGENTS: dict[str, "Agent"] = {}
TERMINALS: dict[str, "Terminal"] = {}
EVENTS: set[web.WebSocketResponse] = set()
UPDATE = {"state": "idle"}
UPDATE_TASK = None
CLI = str(Path.home() / ".local/bin/devin")
CLI_ARGS = [CLI]
TOKEN = ""
os.environ["PATH"] = str(Path.home() / ".local/bin") + os.pathsep + os.environ.get("PATH", "")


def initialize():
    global TOKEN, UPDATE
    os.umask(0o077)
    for folder in (ROOT, PROJECTS, SESSIONS):
        folder.mkdir(parents=True, exist_ok=True)
    token_file = ROOT / "token"
    if not token_file.exists():
        token_file.write_text(secrets.token_urlsafe(32))
    token_file.chmod(0o600)
    TOKEN = token_file.read_text().strip()
    if len(TOKEN) < 40:
        raise RuntimeError("Invalid local pairing token")
    marker = ROOT / "core-update.json"
    if marker.exists():
        state = json.loads(marker.read_text(encoding="utf-8"))
        if state.get("state") == "running":
            saved = Path(state["backup"]).resolve()
            versions = Path.home() / ".local/share/devin/cli/_versions"
            previous = Path(state["previous"]).resolve()
            if not saved.is_relative_to((ROOT / "backups").resolve()) or not previous.is_relative_to(versions.resolve()):
                raise RuntimeError("Invalid private update recovery record")
            activate_version(versions, previous)
            quarantine = saved / ("interrupted-" + secrets.token_hex(4))
            for name, destination in (("config", Path.home() / ".config/devin"),
                                      ("data", Path.home() / ".local/share/devin")):
                restore_private_tree(saved / name, destination, quarantine / name)
            UPDATE = {"state": "failed", "message": "上次核心更新被中断，已恢复原核心和配置"}
            atomic_json(marker, UPDATE)
    # Processes do not survive a bridge restart. Retain history and mark it honestly.
    for path in SESSIONS.glob("*.json"):
        meta = json.loads(path.read_text(encoding="utf-8"))
        if meta.get("state") in ("running", "ready", "waiting"):
            meta["state"] = "interrupted"
            atomic_json(path, meta)


def atomic_json(path, value):
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False), encoding="utf-8")
    temporary.chmod(0o600)
    os.replace(temporary, path)


def error(message, status=400):
    # HTTPException's generic constructor does not accept an arbitrary status.
    response = web.json_response({"error": message}, status=status)
    raise ApiError(response)


class ApiError(Exception):
    def __init__(self, response):
        self.response = response


@web.middleware
async def guard(request, handler):
    if request.remote not in ("127.0.0.1", "::1"):
        return web.json_response({"error": "仅支持手机本地连接"}, status=403)
    if request.headers.get("Origin"):
        return web.json_response({"error": "不接受网页跨域请求"}, status=403)
    supplied = request.headers.get("Authorization", "")
    if not secrets.compare_digest(supplied.encode(), ("Bearer " + TOKEN).encode()):
        return web.json_response({"error": "本地连接凭据无效，请重新连接 Termux"}, status=401)
    try:
        return await handler(request)
    except ApiError as exc:
        return exc.response
    except (ValueError, KeyError, UnicodeError, zipfile.BadZipFile):
        return web.json_response({"error": "请求或文件格式不正确"}, status=400)
    except FileNotFoundError:
        return web.json_response({"error": "文件或项目不存在"}, status=404)
    except web.HTTPException:
        raise
    except Exception:
        # Do not expose command lines, credentials, raw CLI errors, or private paths.
        return web.json_response({"error": "本地操作失败，请检查环境状态后重试"}, status=500)


def project(pid):
    if str(uuid.UUID(pid)) != pid:
        error("项目编号不正确")
    folder = PROJECTS / pid
    if not (folder / "project.json").exists():
        error("项目不存在", 404)
    return folder / "files"


def safe_path(root, relative, must_exist=False):
    if not isinstance(relative, str) or "\0" in relative:
        error("文件路径不正确")
    candidate = (root / relative).resolve(strict=must_exist)
    if not candidate.is_relative_to(root.resolve()):
        error("文件路径超出当前项目", 403)
    return candidate


def revision(path):
    return hashlib.sha256(path.read_bytes()).hexdigest() if path.exists() else "missing"


async def event(data):
    data = {**data, "anyBusy": any(x.busy for x in AGENTS.values()),
            "anyWaiting": any(x.meta.get("state") == "waiting" for x in AGENTS.values())}
    for socket in list(EVENTS):
        try:
            await socket.send_json(data)
        except (ConnectionError, RuntimeError):
            EVENTS.discard(socket)


async def health(_):
    return web.json_response({"apiVersion": 1, "bridgeVersion": VERSION,
                             "bridgeHash": BRIDGE_HASH,
                             "bundleHash": os.environ.get("DEVIN_ANDROID_BUNDLE_HASH", ""),
                             "local": True, "agentBusy": any(x.busy for x in AGENTS.values()),
                             "update": UPDATE})


async def events(request):
    socket = web.WebSocketResponse(heartbeat=25)
    await socket.prepare(request)
    EVENTS.add(socket)
    await socket.send_json({"type": "connected"})
    try:
        async for _ in socket:
            pass
    finally:
        EVENTS.discard(socket)
    return socket


async def projects(request):
    if request.method == "GET":
        values = [json.loads(x.read_text(encoding="utf-8")) for x in PROJECTS.glob("*/project.json")]
        return web.json_response(sorted(values, key=lambda x: x["created"], reverse=True))
    data = await request.json()
    name = str(data.get("name", "")).strip()[:100]
    if not name or any(ord(c) < 32 for c in name):
        error("请输入项目名称")
    pid = str(uuid.uuid4())
    folder = PROJECTS / pid
    files = folder / "files"
    files.mkdir(parents=True)
    kind = data.get("kind", "python")
    if kind == "python":
        (files / "main.py").write_text('print("你好，手机本地环境！")\n', encoding="utf-8")
    elif kind == "web":
        (files / "index.html").write_text('<!doctype html><meta charset="utf-8"><h1>你好，手机本地网站！</h1>\n', encoding="utf-8")
    elif kind != "empty":
        error("不支持的项目类型")
    meta = {"id": pid, "name": name, "kind": kind, "created": time.time()}
    atomic_json(folder / "project.json", meta)
    await command(["git", "init", "-q"], files)
    return web.json_response(meta, status=201)


async def command(arguments, cwd, timeout=60, input_data=None):
    process = await asyncio.create_subprocess_exec(*arguments, cwd=cwd,
        stdin=asyncio.subprocess.PIPE if input_data is not None else asyncio.subprocess.DEVNULL,
        stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE,
        env={**os.environ, "GIT_TERMINAL_PROMPT": "0"})
    try:
        out, err = await asyncio.wait_for(process.communicate(input_data), timeout)
    except asyncio.TimeoutError:
        process.kill()
        await process.wait()
        error("操作超时，请在终端检查网络或完成认证", 504)
    return process.returncode, out, err


async def file_list(request):
    root = project(request.match_info["pid"])
    relative = request.query.get("path", "")
    folder = safe_path(root, relative, True)
    if not folder.is_dir():
        error("此路径不是文件夹")
    rows = []
    for path in folder.iterdir():
        if path.name == ".git":
            continue
        if path.is_symlink():
            continue
        rows.append({"name": path.name, "path": str(path.relative_to(root)),
                     "directory": path.is_dir(), "size": path.stat().st_size})
    return web.json_response(sorted(rows, key=lambda x: (not x["directory"], x["name"].lower())))


async def file_content(request):
    root = project(request.match_info["pid"])
    path = safe_path(root, request.query.get("path", ""))
    if path == root:
        error("请选择文件")
    if request.method == "GET":
        if path.stat().st_size > 2 * 1024 * 1024:
            error("此文件超过 2MB，请在终端中编辑", 413)
        data = path.read_text(encoding="utf-8")
        return web.json_response({"content": data, "revision": revision(path)})
    data = await request.json()
    current = revision(path)
    if any(x.busy and x.meta["projectId"] == request.match_info["pid"] for x in AGENTS.values()):
        error("代理正在处理此项目，请保留草稿并在任务结束后保存", 409)
    if data.get("revision") != current:
        return web.json_response({"error": "文件已被代理或其他编辑器修改，请重新读取并合并草稿",
                                  "revision": current}, status=409)
    content = str(data.get("content", ""))
    if len(content.encode()) > 2 * 1024 * 1024:
        error("文件超过编辑器大小限制", 413)
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(prefix=".android-save-", dir=path.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="") as output:
            output.write(content)
        if revision(path) != current:
            error("文件刚刚发生变化，请重新读取后合并草稿", 409)
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)
    return web.json_response({"revision": revision(path)})


def download_path(root, value):
    parsed = urlsplit(value)
    if parsed.scheme and (parsed.scheme != "file" or parsed.netloc not in ("", "localhost")):
        error("只能下载当前项目中的本地文件", 403)
    relative = urllib.request.url2pathname(parsed.path) if parsed.scheme else value
    path = safe_path(root, relative)
    # Markdown parsers percent-encode Unicode and spaces; file pickers send literal paths.
    # Prefer an existing literal filename, then resolve an encoded link with the same boundary checks.
    if not parsed.scheme and not path.exists() and unquote(relative) != relative:
        path = safe_path(root, unquote(relative))
    if ".git" in path.relative_to(root.resolve()).parts:
        error("不能通过对话下载 Git 内部文件", 403)
    if not path.is_file():
        error("文件不存在或不是普通文件", 404)
    return path


async def file_download(request):
    root = project(request.match_info["pid"])
    path = download_path(root, request.query.get("path", ""))
    if path.stat().st_size > MAX_DOWNLOAD:
        error("文件超过 50MB，请通过终端或项目导出处理", 413)
    content = path.read_bytes()
    if len(content) > MAX_DOWNLOAD:
        error("文件超过 50MB", 413)
    return web.Response(body=content, content_type=mimetypes.guess_type(path.name)[0] or "application/octet-stream",
        headers={"Content-Disposition": "attachment; filename*=UTF-8''" + quote(path.name),
                 "X-Content-Type-Options": "nosniff", "X-Content-SHA256": hashlib.sha256(content).hexdigest()})


async def attachment_upload(request):
    pid = request.match_info["pid"]
    root = project(pid)
    name = request.query.get("name", "").strip()
    if not name or len(name) > 180 or name in (".", "..") or any(x in name for x in ("/", "\\")) or any(ord(x) < 32 for x in name):
        error("附件文件名不正确")
    if request.content_length and request.content_length > MAX_ATTACHMENT:
        error("单个附件不能超过 20MB", 413)
    aid = str(uuid.uuid4())
    relative = "attachments/" + aid + "/" + name
    path = safe_path(root, relative)
    path.parent.mkdir(parents=True, exist_ok=False)
    size = 0
    digest = hashlib.sha256()
    try:
        with path.open("xb") as output:
            async for chunk in request.content.iter_chunked(64 * 1024):
                size += len(chunk)
                if size > MAX_ATTACHMENT:
                    error("单个附件不能超过 20MB", 413)
                digest.update(chunk)
                output.write(chunk)
        mime = mimetypes.guess_type(name)[0] or "application/octet-stream"
        meta = {"id": aid, "projectId": pid, "name": name, "path": relative,
                "mimeType": mime, "size": size, "sha256": digest.hexdigest()}
        store = ROOT / "attachments" / pid
        store.mkdir(parents=True, exist_ok=True)
        atomic_json(store / (aid + ".json"), meta)
        return web.json_response(meta, status=201)
    except BaseException:
        path.unlink(missing_ok=True)
        path.parent.rmdir()
        raise


def prompt_attachments(agent, ids):
    if not isinstance(ids, list) or len(ids) > 8 or any(not isinstance(x, str) for x in ids) or len(set(ids)) != len(ids):
        error("每次最多附加 8 个不同文件")
    root = project(agent.meta["projectId"])
    attachments, blocks = [], []
    capabilities = agent.initialized.get("agentCapabilities", {}).get("promptCapabilities", {})
    model_images = True
    for option in agent.meta.get("configOptions", []):
        if option.get("category") == "model":
            values = [child for item in option.get("options", []) for child in item.get("options", [item])]
            current = next((x for x in values if x.get("value") == option.get("currentValue")), {})
            model_images = current.get("_meta", {}).get("cognition.ai/supportsImages", True)
    for aid in ids:
        try:
            if str(uuid.UUID(aid)) != aid: raise ValueError()
            meta = json.loads((ROOT / "attachments" / agent.meta["projectId"] / (aid + ".json")).read_text())
        except (ValueError, FileNotFoundError):
            error("附件不存在或不属于当前项目", 404)
        path = safe_path(root, meta["path"], True)
        if not path.is_file() or path.stat().st_size > MAX_ATTACHMENT or revision(path) != meta["sha256"]:
            error("附件已被修改，请重新上传后发送", 409)
        attachments.append(meta)
        link = {"type": "resource_link", "uri": path.as_uri(), "name": meta["name"],
                "mimeType": meta["mimeType"], "size": meta["size"]}
        blocks.append(link)
        if capabilities.get("image") and model_images and meta["mimeType"] in ("image/png", "image/jpeg", "image/webp", "image/gif") and meta["size"] <= 8 * 1024 * 1024:
            blocks.append({"type": "image", "mimeType": meta["mimeType"], "data": base64.b64encode(path.read_bytes()).decode()})
        elif capabilities.get("embeddedContext") and meta["size"] <= 200 * 1024:
            try:
                text = path.read_text(encoding="utf-8")
                if "\0" not in text:
                    blocks.append({"type": "resource", "resource": {"uri": path.as_uri(), "mimeType": meta["mimeType"], "text": text}})
            except UnicodeError:
                pass
    return attachments, blocks


async def search(request):
    root = project(request.match_info["pid"])
    query = request.query.get("q", "").strip()
    if not query:
        return web.json_response([])
    matches = []
    for current, folders, files in os.walk(root, followlinks=False):
        folders[:] = [x for x in folders if x not in (".git", "node_modules", ".venv", "__pycache__")]
        for name in files:
            path = Path(current) / name
            if path.is_symlink() or path.stat().st_size > 1024 * 1024:
                continue
            try:
                for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
                    if query.lower() in line.lower():
                        matches.append({"path": str(path.relative_to(root)), "line": number, "text": line[:250]})
                        if len(matches) >= 200:
                            return web.json_response(matches)
            except (UnicodeError, OSError):
                continue
    return web.json_response(matches)


async def git(request):
    root = project(request.match_info["pid"])
    if request.method == "GET":
        code, status, _ = await command(["git", "status", "--short"], root)
        diff_code, diff, _ = await command(["git", "--no-pager", "diff", "--no-ext-diff", "HEAD"], root)
        # A repository without an initial commit has no HEAD yet.
        if diff_code:
            _, cached, _ = await command(["git", "--no-pager", "diff", "--no-ext-diff", "--cached"], root)
            _, unstaged, _ = await command(["git", "--no-pager", "diff", "--no-ext-diff"], root)
            diff = cached + unstaged
        text = diff.decode(errors="replace")
        _, untracked, _ = await command(["git", "ls-files", "--others", "--exclude-standard", "-z"], root)
        for relative in untracked.decode(errors="replace").split("\0")[:100]:
            if not relative or len(text) > 200000:
                break
            path = safe_path(root, relative)
            if path.is_symlink() or not path.is_file() or path.stat().st_size > 2 * 1024 * 1024:
                continue
            try:
                text += "".join(difflib.unified_diff([], path.read_text(encoding="utf-8").splitlines(keepends=True),
                                                    fromfile="/dev/null", tofile="b/" + relative))
            except UnicodeError:
                pass
        if len(text) > 200000:
            text = text[:200000] + "\n（差异较大，完整内容请在终端查看）"
        return web.json_response({"status": status.decode(errors="replace"),
                                 "diff": text, "initialized": code == 0})
    data = await request.json()
    action = data.get("action")
    if action == "init":
        args = ["git", "init"]
    elif action == "stage":
        args = ["git", "add", "--all"]
    elif action == "commit":
        message = str(data.get("message", "")).strip()
        if not message:
            error("请输入提交说明")
        args = ["git", "commit", "-m", message]
    elif action in ("pull", "push"):
        args = ["git", action]
    elif action == "identity":
        name, email = str(data.get("name", "")).strip(), str(data.get("email", "")).strip()
        if not name or "@" not in email:
            error("请填写 Git 提交姓名和邮箱")
        await command(["git", "config", "user.name", name], root)
        args = ["git", "config", "user.email", email]
    elif action == "clone":
        url = str(data.get("url", "")).strip()
        if not url.startswith(("https://", "ssh://", "git@")) or "\n" in url:
            error("请使用 HTTPS 或 SSH 仓库地址")
        # Cloning imports into an empty existing project, without overwriting work.
        if any(x.name != ".git" for x in root.iterdir()):
            error("请先创建空项目，再克隆仓库", 409)
        temporary = root.parent / ("clone-" + secrets.token_hex(6))
        code, _, _ = await command(["git", "-c", "protocol.file.allow=never", "clone", "--", url, str(temporary)], root, 180)
        if code:
            if temporary.exists():
                shutil.rmtree(temporary)
            error("克隆失败，请在终端检查网络或完成 Git 认证")
        for item in list(root.iterdir()):
            if item.name == ".git":
                shutil.rmtree(item)
        for item in temporary.iterdir():
            shutil.move(str(item), str(root / item.name))
        temporary.rmdir()
        return web.json_response({"ok": True})
    else:
        error("不支持的 Git 操作")
    code, _, _ = await command(args, root, 180)
    if code:
        error("Git 操作未完成，请检查提交身份、仓库状态，或在终端完成认证", 409)
    return web.json_response({"ok": True})


async def archive(request):
    root = project(request.match_info["pid"])
    if request.method == "GET":
        stream = io.BytesIO()
        total = 0
        with zipfile.ZipFile(stream, "w", zipfile.ZIP_DEFLATED) as output:
            for path in root.rglob("*"):
                if ".git" in path.relative_to(root).parts or path.is_symlink() or not path.is_file():
                    continue
                total += path.stat().st_size
                if total > 200 * 1024 * 1024:
                    error("项目超过 200MB，请在终端打包", 413)
                output.write(path, str(path.relative_to(root)))
        return web.Response(body=stream.getvalue(), content_type="application/zip")
    if any(x.name != ".git" for x in root.iterdir()):
        error("请先创建空项目，再导入 ZIP，避免覆盖已有文件", 409)
    raw = await request.read()
    with zipfile.ZipFile(io.BytesIO(raw)) as source:
        members = source.infolist()
        if len(members) > 10000 or sum(x.file_size for x in members) > 200 * 1024 * 1024:
            error("压缩包展开后过大", 413)
        for item in members:
            if item.filename.startswith("/") or ".." in Path(item.filename.replace("\\", "/")).parts:
                error("压缩包包含越界文件", 403)
            if ((item.external_attr >> 16) & 0o170000) == 0o120000:
                error("压缩包包含符号链接，无法安全导入", 403)
            if ".git" in Path(item.filename.replace("\\", "/")).parts:
                error("请导入不含 .git 目录的项目文件；克隆仓库请使用 Git 入口", 403)
            safe_path(root, item.filename)
        for item in members:
            destination = safe_path(root, item.filename)
            if item.is_dir():
                destination.mkdir(parents=True, exist_ok=True)
            else:
                destination.parent.mkdir(parents=True, exist_ok=True)
                with source.open(item) as input_file, destination.open("wb") as output:
                    shutil.copyfileobj(input_file, output)
    return web.json_response({"ok": True})


def isolation_error(message):
    value = message.lower()
    return any(x in value for x in ("sandbox", "isolation", "隔离")) and any(
        x in value for x in ("required", "mandatory", "unsupported", "unavailable", "not supported", "policy", "要求"))


class Agent:
    def __init__(self, meta):
        self.meta = meta
        self.id = meta["id"]
        self.process = None
        self.pending = {}
        self.sequence = 0
        self.busy = False
        self.loading_history = False
        self.sockets = set()
        self.history = deque(maxlen=2000)
        history_file = SESSIONS / (self.id + ".jsonl")
        if history_file.exists():
            with history_file.open(encoding="utf-8") as source:
                for line in source:
                    self.history.append(json.loads(line))
        self.event_sequence = max((x.get("seq", 0) for x in self.history), default=0)
        self.permission_ids = {}
        self.initialized = {}
        self.reader = None
        self.stderr = None
        self.prompt_task = None
        self.failure_reason = ""

    def save(self):
        self.meta["updated"] = time.time()
        atomic_json(SESSIONS / (self.id + ".json"), self.meta)

    async def broadcast(self, message):
        self.event_sequence += 1
        message = {**message, "seq": self.event_sequence}
        self.history.append(message)
        # This is private local conversation history, never included in releases.
        with (SESSIONS / (self.id + ".jsonl")).open("a", encoding="utf-8") as log:
            log.write(json.dumps(message, ensure_ascii=False) + "\n")
        for socket in list(self.sockets):
            try:
                await socket.send_json(message)
            except (ConnectionError, RuntimeError):
                self.sockets.discard(socket)
        await event({"type": "agent", "id": self.id, "state": self.meta["state"], "busy": self.busy})

    async def send(self, message):
        self.process.stdin.write((json.dumps(message) + "\n").encode())
        await self.process.stdin.drain()

    async def rpc(self, method, params, timeout=45):
        self.sequence += 1
        request_id = self.sequence
        future = asyncio.get_running_loop().create_future()
        self.pending[request_id] = future
        try:
            await self.send({"jsonrpc": "2.0", "id": request_id, "method": method, "params": params})
            return await asyncio.wait_for(future, timeout) if timeout else await future
        finally:
            self.pending.pop(request_id, None)

    async def drain_stderr(self):
        while data := await self.process.stderr.read(8192):
            if isolation_error(data.decode(errors="replace")):
                self.failure_reason = "账号或组织要求当前手机无法提供的系统隔离，环境不兼容；请保留现有官方策略"

    async def read(self):
        try:
            while raw := await self.process.stdout.readline():
                message = json.loads(raw)
                if "method" in message:
                    if "id" in message:
                        if message["method"] == "session/request_permission":
                            self.permission_ids[str(message["id"])] = message
                            self.meta["state"] = "waiting"
                            self.save()
                        else:
                            await self.send({"jsonrpc": "2.0", "id": message["id"],
                                "error": {"code": -32601, "message": "Client method not supported"}})
                            continue
                    if message.get("method") == "session/update":
                        update = message.get("params", {}).get("update", {})
                        if update.get("sessionUpdate") == "config_option_update":
                            self.meta["configOptions"] = update.get("configOptions", [])
                            self.save()
                        elif update.get("sessionUpdate") == "current_mode_update":
                            self.meta.setdefault("modes", {})["currentModeId"] = update.get("currentModeId")
                            self.save()
                        if self.loading_history:
                            # session/load replays old updates; our durable history already has them.
                            continue
                    await self.broadcast({"type": "acp", "message": message})
                else:
                    future = self.pending.get(message.get("id"))
                    if future and not future.done():
                        if "error" in message:
                            if isolation_error(json.dumps(message["error"], ensure_ascii=False)):
                                self.failure_reason = "账号或组织要求当前手机无法提供的系统隔离，环境不兼容；请保留现有官方策略"
                            future.set_exception(RuntimeError(self.failure_reason or "官方代理拒绝了请求，请检查登录、账号权限或网络"))
                        else:
                            future.set_result(message.get("result", {}))
        except (ValueError, OSError, asyncio.CancelledError):
            pass
        finally:
            self.busy = False
            if self.meta["state"] != "closed":
                self.meta["state"] = "interrupted"
                self.save()
                await self.broadcast({"type": "state", "state": "interrupted",
                                      "message": "本地代理连接中断，可恢复会话后继续"})
            for future in self.pending.values():
                if not future.done():
                    future.set_exception(RuntimeError(self.failure_reason or "本地代理连接中断"))

    async def start(self):
        root = project(self.meta["projectId"])
        self.process = await asyncio.create_subprocess_exec(*CLI_ARGS, "acp", cwd=root,
            stdin=asyncio.subprocess.PIPE, stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE, start_new_session=os.name != "nt", limit=4 * 1024 * 1024)
        self.reader = asyncio.create_task(self.read())
        self.stderr = asyncio.create_task(self.drain_stderr())
        self.initialized = await self.rpc("initialize", {"protocolVersion": 1,
            "clientCapabilities": {}, "clientInfo": {"name": "devin-local-android", "version": VERSION}})
        if self.initialized.get("protocolVersion") != 1:
            raise RuntimeError("ACP 版本不兼容，请更新客户端")
        method = "session/new"
        params = {"cwd": str(root), "mcpServers": []}
        if self.meta.get("sessionId"):
            if not self.initialized.get("agentCapabilities", {}).get("loadSession"):
                raise RuntimeError("当前代理不支持恢复；历史记录仍保留，请新建对话")
            method = "session/load"
            params["sessionId"] = self.meta["sessionId"]
        self.loading_history = method == "session/load"
        try:
            result = await self.rpc(method, params)
        finally:
            self.loading_history = False
        self.meta["sessionId"] = result.get("sessionId", self.meta.get("sessionId"))
        self.meta["configOptions"] = result.get("configOptions", self.meta.get("configOptions", []))
        self.meta["modes"] = result.get("modes", self.meta.get("modes", {}))
        self.meta["models"] = result.get("models", self.meta.get("models", {}))
        self.meta["promptCapabilities"] = self.initialized.get("agentCapabilities", {}).get("promptCapabilities", {})
        self.meta["state"] = "ready"
        self.save()

    async def prompt(self, text, attachments=None, blocks=None):
        self.busy = True
        self.meta["state"] = "running"
        self.save()
        await self.broadcast({"type": "user", "text": text, "attachments": attachments or []})
        try:
            result = await self.rpc("session/prompt", {"sessionId": self.meta["sessionId"],
                "prompt": [{"type": "text", "text": text}] + (blocks or [])}, timeout=None)
            await self.broadcast({"type": "complete", "stopReason": result.get("stopReason")})
        except Exception:
            if self.meta["state"] != "closed":
                await self.broadcast({"type": "error", "message": self.failure_reason or "任务未完成，请检查官方登录、额度和网络后重试"})
        finally:
            self.busy = False
            if self.meta["state"] != "closed":
                self.meta["state"] = "ready" if self.process.returncode is None else "interrupted"
                self.save()
                await self.broadcast({"type": "state", "state": self.meta["state"]})

    async def close(self):
        self.meta["state"] = "closed"
        self.save()
        if self.process and self.process.returncode is None:
            try:
                if os.name == "nt":
                    self.process.terminate()
                else:
                    os.killpg(self.process.pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
            try:
                await asyncio.wait_for(self.process.wait(), 5)
            except asyncio.TimeoutError:
                self.process.kill()
                await self.process.wait()
        if self.prompt_task and not self.prompt_task.done():
            try:
                await asyncio.wait_for(asyncio.shield(self.prompt_task), 5)
            except (asyncio.TimeoutError, asyncio.CancelledError):
                self.prompt_task.cancel()
            await asyncio.gather(self.prompt_task, return_exceptions=True)
        for task in (self.reader, self.stderr):
            if task:
                task.cancel()
        await asyncio.gather(*(x for x in (self.reader, self.stderr) if x), return_exceptions=True)


def session_meta(sid):
    if str(uuid.UUID(sid)) != sid:
        error("会话编号不正确")
    path = SESSIONS / (sid + ".json")
    if not path.exists(): error("对话记录不存在", 404)
    return json.loads(path.read_text(encoding="utf-8"))


async def agent_delete(request):
    sid = request.match_info["sid"]
    session_meta(sid)
    agent = AGENTS.get(sid)
    if agent:
        if agent.busy: error("请先结束或取消此对话中的任务，再删除记录", 409)
        await agent.close()
        for socket in list(agent.sockets): await socket.close()
        AGENTS.pop(sid, None)
    for suffix in (".json", ".jsonl"):
        (SESSIONS / (sid + suffix)).unlink(missing_ok=True)
    return web.json_response({"ok": True, "scope": "client-local-history"})


async def agents(request):
    if request.method == "GET":
        rows = [json.loads(x.read_text(encoding="utf-8")) for x in SESSIONS.glob("*.json")]
        return web.json_response(sorted(rows, key=lambda x: x.get("updated", 0), reverse=True))
    if UPDATE["state"] == "running":
        error("核心正在更新，请稍后开始任务", 409)
    data = await request.json()
    project(data["projectId"])
    if data.get("resumeId"):
        meta = session_meta(data["resumeId"])
        if meta["projectId"] != data["projectId"]:
            error("会话不属于当前项目", 403)
        existing = AGENTS.get(meta["id"])
        if existing and existing.process.returncode is None:
            return web.json_response(existing.meta)
    else:
        meta = {"id": str(uuid.uuid4()), "projectId": data["projectId"],
                "title": data.get("title", "新对话")[:100], "state": "connecting"}
    agent = Agent(meta)
    try:
        await agent.start()
    except Exception as exc:
        await agent.close()
        error(str(exc) if isinstance(exc, RuntimeError) else "本地代理启动失败，请检查官方登录", 503)
    AGENTS[agent.id] = agent
    return web.json_response(agent.meta)


async def agent_action(request):
    sid, action = request.match_info["sid"], request.match_info["action"]
    agent = AGENTS.get(sid)
    if action == "history":
        session_meta(sid)
        path = SESSIONS / (sid + ".jsonl")
        rows = [json.loads(x) for x in path.read_text(encoding="utf-8").splitlines()[-2000:]] if path.exists() else []
        return web.json_response(rows)
    if not agent or agent.process.returncode is not None:
        error("会话已中断，请从历史会话中恢复", 409)
    data = await request.json() if request.can_read_body else {}
    if action == "prompt":
        if agent.busy or UPDATE["state"] == "running":
            error("请等待当前任务结束，或先取消任务", 409)
        text = str(data.get("text", "")).strip()
        attachments, blocks = prompt_attachments(agent, data.get("attachments", []))
        if not text and attachments: text = "请查看附加的文件。"
        if not text:
            error("请输入任务内容")
        if text.split()[0].lower() in ("/cloud", "/handoff", "/ssh", "/forward"):
            error("当前客户端使用手机本地代理模式")
        agent.busy = True
        agent.prompt_task = asyncio.create_task(agent.prompt(text, attachments, blocks))
        return web.json_response({"ok": True})
    if action == "cancel":
        for original in list(agent.permission_ids.values()):
            await agent.send({"jsonrpc": "2.0", "id": original["id"],
                             "result": {"outcome": {"outcome": "cancelled"}}})
            await agent.broadcast({"type": "permission_resolved", "id": original["id"]})
        agent.permission_ids.clear()
        await agent.send({"jsonrpc": "2.0", "method": "session/cancel",
                          "params": {"sessionId": agent.meta["sessionId"]}})
    elif action == "reply":
        request_id = data.get("id")
        if str(request_id) not in agent.permission_ids:
            error("授权请求已失效", 409)
        original = agent.permission_ids[str(request_id)]
        outcome = data.get("result", {}).get("outcome", {})
        if outcome.get("outcome") == "selected":
            if outcome.get("optionId") not in [x.get("optionId") for x in original.get("params", {}).get("options", [])]:
                error("授权选项不属于当前请求")
        elif outcome.get("outcome") != "cancelled":
            error("授权响应格式不正确")
        # The native client returns the exact ACP option ID chosen by the user.
        await agent.send({"jsonrpc": "2.0", "id": request_id, "result": data["result"]})
        agent.permission_ids.pop(str(request_id), None)
        await agent.broadcast({"type": "permission_resolved", "id": request_id})
        agent.meta["state"] = "running"
        agent.save()
    elif action == "configure":
        if agent.busy:
            error("请等待当前任务结束再修改模型或模式", 409)
        if "configId" in data:
            option = next((x for x in agent.meta.get("configOptions", []) if x.get("id") == data["configId"]), None)
            values = [child.get("value") for item in (option or {}).get("options", []) for child in item.get("options", [item])]
            if not option or data.get("value") not in values: error("代理没有提供此配置选项")
            result = await agent.rpc("session/set_config_option", {"sessionId": agent.meta["sessionId"],
                                     "configId": data["configId"], "value": data["value"]})
            agent.meta["configOptions"] = result.get("configOptions", agent.meta.get("configOptions", []))
        elif "modeId" in data:
            await agent.rpc("session/set_mode", {"sessionId": agent.meta["sessionId"], "modeId": data["modeId"]})
            agent.meta.setdefault("modes", {})["currentModeId"] = data["modeId"]
        elif "modelId" in data and agent.meta.get("models"):
            await agent.rpc("session/set_model", {"sessionId": agent.meta["sessionId"], "modelId": data["modelId"]})
            agent.meta["models"]["currentModelId"] = data["modelId"]
        else:
            error("代理没有提供此配置选项")
        agent.save()
        return web.json_response(agent.meta)
    else:
        error("不支持的会话操作")
    return web.json_response({"ok": True})


async def agent_stream(request):
    agent = AGENTS.get(request.match_info["sid"])
    if not agent:
        error("请先恢复此会话", 409)
    socket = web.WebSocketResponse(heartbeat=25, max_msg_size=4 * 1024 * 1024)
    await socket.prepare(request)
    after = int(request.query.get("after", "0"))
    for message in list(agent.history):
        if message.get("seq", 0) > after:
            await socket.send_json(message)
    agent.sockets.add(socket)
    try:
        async for _ in socket:
            pass
    finally:
        agent.sockets.discard(socket)
    return socket


class Terminal:
    def __init__(self, pid):
        if os.name != "posix":
            error("交互终端需在手机的 Linux 环境中使用", 503)
        self.id = str(uuid.uuid4())
        self.project_id = pid
        self.master, slave = pty.openpty()
        self.process = None
        self.slave = slave
        self.buffer = deque(maxlen=512)
        self.sockets = set()
        self.closed = False
        self.watcher = None

    async def start(self):
        self.process = await asyncio.create_subprocess_exec("/bin/bash", "--login",
            cwd=project(self.project_id), stdin=self.slave, stdout=self.slave, stderr=self.slave,
            start_new_session=True, preexec_fn=lambda: fcntl.ioctl(0, termios.TIOCSCTTY, 0),
            env={**os.environ, "TERM": "xterm-256color",
                                       "PATH": str(Path.home() / ".local/bin") + ":" + os.environ["PATH"]})
        os.close(self.slave)
        os.set_blocking(self.master, False)
        asyncio.get_running_loop().add_reader(self.master, self.read)
        self.watcher = asyncio.create_task(self.watch())

    async def watch(self):
        await self.process.wait()
        await self.close()
        TERMINALS.pop(self.id, None)

    def read(self):
        try:
            data = os.read(self.master, 8192)
        except BlockingIOError:
            return
        except OSError:
            data = b""
        if not data:
            asyncio.get_running_loop().remove_reader(self.master)
            return
        encoded = base64.b64encode(data).decode()
        self.buffer.append(encoded)
        for socket in list(self.sockets):
            asyncio.create_task(socket.send_json({"type": "output", "data": encoded}))

    async def close(self):
        if self.closed:
            return
        self.closed = True
        asyncio.get_running_loop().remove_reader(self.master)
        if self.process.returncode is None:
            try:
                os.killpg(self.process.pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
            try:
                await asyncio.wait_for(self.process.wait(), 3)
            except asyncio.TimeoutError:
                os.killpg(self.process.pid, signal.SIGKILL)
                await self.process.wait()
        os.close(self.master)
        for socket in list(self.sockets):
            await socket.close()


async def terminal_create(request):
    if UPDATE["state"] == "running":
        error("核心更新期间暂不启动终端", 409)
    data = await request.json()
    terminal = Terminal(data["projectId"])
    TERMINALS[terminal.id] = terminal
    try:
        await terminal.start()
    except Exception:
        TERMINALS.pop(terminal.id, None)
        os.close(terminal.master)
        os.close(terminal.slave)
        raise
    return web.json_response({"id": terminal.id})


async def terminal_stream(request):
    terminal = TERMINALS.get(request.match_info["tid"])
    if not terminal:
        error("终端已结束，请重新打开", 404)
    socket = web.WebSocketResponse(heartbeat=25)
    await socket.prepare(request)
    terminal.sockets.add(socket)
    for data in terminal.buffer:
        await socket.send_json({"type": "output", "data": data})
    try:
        async for message in socket:
            if message.type != web.WSMsgType.TEXT:
                continue
            data = json.loads(message.data)
            if data.get("type") == "input":
                os.write(terminal.master, str(data.get("data", "")).encode())
            elif data.get("type") == "resize":
                rows, cols = max(1, min(200, int(data["rows"]))), max(1, min(400, int(data["cols"])))
                fcntl.ioctl(terminal.master, termios.TIOCSWINSZ, struct.pack("HHHH", rows, cols, 0, 0))
            elif data.get("type") == "close":
                await terminal.close()
                TERMINALS.pop(terminal.id, None)
                break
    finally:
        terminal.sockets.discard(socket)
    return socket


async def terminal_close(request):
    terminal = TERMINALS.pop(request.match_info["tid"], None)
    if terminal:
        await terminal.close()
    return web.json_response({"ok": True})


async def test_candidate(binary):
    process = await asyncio.create_subprocess_exec(str(binary), "acp",
        stdin=asyncio.subprocess.PIPE, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.DEVNULL)
    try:
        process.stdin.write(b'{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":1,"clientCapabilities":{}}}\n')
        await process.stdin.drain()
        result = json.loads(await asyncio.wait_for(process.stdout.readline(), 30))
        if result.get("result", {}).get("protocolVersion") != 1:
            raise RuntimeError("ACP handshake failed")
    finally:
        if process.returncode is None:
            process.terminate()
        await process.wait()


def restore_private_tree(snapshot, destination, quarantine, relative=Path(".")):
    """Restore exactly, retaining failed migration files privately instead of deleting them."""
    destination.mkdir(parents=True, exist_ok=True)
    for child in list(destination.iterdir()):
        nested = relative / child.name
        if nested.as_posix() == "cli/_versions":
            continue
        saved = snapshot / child.name
        if child.is_dir() and not child.is_symlink() and saved.is_dir() and not saved.is_symlink():
            restore_private_tree(saved, child, quarantine / child.name, nested)
        else:
            quarantine.mkdir(parents=True, exist_ok=True)
            child.rename(quarantine / child.name)
    if snapshot.exists():
        for saved in snapshot.iterdir():
            target = destination / saved.name
            if saved.is_symlink():
                target.symlink_to(os.readlink(saved), target_is_directory=saved.is_dir())
            elif saved.is_dir():
                if not target.exists():
                    shutil.copytree(saved, target, symlinks=True)
            else:
                shutil.copy2(saved, target)


def activate_version(versions, candidate):
    replacement = versions / "current.next"
    replacement.unlink(missing_ok=True)
    replacement.symlink_to(candidate)
    os.replace(replacement, versions / "current")


async def update_agent():
    global UPDATE
    UPDATE = {"state": "running", "message": "正在下载官方核心"}
    versions = Path.home() / ".local/share/devin/cli/_versions"
    current = versions / "current"
    previous = current.resolve()
    backup = ROOT / "backups" / (str(int(time.time())) + "-" + secrets.token_hex(4))
    backup.mkdir(parents=True, exist_ok=True)
    backup_complete = False
    try:
        # Preserve private settings, credentials, and conversations before candidate startup.
        for source, name in ((Path.home() / ".config/devin", "config"), (Path.home() / ".local/share/devin", "data")):
            if source.exists():
                await asyncio.to_thread(shutil.copytree, source, backup / name,
                                        ignore=shutil.ignore_patterns("_versions"), symlinks=True)
            else:
                (backup / name).mkdir()
        backup_complete = True
        atomic_json(ROOT / "core-update.json", {"state": "running", "previous": str(previous), "backup": str(backup)})
        def fetch_release():
            with urllib.request.urlopen("https://static.devin.ai/cli/current/manifest.json", timeout=30) as response:
                manifest = json.load(response)
            version = manifest["version"]
            if not isinstance(version, str) or not re.fullmatch(r"[0-9][0-9A-Za-z._-]{0,80}", version):
                raise ValueError("Invalid version")
            release = manifest["platforms"]["aarch64-unknown-linux"]
            if not release["url"].startswith("https://static.devin.ai/cli/"):
                raise ValueError("Unexpected distribution host")
            path = versions / (version + ".download")
            urllib.request.urlretrieve(release["url"], path)
            if hashlib.sha256(path.read_bytes()).hexdigest() != release["sha256"]:
                raise ValueError("Checksum mismatch")
            candidate = versions / version
            if not candidate.resolve().is_relative_to(versions.resolve()):
                raise ValueError("Invalid candidate path")
            staging = Path(tempfile.mkdtemp(prefix="candidate-", dir=versions))
            try:
                with tarfile.open(path) as archive_file:
                    items = archive_file.getmembers()
                    if sum(x.size for x in items) > 1024 * 1024 * 1024:
                        raise ValueError("Unexpected archive size")
                    for item in items:
                        if not (staging / item.name).resolve().is_relative_to(staging.resolve()):
                            raise ValueError("Unsafe archive")
                        if item.issym() or item.islnk():
                            raise ValueError("Unexpected archive link")
                    archive_file.extractall(staging, filter="data")
                if not candidate.exists():
                    staging.rename(candidate)
                elif candidate.resolve() != previous:
                    # Retain any incomplete earlier extraction, then use the verified archive.
                    candidate.rename(backup / "previous-candidate")
                    staging.rename(candidate)
            finally:
                if staging.exists():
                    shutil.rmtree(staging)
            path.unlink()
            return version, candidate
        version, candidate = await asyncio.to_thread(fetch_release)
        UPDATE = {"state": "running", "message": "正在验证新核心与本地协议"}
        await test_candidate(candidate / "bin/devin")
        activate_version(versions, candidate)
        UPDATE = {"state": "complete", "message": "核心已更新并通过协议检查", "version": version}
    except (Exception, asyncio.CancelledError):
        activate_version(versions, previous)
        # Restore exactly; migration-created files are quarantined in the private backup.
        for saved, destination in ((backup / "config", Path.home() / ".config/devin"),
                                   (backup / "data", Path.home() / ".local/share/devin")):
            if backup_complete:
                restore_private_tree(saved, destination, backup / ("failed-" + saved.name))
        UPDATE = {"state": "failed", "message": "更新未通过验证，已保留原核心和私有备份"}
    atomic_json(ROOT / "core-update.json", UPDATE)
    await event({"type": "update", **UPDATE})


async def updates(request):
    global UPDATE, UPDATE_TASK
    if request.method == "GET":
        return web.json_response(UPDATE)
    if any(x.busy for x in AGENTS.values()) or TERMINALS or UPDATE["state"] == "running":
        error("请等待当前任务结束，并关闭终端后再更新核心", 409)
    UPDATE = {"state": "running", "message": "正在准备核心更新"}
    for agent in list(AGENTS.values()):
        await agent.close()
    AGENTS.clear()
    UPDATE_TASK = asyncio.create_task(update_agent())
    return web.json_response({"ok": True})


async def cleanup(_):
    if UPDATE_TASK and not UPDATE_TASK.done():
        UPDATE_TASK.cancel()
        await asyncio.gather(UPDATE_TASK, return_exceptions=True)
    for agent in list(AGENTS.values()):
        await agent.close()
    for terminal in list(TERMINALS.values()):
        await terminal.close()


async def shutdown(request):
    if any(x.busy for x in AGENTS.values()) or TERMINALS or UPDATE["state"] == "running":
        error("请先结束任务并关闭终端，再更新本地服务", 409)
    asyncio.get_running_loop().call_later(0.5, lambda: os.kill(os.getpid(), signal.SIGTERM))
    return web.json_response({"ok": True})


def application():
    app = web.Application(middlewares=[guard], client_max_size=100 * 1024 * 1024)
    app.add_routes([
        web.get("/v1/health", health), web.get("/v1/events", events),
        web.route("*", "/v1/projects", projects),
        web.get("/v1/projects/{pid}/files", file_list),
        web.get("/v1/projects/{pid}/download", file_download),
        web.post("/v1/projects/{pid}/attachments", attachment_upload),
        web.route("*", "/v1/projects/{pid}/file", file_content),
        web.get("/v1/projects/{pid}/search", search),
        web.route("*", "/v1/projects/{pid}/git", git),
        web.route("*", "/v1/projects/{pid}/archive", archive),
        web.route("*", "/v1/agents", agents),
        web.delete("/v1/agents/{sid}", agent_delete),
        web.get("/v1/agents/{sid}/stream", agent_stream),
        web.route("*", "/v1/agents/{sid}/{action}", agent_action),
        web.post("/v1/terminals", terminal_create),
        web.get("/v1/terminals/{tid}/stream", terminal_stream),
        web.post("/v1/terminals/{tid}/close", terminal_close),
        web.route("*", "/v1/updates/agent", updates),
        web.post("/v1/admin/shutdown", shutdown),
    ])
    app.on_cleanup.append(cleanup)
    return app


if __name__ == "__main__":
    initialize()
    web.run_app(application(), host="127.0.0.1", port=PORT, access_log=None,
                print=None, shutdown_timeout=5)
