"""Publish the already tested APK without rebuilding, local file access, or external actions."""
import hashlib
import json
import os
from pathlib import Path
import urllib.error
import urllib.parse
import urllib.request

REPOSITORY = "helenlawrence2104-luo/NevolutionXposed"
TAG = "v2.0.59-beta"
TARGET = "0f71c45ea89b16e6b32d1a5c379ab7eda227789a"
TITLE = "v2.0.59 测试版 · 通知回复、图片表情与语音转文字"
EXPECTED_APK = "ea52d7fc5dcd6bcc2f8fc0c74a1f59b838d471a310eabebe2dfa073d679dd9ee"
APK = Path("downloads/NevolutionXposed-2.0.59-voice-notification-debug.apk")
CHECKSUM = Path("downloads/SHA256SUMS.txt")
API = f"https://api.github.com/repos/{REPOSITORY}"


def request(method, url, data=None, content_type="application/json"):
    headers = {
        "Authorization": f"Bearer {os.environ['GH_TOKEN']}",
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
        "User-Agent": "NevolutionXposed-release",
    }
    if isinstance(data, dict):
        data = json.dumps(data, ensure_ascii=False).encode("utf-8")
    if data is not None:
        headers["Content-Type"] = content_type
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    with urllib.request.urlopen(req, timeout=60) as response:
        return json.load(response)


def verify_files():
    apk = APK.read_bytes()
    if hashlib.sha256(apk).hexdigest() != EXPECTED_APK:
        raise RuntimeError("APK differs from the device-tested version; refusing to publish")
    if CHECKSUM.read_text(encoding="utf-8").split() != [EXPECTED_APK, APK.name]:
        raise RuntimeError("Checksum manifest mismatch")
    return [(APK, apk), (CHECKSUM, CHECKSUM.read_bytes())]


def main():
    files = verify_files()
    releases = request("GET", API + "/releases?per_page=100")
    matching = [release for release in releases if release["tag_name"] == TAG]
    if len(matching) > 1:
        raise RuntimeError("Ambiguous release; refusing to select a different draft")
    notes = ("发行条目创建：2026-11-06；APK构建：2026-11-05。下载下方 Assets 中的 APK。\n\n"
             + Path("docs/UPDATE-2.0.59.md").read_text(encoding="utf-8"))
    release = matching[0] if matching else request("POST", API + "/releases", {
        "tag_name": TAG, "target_commitish": TARGET, "name": TITLE,
        "body": notes, "draft": True, "prerelease": True,
    })
    release_url = API + f"/releases/{release['id']}"
    existing = {asset["name"]: asset for asset in release["assets"]}
    for path, content in files:
        digest = "sha256:" + hashlib.sha256(content).hexdigest()
        if path.name in existing:
            asset = existing[path.name]
            if asset.get("digest") != digest:
                raise RuntimeError("Existing asset is not verified; refusing to overwrite it")
            print("Verified existing asset:", path.name)
            continue
        upload = (f"https://uploads.github.com/repos/{REPOSITORY}/releases/"
                  f"{release['id']}/assets?name={urllib.parse.quote(path.name)}")
        mime = "application/vnd.android.package-archive" if path.suffix == ".apk" else "text/plain"
        asset = request("POST", upload, content, mime)
        if asset["size"] != len(content) or (asset.get("digest") and asset["digest"] != digest):
            raise RuntimeError("Uploaded asset verification failed; release remains draft")
        print("Uploaded verified asset:", path.name)
    release = request("PATCH", release_url, {
        "name": TITLE, "body": notes, "draft": False, "prerelease": True, "make_latest": "false",
    })
    release = request("GET", release_url)
    assets = {asset["name"]: asset for asset in release["assets"]}
    if release["draft"] or not release["prerelease"] or any(p.name not in assets for p, _ in files):
        raise RuntimeError("Published release verification failed")
    print("Published prerelease:", release["html_url"])
    for path, _ in files:
        print("Asset:", assets[path.name]["browser_download_url"])


if __name__ == "__main__":
    main()
