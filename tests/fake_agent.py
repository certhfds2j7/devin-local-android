"""Explicit protocol fixture, never used as the application's agent."""
import json
from pathlib import Path
import sys

def send(value):
    print(json.dumps({"jsonrpc": "2.0", **value}), flush=True)

pending = None
configs = [{"id": "model", "category": "model", "type": "select", "currentValue": "fixture-model", "options": [{"value": "fixture-model", "name": "Fixture"}]},
           {"id": "thought_level", "category": "thought_level", "type": "select", "currentValue": "high", "options": [{"value": "low", "name": "Low"}, {"value": "high", "name": "High"}]}]
for line in sys.stdin:
    message = json.loads(line)
    method = message.get("method")
    if method == "initialize":
        send({"id": message["id"], "result": {"protocolVersion": 1, "agentCapabilities": {"loadSession": True, "promptCapabilities": {"image": True, "embeddedContext": True}}}})
    elif method in ("session/new", "session/load"):
        if method == "session/load":
            send({"method": "session/update", "params": {"sessionId": "fixture-session", "update": {
                "sessionUpdate": "agent_message_chunk", "content": {"type": "text", "text": "Replay must not be duplicated"}}}})
        send({"id": message["id"], "result": {"sessionId": "fixture-session", "configOptions": configs}})
    elif method == "session/prompt":
        Path("fixture-prompt.json").write_text(json.dumps(message["params"]["prompt"]), encoding="utf-8")
        pending = message["id"]
        send({"id": "fixture-permission", "method": "session/request_permission",
              "params": {"sessionId": "fixture-session", "toolCall": {"kind": "edit", "title": "Fixture edit"},
                         "options": [{"optionId": "allow-once", "kind": "allow_once", "name": "Allow once"},
                                     {"optionId": "reject-once", "kind": "reject_once", "name": "Reject once"}]}})
    elif message.get("id") == "fixture-permission":
        outcome = message.get("result", {}).get("outcome", {})
        if outcome.get("optionId") == "allow-once":
            Path("fixture-result.txt").write_text("Unit test only", encoding="utf-8")
        send({"method": "session/update", "params": {"sessionId": "fixture-session",
              "update": {"sessionUpdate": "agent_message_chunk", "content": {"type": "text", "text": "Fixture response"}}}})
        send({"id": pending, "result": {"stopReason": "end_turn"}})
    elif method == "session/cancel" and pending:
        send({"id": pending, "result": {"stopReason": "cancelled"}})
    elif method == "session/set_config_option":
        for config in configs:
            if config["id"] == message["params"]["configId"]: config["currentValue"] = message["params"]["value"]
        send({"id": message["id"], "result": {"configOptions": configs}})
