"""Host-only regression transaction tests. Never contact an Android device."""
import copy
import io
import json
from pathlib import Path
import subprocess
import tarfile
import tempfile
import unittest
from unittest import mock

import regression as reg


def make_archive(path, files):
    with tarfile.open(path, "w") as archive:
        for name, value in files.items():
            entry = tarfile.TarInfo("./" + name)
            entry.mode = 0o600
            entry.mtime = 100
            entry.size = len(value)
            archive.addfile(entry, io.BytesIO(value))


class FakeDevice:
    """Durable package/IME state, not a mock of the implementation's commands."""
    serial = "host-fixture"

    def __init__(self, packages=(reg.APP, reg.TEST_APP)):
        self.packages = {package: self.original(package) for package in packages}
        self.secure = {key: None for key in reg.IME_SETTINGS}
        self.secure.update(default_input_method=reg.APP + "/.IpaBoardService",
                           enabled_input_methods=reg.APP + "/.IpaBoardService:other/.Ime")
        self.calls = []
        self.unsupported = None
        self.failed_restore = False
        self.failed_archive = False
        self.test_output = "OK (2 tests)\n"
        self.test_outputs = []
        self.test_exception = None
        self.install_error = False

    @staticmethod
    def original(package):
        return {"apk": package.encode(), "data": {"ce": {"shared_prefs/settings.xml": b"user configuration"},
                "de": {"files/device-state": b"original"}}, "state": {"enabled": 0, "stopped": False,
                "permissions": {"android.permission.RECEIVE_SMS": {"granted": False, "flags": ["USER_SET"]}},
                "components": {"enabledComponents": [], "disabledComponents": []}}, "appops": {"package:READ_SMS": "ignore"}}

    def check(self):
        return {"serial": self.serial, "fingerprint": "fixture/android15"}

    def package_paths(self, package):
        return [f"/data/app/{package}/base.apk"] if package in self.packages else []

    def state(self, package):
        return copy.deepcopy(self.packages[package]["state"])

    def appops(self, package):
        return copy.deepcopy(self.packages[package]["appops"])

    def data_roots(self, package):
        if package == self.unsupported:
            raise reg.RegressionError("not debuggable")
        return {"ce": f"/data/user/0/{package}", "de": f"/data/user_de/0/{package}"}

    def settings(self):
        return copy.deepcopy(self.secure)

    def stop(self, package):
        self.calls.append(("stop", package))
        self.packages[package]["state"]["stopped"] = True

    def archive(self, package, root, destination):
        if self.failed_archive:
            raise reg.RegressionError("archive incomplete")
        kind = "de" if "user_de" in root else "ce"
        make_archive(destination, self.packages[package]["data"][kind])
        return reg.archive_inventory(destination)

    def shell(self, *args):
        if args[0] == "sha256sum":
            package = args[1].split("/")[-2]
            return reg.hashlib.sha256(self.packages[package]["apk"]).hexdigest() + "  base.apk"
        if args[:2] == ("pm", "enable"):
            self.packages[args[-1]]["state"]["enabled"] = 1
            return "enabled"
        if args[:2] == ("settings", "--user"):
            self.secure[args[-2]] = args[-1]
            return ""
        raise AssertionError(args)

    def adb_command(self, *args, **kwargs):
        if args[0] == "pull":
            package = args[1].split("/")[-2]
            Path(args[2]).write_bytes(self.packages[package]["apk"])
            return b"pulled"
        if args[0] == "uninstall":
            self.calls.append(("uninstall", args[1]))
            del self.packages[args[1]]
            return b"Success"
        if args[0] == "shell":
            self.calls.append(("instrument", args[1]))
            self.packages[reg.APP]["data"]["ce"]["files/test-generated"] = b"test state"
            self.secure["default_input_method"] = reg.APP + "/.IpaBoardService"
            if self.test_exception:
                raise self.test_exception
            return (self.test_outputs.pop(0) if self.test_outputs else self.test_output).encode()
        raise AssertionError(args)

    def install(self, apks):
        self.calls.append(("install", str(apks[0])))
        if self.install_error and "snapshot-" not in str(apks[0]):
            raise reg.RegressionError("INSTALL_FAILED_UPDATE_INCOMPATIBLE")
        if self.failed_restore and "snapshot-" in str(apks[0]):
            raise reg.RegressionError("restore installer failure")
        path = Path(apks[0])
        package = reg.TEST_APP if "androidTest" in path.name or reg.TEST_APP in str(path) else reg.APP
        if package not in self.packages:
            self.packages[package] = self.original(package)
        self.packages[package]["apk"] = path.read_bytes()

    def clear_private_data(self, package, roots=None):
        self.calls.append(("clear", package))
        self.packages[package]["data"] = {"ce": {}, "de": {}}

    def seed_roundtrip(self, marker):
        self.calls.append(("seed", marker))
        for package in reg.PACKAGES:
            for kind in ("ce", "de"):
                self.packages[package]["data"][kind].update({
                    "files/regression-roundtrip/sentinel": marker.encode(),
                    "shared_prefs/roundtrip.xml": marker.encode(), "cache/roundtrip": marker.encode(),
                })

    def restore_data(self, package, root, archive):
        kind = "de" if "user_de" in root else "ce"
        with tarfile.open(archive) as data:
            self.packages[package]["data"][kind] = {entry.name.removeprefix("./"): data.extractfile(entry).read()
                                                       for entry in data if entry.isfile()}

    def restore_permissions(self, package, original):
        self.packages[package]["state"]["permissions"] = copy.deepcopy(original)

    def restore_appops(self, package, original):
        self.packages[package]["appops"] = copy.deepcopy(original)

    def restore_package_state(self, package, original):
        self.calls.append(("restore_state", package))
        self.packages[package]["state"] = copy.deepcopy(original)

    def restore_components(self, package, original):
        self.packages[package]["state"]["components"] = copy.deepcopy(original)

    def quarantine_ime(self):
        self.calls.append(("quarantine",))
        self.secure["default_input_method"] = ""

    def restore_settings(self, original):
        self.calls.append(("resume_ime",))
        self.secure = copy.deepcopy(original)


class TransactionTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)
        self.apks = [self.root / "app-debug.apk", self.root / "app-debug-androidTest.apk"]
        for apk in self.apks:
            apk.write_bytes(b"new build")

    def tearDown(self):
        self.directory.cleanup()

    def run_suite(self, device, roundtrip=False, classes=None, keep_going=False):
        with mock.patch.object(reg, "ROOT", self.root):
            return reg.run_suite(device, classes or [reg.APP + ".FixtureTest"], "smoke", self.apks, self.root / "backups", 10,
                                 roundtrip=roundtrip, keep_going=keep_going)

    def test_existing_apks_private_data_and_config_restored_and_backup_deleted(self):
        device = FakeDevice()
        original_packages, original_secure = copy.deepcopy(device.packages), device.settings()
        report = self.run_suite(device)
        self.assertEqual(original_packages, device.packages)
        self.assertEqual(original_secure, device.secure)
        self.assertFalse(list((self.root / "backups").glob("snapshot-*")))
        self.assertTrue(json.loads((report / "summary.json").read_text())["restored"])
        self.assertFalse(any(call[0] == "uninstall" for call in device.calls))
        instrumentation = next(call[1] for call in device.calls if call[0] == "instrument")
        self.assertIn("regressionSnapshot", instrumentation)
        self.assertIn(reg.RUNNER, instrumentation)

    def test_disk_snapshot_contains_only_software_private_data_and_metadata(self):
        device = FakeDevice()
        transaction = reg.Transaction.snapshot(device, self.root / "backups")
        saved = json.loads((transaction.location / "manifest.json").read_text())
        self.assertNotIn("settings", saved)
        for record in saved["packages"].values():
            self.assertNotIn("state", record)
            self.assertNotIn("appops", record)
            self.assertNotIn("permissions", record)
        self.assertEqual(set(reg.PACKAGES), set(saved["packages"]))
        transaction.restore()

    def test_separate_recovery_protects_current_environment_not_historical_settings(self):
        device = FakeDevice()
        original_data = copy.deepcopy(device.packages[reg.APP]["data"])
        transaction = reg.Transaction.snapshot(device, self.root / "backups")
        transaction.begin()
        device.secure["default_input_method"] = "current/.Ime"
        device.packages[reg.APP]["state"]["enabled"] = 3
        device.packages[reg.APP]["state"]["permissions"]["android.permission.RECEIVE_SMS"]["granted"] = True
        current_settings = device.settings()
        current_state = device.state(reg.APP)
        device.packages[reg.APP]["data"]["ce"]["files/test"] = b"changed"
        reg.Transaction.load(device, transaction.location).restore()
        self.assertEqual(original_data, device.packages[reg.APP]["data"])
        self.assertEqual(current_settings, device.secure)
        self.assertEqual(current_state, device.state(reg.APP))

    def test_legacy_interrupted_snapshot_can_still_restore_saved_environment(self):
        device = FakeDevice()
        transaction = reg.Transaction.snapshot(device, self.root / "backups")
        original_settings = copy.deepcopy(transaction.environment.settings)
        transaction.manifest["settings"] = original_settings
        for package, record in transaction.manifest["packages"].items():
            record["state"] = copy.deepcopy(transaction.environment.states[package])
            record["appops"] = copy.deepcopy(transaction.environment.appops[package])
        transaction.save()
        reg.Transaction.load(device, transaction.location).restore()
        self.assertEqual(original_settings, device.secure)

    def test_absent_packages_removed_after_regression(self):
        device = FakeDevice(packages=())
        self.run_suite(device)
        self.assertEqual({}, device.packages)
        self.assertEqual(set(reg.PACKAGES), {call[1] for call in device.calls if call[0] == "uninstall"})

    def test_installed_roundtrip_is_nested_inside_absent_original_transaction(self):
        device = FakeDevice(packages=())
        original_secure = device.settings()
        report = self.run_suite(device, roundtrip=True)
        self.assertEqual({}, device.packages)
        self.assertEqual(original_secure, device.secure)
        self.assertTrue(json.loads((report / "summary.json").read_text())["roundtrip_verified"])
        self.assertFalse(list((self.root / "backups").glob("snapshot-*")))
        self.assertTrue(any(call[0] == "seed" for call in device.calls))

    def test_failed_inner_roundtrip_retains_both_backups_until_manual_recovery(self):
        device = FakeDevice(packages=())
        device.failed_restore = True
        with self.assertRaises(reg.RegressionError):
            self.run_suite(device, roundtrip=True)
        outer = next((self.root / "backups").glob("snapshot-*"))
        manifest = json.loads((outer / "manifest.json").read_text())
        inner = outer / manifest["dependent_backup"]
        self.assertTrue(inner.exists())
        self.assertTrue(outer.exists())
        self.assertFalse(any(call[0] in ("uninstall", "instrument") for call in device.calls))
        device.failed_restore = False
        reg.Transaction.load(device, outer).restore()
        self.assertEqual({}, device.packages)
        self.assertFalse(outer.exists())

    def test_failed_instrumentation_still_restores_original(self):
        device = FakeDevice()
        original = copy.deepcopy(device.packages)
        device.test_output = "FAILURES!!!\nINSTRUMENTATION_CODE: -1\n"
        with self.assertRaises(reg.RegressionError):
            self.run_suite(device)
        self.assertEqual(original, device.packages)
        self.assertFalse(list((self.root / "backups").glob("snapshot-*")))

    def test_failed_class_report_records_the_tests_that_actually_ran(self):
        device = FakeDevice()
        device.test_output = "INSTRUMENTATION_STATUS_CODE: 1\nINSTRUMENTATION_STATUS_CODE: -2\nINSTRUMENTATION_STATUS_CODE: 1\nINSTRUMENTATION_STATUS_CODE: 0\nFAILURES!!!\nTests run: 2,  Failures: 1\n"
        with self.assertRaises(reg.RegressionError):
            self.run_suite(device)
        report = next((self.root / "app/build/reports/regression").glob("*/summary.json"))
        result = json.loads(report.read_text())["results"][0]
        self.assertEqual((2, 2, 1, 1, 0), tuple(result[key] for key in ("tests", "executed", "passed", "failed", "skipped")))

    def test_keep_going_collects_completed_failure_or_skip_and_still_restores(self):
        for output in ("FAILURES!!!\nTests run: 2, Failures: 1\n",
                       "INSTRUMENTATION_STATUS_CODE: -3\nOK (0 tests)\n"):
            with self.subTest(output=output):
                device = FakeDevice()
                original = copy.deepcopy(device.packages)
                device.test_outputs = [output, "OK (1 test)\n"]
                with self.assertRaises(reg.RegressionError):
                    self.run_suite(device, classes=[reg.APP + ".FirstTest", reg.APP + ".SecondTest"], keep_going=True)
                self.assertEqual(2, len([call for call in device.calls if call[0] == "instrument"]))
                self.assertEqual(original, device.packages)
                self.assertFalse(list((self.root / "backups").glob("snapshot-*")))
                reports = list((self.root / "app/build/reports/regression").glob("*/summary.json"))
                summary = json.loads(max(reports, key=lambda path: path.stat().st_mtime_ns).read_text())
                self.assertFalse(summary["success"])
                self.assertTrue(summary["restored"])
                self.assertEqual("passed", summary["results"][1]["status"])

    def test_keep_going_stops_on_environment_failure_crash_or_timeout(self):
        for output in ("INSTRUMENTATION_FAILED: bad runner\n", "shortMsg=Process crashed\nFAILURES!!!\nTests run: 2, Failures: 1\n", None):
            with self.subTest(output=output):
                device = FakeDevice()
                original = copy.deepcopy(device.packages)
                if output is None:
                    device.test_exception = subprocess.TimeoutExpired("instrument", 10)
                else:
                    device.test_output = output
                with self.assertRaises((reg.RegressionError, subprocess.TimeoutExpired)):
                    self.run_suite(device, classes=[reg.APP + ".FirstTest", reg.APP + ".SecondTest"], keep_going=True)
                self.assertEqual(1, len([call for call in device.calls if call[0] == "instrument"]))
                self.assertEqual(original, device.packages)
                self.assertFalse(list((self.root / "backups").glob("snapshot-*")))

    def test_timeout_and_interrupt_still_restore(self):
        for error in (subprocess.TimeoutExpired("instrument", 10), InterruptedError("signal")):
            with self.subTest(error=type(error).__name__):
                device = FakeDevice()
                original = copy.deepcopy(device.packages)
                device.test_exception = error
                with self.assertRaises(type(error)):
                    self.run_suite(device)
                self.assertEqual(original, device.packages)

    def test_unsupported_original_package_fails_before_stop_install_or_clear(self):
        device = FakeDevice()
        device.unsupported = reg.TEST_APP
        with self.assertRaises(reg.RegressionError):
            self.run_suite(device)
        self.assertEqual([], device.calls)

    def test_snapshot_failure_does_not_install_or_clear(self):
        device = FakeDevice()
        original = copy.deepcopy(device.packages)
        device.failed_archive = True
        with self.assertRaises(reg.RegressionError):
            self.run_suite(device)
        self.assertEqual(original, device.packages)
        self.assertFalse(any(call[0] in ("install", "clear", "instrument") for call in device.calls))

    def test_signature_mismatch_never_uninstalls_original_package(self):
        device = FakeDevice()
        original = copy.deepcopy(device.packages)
        device.install_error = True
        with self.assertRaises(reg.RegressionError):
            self.run_suite(device)
        self.assertEqual(original, device.packages)
        self.assertFalse(any(call[0] == "uninstall" for call in device.calls))
        self.assertFalse(any(call[0] == "instrument" for call in device.calls))

    def test_data_verified_before_original_ime_is_resumed(self):
        device = FakeDevice()
        transaction = reg.Transaction.snapshot(device, self.root / "backups")
        transaction.begin()
        with mock.patch.object(transaction, "verify", wraps=transaction.verify) as verify:
            original_resume = device.restore_settings
            def resume(values):
                self.assertTrue(verify.called)
                return original_resume(values)
            with mock.patch.object(device, "restore_settings", side_effect=resume):
                transaction.restore()

    def test_private_backup_directory_and_lock_fail_closed(self):
        parent = self.root / "backups"
        with reg.backup_lock(parent):
            self.assertEqual(0o700, parent.stat().st_mode & 0o777)
            with self.assertRaises(reg.RegressionError):
                with reg.backup_lock(parent):
                    pass
        unsafe = self.root / "unsafe"
        unsafe.mkdir(mode=0o755)
        with self.assertRaises(reg.RegressionError):
            reg.private_directory(unsafe)

    def test_restore_failure_retains_recovery_journal_and_backup(self):
        device = FakeDevice()
        device.failed_restore = True
        with self.assertRaises(reg.RegressionError):
            self.run_suite(device)
        backups = list((self.root / "backups").glob("snapshot-*"))
        self.assertEqual(1, len(backups))
        self.assertEqual("restore_failed", json.loads((backups[0] / "manifest.json").read_text())["phase"])
        device.failed_restore = False
        reg.Transaction.load(device, backups[0]).restore()
        self.assertFalse(backups[0].exists())

    def test_corrupted_backup_fails_before_destructive_recovery(self):
        device = FakeDevice()
        transaction = reg.Transaction.snapshot(device, self.root / "backups")
        item = transaction.manifest["packages"][reg.APP]["data"]["ce"]
        transaction.file(item["file"]).write_bytes(b"corrupt")
        device.calls.clear()
        with self.assertRaises(reg.RegressionError):
            transaction.restore()
        self.assertEqual([], device.calls)
        self.assertTrue(transaction.location.exists())

    def test_old_inventory_owners_upgrade_only_from_verified_original_tar(self):
        device = FakeDevice()
        transaction = reg.Transaction.snapshot(device, self.root / "backups")
        item = transaction.manifest["packages"][reg.APP]["data"]["ce"]
        original_hash = item["sha256"]
        for entry in item["inventory"].values():
            entry.pop("uid")
            entry.pop("gid")
        transaction.save()
        loaded = reg.Transaction.load(device, transaction.location)
        self.assertEqual(original_hash, loaded.manifest["packages"][reg.APP]["data"]["ce"]["sha256"])
        self.assertTrue(all("uid" in entry and "gid" in entry for entry in loaded.manifest["packages"][reg.APP]["data"]["ce"]["inventory"].values()))
        loaded.restore()

    def test_restore_verification_mismatch_retains_backup(self):
        device = FakeDevice()
        transaction = reg.Transaction.snapshot(device, self.root / "backups")
        transaction.begin()
        with mock.patch.object(device, "restore_data", return_value=None):
            with self.assertRaises(reg.RegressionError):
                transaction.restore()
        self.assertEqual("restore_failed", transaction.manifest["phase"])
        self.assertTrue(transaction.location.exists())

    def test_interrupted_incomplete_snapshot_only_recovers_package_state(self):
        device = FakeDevice()
        transaction = reg.Transaction.snapshot(device, self.root / "backups")
        transaction.manifest["phase"] = "snapshotting"
        transaction.manifest["packages"][reg.APP].pop("data")
        transaction.save()
        device.calls.clear()
        reg.Transaction.load(device, transaction.location).restore()
        self.assertFalse(any(call[0] in ("clear", "install", "uninstall") for call in device.calls))
        self.assertFalse(transaction.location.exists())

    def test_backup_identity_must_match_device(self):
        device = FakeDevice()
        transaction = reg.Transaction.snapshot(device, self.root / "backups")
        with mock.patch.object(device, "check", return_value={"serial": "another", "fingerprint": "fixture/android15"}):
            with self.assertRaises(reg.RegressionError):
                reg.Transaction.load(device, transaction.location)
        self.assertTrue(transaction.location.exists())

    def test_new_device_descriptor_fields_do_not_block_older_valid_backup(self):
        device = FakeDevice()
        transaction = reg.Transaction.snapshot(device, self.root / "backups")
        descriptor = device.check() | {"abi": "x86_64"}
        with mock.patch.object(device, "check", return_value=descriptor):
            loaded = reg.Transaction.load(device, transaction.location)
            loaded.restore()
        self.assertFalse(transaction.location.exists())


class ParsingTest(unittest.TestCase):
    def test_am_instrument_zero_exit_is_not_success(self):
        for output in ("INSTRUMENTATION_CODE: -1", "OK (0 tests)", "OK (2 tests)\nProcess crashed", "FAILURES!!!", "OK (1 test)\nshortMsg=Crash"):
            self.assertFalse(reg.instrumentation_passed(output))
        self.assertTrue(reg.instrumentation_passed("\nOK (1 test)\n\nINSTRUMENTATION_CODE: -1"))
        for status in (-1, -2, -3, -4):
            self.assertFalse(reg.instrumentation_passed(f"INSTRUMENTATION_STATUS_CODE: {status}\nOK (2 tests)\n"))

    def test_instrumentation_metrics_distinguish_pass_failure_and_skip(self):
        self.assertEqual({"tests": 2, "executed": 2, "passed": 1, "failed": 1, "skipped": 0},
                         reg.instrumentation_metrics("FAILURES!!!\nTests run: 2, Failures: 1\n"))
        output = "INSTRUMENTATION_STATUS_CODE: 1\nINSTRUMENTATION_STATUS_CODE: 0\nINSTRUMENTATION_STATUS_CODE: -3\nINSTRUMENTATION_STATUS_CODE: -4\nOK (2 tests)\n"
        self.assertEqual({"tests": 2, "executed": 1, "passed": 1, "failed": 0, "skipped": 2}, reg.instrumentation_metrics(output))

    def test_user_zero_permission_flags_and_components(self):
        dump = """  User 0: installed=true hidden=false suspended=false stopped=false enabled=0
    runtime permissions:
      android.permission.RECEIVE_SMS: granted=false, flags=[ USER_SET|USER_FIXED ]
    enabledComponents:
      com.example.ipa_board.Component
  User 10: installed=true stopped=true enabled=3
    runtime permissions:
      android.permission.RECEIVE_SMS: granted=true, flags=[ SYSTEM_FIXED ]
"""
        state = reg.parse_package_dump(dump)
        self.assertFalse(state["permissions"]["android.permission.RECEIVE_SMS"]["granted"])
        self.assertEqual(["USER_FIXED", "USER_SET"], state["permissions"]["android.permission.RECEIVE_SMS"]["flags"])
        self.assertEqual(["com.example.ipa_board.Component"], state["components"]["enabledComponents"])
        with self.assertRaises(reg.RegressionError):
            reg.parse_package_dump(dump.replace("USER_SET|USER_FIXED", "ONE_TIME"))

    def test_appops_excludes_usage_timestamps(self):
        self.assertEqual({"package:READ_SMS": "ignore", "uid:COARSE_LOCATION": "foreground"},
                         reg.parse_appops("Uid mode: COARSE_LOCATION: foreground\nREAD_SMS: ignore; time=+1m ago; rejectTime=+5s\n",
                                          "Uid mode: COARSE_LOCATION: foreground"))
        uid = "Uid mode: COARSE_LOCATION: ignore\nFINE_LOCATION: ignore\nREAD_SMS: ignore"
        self.assertEqual({"uid:COARSE_LOCATION": "ignore", "uid:FINE_LOCATION": "ignore", "uid:READ_SMS": "ignore", "package:READ_SMS": "allow"},
                         reg.parse_appops(uid + "\nREAD_SMS: allow; time=+1m ago", uid))
        with self.assertRaises(reg.RegressionError):
            reg.parse_appops("unrecognized OEM format")
        with self.assertRaises(reg.RegressionError):
            reg.parse_appops(uid + "\nREAD_SMS: allow", uid.replace("ignore", "allow", 1))

    def test_instrumentation_manifest_checks_direct_attributes_and_one_runner(self):
        tree = f'''  E: manifest (line=2)
      E: instrumentation (line=9)
        A: http://schemas.android.com/apk/res/android:name(0x01010003)="{reg.APP}.RegressionTestRunner" (Raw: "runner")
        A: http://schemas.android.com/apk/res/android:targetPackage(0x01010021)="{reg.APP}" (Raw: "target")
      E: application (line=24)
        A: http://schemas.android.com/apk/res/android:name(0x01010003)="unrelated"
'''
        reg.validate_instrumentation_manifest(tree)
        for broken in (tree.replace(".RegressionTestRunner", ".OtherRunner"),
                       tree + "      E: instrumentation (line=50)\n",
                       tree.replace("        A:", "          A:"),
                       tree.replace("E: instrumentation", "E: application")):
            with self.assertRaises(reg.RegressionError):
                reg.validate_instrumentation_manifest(broken)

    def test_absent_package_is_checked_without_pm_path_nonzero_exit(self):
        device = reg.Device(Path("adb"), "fixture")
        with mock.patch.object(device, "shell", return_value="package:" + reg.TEST_APP) as shell:
            self.assertEqual([], device.package_paths(reg.APP))
            self.assertEqual(1, shell.call_count)

    def test_component_overrides_are_reset_and_original_explicit_states_reapplied(self):
        device = reg.Device(Path("adb"), "fixture")
        original = {"enabledComponents": [reg.APP + ".Enabled"], "disabledComponents": [reg.APP + ".Disabled"]}
        current = {"enabledComponents": [reg.APP + ".NewOverride"], "disabledComponents": []}
        with mock.patch.object(device, "state", return_value={"components": current}), mock.patch.object(device, "shell") as shell:
            device.restore_components(reg.APP, original)
            calls = [entry.args for entry in shell.call_args_list]
            self.assertIn(("pm", "default-state", "--user", "0", reg.APP + "/" + reg.APP + ".NewOverride"), calls)
            self.assertIn(("pm", "enable", "--user", "0", reg.APP + "/" + reg.APP + ".Enabled"), calls)
            self.assertIn(("pm", "disable", "--user", "0", reg.APP + "/" + reg.APP + ".Disabled"), calls)

    def test_suite_manifest_rejects_shell_content_and_duplicates(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "suites.json"
            for classes in ([reg.APP + ".ATest", reg.APP + ".ATest"], ["$(echo secret)"]):
                path.write_text(json.dumps({"suites": {"smoke": {"classes": classes}}}))
                with self.assertRaises(reg.RegressionError):
                    reg.load_suites(path)

    def test_unsafe_tar_paths_and_special_files_fail_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "data.tar"
            for name, kind in (("../escape", tarfile.REGTYPE), ("/absolute", tarfile.REGTYPE), ("socket", tarfile.FIFOTYPE)):
                with tarfile.open(path, "w") as archive:
                    member = tarfile.TarInfo(name)
                    member.type = kind
                    archive.addfile(member)
                with self.assertRaises(reg.RegressionError):
                    reg.archive_inventory(path)

    def test_invalid_or_truncated_device_tar_reports_no_private_content(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "data.tar"
            for content in (b"sh: private/user-filename: not found\n", b"not a tar" * 1000, b"\0" * 511):
                path.write_bytes(content)
                with self.assertRaises(reg.RegressionError) as error:
                    reg.archive_inventory(path)
                self.assertNotIn("private/user-filename", str(error.exception))
                self.assertIn("bytes", str(error.exception))

    def test_exec_out_script_is_passed_as_argument_without_extra_shell_quotes(self):
        device = reg.Device(Path("adb"), "fixture")
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "data.tar"
            def adb(*args, **kwargs):
                self.assertEqual("cd /data/user/0/pkg && tar -cf - --exclude=./lib .", args[-1])
                make_archive(path, {"sentinel": b"synthetic"})
            with mock.patch.object(device, "adb_command", side_effect=adb), mock.patch.object(device, "validate_ownership"):
                self.assertIn("sentinel", device.archive("pkg", "/data/user/0/pkg", path))

    def test_restore_ownership_must_follow_parent_sgid_inheritance(self):
        device = reg.Device(Path("adb"), "fixture")
        with tempfile.TemporaryDirectory() as directory:
            archive = Path(directory) / "data.tar"
            with tarfile.open(archive, "w") as output:
                root = tarfile.TarInfo(".")
                root.type, root.uid, root.gid, root.mode = tarfile.DIRTYPE, 10209, 10209, 0o700
                output.addfile(root)
                cache = tarfile.TarInfo("./cache")
                cache.type, cache.uid, cache.gid, cache.mode = tarfile.DIRTYPE, 10209, 20209, 0o2771
                output.addfile(cache)
                file = tarfile.TarInfo("./cache/sentinel")
                file.uid, file.gid, file.mode = 10209, 20209, 0o600
                output.addfile(file)
            inventory = reg.archive_inventory(archive)
            def identity(*args):
                return "10209" if args[-1] in ("-u", "-g") else "10209 3003"
            with mock.patch.object(device, "shell", side_effect=identity):
                device.validate_ownership("pkg", archive, inventory)
                inventory["cache/sentinel"]["gid"] = 10209
                with self.assertRaises(reg.RegressionError):
                    device.validate_ownership("pkg", archive, inventory)

    def test_clear_keeps_installer_cache_root_inodes(self):
        device = reg.Device(Path("adb"), "fixture")
        with mock.patch.object(device, "shell") as shell:
            device.clear_private_data("pkg", {"ce": "/data/user/0/pkg"})
            script = shell.call_args.args[-1]
            self.assertIn("! -name cache ! -name code_cache", script)
            self.assertIn('find "$d" -mindepth 1', script)


if __name__ == "__main__":
    unittest.main()
