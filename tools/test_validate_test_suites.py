"""Host checks for omissions, stale registrations, and source-discovery false positives."""
import copy
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

import validate_test_suites as registry


class RegistrationTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.sources = self.root / "androidTest"
        self.sources.mkdir()
        self.write_source("ATest", "class ATest { @Test fun inputCommitsOnce() {} }")
        self.write_source("BTest", "class BTest { @org.junit.Test public void nativeQuery() {} }", suffix=".java")
        self.a = "example.tests.ATest"
        self.b = "example.tests.BTest"
        self.suites = {name: [] for name in registry.PRIMARY_SUITES}
        self.suites.update(platform=[self.a], engines=[self.b], full=[self.a, self.b], smoke=[self.a])

    def write_source(self, name, body, suffix=".kt"):
        (self.sources / (name + suffix)).write_text("package example.tests\n" + body, encoding="utf-8")

    def test_valid_union_assigns_each_source_test_once(self):
        owners = registry.validate_suites(self.suites, self.sources)
        self.assertEqual({self.a: "platform", self.b: "engines"}, owners)

    def test_new_test_cannot_be_omitted_even_if_full_matches_old_manifest(self):
        self.write_source("NewInputTest", "class NewInputTest { @Test fun newAlphabetCommits() {} }")
        with self.assertRaisesRegex(registry.SuiteValidationError, "Source tests missing a primary suite.*NewInputTest"):
            registry.validate_suites(self.suites, self.sources)

    def test_same_test_in_two_primary_suites_is_rejected(self):
        self.suites["ui"].append(self.a)
        with self.assertRaisesRegex(registry.SuiteValidationError, "multiple primary suites.*ATest"):
            registry.validate_suites(self.suites, self.sources)

    def test_full_must_include_every_primary_test_without_extra_entries(self):
        for full in ([self.a], [self.a, self.b, "example.tests.RemovedTest"]):
            with self.subTest(full=full):
                suites = copy.deepcopy(self.suites)
                suites["full"] = full
                with self.assertRaisesRegex(registry.SuiteValidationError, "full must exactly equal"):
                    registry.validate_suites(suites, self.sources)

    def test_smoke_cannot_request_a_test_outside_full(self):
        self.suites["smoke"].append("example.tests.UnregisteredSmokeTest")
        with self.assertRaisesRegex(registry.SuiteValidationError, "smoke must be a subset"):
            registry.validate_suites(self.suites, self.sources)

    def test_removed_or_helper_class_is_not_a_runnable_registration(self):
        self.write_source("Support", "class Support { fun helper() {} }")
        for name in ("example.tests.RemovedTest", "example.tests.Support"):
            with self.subTest(name=name):
                suites = copy.deepcopy(self.suites)
                suites["platform"].append(name)
                suites["full"].append(name)
                with self.assertRaisesRegex(registry.SuiteValidationError, "missing or non-test source classes"):
                    registry.validate_suites(suites, self.sources)

    def test_duplicate_entries_and_method_filters_cannot_hide_in_the_roster(self):
        for entry in (self.a, self.a + "#inputCommitsOnce"):
            with self.subTest(entry=entry):
                suites = copy.deepcopy(self.suites)
                suites["platform"].append(entry)
                with self.assertRaises(registry.SuiteValidationError):
                    registry.validate_suites(suites, self.sources)

    def test_discovery_ignores_docs_and_strings_but_supports_aliases_and_nested_classes(self):
        self.write_source("DocumentedSupport", '''
            /* class FakeTest { /* nested comment */ @Test fun fake() {} } */
            class DocumentedSupport {
                val example = "class FakeLiteralTest { @Test fun fake() {} }"
                val raw = """class FakeRawTest { @Test fun fake() {} }"""
            }
        ''')
        self.write_source("Outer", '''
            import org.junit.Test as Check
            class Outer(val callback: () -> Unit = {}) {
                class Nested { @Check fun rejectsLateReply() {} }
            }
        ''')
        nested = "example.tests.Outer$Nested"
        self.suites["platform"].append(nested)
        self.suites["full"].append(nested)
        self.assertEqual({self.a, self.b, nested}, set(registry.validate_suites(self.suites, self.sources)))

    def test_missing_source_tree_or_primary_group_fails_closed(self):
        with self.assertRaisesRegex(registry.SuiteValidationError, "source directory is missing"):
            registry.validate_suites(self.suites, self.root / "missing")
        del self.suites["semantic"]
        with self.assertRaisesRegex(registry.SuiteValidationError, "Required suites missing.*semantic"):
            registry.validate_suites(self.suites, self.sources)

    def test_cli_reports_invalid_registration_with_nonzero_exit(self):
        manifest = self.root / "suites.json"
        self.suites["full"].remove(self.b)
        manifest.write_text(json.dumps({"suites": {name: {"classes": classes} for name, classes in self.suites.items()}}))
        completed = subprocess.run([sys.executable, str(Path(registry.__file__)), "--manifest", str(manifest),
                                    "--source-root", str(self.sources)], capture_output=True, text=True)
        self.assertNotEqual(0, completed.returncode)
        self.assertIn("full must exactly equal", completed.stderr)


if __name__ == "__main__":
    unittest.main()
