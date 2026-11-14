import importlib.util
import json
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

    def test_group_snapshot_is_copied_and_projected(self):
        self.assertIn("group-chat-snapshot.schema.json", contract.ASSETS)
        event = json.loads((ROOT / "contract/assets/group-chat-snapshot.schema.json").read_text())
        projection = json.loads((contract.GENERATED / "event-projection.json").read_text())["components"]["schemas"]
        snapshot = projection["GroupSnapshotEventPayload"]
        self.assertEqual(event["required"], snapshot["required"])
        self.assertEqual("#/components/schemas/TranscriptBlock", snapshot["properties"]["messages"]["items"]["properties"]["blocks"]["items"]["$ref"])
        source = (contract.GENERATED / "ContractModels.kt").read_text()
        message = source.split("data class GroupSnapshotEventPayloadMessagesItem(", 1)[1].split("\n)", 1)[0]
        self.assertIn("val `blocks`: List<TranscriptBlock>? = null", message)
        self.assertIn("val `files`: List<GroupSnapshotEventPayloadMessagesItemFilesItem>? = null", message)
        self.assertNotIn("GroupSnapshotEventPayloadTranscriptBlock", source)
        for name in ("TranscriptMessageFilesItem", "SnapshotEventPayloadMessagesItemFilesItem", "GroupSnapshotEventPayloadMessagesItemFilesItem"):
            with self.subTest(name=name):
                self.assertIn("sealed class " + name, source)
                deleted = source.split("data class " + name + "Variant2(", 1)[1].split("\n)", 1)[0]
                self.assertIn("val `id`: String", deleted)
                self.assertIn("val `deleted`: Boolean", deleted)

    def operation_source(self, operation):
        document = {"components": {"schemas": {}}, "paths": {"/files": {"post": dict(operationId="uploadFile", **operation)}}}
        return generator.Generator(document).operations()

    def test_header_parameters_are_forwarded(self):
        for media, boundary in ((None, "send"), ("text/event-stream", "request")):
            with self.subTest(media=media):
                response = {} if media is None else {"content": {media: {}}}
                source = self.operation_source({
                    "parameters": [
                        {"in": "header", "name": "Content-Type", "required": True, "schema": {"type": "string"}},
                        {"in": "header", "name": "X-File-Name", "schema": {"type": "string"}},
                        {"in": "header", "name": "X-Count", "schema": {"type": "integer"}},
                    ],
                    "responses": {"200": response},
                })
                self.assertIn('`Content-Type`: String', source)
                self.assertIn('`X-File-Name`: String? = null', source)
                self.assertIn('`Content-Type`.let { headers["Content-Type"] = it }', source)
                self.assertIn('`X-File-Name`?.let { headers["X-File-Name"] = it }', source)
                self.assertIn('`X-Count`?.let { headers["X-Count"] = it.toString() }', source)
                self.assertIn('boundary.' + boundary + '("POST", path, null, timeoutMs, headers)', source)

    def test_raw_binary_request_preserves_bytes(self):
        source = self.operation_source({
            "requestBody": {"required": True, "content": {"*/*": {"schema": {"type": "string", "format": "binary"}}}},
            "responses": {"204": {}},
        })
        self.assertIn('suspend fun uploadFile(body: ByteArray, timeoutMs: Int = 20_000): Unit', source)
        self.assertIn('boundary.send("POST", path, body, timeoutMs)', source)
        self.assertNotIn('encodeToString', source)
        self.assertNotIn('headers', source)

    def test_binary_request_requires_binary_schema(self):
        for media, schema in (("*/*", {"type": "string"}), ("application/octet-stream", {"type": "object"}), ("text/plain", {"type": "string"})):
            with self.subTest(media=media, schema=schema), self.assertRaises(ValueError):
                self.operation_source({"requestBody": {"content": {media: {"schema": schema}}}, "responses": {"204": {}}})

    def test_octet_stream_download_returns_bytes(self):
        source = self.operation_source({"responses": {"200": {"content": {"application/octet-stream": {"schema": {"type": "string", "format": "binary"}}}}}})
        self.assertIn('suspend fun uploadFile(timeoutMs: Int = 20_000): ByteArray', source)
        self.assertIn('return boundary.send("POST", path, null, timeoutMs)', source)
        self.assertNotIn('decodeFromString', source)
        self.assertNotIn('headers', source)

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
