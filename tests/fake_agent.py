"""Explicit protocol fixture, never used as the application's agent."""
import json
from pathlib import Path
import sys

def send(value):
    print(json.dumps({"jsonrpc": "2.0", **value}), flush=True)

pending = None
for line in sys.stdin:
    message = json.loads(line)
    method = message.get("method")
    if method == "initialize":
        send({"id": message["id"], "result": {"protocolVersion": 1, "agentCapabilities": {"loadSession": True}}})
    elif method in ("session/new", "session/load"):
        send({"id": message["id"], "result": {"sessionId": "fixture-session", "configOptions": []}})
    elif method == "session/prompt":
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
        send({"id": message["id"], "result": {"configOptions": []}})
