from pathlib import Path
import base64
import gzip
import hashlib
import json

root = Path(__file__).resolve().parents[1]
files = {
    "server.py": (root / "bridge/server.py").read_text(encoding="utf-8"),
    "bootstrap.py": (root / "runtime/bootstrap.py").read_text(encoding="utf-8"),
    "install-linux.sh": (root / "runtime/install-linux.sh").read_text(encoding="utf-8"),
    "node-release.json": (root / "runtime/node-release.json").read_text(encoding="utf-8"),
}
payload = json.dumps(files, sort_keys=True, ensure_ascii=False, separators=(",", ":")).encode()
encoded = base64.b64encode(gzip.compress(payload, mtime=0)).decode()
content_hash = hashlib.sha256(payload).hexdigest()
script = """#!/data/data/com.termux/files/usr/bin/bash
set -Eeuo pipefail
umask 077
task_root="$HOME/.local/share/devin-local-android"
mkdir -p "$task_root"
if ! command -v python >/dev/null 2>&1; then
  pkg upgrade -y -o Dpkg::Options::=--force-confold >>"$task_root/bootstrap.log" 2>&1
  pkg install -y python proot proot-distro >>"$task_root/bootstrap.log" 2>&1
fi
python - <<'BUNDLED_RUNTIME'
import base64, gzip, hashlib, json, os
from pathlib import Path
os.umask(0o077)
files=json.loads(gzip.decompress(base64.b64decode("PAYLOAD")))
root=Path.home()/".local/share/devin-local-android"
folder=root/"bridge"/"CONTENT_HASH"
folder.mkdir(parents=True,exist_ok=True)
for name,text in files.items():
    (folder/name).write_text(text,encoding="utf-8",newline="\\n")
if hashlib.sha256(json.dumps(files,sort_keys=True,ensure_ascii=False,separators=(",",":")).encode()).hexdigest() != "CONTENT_HASH":
    raise SystemExit("Bundled bridge integrity check failed")
path=folder/"bootstrap.py"
exec(compile(path.read_text(),str(path),"exec"),{"__name__":"__main__","__file__":str(path)})
BUNDLED_RUNTIME
"""
script = script.replace("PAYLOAD", encoded).replace("CONTENT_HASH", content_hash)
destination = root / "app/src/main/assets/bootstrap.sh"
destination.parent.mkdir(parents=True, exist_ok=True)
destination.write_text(script, encoding="utf-8", newline="\n")
assert len(script.encode()) < 120000, "Termux IPC payload too large"
print("Bundled private bridge:", content_hash, len(script.encode()), "bytes")
