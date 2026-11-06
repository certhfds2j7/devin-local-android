"""Create a GitHub Releases updater manifest from the signed APK."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil

parser = argparse.ArgumentParser()
parser.add_argument("--apk", required=True, type=Path)
parser.add_argument("--output", required=True, type=Path)
parser.add_argument("--tag")
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
gradle = (root / "app/build.gradle.kts").read_text(encoding="utf-8")
version = re.search(r'versionName\s*=\s*"([^"]+)"', gradle)[1]
code = int(re.search(r"versionCode\s*=\s*(\d+)", gradle)[1])
tag = args.tag or "v" + version
if tag != "v" + version:
    raise SystemExit("Release tag does not match the application version")
args.output.mkdir(parents=True, exist_ok=True)
name = "devin-local-android-" + version + ".apk"
destination = args.output / name
shutil.copyfile(args.apk, destination)
checksum = hashlib.sha256(destination.read_bytes()).hexdigest()
manifest = {
    "versionName": version, "versionCode": code, "minSdk": 26, "bridgeApiVersion": 1,
    "apkUrl": f"https://github.com/certhfds2j7/devin-local-android/releases/download/{tag}/{name}",
    "sha256": checksum,
    "signerSha256": (root / "docs/release-signing-cert.sha256").read_text().strip(),
    "notes": "简体中文客户端；项目、编辑、Git 和命令在手机执行，AI 使用官方在线服务。"
}
(args.output / "update.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
(args.output / "SHA256SUMS.txt").write_text(checksum + "  " + name + "\n", encoding="ascii")
print("Prepared release", version, "versionCode", code, "SHA256", checksum)
