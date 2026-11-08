import asyncio
import importlib.util
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
import zipfile
import hashlib
import tarfile
from unittest.mock import patch, AsyncMock
from types import SimpleNamespace
from aiohttp.test_utils import TestClient, TestServer

SOURCE = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("local_bridge", SOURCE / "bridge/server.py")
bridge = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bridge)


class BridgeTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        parent = SOURCE / "test-data"
        parent.mkdir(exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=parent)
        root = Path(self.temp.name).resolve()
        assert root.is_relative_to(parent.resolve())
        bridge.ROOT = root
        bridge.PROJECTS = root / "workspaces"
        bridge.SESSIONS = root / "sessions"
        bridge.AGENTS.clear()
        bridge.TERMINALS.clear()
        bridge.UPDATE = {"state": "idle"}
        bridge.CLI_ARGS = [sys.executable, str(Path(__file__).with_name("fake_agent.py"))]
        bridge.initialize()
        self.client = TestClient(TestServer(bridge.application()))
        await self.client.start_server()
        self.auth = {"Authorization": "Bearer " + bridge.TOKEN}
        response = await self.client.post("/v1/projects", headers=self.auth,
                                         json={"name": "测试项目", "kind": "empty"})
        self.assertEqual(response.status, 201)
        self.project = await response.json()
        self.path = "/v1/projects/" + self.project["id"]

    async def asyncTearDown(self):
        await self.client.close()
        bridge.AGENTS.clear()
        self.temp.cleanup()

    async def test_token_and_browser_origin_are_required(self):
        response = await self.client.get("/v1/health")
        self.assertEqual(response.status, 401)
        response = await self.client.get("/v1/health", headers={**self.auth, "Origin": "https://untrusted.example"})
        self.assertEqual(response.status, 403)
        response = await self.client.get("/v1/health", headers=self.auth)
        data = await response.json()
        self.assertTrue(data["local"])
        self.assertNotIn(bridge.TOKEN, json.dumps(data))

    async def test_file_revision_prevents_overwriting_external_edit(self):
        response = await self.client.put(self.path + "/file?path=main.py", headers=self.auth,
                                         json={"revision": "missing", "content": "original"})
        self.assertEqual(response.status, 200)
        original = (await response.json())["revision"]
        target = bridge.project(self.project["id"]) / "main.py"
        target.write_text("agent changed this")
        response = await self.client.put(self.path + "/file?path=main.py", headers=self.auth,
                                         json={"revision": original, "content": "stale editor"})
        self.assertEqual(response.status, 409)
        self.assertEqual(target.read_text(), "agent changed this")

    async def test_project_rename_preserves_id_files_git_and_session(self):
        root = bridge.project(self.project["id"])
        (root / "keep.txt").write_text("keep")
        agent = await self.make_agent()
        response = await self.client.patch(self.path, headers=self.auth, json={"name": "  中文项目 / ../ 😀  "})
        self.assertEqual(response.status, 200)
        renamed = await response.json()
        self.assertEqual(renamed["name"], "中文项目 / ../ 😀")
        self.assertEqual(renamed["id"], self.project["id"])
        self.assertEqual(root, bridge.project(self.project["id"]))
        self.assertEqual((root / "keep.txt").read_text(), "keep")
        self.assertTrue((root / ".git").is_dir())
        self.assertEqual(bridge.session_meta(agent["id"])["projectId"], self.project["id"])

    async def test_rename_validation_does_not_change_existing_metadata(self):
        agent = await self.make_agent()
        for invalid in ("", "   ", "换\n行", "a" * 101, None, 1):
            response = await self.client.patch(self.path, headers=self.auth, json={"name": invalid})
            self.assertEqual(response.status, 400)
            response = await self.client.patch("/v1/agents/" + agent["id"], headers=self.auth, json={"title": invalid})
            self.assertEqual(response.status, 400)
        self.assertEqual(json.loads((bridge.PROJECTS / self.project["id"] / "project.json").read_text(encoding="utf-8"))["name"], "测试项目")
        self.assertEqual(bridge.session_meta(agent["id"])["title"], "新对话")

    async def test_conversation_rename_persists_for_live_and_closed_agents(self):
        agent = await self.make_agent()
        instance = bridge.AGENTS[agent["id"]]
        await instance.broadcast({"type": "user", "text": "retained"})
        response = await self.client.patch("/v1/agents/" + agent["id"], headers=self.auth, json={"title": "活跃会话新名称"})
        self.assertEqual(response.status, 200)
        instance.save()
        self.assertEqual(bridge.session_meta(agent["id"])["title"], "活跃会话新名称")
        await instance.close()
        bridge.AGENTS.pop(agent["id"])
        response = await self.client.patch("/v1/agents/" + agent["id"], headers=self.auth, json={"title": "旧会话新名称"})
        self.assertEqual(response.status, 200)
        self.assertEqual(bridge.session_meta(agent["id"])["title"], "旧会话新名称")
        history = (bridge.SESSIONS / (agent["id"] + ".jsonl")).read_text()
        self.assertIn("retained", history)

    async def test_project_delete_requires_current_exact_name(self):
        for body in ({}, {"confirmName": "错误项目"}):
            response = await self.client.delete(self.path, headers=self.auth, json=body)
            self.assertEqual(response.status, 409)
        await self.client.patch(self.path, headers=self.auth, json={"name": "新项目名称"})
        response = await self.client.delete(self.path, headers=self.auth, json={"confirmName": "测试项目"})
        self.assertEqual(response.status, 409)
        self.assertTrue(bridge.project(self.project["id"]).exists())

    async def test_project_delete_removes_only_its_files_attachments_and_histories(self):
        root = bridge.project(self.project["id"])
        (root / "result.bin").write_bytes(b"result")
        upload = await (await self.client.post(self.path + "/attachments", params={"name": "input.bin"}, headers=self.auth, data=b"input")).json()
        agent = await self.make_agent()
        await bridge.AGENTS[agent["id"]].broadcast({"type": "user", "text": "delete test"})
        other = await (await self.client.post("/v1/projects", headers=self.auth, json={"name": "保留项目", "kind": "empty"})).json()
        other_root = bridge.project(other["id"])
        (other_root / "keep.txt").write_text("retained")
        other_agent = await (await self.client.post("/v1/agents", headers=self.auth, json={"projectId": other["id"]})).json()
        await bridge.AGENTS[other_agent["id"]].broadcast({"type": "user", "text": "keep history"})
        response = await self.client.delete(self.path, headers=self.auth, json={"confirmName": "测试项目"})
        self.assertEqual(response.status, 200)
        self.assertFalse((bridge.PROJECTS / self.project["id"]).exists())
        self.assertFalse((bridge.ROOT / "attachments" / self.project["id"]).exists())
        self.assertFalse((bridge.SESSIONS / (agent["id"] + ".json")).exists())
        self.assertFalse((bridge.SESSIONS / (agent["id"] + ".jsonl")).exists())
        self.assertNotIn(agent["id"], bridge.AGENTS)
        self.assertEqual((other_root / "keep.txt").read_text(), "retained")
        self.assertIn(other_agent["id"], bridge.AGENTS)
        self.assertIn("keep history", (bridge.SESSIONS / (other_agent["id"] + ".jsonl")).read_text())

    async def test_project_delete_rejects_running_tasks_terminals_and_core_upgrade(self):
        agent = await self.make_agent()
        instance = bridge.AGENTS[agent["id"]]
        instance.busy = True
        response = await self.client.delete(self.path, headers=self.auth, json={"confirmName": "测试项目"})
        self.assertEqual(response.status, 409)
        instance.busy = False
        bridge.TERMINALS["fixture"] = SimpleNamespace(project_id=self.project["id"], close=AsyncMock())
        response = await self.client.delete(self.path, headers=self.auth, json={"confirmName": "测试项目"})
        self.assertEqual(response.status, 409)
        bridge.TERMINALS.clear()
        bridge.UPDATE = {"state": "running"}
        response = await self.client.delete(self.path, headers=self.auth, json={"confirmName": "测试项目"})
        self.assertEqual(response.status, 409)
        bridge.UPDATE = {"state": "idle"}
        self.assertTrue(bridge.project(self.project["id"]).exists())

    async def test_project_delete_rejects_symlink_root_before_removing_any_data(self):
        folder = bridge.PROJECTS / self.project["id"]
        original = Path.is_symlink
        with patch.object(Path, "is_symlink", lambda path: path == folder or original(path)):
            response = await self.client.delete(self.path, headers=self.auth, json={"confirmName": "测试项目"})
        self.assertEqual(response.status, 403)
        self.assertTrue((folder / "project.json").exists())

    async def test_project_deletion_waits_for_inflight_project_operation(self):
        started, release = asyncio.Event(), asyncio.Event()
        async def slow_command(*args, **kwargs):
            started.set()
            await release.wait()
            return 0, b"", b""
        with patch.object(bridge, "command", side_effect=slow_command):
            git = asyncio.create_task(self.client.post(self.path + "/git", headers=self.auth, json={"action": "stage"}))
            await asyncio.wait_for(started.wait(), 3)
            deletion = asyncio.create_task(self.client.delete(self.path, headers=self.auth, json={"confirmName": "测试项目"}))
            await asyncio.sleep(0.05)
            self.assertFalse(deletion.done())
            self.assertTrue(bridge.project(self.project["id"]).exists())
            release.set()
            self.assertEqual((await git).status, 200)
            self.assertEqual((await deletion).status, 200)

    async def test_project_delete_rejects_redirected_attachment_root(self):
        base = bridge.ROOT / "attachments"
        target = base / self.project["id"]
        actual = Path.resolve
        def redirected(path, *args, **kwargs):
            if path == base: return bridge.ROOT / "private-copy"
            if path == target: return bridge.ROOT / "private-copy" / self.project["id"]
            return actual(path, *args, **kwargs)
        with patch.object(Path, "resolve", redirected):
            response = await self.client.delete(self.path, headers=self.auth, json={"confirmName": "测试项目"})
        self.assertEqual(response.status, 403)
        self.assertTrue((bridge.PROJECTS / self.project["id"] / "project.json").exists())

    async def test_project_deletion_waits_for_connecting_agent_without_orphan_history(self):
        started, release = asyncio.Event(), asyncio.Event()
        async def delayed_start(instance):
            started.set()
            await release.wait()
            instance.process = SimpleNamespace(returncode=0)
            instance.meta["state"] = "ready"
            instance.save()
        with patch.object(bridge.Agent, "start", delayed_start):
            creation = asyncio.create_task(self.client.post("/v1/agents", headers=self.auth, json={"projectId": self.project["id"]}))
            await asyncio.wait_for(started.wait(), 3)
            deletion = asyncio.create_task(self.client.delete(self.path, headers=self.auth, json={"confirmName": "测试项目"}))
            await asyncio.sleep(0.05)
            self.assertFalse(deletion.done())
            release.set()
            self.assertEqual((await creation).status, 200)
            self.assertEqual((await deletion).status, 200)
        self.assertEqual(list(bridge.SESSIONS.glob("*.json")), [])

    async def test_binary_attachment_and_download_preserve_bytes_and_unicode_filename(self):
        data = bytes(range(256)) * 4
        response = await self.client.post(self.path + "/attachments", params={"name": "中文数据.bin"}, headers=self.auth, data=data)
        self.assertEqual(response.status, 201)
        attachment = await response.json()
        self.assertEqual(attachment["sha256"], hashlib.sha256(data).hexdigest())
        response = await self.client.get(self.path + "/download", params={"path": attachment["path"]}, headers=self.auth)
        self.assertEqual(await response.read(), data)
        self.assertEqual(response.headers["X-Content-SHA256"], attachment["sha256"])
        self.assertIn("filename*=UTF-8''", response.headers["Content-Disposition"])
        again = await (await self.client.post(self.path + "/attachments", params={"name": "中文数据.bin"}, headers=self.auth, data=b"new")).json()
        self.assertNotEqual(attachment["path"], again["path"])

    async def test_upload_limits_and_invalid_names_leave_no_partial_file(self):
        for name in ("../secret", "..", "a/b.txt", "a\\b.txt", "a\n.txt"):
            response = await self.client.post(self.path + "/attachments", params={"name": name}, headers=self.auth, data=b"x")
            self.assertEqual(response.status, 400)
        with patch.object(bridge, "MAX_ATTACHMENT", 4):
            response = await self.client.post(self.path + "/attachments", params={"name": "large.bin"}, headers=self.auth, data=b"12345")
            self.assertEqual(response.status, 413)
        self.assertEqual(list(bridge.project(self.project["id"]).glob("attachments/*/*")), [])

    async def test_encoded_markdown_download_links_preserve_unicode_spaces_and_path_boundary(self):
        from urllib.parse import quote
        uploaded = await (await self.client.post(self.path + "/attachments", params={"name": "中文 文件.bin"}, headers=self.auth, data=b"unicode-file")).json()
        response = await self.client.get(self.path + "/download", params={"path": quote(uploaded["path"])}, headers=self.auth)
        self.assertEqual(await response.read(), b"unicode-file")
        literal = bridge.project(self.project["id"]) / "literal%20name.txt"
        literal.write_bytes(b"literal-percent")
        response = await self.client.get(self.path + "/download", params={"path": literal.name}, headers=self.auth)
        self.assertEqual(await response.read(), b"literal-percent")
        response = await self.client.get(self.path + "/download", params={"path": "%2e%2e/%2e%2e/secret.txt"}, headers=self.auth)
        self.assertEqual(response.status, 403)

    async def test_download_cannot_read_outside_project_or_git_internal_data(self):
        outside = bridge.ROOT / "secret.txt"
        outside.write_text("private")
        for path in ("../../secret.txt", outside.as_uri(), ".git/config", "https://example.invalid/file"):
            response = await self.client.get(self.path + "/download", params={"path": path}, headers=self.auth)
            self.assertEqual(response.status, 403)
        response = await self.client.get(self.path + "/download", params={"path": "missing.bin"}, headers=self.auth)
        self.assertEqual(response.status, 404)

    async def test_attachments_reject_other_projects_and_external_changes(self):
        uploaded = await (await self.client.post(self.path + "/attachments", params={"name": "input.txt"}, headers=self.auth, data=b"original")).json()
        agent = await self.make_agent()
        prefix = "/v1/agents/" + agent["id"]
        other = await (await self.client.post("/v1/projects", headers=self.auth, json={"name": "other", "kind": "empty"})).json()
        other_agent = await (await self.client.post("/v1/agents", headers=self.auth, json={"projectId": other["id"]})).json()
        response = await self.client.post("/v1/agents/" + other_agent["id"] + "/prompt", headers=self.auth, json={"text": "test", "attachments": [uploaded["id"]]})
        self.assertEqual(response.status, 404)
        (bridge.project(self.project["id"]) / uploaded["path"]).write_text("changed")
        response = await self.client.post(prefix + "/prompt", headers=self.auth, json={"text": "test", "attachments": [uploaded["id"]]})
        self.assertEqual(response.status, 409)

    async def test_prompt_sends_actual_image_and_resource_content_and_persists_attachment_reference(self):
        image = b"\x89PNG\r\n\x1a\nfixture"
        uploaded = await (await self.client.post(self.path + "/attachments", params={"name": "image.png"}, headers=self.auth, data=image)).json()
        agent = await self.make_agent()
        prefix = "/v1/agents/" + agent["id"]
        response = await self.client.post(prefix + "/prompt", headers=self.auth, json={"text": "", "attachments": [uploaded["id"]]})
        self.assertEqual(response.status, 200)
        await self.wait_permission(agent)
        prompt = json.loads((bridge.project(self.project["id"]) / "fixture-prompt.json").read_text())
        self.assertIn("resource_link", [x["type"] for x in prompt])
        import base64
        self.assertEqual(base64.b64decode(next(x["data"] for x in prompt if x["type"] == "image")), image)
        history = await (await self.client.get(prefix + "/history", headers=self.auth)).json()
        self.assertEqual(history[0]["attachments"][0]["id"], uploaded["id"])

    async def test_thought_level_uses_advertised_values_and_returns_confirmed_state(self):
        agent = await self.make_agent()
        prefix = "/v1/agents/" + agent["id"]
        response = await self.client.post(prefix + "/configure", headers=self.auth, json={"configId": "thought_level", "value": "invented"})
        self.assertEqual(response.status, 400)
        response = await self.client.post(prefix + "/configure", headers=self.auth, json={"configId": "thought_level", "value": "low"})
        self.assertEqual(response.status, 200)
        meta = await response.json()
        self.assertEqual(next(x["currentValue"] for x in meta["configOptions"] if x["id"] == "thought_level"), "low")

    async def test_delete_one_history_preserves_other_session_and_project_files(self):
        first, second = await self.make_agent(), await self.make_agent()
        for meta in (first, second): await bridge.AGENTS[meta["id"]].broadcast({"type": "user", "text": "fixture history"})
        target = bridge.project(self.project["id"]) / "keep.txt"
        target.write_text("keep")
        response = await self.client.delete("/v1/agents/" + first["id"], headers=self.auth)
        self.assertEqual(response.status, 200)
        self.assertFalse((bridge.SESSIONS / (first["id"] + ".jsonl")).exists())
        self.assertFalse((bridge.SESSIONS / (first["id"] + ".json")).exists())
        rows = await (await self.client.get("/v1/agents", headers=self.auth)).json()
        self.assertEqual([x["id"] for x in rows], [second["id"]])
        self.assertTrue((bridge.SESSIONS / (second["id"] + ".jsonl")).exists())
        self.assertEqual(target.read_text(), "keep")

    async def test_delete_running_session_is_rejected(self):
        agent = await self.make_agent()
        await self.client.post("/v1/agents/" + agent["id"] + "/prompt", headers=self.auth, json={"text": "fixture"})
        await self.wait_permission(agent)
        response = await self.client.delete("/v1/agents/" + agent["id"], headers=self.auth)
        self.assertEqual(response.status, 409)
        self.assertTrue((bridge.SESSIONS / (agent["id"] + ".json")).exists())

    async def test_paths_cannot_escape_workspace(self):
        response = await self.client.put(self.path + "/file?path=../../outside.txt", headers=self.auth,
                                         json={"revision": "missing", "content": "bad"})
        self.assertEqual(response.status, 403)
        self.assertFalse((bridge.ROOT / "outside.txt").exists())

    async def test_zip_slip_rejected_before_extraction(self):
        stream = io.BytesIO()
        with zipfile.ZipFile(stream, "w") as archive:
            archive.writestr("safe.txt", "safe")
            archive.writestr("../../escape.txt", "unsafe")
        response = await self.client.post(self.path + "/archive", headers=self.auth, data=stream.getvalue())
        self.assertEqual(response.status, 403)
        self.assertFalse((bridge.project(self.project["id"]) / "safe.txt").exists())

    async def test_zip_round_trip_preserves_unicode_contents(self):
        stream = io.BytesIO()
        with zipfile.ZipFile(stream, "w") as archive:
            archive.writestr("源码/你好.py", 'print("你好")\n')
        response = await self.client.post(self.path + "/archive", headers=self.auth, data=stream.getvalue())
        self.assertEqual(response.status, 200)
        response = await self.client.get(self.path + "/archive", headers=self.auth)
        with zipfile.ZipFile(io.BytesIO(await response.read())) as archive:
            self.assertEqual(archive.read("源码/你好.py").decode(), 'print("你好")\n')

    async def test_git_clone_does_not_accept_options_as_urls(self):
        response = await self.client.post(self.path + "/git", headers=self.auth,
                                          json={"action": "clone", "url": "--upload-pack=arbitrary-command"})
        self.assertEqual(response.status, 400)

    async def make_agent(self):
        response = await self.client.post("/v1/agents", headers=self.auth, json={"projectId": self.project["id"]})
        self.assertEqual(response.status, 200, await response.text())
        return await response.json()

    async def wait_permission(self, agent):
        for _ in range(100):
            if bridge.AGENTS[agent["id"]].permission_ids:
                return
            await asyncio.sleep(0.02)
        self.fail("Fixture did not request permission")

    async def test_permission_response_is_explicit_and_validated(self):
        agent = await self.make_agent()
        prefix = "/v1/agents/" + agent["id"]
        response = await self.client.post(prefix + "/prompt", headers=self.auth, json={"text": "Fixture test"})
        self.assertEqual(response.status, 200)
        await self.wait_permission(agent)
        target = bridge.project(self.project["id"]) / "fixture-result.txt"
        self.assertFalse(target.exists())
        response = await self.client.post(prefix + "/reply", headers=self.auth,
            json={"id": "fixture-permission", "result": {"outcome": {"outcome": "selected", "optionId": "invented"}}})
        self.assertEqual(response.status, 400)
        self.assertFalse(target.exists())
        response = await self.client.post(prefix + "/reply", headers=self.auth,
            json={"id": "fixture-permission", "result": {"outcome": {"outcome": "selected", "optionId": "allow-once"}}})
        self.assertEqual(response.status, 200)
        for _ in range(100):
            if target.exists() and not bridge.AGENTS[agent["id"]].busy:
                break
            await asyncio.sleep(0.02)
        self.assertTrue(target.exists())
        history = await (await self.client.get(prefix + "/history", headers=self.auth)).json()
        self.assertTrue(any(x["type"] == "permission_resolved" for x in history))
        sequences = [x["seq"] for x in history]
        self.assertEqual(sequences, sorted(set(sequences)))

    async def test_busy_project_save_and_update_are_rejected(self):
        agent = await self.make_agent()
        await self.client.post("/v1/agents/" + agent["id"] + "/prompt", headers=self.auth, json={"text": "Fixture test"})
        await self.wait_permission(agent)
        response = await self.client.put(self.path + "/file?path=other.py", headers=self.auth,
                                         json={"revision": "missing", "content": "editor write"})
        self.assertEqual(response.status, 409)
        response = await self.client.post("/v1/updates/agent", headers=self.auth, json={})
        self.assertEqual(response.status, 409)

    async def test_cloud_handoff_is_not_a_local_prompt(self):
        agent = await self.make_agent()
        response = await self.client.post("/v1/agents/" + agent["id"] + "/prompt",
                                         headers=self.auth, json={"text": "/handoff move task"})
        self.assertEqual(response.status, 400)

    async def test_history_survives_process_close_and_resume(self):
        agent = await self.make_agent()
        original = bridge.AGENTS.pop(agent["id"])
        await original.broadcast({"type": "user", "text": "retained history"})
        await original.close()
        response = await self.client.post("/v1/agents", headers=self.auth,
                                         json={"projectId": self.project["id"], "resumeId": agent["id"]})
        self.assertEqual(response.status, 200)
        loaded = bridge.AGENTS[agent["id"]]
        self.assertTrue(any(x.get("text") == "retained history" for x in loaded.history))
        self.assertNotIn("Replay must not be duplicated", json.dumps(list(loaded.history)))

    async def test_repeated_session_load_does_not_append_replayed_messages(self):
        agent = await self.make_agent()
        await bridge.AGENTS[agent["id"]].broadcast({"type": "user", "text": "preserve original"})
        for _ in range(2):
            previous = bridge.AGENTS.pop(agent["id"])
            await previous.close()
            response = await self.client.post("/v1/agents", headers=self.auth, json={"projectId": self.project["id"], "resumeId": agent["id"]})
            self.assertEqual(response.status, 200)
        rows = [json.loads(line) for line in (bridge.SESSIONS / (agent["id"] + ".jsonl")).read_text().splitlines()]
        self.assertEqual([x["text"] for x in rows if x.get("type") == "user"], ["preserve original"])
        self.assertNotIn("Replay must not be duplicated", json.dumps(rows))

    async def test_failed_candidate_restores_configuration_without_losing_versions(self):
        home = bridge.ROOT / "home"
        config = home / ".config/devin"
        data = home / ".local/share/devin"
        versions = data / "cli/_versions"
        (versions / "current").mkdir(parents=True)
        config.mkdir(parents=True)
        (config / "account.txt").write_text("original-private-state")
        (data / "history.txt").write_text("old-history")
        (versions / "retained-core.txt").write_text("keep")
        stream = io.BytesIO()
        with tarfile.open(fileobj=stream, mode="w:gz") as archive:
            payload = b"fixture-binary"
            item = tarfile.TarInfo("bin/devin")
            item.size = len(payload)
            archive.addfile(item, io.BytesIO(payload))
        blob = stream.getvalue()
        manifest = {"version": "3000.fixture", "platforms": {"aarch64-unknown-linux": {
            "url": "https://static.devin.ai/cli/fixture.tar.gz",
            "sha256": hashlib.sha256(blob).hexdigest()}}}
        def download(_, destination):
            destination.write_bytes(blob)
        async def reject_candidate(_):
            (config / "account.txt").write_text("migrated")
            (config / "new-format.txt").write_text("migration")
            (data / "history.txt").write_text("migrated-history")
            raise RuntimeError("Fixture ACP protocol mismatch")
        with patch.object(Path, "home", return_value=home), \
             patch.object(bridge.urllib.request, "urlopen", return_value=io.BytesIO(json.dumps(manifest).encode())), \
             patch.object(bridge.urllib.request, "urlretrieve", side_effect=download), \
             patch.object(bridge, "test_candidate", side_effect=reject_candidate), \
             patch.object(bridge, "activate_version") as activate:
            await bridge.update_agent()
        self.assertEqual(bridge.UPDATE["state"], "failed")
        self.assertEqual((config / "account.txt").read_text(), "original-private-state")
        self.assertFalse((config / "new-format.txt").exists())
        self.assertEqual((data / "history.txt").read_text(), "old-history")
        self.assertEqual((versions / "retained-core.txt").read_text(), "keep")
        self.assertTrue(list((bridge.ROOT / "backups").glob("*/failed-config/new-format.txt")))
        activate.assert_called_once_with(versions, versions / "current")

    async def test_bad_core_digest_never_executes_candidate(self):
        home = bridge.ROOT / "home"
        versions = home / ".local/share/devin/cli/_versions"
        (versions / "current").mkdir(parents=True)
        manifest = {"version": "3000.fixture", "platforms": {"aarch64-unknown-linux": {
            "url": "https://static.devin.ai/cli/fixture.tar.gz", "sha256": "0" * 64}}}
        with patch.object(Path, "home", return_value=home), \
             patch.object(bridge.urllib.request, "urlopen", return_value=io.BytesIO(json.dumps(manifest).encode())), \
             patch.object(bridge.urllib.request, "urlretrieve", side_effect=lambda _, target: target.write_bytes(b"corrupt")), \
             patch.object(bridge, "test_candidate", new_callable=AsyncMock) as candidate, \
             patch.object(bridge, "activate_version"):
            await bridge.update_agent()
        candidate.assert_not_called()
        self.assertEqual(bridge.UPDATE["state"], "failed")

    async def test_initial_project_diff_shows_new_and_staged_files(self):
        target = bridge.project(self.project["id"]) / "main.py"
        target.write_text("print('hello')\n")
        response = await self.client.get(self.path + "/git", headers=self.auth)
        self.assertIn("+print('hello')", (await response.json())["diff"])
        await self.client.post(self.path + "/git", headers=self.auth, json={"action": "stage"})
        response = await self.client.get(self.path + "/git", headers=self.auth)
        self.assertIn("+print('hello')", (await response.json())["diff"])

    async def test_project_export_omits_git_and_import_rejects_git_hooks(self):
        response = await self.client.get(self.path + "/archive", headers=self.auth)
        with zipfile.ZipFile(io.BytesIO(await response.read())) as archive:
            self.assertFalse(any(".git" in Path(x).parts for x in archive.namelist()))
        stream = io.BytesIO()
        with zipfile.ZipFile(stream, "w") as archive:
            archive.writestr(".git/hooks/pre-commit", "fixture hook")
        response = await self.client.post(self.path + "/archive", headers={**self.auth, "Content-Type": "application/zip"},
                                          data=stream.getvalue())
        self.assertEqual(response.status, 403)
        self.assertFalse((bridge.project(self.project["id"]) / ".git/hooks/pre-commit").exists())

    async def test_interrupted_core_upgrade_recovers_on_bridge_restart(self):
        home = bridge.ROOT / "home"
        versions = home / ".local/share/devin/cli/_versions"
        previous = versions / "3000.old"
        previous.mkdir(parents=True)
        config = home / ".config/devin"
        config.mkdir(parents=True)
        (config / "account.txt").write_text("partial-migration")
        (config / "new-setting.txt").write_text("new")
        backup = bridge.ROOT / "backups/interrupted-fixture"
        (backup / "config").mkdir(parents=True)
        (backup / "config/account.txt").write_text("original")
        (backup / "data/cli").mkdir(parents=True)
        bridge.atomic_json(bridge.ROOT / "core-update.json",
                           {"state": "running", "previous": str(previous), "backup": str(backup)})
        with patch.object(Path, "home", return_value=home), patch.object(bridge, "activate_version") as activate:
            bridge.initialize()
        self.assertEqual((config / "account.txt").read_text(), "original")
        self.assertFalse((config / "new-setting.txt").exists())
        self.assertTrue(previous.exists())
        activate.assert_called_once_with(versions, previous)
        self.assertEqual(bridge.UPDATE["state"], "failed")

    async def test_bridge_replacement_cannot_interrupt_core_upgrade(self):
        bridge.UPDATE = {"state": "running"}
        response = await self.client.post("/v1/admin/shutdown", headers=self.auth, json={})
        self.assertEqual(response.status, 409)


if __name__ == "__main__":
    unittest.main()
