import importlib.util
import io
import json
import pathlib
import shutil
import tempfile
import unittest
from unittest.mock import patch

ROOT = pathlib.Path(__file__).resolve().parents[1]


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, ROOT / "scripts" / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


contract = load("contract", "contract.py")
generator = load("generate_contract", "generate-contract.py")


class ContractTests(unittest.TestCase):
    def test_regeneration_is_byte_identical(self):
        contract.check()

    def test_rejects_manual_edits_and_unexpected_files(self):
        original = contract.GENERATED
        with tempfile.TemporaryDirectory() as temporary:
            output = pathlib.Path(temporary) / "generated"
            shutil.copytree(original, output)
            contract.GENERATED = output
            try:
                file = output / "ContractModels.kt"
                file.write_text(file.read_text() + "\n// edited\n")
                with self.assertRaisesRegex(ValueError, "stale or manually edited"):
                    contract.check()
                shutil.copyfile(original / file.name, file)
                (output / "Unexpected.kt").write_text("manual file")
                with self.assertRaisesRegex(ValueError, "Unexpected.kt"):
                    contract.check()
            finally:
                contract.GENERATED = original

    def test_every_payload_checksum_is_required_and_verified(self):
        for filename in contract.ASSETS:
            with self.subTest(asset=filename), tempfile.TemporaryDirectory() as temporary:
                output = pathlib.Path(temporary) / "assets"
                shutil.copytree(ROOT / "contract/assets", output)
                path = output / filename
                path.write_bytes(path.read_bytes() + b"corruption")
                with self.assertRaisesRegex(ValueError, "checksum mismatch"):
                    contract.verify_assets(output)

    def test_corrupt_local_update_does_not_replace_the_pin(self):
        pin = (ROOT / "contract/pin.json").read_bytes()
        with tempfile.TemporaryDirectory() as temporary:
            output = pathlib.Path(temporary) / "assets"
            shutil.copytree(ROOT / "contract/assets", output)
            (output / "CHANGELOG.md").write_text("corrupt")
            with self.assertRaisesRegex(ValueError, "checksum mismatch"):
                contract.update(local=output)
        self.assertEqual(pin, (ROOT / "contract/pin.json").read_bytes())
        contract.check()

    def test_corrupt_release_download_does_not_replace_the_pin(self):
        pin = (ROOT / "contract/pin.json").read_bytes()
        assets = {name: (ROOT / "contract/assets" / name).read_bytes() for name in (*contract.ASSETS, "SHA256SUMS")}
        assets["openapi.json"] += b" "
        metadata = {"draft": False, "prerelease": False, "tag_name": "contract-v1.0.0", "assets": [{"name": name, "browser_download_url": "https://fixture.invalid/" + name} for name in assets]}
        downloaded = []
        def download(request, timeout):
            url = request.full_url if isinstance(request, contract.urllib.request.Request) else request
            downloaded.append(url)
            if url.startswith("https://api.github.com/"):
                return io.BytesIO(json.dumps(metadata).encode())
            return io.BytesIO(assets[url.rsplit("/", 1)[1]])
        with patch.object(contract.urllib.request, "urlopen", side_effect=download):
            with self.assertRaisesRegex(ValueError, "checksum mismatch"):
                contract.update(release="contract-v1.0.0")
        self.assertEqual(8, len(downloaded))
        self.assertEqual(pin, (ROOT / "contract/pin.json").read_bytes())
        contract.check()

    def test_missing_duplicate_and_traversal_checksums_fail(self):
        for entry in ("", "../openapi.json", "openapi.json\n"):
            with self.subTest(entry=entry), tempfile.TemporaryDirectory() as temporary:
                output = pathlib.Path(temporary) / "assets"
                shutil.copytree(ROOT / "contract/assets", output)
                sums = output / "SHA256SUMS"
                sums.write_text("0" * 64 + "  " + entry + "\n")
                with self.assertRaises(ValueError):
                    contract.verify_assets(output)

    def test_unsupported_schema_fails_closed(self):
        instance = generator.Generator({"components": {"schemas": {}}, "paths": {}})
        for schema in ({"type": "object", "patternProperties": {}}, {"type": "object", "additionalProperties": False}, {"type": ["string", "number"]}):
            with self.subTest(schema=schema), self.assertRaises(ValueError):
                instance.type(schema, "Unsupported")

    def test_nullable_and_optional_are_distinct(self):
        instance = generator.Generator({"components": {"schemas": {"Value": {"type": "object", "properties": {"name": {"type": "string"}}, "required": ["name"]}}}, "paths": {}})
        self.assertEqual("Value?", instance.type({"anyOf": [{"$ref": "#/components/schemas/Value"}, {"type": "null"}]}, "Nullable"))
        schema = {"type": "object", "properties": {"requiredNull": {"type": ["string", "null"]}, "optionalNull": {"type": ["string", "null"]}}, "required": ["requiredNull"]}
        instance.register("Patch", schema, request=True)
        code = instance.model("Patch", schema)
        self.assertIn("val `requiredNull`: String?,", code)
        self.assertIn("val `optionalNull`: OptionalField<String?> = OptionalField.Absent", code)


if __name__ == "__main__":
    unittest.main()
