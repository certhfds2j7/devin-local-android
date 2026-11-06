"""Runs inside Termux; pairing secrets only return to the app's PendingIntent."""
import fcntl
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import time
import urllib.error
import urllib.request

ROOT = Path.home() / ".local/share/devin-local-android"
SOURCE = Path(__file__).resolve().parent
CONTAINER = "devin-android-gate"
files = {name: (SOURCE / name).read_text(encoding="utf-8") for name in
         ("server.py", "bootstrap.py", "install-linux.sh", "node-release.json")}
EXPECTED = hashlib.sha256(json.dumps(files, sort_keys=True, ensure_ascii=False,
                                     separators=(",", ":")).encode()).hexdigest()
ROOT.mkdir(parents=True, exist_ok=True)
os.umask(0o077)
lock = (ROOT / "bootstrap.lock").open("w")
fcntl.flock(lock, fcntl.LOCK_EX)


def run(arguments, input_data=None):
    with (ROOT / "bootstrap.log").open("ab") as log:
        subprocess.run(arguments, input=input_data, stdout=log, stderr=log, check=True)


pd = shutil.which("proot-distro")
if not pd:
    run(["pkg", "install", "-y", "python", "proot", "proot-distro"])
    pd = shutil.which("proot-distro")
if not pd:
    raise SystemExit("Local Linux runtime unavailable")

check = subprocess.run([pd, "login", CONTAINER, "--", "/usr/bin/true"],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
if check.returncode:
    transfers = ROOT / "transfers"
    transfers.mkdir(exist_ok=True)
    archive = transfers / "ubuntu-arm64.tar.gz"
    if not archive.exists():
        urllib.request.urlretrieve("https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz", archive)
    if hashlib.sha256(archive.read_bytes()).hexdigest() != "a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2":
        raise SystemExit("Ubuntu checksum mismatch")
    run([pd, "install", "--name", CONTAINER, str(archive)])


def health():
    try:
        token = (ROOT / "token").read_text().strip()
        request = urllib.request.Request("http://127.0.0.1:8765/v1/health", headers={"Authorization": "Bearer " + token})
        with urllib.request.urlopen(request, timeout=2) as response:
            return token, json.load(response)
    except (OSError, ValueError, urllib.error.URLError):
        return None, None


token, current = health()
if current and current.get("bundleHash") != EXPECTED:
    if current.get("agentBusy"):
        raise SystemExit("Finish local tasks before updating the bridge")
    request = urllib.request.Request("http://127.0.0.1:8765/v1/admin/shutdown", method="POST",
                                    data=b"{}", headers={"Authorization": "Bearer " + token})
    with urllib.request.urlopen(request, timeout=5):
        pass
    for _ in range(30):
        if not health()[1]:
            break
        time.sleep(0.5)
    else:
        raise SystemExit("Previous bridge did not stop")
    current = None

if not current:
    source_relative = SOURCE.relative_to(ROOT)
    guest_source = str(Path("/mnt/devin-local") / source_relative)
    run([pd, "login", "--bind", str(ROOT) + ":/mnt/devin-local", CONTAINER, "--",
         "/usr/bin/env", "DEVIN_ANDROID_SOURCE=" + guest_source, "/bin/bash", "-s"],
        (SOURCE / "install-linux.sh").read_bytes())
    run([pd, "login", "--bind", str(ROOT) + ":/mnt/devin-local", "--detach", CONTAINER, "--",
         "/usr/bin/env", "DEVIN_ANDROID_DATA=/mnt/devin-local", "DEVIN_ANDROID_BUNDLE_HASH=" + EXPECTED, "/usr/bin/python3",
         str(Path("/mnt/devin-local") / source_relative / "server.py")])
    for _ in range(60):
        token, current = health()
        if current and current.get("bundleHash") == EXPECTED:
            break
        time.sleep(0.5)
    else:
        raise SystemExit("Local bridge did not start")
print("PAIRING:" + json.dumps({"token": token, "port": 8765}))
