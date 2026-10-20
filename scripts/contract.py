#!/usr/bin/env python3
"""Verify, regenerate, compare, or update the committed Crumble contract."""
import argparse
import hashlib
import json
import os
import pathlib
import re
import shutil
import subprocess
import tempfile
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
ASSETS = ("openapi.json", "chat-snapshot.schema.json", "activity-event.schema.json", "heartbeat.schema.json", "CHANGELOG.md", "source-commit.txt")
GENERATED = ROOT / "app/src/main/java/dev/crumble/android/generated"
GENERATOR = ("scripts/generate-contract.py", "scripts/templates/ContractBoundary.kt")
VERSION = "1.0.0"


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def verify_assets(directory):
    entries = {}
    for line in (directory / "SHA256SUMS").read_text().splitlines():
        match = re.fullmatch(r"([0-9a-f]{64})  ([a-zA-Z0-9.-]+)", line)
        if not match or match[2] in entries:
            raise ValueError("invalid or duplicate checksum entry: " + line)
        entries[match[2]] = match[1]
    if set(entries) != set(ASSETS):
        raise ValueError("checksums must cover exactly the six payload assets")
    if set(p.name for p in directory.iterdir()) != set(ASSETS) | {"SHA256SUMS"}:
        raise ValueError("unexpected contract assets")
    for name, expected in entries.items():
        if digest(directory / name) != expected:
            raise ValueError("checksum mismatch: " + name)
    source = (directory / "source-commit.txt").read_text().strip()
    if not re.fullmatch(r"[0-9a-f]{40}", source):
        raise ValueError("invalid source commit")
    version = json.loads((directory / "openapi.json").read_text())["info"]["version"]
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", version):
        raise ValueError("invalid contract version")
    for name in ASSETS:
        if name.endswith(".json"):
            json.loads((directory / name).read_text())
    return entries, source, version


def verify_pin():
    assets = ROOT / "contract/assets"
    entries, source, version = verify_assets(assets)
    manifest = json.loads((ROOT / "contract/pin.json").read_text())
    if (manifest["checksums"] != entries or manifest["sourceCommit"] != source or manifest["version"] != version):
        raise ValueError("contract pin does not match verified assets")
    if manifest["generator"] != {"version": VERSION, "sha256": {p: digest(ROOT / p) for p in GENERATOR}}:
        raise ValueError("generator changed; regenerate and review the pin with the updater")
    if manifest["repository"] != "crumble-project/bot":
        raise ValueError("unexpected contract repository")
    if manifest["source"] not in ("local-export", "github-release"):
        raise ValueError("unknown pin source")
    if manifest["source"] == "local-export" and manifest["release"] is not None:
        raise ValueError("local export must not claim to be a published release")
    if manifest["source"] == "github-release" and manifest["release"] != "contract-v" + version:
        raise ValueError("release tag must match contract version")
    return manifest


def generate(output=GENERATED):
    subprocess.run(["python3", str(ROOT / "scripts/generate-contract.py"), "--output", str(output)], cwd=ROOT, check=True)


def check():
    verify_pin()
    with tempfile.TemporaryDirectory(prefix="crumble-generated-") as temporary:
        output = pathlib.Path(temporary)
        generate(output)
        expected = {p.relative_to(output): p.read_bytes() for p in output.rglob("*") if p.is_file()}
        actual = {p.relative_to(GENERATED): p.read_bytes() for p in GENERATED.rglob("*") if p.is_file()}
        differences = sorted(str(p) for p in expected.keys() | actual.keys() if expected.get(p) != actual.get(p))
        if differences:
            raise ValueError("stale or manually edited generated output: " + ", ".join(differences))
    print("Verified assets, generator pin, and byte-identical generated output")


def fetch_json(url):
    headers = {"Accept": "application/vnd.github+json", "X-GitHub-Api-Version": "2022-11-28", "User-Agent": "crumble-android-contract"}
    token = os.environ.get("GITHUB_TOKEN")
    if token:
        headers["Authorization"] = "Bearer " + token
    with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=60) as response:
        return json.load(response)


def latest():
    releases = []
    page = 1
    while True:
        batch = fetch_json("https://api.github.com/repos/crumble-project/bot/releases?per_page=100&page=" + str(page))
        releases.extend(r for r in batch if not r["draft"] and not r["prerelease"] and re.fullmatch(r"contract-v[0-9]+\.[0-9]+\.[0-9]+", r["tag_name"]))
        if len(batch) < 100:
            break
        page += 1
    if not releases:
        print("No published contract releases; keeping the explicit local bootstrap pin")
        return None
    return max(releases, key=lambda r: r["published_at"])["tag_name"]


def update(local=None, release=None):
    with tempfile.TemporaryDirectory(prefix="crumble-contract-") as temporary:
        directory = pathlib.Path(temporary)
        if local:
            verify_assets(local)
            for name in (*ASSETS, "SHA256SUMS"):
                shutil.copyfile(local / name, directory / name)
        else:
            if not re.fullmatch(r"contract-v[0-9]+\.[0-9]+\.[0-9]+", release):
                raise ValueError("expected immutable contract-vX.Y.Z release tag")
            metadata = fetch_json("https://api.github.com/repos/crumble-project/bot/releases/tags/" + release)
            if metadata["draft"] or metadata["prerelease"] or metadata["tag_name"] != release:
                raise ValueError("expected a published stable contract release")
            urls = {asset["name"]: asset["browser_download_url"] for asset in metadata["assets"]}
            for name in (*ASSETS, "SHA256SUMS"):
                with urllib.request.urlopen(urls[name], timeout=60) as response:
                    (directory / name).write_bytes(response.read())
        entries, source, version = verify_assets(directory)
        if release and release != "contract-v" + version:
            raise ValueError("downloaded version does not match requested tag")
        # Generate before changing the pin so unsupported schema updates leave it untouched.
        candidate = directory / "generated"
        subprocess.run(["python3", str(ROOT / "scripts/generate-contract.py"), "--assets", str(directory), "--output", str(candidate)], cwd=ROOT, check=True)
        assets = ROOT / "contract/assets"
        assets.mkdir(parents=True, exist_ok=True)
        for name in (*ASSETS, "SHA256SUMS"):
            shutil.copyfile(directory / name, assets / name)
        manifest = {
            "repository": "crumble-project/bot", "source": "local-export" if local else "github-release",
            "release": release, "version": version, "sourceCommit": source, "checksums": entries,
            "generator": {"version": VERSION, "sha256": {p: digest(ROOT / p) for p in GENERATOR}},
        }
        (ROOT / "contract/pin.json").write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n")
        if GENERATED.exists():
            shutil.rmtree(GENERATED)
        shutil.copytree(candidate, GENERATED)
    check()
    subprocess.run(["./gradlew", "test"], cwd=ROOT, check=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=["verify", "generate", "check", "update", "update-latest"])
    group = parser.add_mutually_exclusive_group()
    group.add_argument("--local", type=pathlib.Path)
    group.add_argument("--release")
    args = parser.parse_args()
    if args.command == "verify":
        verify_pin()
    elif args.command == "generate":
        verify_pin()
        generate()
    elif args.command == "check":
        check()
    elif args.command == "update-latest":
        release = latest()
        pin = json.loads((ROOT / "contract/pin.json").read_text())
        if release and pin["release"] != release:
            update(release=release)
        else:
            check()
            subprocess.run(["./gradlew", "test"], cwd=ROOT, check=True)
    else:
        if not args.local and not args.release:
            parser.error("update requires --local DIRECTORY or --release contract-vX.Y.Z")
        update(args.local, args.release)


if __name__ == "__main__":
    main()
