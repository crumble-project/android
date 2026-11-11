import importlib.util
import pathlib
import shutil
import tempfile
import unittest

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

    def test_event_definitions_reuse_canonical_schemas(self):
        canonical = {"type": "object", "properties": {"text": {"type": "string"}}}
        document = {"components": {"schemas": {"TranscriptBlock": canonical}}}
        event = {"$defs": {"TranscriptBlock": canonical, "Local": {"$ref": "#/$defs/TranscriptBlock"}}, "properties": {"block": {"$ref": "#/$defs/TranscriptBlock"}, "local": {"$ref": "#/$defs/Local"}}}
        projected = generator.project(document, {"Snapshot": event})["components"]["schemas"]
        self.assertEqual(canonical, projected["TranscriptBlock"])
        self.assertEqual("#/components/schemas/TranscriptBlock", projected["Snapshot"]["properties"]["block"]["$ref"])
        self.assertEqual("#/components/schemas/SnapshotLocal", projected["Snapshot"]["properties"]["local"]["$ref"])
        self.assertEqual("#/components/schemas/TranscriptBlock", projected["SnapshotLocal"]["$ref"])
        self.assertNotIn("SnapshotTranscriptBlock", projected)
        conflicting = {"$defs": {"TranscriptBlock": {"type": "string"}}}
        with self.assertRaisesRegex(ValueError, "event definition conflicts with HTTP schema: TranscriptBlock"):
            generator.project(document, {"Snapshot": conflicting})
        nested = generator.project(document, {"Snapshot": {"$defs": {"TranscriptBlock": canonical}, "properties": {"text": {"$ref": "#/$defs/TranscriptBlock/properties/text"}}}})
        self.assertEqual("#/components/schemas/TranscriptBlock/properties/text", nested["components"]["schemas"]["Snapshot"]["properties"]["text"]["$ref"])

    def test_snapshot_reuses_generated_transcript_block(self):
        source = (contract.GENERATED / "ContractModels.kt").read_text()
        snapshot_message = source.split("data class SnapshotEventPayloadMessagesItem(", 1)[1].split("\n)", 1)[0]
        self.assertIn("val `blocks`: List<TranscriptBlock>? = null", snapshot_message)
        self.assertNotIn("SnapshotEventPayloadTranscriptBlock", source)
        self.assertIn("data class Variant3(val value: JobStepItem) : JobHistoryItem()", source)

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

    def test_open_object_keeps_additional_properties(self):
        instance = generator.Generator({"components": {"schemas": {}}, "paths": {}})
        schema = {"type": "object", "properties": {"openapi": {"type": "string"}}, "required": ["openapi"], "additionalProperties": {}}
        code = instance.model("Document", schema)
        self.assertIn("val additionalProperties: Map<String, JsonElement> = emptyMap(),", code)
        self.assertIn("return Document(fields.`openapi`, additional)", code)
        self.assertIn("output.encodeJsonElement(JsonObject(additional + fields))", code)
        self.assertNotIn("additionalProperties", instance.model("Closed", dict(schema, additionalProperties=False)))
        with self.assertRaises(ValueError):
            instance.model("Clash", dict(schema, properties={"additionalProperties": {"type": "string"}}))


if __name__ == "__main__":
    unittest.main()
