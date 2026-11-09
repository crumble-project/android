#!/usr/bin/env python3
"""Regenerate, compare, or update the committed Crumble contract."""
import argparse
import pathlib
import shutil
import subprocess
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
ASSETS = ("openapi.json", "chat-snapshot.schema.json", "activity-event.schema.json", "heartbeat.schema.json")
GENERATED = ROOT / "app/src/main/java/dev/crumble/android/generated"


def generate(output=GENERATED, assets=ROOT / "contract/assets"):
    subprocess.run(["python3", str(ROOT / "scripts/generate-contract.py"), "--assets", str(assets), "--output", str(output)], cwd=ROOT, check=True)


def check():
    with tempfile.TemporaryDirectory(prefix="crumble-generated-") as temporary:
        output = pathlib.Path(temporary)
        generate(output)
        expected = {p.relative_to(output): p.read_bytes() for p in output.rglob("*") if p.is_file()}
        actual = {p.relative_to(GENERATED): p.read_bytes() for p in GENERATED.rglob("*") if p.is_file()}
        differences = sorted(str(p) for p in expected.keys() | actual.keys() if expected.get(p) != actual.get(p))
        if differences:
            raise ValueError("stale or manually edited generated output: " + ", ".join(differences))
    print("Verified byte-identical generated output")


def update(source):
    with tempfile.TemporaryDirectory(prefix="crumble-contract-") as temporary:
        directory = pathlib.Path(temporary)
        for name in ASSETS:
            shutil.copyfile(source / name, directory / name)
        # Generate before replacing the assets so an unsupported schema leaves them untouched.
        candidate = directory / "generated"
        generate(candidate, directory)
        assets = ROOT / "contract/assets"
        assets.mkdir(parents=True, exist_ok=True)
        for name in ASSETS:
            shutil.copyfile(directory / name, assets / name)
        if GENERATED.exists():
            shutil.rmtree(GENERATED)
        shutil.copytree(candidate, GENERATED)
    check()
    subprocess.run(["./gradlew", "test"], cwd=ROOT, check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["generate", "check", "update"])
    parser.add_argument("--from", dest="source", type=pathlib.Path, help="Directory with the contract files, such as bot/openapi")
    args = parser.parse_args()
    if args.command == "generate":
        generate()
    elif args.command == "check":
        check()
    else:
        if not args.source:
            parser.error("update requires --from DIRECTORY")
        update(args.source)


if __name__ == "__main__":
    main()
