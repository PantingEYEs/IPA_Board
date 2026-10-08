#!/usr/bin/env python3
"""Run Android regression tests inside a verified backup/restore transaction.

Only owner-user (user 0), debuggable IPA Board installations are supported.
No adb uninstall of a pre-existing package is ever used to fix an install failure.
The backup is deliberately retained if *any* restoration check fails.
"""
from __future__ import annotations

import argparse
import contextlib
import fcntl
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shlex
import shutil
import signal
import subprocess
import sys
import tarfile
import tempfile
import time
import uuid
from datetime import datetime, timezone

from validate_test_suites import validate_suites

ROOT = Path(__file__).resolve().parents[1]
APP = "com.example.ipa_board"
TEST_APP = APP + ".test"
RUNNER = TEST_APP + "/" + APP + ".RegressionTestRunner"
PACKAGES = (APP, TEST_APP)
IME_SETTINGS = (
    "enabled_input_methods", "default_input_method", "selected_input_method_subtype",
    "input_methods_subtype_history", "disabled_system_input_methods",
)
PERMISSION_FLAGS = {
    "USER_SET": "user-set", "USER_FIXED": "user-fixed",
    "REVOKED_COMPAT": "revoked-compat", "REVOKE_WHEN_REQUESTED": "revoke-when-requested",
    "REVIEW_REQUIRED": "review-required",
}
ENABLED_COMMANDS = {0: "default-state", 1: "enable", 2: "disable", 3: "disable-user", 4: "disable-until-used"}
CLASS_RE = re.compile(r"com\.example\.ipa_board\.[A-Za-z0-9_.$]+(?:#[A-Za-z0-9_$]+)?\Z")


class RegressionError(RuntimeError):
    pass


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def write_json(path: Path, value) -> None:
    temporary = path.with_suffix(path.suffix + ".tmp")
    with temporary.open("w", encoding="utf-8") as stream:
        json.dump(value, stream, indent=2, ensure_ascii=False)
        stream.write("\n")
        stream.flush()
        os.fsync(stream.fileno())
    os.chmod(temporary, 0o600)
    temporary.replace(path)
    # Persist the rename as well: the manifest is the recovery journal.
    fd = os.open(path.parent, os.O_RDONLY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def private_directory(path: Path) -> Path:
    if path.is_symlink():
        raise RegressionError(f"Refusing symlink backup directory: {path}")
    path.mkdir(parents=True, exist_ok=True, mode=0o700)
    stat = path.stat()
    if stat.st_uid != os.getuid() or stat.st_mode & 0o077:
        raise RegressionError(f"Backup directory must be owned by this user and mode 0700: {path}")
    return path


def backup_root() -> Path:
    return Path(tempfile.gettempdir()) / f"ipa-board-regression-{os.getuid()}"


@contextlib.contextmanager
def backup_lock(parent: Path):
    """Prevent two host processes from snapshotting/restoring the same device."""
    private_directory(parent)
    path = parent / ".lock"
    if path.is_symlink():
        raise RegressionError("Refusing symlink backup lock")
    with path.open("a") as stream:
        try:
            fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as error:
            raise RegressionError("Another regression/restore process holds the backup lock") from error
        try:
            yield
        finally:
            fcntl.flock(stream, fcntl.LOCK_UN)


def command(args, *, timeout=60, input=None, output=None, cwd=None) -> bytes:
    """No shell interpolation; timeouts/signals also kill the local adb child."""
    process = subprocess.Popen(
        [str(arg) for arg in args], stdin=subprocess.PIPE if input is not None else subprocess.DEVNULL,
        stdout=output if output is not None else subprocess.PIPE, stderr=subprocess.PIPE,
        cwd=cwd, start_new_session=True,
    )
    try:
        stdout, stderr = process.communicate(input=input, timeout=timeout)
    except BaseException:
        with contextlib.suppress(ProcessLookupError):
            os.killpg(process.pid, signal.SIGKILL)
        process.communicate()
        raise
    if process.returncode:
        # stderr is bounded and adb errors do not include backed-up private data.
        detail = stderr.decode("utf-8", "replace")[-2000:].strip()
        raise RegressionError(f"Command failed ({process.returncode}): {shlex.join(map(str, args[:6]))}: {detail}")
    return stdout or b""


def parse_package_dump(dump: str) -> dict:
    user = re.search(r"^\s*User 0: (.+)$", dump, re.MULTILINE)
    if not user:
        raise RegressionError("Cannot parse owner-user package state; refusing to mutate device")
    fields = dict(re.findall(r"([A-Za-z]+)=([^ ]+)", user.group(1)))
    if fields.get("installed") != "true" or fields.get("hidden") == "true" or fields.get("suspended") == "true":
        raise RegressionError("Hidden/suspended/archived installations are not supported")
    try:
        enabled = int(fields["enabled"])
        stopped = fields["stopped"] == "true"
    except (KeyError, ValueError) as error:
        raise RegressionError("Cannot parse enabled/stopped package state") from error
    if enabled not in ENABLED_COMMANDS:
        raise RegressionError("Unsupported enabled state")
    # Only the owner-user section, never a work profile's permission state.
    section = dump[user.end():]
    next_user = re.search(r"^\s*User \d+:", section, re.MULTILINE)
    if next_user:
        section = section[:next_user.start()]
    permission_block = re.search(r"runtime permissions:\s*\n((?:\s{6,}.+\n?)*)", section)
    permissions = {}
    if permission_block:
        for name, granted, flags in re.findall(
            r"([A-Za-z0-9_.]+): granted=(true|false), flags=\[([^\]]*)\]", permission_block.group(1)
        ):
            flag_list = sorted(filter(None, (flag.strip() for flag in flags.split("|"))))
            if any(flag in flag_list for flag in ("ONE_TIME", "AUTO_REVOKED", "POLICY_FIXED", "SYSTEM_FIXED")):
                raise RegressionError(f"Permission {name} has a state adb cannot reliably restore")
            permissions[name] = {"granted": granted == "true", "flags": flag_list}
    components = {}
    for kind in ("enabledComponents", "disabledComponents"):
        match = re.search(rf"{kind}:\s*\n((?:\s{{6,}}[A-Za-z0-9_.$/]+\s*\n?)*)", section)
        components[kind] = sorted(match.group(1).split()) if match else []
    return {"enabled": enabled, "stopped": stopped, "permissions": permissions, "components": components}


def parse_appops(value: str, uid_value: str = "") -> dict:
    result = {}
    def parse_lines(lines, scope):
        parsed = {}
        for line in lines:
            stripped = line.strip().removeprefix("Uid mode:").strip()
            if stripped in ("No operations.", "No operations", ""):
                continue
            match = re.fullmatch(r"([A-Z][A-Z0-9_]*): ([a-z_]+)(?:;.*)?", stripped)
            if not match:
                raise RegressionError("Cannot parse app-op configuration; refusing to mutate device")
            operation, mode = match.groups()
            if mode not in ("allow", "ignore", "deny", "default", "foreground", "errored"):
                raise RegressionError(f"Unsupported app-op mode: {mode}")
            parsed[scope + ":" + operation] = mode
        return parsed
    uid_lines = [line for line in uid_value.splitlines() if line.strip() not in ("No operations.", "No operations", "")]
    combined = value.splitlines()
    if uid_lines:
        # Android prints an unindented multi-line UID section with only one
        # leading "Uid mode:". A separate numeric-UID query identifies its
        # exact extent; indentation/timestamps are insufficient to infer it.
        if combined[:len(uid_lines)] != uid_lines:
            raise RegressionError("App-op UID configuration changed during snapshot")
        result.update(parse_lines(uid_lines, "uid"))
        combined = combined[len(uid_lines):]
    elif any(line.startswith("Uid mode:") for line in combined):
        raise RegressionError("App-op UID configuration changed during snapshot")
    result.update(parse_lines(combined, "package"))
    return result


def archive_inventory(path: Path) -> dict:
    """Compare contents, types, modes and mtimes without ever extracting on host."""
    try:
        return _archive_inventory(path)
    except tarfile.TarError as error:
        raise RegressionError(f"Device backup is not a valid tar archive ({path.stat().st_size} bytes); no tests authorized") from error


def _archive_inventory(path: Path) -> dict:
    inventory = {}
    size = path.stat().st_size
    with path.open("rb") as stream:
        if size < 1024 or size % 512:
            raise RegressionError(f"Device backup tar stream is truncated ({size} bytes); no tests authorized")
        stream.seek(-1024, os.SEEK_END)
        if stream.read(1024) != b"\0" * 1024:
            raise RegressionError(f"Device backup tar stream has no complete end marker ({size} bytes); no tests authorized")
    archive = tarfile.open(path, "r:")
    with archive:
        for member in archive:
            name = member.name
            while name.startswith("./"):
                name = name[2:]
            name = name.rstrip("/")
            if name in ("", "."):
                if not member.isdir():
                    raise RegressionError("Unsafe archive root entry")
                continue  # root directory's mtime changes when restoring its children
            if PurePosixPath(name).is_absolute() or ".." in PurePosixPath(name).parts:
                raise RegressionError("Unsafe path in device backup")
            if name == "lib" or name.startswith("lib/"):
                raise RegressionError("Backup unexpectedly contains installer-managed native library link")
            if name in inventory:
                raise RegressionError("Duplicate path in device backup")
            record = {"mode": member.mode, "mtime": member.mtime, "uid": member.uid, "gid": member.gid}
            if member.isfile():
                data = archive.extractfile(member)
                if data is None:
                    raise RegressionError("Unreadable file in device backup")
                digest = hashlib.sha256()
                for chunk in iter(lambda: data.read(1024 * 1024), b""):
                    digest.update(chunk)
                record.update(type="file", size=member.size, sha256=digest.hexdigest())
            elif member.isdir():
                record["type"] = "directory"
            elif member.issym():
                record.update(type="symlink", target=member.linkname)
            elif member.islnk():
                target = member.linkname.removeprefix("./")
                if PurePosixPath(target).is_absolute() or ".." in PurePosixPath(target).parts:
                    raise RegressionError("Unsafe hardlink in device backup")
                record.update(type="hardlink", target=target)
            else:
                raise RegressionError("Sockets/special files cannot be reliably restored; stop before testing")
            inventory[name] = record
    for name in inventory:
        for parent in PurePosixPath(name).parents:
            if str(parent) in inventory and inventory[str(parent)]["type"] != "directory":
                raise RegressionError("Archive path traverses a non-directory")
        if inventory[name]["type"] == "hardlink":
            target = inventory[name]["target"]
            visited = {name}
            while target in inventory and inventory[target]["type"] == "hardlink":
                if target in visited:
                    raise RegressionError("Cyclic hardlink in device backup")
                visited.add(target)
                target = inventory[target]["target"]
            if target not in inventory or inventory[target]["type"] != "file":
                raise RegressionError("Hardlink target is not an archived regular file")
    return inventory


class Device:
    def __init__(self, adb: Path, serial: str):
        self.adb, self.serial = adb, serial

    def adb_command(self, *args, **kwargs):
        return command([self.adb, "-s", self.serial, *args], **kwargs)

    def shell(self, *args) -> str:
        # adb shell reassembles its args; send one properly quoted command.
        return self.adb_command("shell", shlex.join(map(str, args))).decode("utf-8", "replace").strip()

    def check(self) -> dict:
        if self.adb_command("get-state").strip() != b"device":
            raise RegressionError("Device is not online/unlocked/authorized")
        if self.shell("am", "get-current-user") != "0":
            raise RegressionError("Only owner-user (user 0) regression is supported")
        if self.shell("am", "get-started-user-state", "0") != "RUNNING_UNLOCKED":
            raise RegressionError("Unlock the owner user before regression")
        users = re.findall(r"UserInfo\{([0-9]+):", self.shell("pm", "list", "users"))
        if "0" not in users:
            raise RegressionError("Cannot inspect Android users; refusing to mutate device")
        for user in users:
            if user == "0":
                continue
            for package in PACKAGES:
                installed = self.shell("pm", "list", "packages", "--user", user, package).splitlines()
                if "package:" + package in installed:
                    raise RegressionError("IPA/test package is installed in another Android user/profile; owner-only rollback is insufficient")
        help_text = self.shell("cmd", "package", "help")
        if "unstop" not in help_text:
            raise RegressionError("This Android build cannot restore the package stopped state")
        return {"serial": self.serial, "fingerprint": self.shell("getprop", "ro.build.fingerprint"),
                "api": self.shell("getprop", "ro.build.version.sdk"), "model": self.shell("getprop", "ro.product.model"),
                "abi": self.shell("getprop", "ro.product.cpu.abi")}

    def package_paths(self, package: str) -> list[str]:
        # Android's `pm path` exits 1 for an absent package. Do not confuse that
        # expected state with a broken adb connection or swallow real errors.
        installed = self.shell("pm", "list", "packages", "--user", "0", package).splitlines()
        if "package:" + package not in installed:
            return []
        value = self.shell("pm", "path", "--user", "0", package)
        if not value:
            raise RegressionError(f"Installed package has no APK path: {package}")
        lines = value.splitlines()
        if any(not line.startswith("package:/") for line in lines):
            raise RegressionError(f"Cannot inspect package APK paths: {package}")
        return [line[len("package:"):] for line in lines]

    def state(self, package: str) -> dict:
        return parse_package_dump(self.shell("dumpsys", "package", package))

    def appops(self, package: str) -> dict:
        packages = self.shell("pm", "list", "packages", "-U", "--user", "0", package)
        uid = re.search(rf"^package:{re.escape(package)} uid:([0-9]+)$", packages, re.MULTILINE)
        if not uid:
            raise RegressionError("Cannot resolve package UID for app-op backup")
        uid_value = self.shell("cmd", "appops", "get", "--user", "0", uid.group(1))
        return parse_appops(self.shell("cmd", "appops", "get", "--user", "0", package), uid_value)

    def stop(self, package: str):
        self.shell("am", "force-stop", "--user", "0", package)

    def archive(self, package: str, root: str, destination: Path) -> dict:
        # No native-library symlink (managed by APK installer); no following links.
        script = f"cd {shlex.quote(root)} && tar -cf - --exclude=./lib ."
        with destination.open("wb") as stream:
            # exec-out transports argument boundaries itself. Adding shell
            # quotes here makes them literal inside sh -c and runs a nonexistent
            # command named "cd ... && tar ..." (raw service also returns rc 0).
            self.adb_command("exec-out", "run-as", package, "sh", "-c", script, output=stream, timeout=300)
        inventory = archive_inventory(destination)
        self.validate_ownership(package, destination, inventory)
        return inventory

    def validate_ownership(self, package: str, archive: Path, inventory: dict):
        uid = int(self.shell("run-as", package, "id", "-u"))
        primary_gid = int(self.shell("run-as", package, "id", "-g"))
        groups = {int(value) for value in self.shell("run-as", package, "id", "-G").split()}
        with tarfile.open(archive) as data:
            roots = [member for member in data if member.name.rstrip("/") in (".", "./")]
        if len(roots) != 1 or roots[0].uid != uid or roots[0].gid != primary_gid:
            raise RegressionError("App storage root ownership cannot be restored by run-as")
        root = {"mode": roots[0].mode, "gid": roots[0].gid}
        for name, record in inventory.items():
            if record["uid"] != uid:
                raise RegressionError("App backup contains a file owned by another UID; rootless restore unsupported")
            if name in ("cache", "code_cache") and record["type"] == "directory":
                if record["gid"] != uid + 10000 or record["mode"] != 0o2771:
                    raise RegressionError("Installer cache directory ownership/mode is nonstandard; rootless restore unsupported")
                continue
            if record["type"] == "hardlink":
                target = inventory[record["target"]]
                while target["type"] == "hardlink":
                    target = inventory[target["target"]]
                if (record["uid"], record["gid"]) != (target["uid"], target["gid"]):
                    raise RegressionError("Hardlink ownership mismatch in backup")
                continue
            parent_name = str(PurePosixPath(name).parent)
            parent = inventory.get(parent_name, root)
            inherited_gid = parent["gid"] if parent["mode"] & 0o2000 else primary_gid
            if record["gid"] != inherited_gid:
                raise RegressionError("App file GID cannot be reproduced from its parent directory; rootless restore unsupported")
            if record["type"] == "directory" and record["mode"] & 0o2000 and record["gid"] not in groups:
                if not parent["mode"] & 0o2000 or record["mode"] & 0o1000 or record["mode"] & 0o700 != 0o700:
                    raise RegressionError("Directory special permissions cannot be reproduced through group inheritance")
            if record["mode"] & 0o4000 or (record["type"] != "directory" and record["mode"] & 0o2000):
                raise RegressionError("Special executable file permissions are unsupported for rootless restoration")

    def data_roots(self, package: str) -> dict:
        ce = self.shell("run-as", package, "pwd")
        if ce not in (f"/data/user/0/{package}", f"/data/data/{package}"):
            raise RegressionError(f"{package} is not a supported debuggable owner-user installation")
        de = f"/data/user_de/0/{package}"
        exists = self.shell("run-as", package, "sh", "-c", f"if [ -d {shlex.quote(de)} ]; then echo yes; else echo no; fi")
        if exists not in ("yes", "no"):
            raise RegressionError("Cannot inspect device-encrypted app storage")
        return {"ce": ce, "de": de if exists == "yes" else None}

    def settings(self) -> dict:
        # list preserves absent vs literal value "null", unlike `settings get`.
        values = self.shell("settings", "--user", "0", "list", "secure")
        all_values = dict(line.split("=", 1) for line in values.splitlines() if "=" in line)
        return {key: all_values.get(key) for key in IME_SETTINGS}

    def restore_settings(self, values: dict):
        # enabled IMEs before selected IME; no unrelated global settings written.
        for key, value in values.items():
            if value is None:
                self.shell("settings", "--user", "0", "delete", "secure", key)
            else:
                self.shell("settings", "--user", "0", "put", "secure", key, value)

    def quarantine_ime(self):
        """Keep a selected IPA service from writing data while it is restored."""
        values = self.settings()
        selected = values.get("default_input_method") or ""
        if selected.startswith(APP + "/"):
            self.shell("settings", "--user", "0", "put", "secure", "default_input_method", "")
        enabled = values.get("enabled_input_methods")
        if enabled is not None:
            remaining = ":".join(item for item in enabled.split(":") if not item.startswith(APP + "/"))
            self.shell("settings", "--user", "0", "put", "secure", "enabled_input_methods", remaining)

    def install(self, apks: list[Path]):
        verb = "install" if len(apks) == 1 else "install-multiple"
        value = self.adb_command(verb, "-r", "-d", "-t", "--no-streaming", *apks, timeout=300).decode("utf-8", "replace")
        if "Success" not in value:
            raise RegressionError("APK installer did not confirm success; original installation will not be uninstalled")

    def clear_private_data(self, package: str, roots=None):
        roots = roots or self.data_roots(package)
        for root in roots.values():
            if root is None:
                continue
            # Using run-as limits removal to app-owned data. pm clear would also
            # remove external storage and package-manager state outside this scope.
            script = (
                f"cd {shlex.quote(root)} && find . -mindepth 1 -maxdepth 1 ! -name lib ! -name cache ! -name code_cache -exec rm -rf -- {{}} + && "
                "for d in cache code_cache; do if [ -d \"$d\" ]; then find \"$d\" -mindepth 1 -maxdepth 1 -exec rm -rf -- {} +; fi; done"
            )
            self.shell("run-as", package, "sh", "-c", script)

    def seed_roundtrip(self, marker: str):
        """Synthetic data only; used inside an already protected outer run."""
        xml = f'<?xml version="1.0" encoding="utf-8"?><map><string name="sentinel">{marker}</string></map>'
        for package in PACKAGES:
            for root in self.data_roots(package).values():
                if root is None:
                    continue
                script = (
                    f"cd {shlex.quote(root)} && mkdir -p files/regression-roundtrip shared_prefs cache no_backup && "
                    f"printf %s {shlex.quote(marker)} > files/regression-roundtrip/sentinel && "
                    f"printf %s {shlex.quote(xml)} > shared_prefs/regression_roundtrip.xml && "
                    f"printf %s {shlex.quote(marker)} > cache/regression-roundtrip && "
                    f"printf %s {shlex.quote(marker)} > no_backup/regression-roundtrip"
                )
                self.shell("run-as", package, "sh", "-c", script)

    def restore_data(self, package: str, root: str, archive: Path):
        inventory = archive_inventory(archive)
        self.validate_ownership(package, archive, inventory)
        directories = sorted((name for name in inventory if inventory[name]["type"] == "directory"),
                             key=lambda name: (len(PurePosixPath(name).parts), name))
        # run-as is not a member of the installer cache GID. Preserve cache root
        # inodes, and create descendants through parent SGID inheritance instead
        # of chown/chmod that silently clears that bit on Android.
        for name in directories:
            if name in ("cache", "code_cache"):
                continue
            record = inventory[name]
            mask = 0o777 ^ (record["mode"] & 0o777)
            script = f"cd {shlex.quote(root)} && umask {mask:o} && mkdir -- {shlex.quote('./' + name)}"
            self.shell("run-as", package, "sh", "-c", script)
        # Directory headers would make tar chown/clear inherited SGID again.
        # Extract only files and links, with owners inherited on creation.
        with tempfile.TemporaryDirectory(prefix="ipa-board-restore-") as directory:
            filtered = Path(directory) / "files.tar"
            with tarfile.open(archive) as original, tarfile.open(filtered, "w") as output:
                for member in original:
                    if member.isdir():
                        continue
                    output.addfile(member, original.extractfile(member) if member.isfile() else None)
            self.extract_files(package, root, filtered)
        groups = {int(value) for value in self.shell("run-as", package, "id", "-G").split()}
        for name in reversed(directories):
            record = inventory[name]
            # chmod(+SGID) requires membership in that GID, even for an owner.
            # A nonmember SGID dir already got its exact bits from mkdir+umask.
            if not record["mode"] & 0o2000 or record["gid"] in groups:
                self.shell("run-as", package, "sh", "-c",
                           f"cd {shlex.quote(root)} && chmod {record['mode']:o} -- {shlex.quote('./' + name)}")
            date = datetime.fromtimestamp(record["mtime"], timezone.utc).strftime("%Y-%m-%dT%H:%M:%S+0000")
            self.shell("run-as", package, "sh", "-c",
                       f"cd {shlex.quote(root)} && touch -m -d {shlex.quote(date)} -- {shlex.quote('./' + name)}")

    def extract_files(self, package: str, root: str, archive: Path):
        script = f"cd {shlex.quote(root)} && tar -xof -"
        with archive.open("rb") as stream:
            process = subprocess.Popen(
                [str(self.adb), "-s", self.serial, "exec-in", "run-as", package, "sh", "-c", script],
                stdin=stream, stdout=subprocess.PIPE, stderr=subprocess.PIPE, start_new_session=True,
            )
            try:
                stdout, stderr = process.communicate(timeout=300)
            except BaseException:
                with contextlib.suppress(ProcessLookupError):
                    os.killpg(process.pid, signal.SIGKILL)
                process.communicate()
                raise
            if process.returncode:
                raise RegressionError("Private-data restoration failed: " + stderr.decode("utf-8", "replace")[-1000:])

    def restore_permissions(self, package: str, original: dict):
        current = self.state(package)["permissions"]
        if set(current) != set(original):
            raise RegressionError(f"Permission declarations changed after APK restoration: {package}")
        for permission, state in original.items():
            if current[permission]["granted"] != state["granted"]:
                self.shell("pm", "grant" if state["granted"] else "revoke", "--user", "0", package, permission)
            self.shell("pm", "clear-permission-flags", "--user", "0", package, permission, *PERMISSION_FLAGS.values())
            flags = [PERMISSION_FLAGS[flag] for flag in state["flags"] if flag in PERMISSION_FLAGS]
            if flags:
                self.shell("pm", "set-permission-flags", "--user", "0", package, permission, *flags)

    def restore_appops(self, package: str, original: dict):
        self.shell("cmd", "appops", "reset", "--user", "0", package)
        for key, mode in original.items():
            scope, operation = key.split(":", 1)
            self.shell("cmd", "appops", "set", "--user", "0", *( ["--uid"] if scope == "uid" else []), package, operation, mode)

    def restore_package_state(self, package: str, original: dict):
        self.restore_components(package, original["components"])
        self.shell("pm", ENABLED_COMMANDS[original["enabled"]], "--user", "0", package)
        if original["stopped"]:
            self.stop(package)
        else:
            self.shell("cmd", "package", "unstop", "--user", "0", package)

    def restore_components(self, package: str, original: dict):
        current = self.state(package)["components"]
        all_components = set(original["enabledComponents"] + original["disabledComponents"] +
                             current["enabledComponents"] + current["disabledComponents"])
        for component in sorted(all_components):
            qualified = component if "/" in component else package + "/" + component
            self.shell("pm", "default-state", "--user", "0", qualified)
        for state, verb in (("enabledComponents", "enable"), ("disabledComponents", "disable")):
            for component in original[state]:
                qualified = component if "/" in component else package + "/" + component
                self.shell("pm", verb, "--user", "0", qualified)


class TransientEnvironment:
    """Temporary execution environment, kept ONLY in RAM, outside the snapshot.

    A separate restore command protects its current environment. A SIGKILL loses
    historical IME/permission/package-manager state by design: the user's backup
    scope includes IPA software/private user data and excludes system settings.
    """
    def __init__(self, device: Device, states: dict, appops: dict, settings: dict):
        self.device, self.states, self.appops, self.settings = device, states, appops, settings
        self.restored = False

    @classmethod
    def capture(cls, device: Device):
        states, appops = {}, {}
        for package in PACKAGES:
            if device.package_paths(package):
                states[package] = device.state(package)
                appops[package] = device.appops(package)
        return cls(device, states, appops, device.settings())

    @classmethod
    def from_legacy(cls, device: Device, manifest: dict):
        # Compatibility for previously interrupted backups only. New manifests
        # never write these fields; remove this path after old backups disappear.
        return cls(device,
                   {package: record["state"] for package, record in manifest["packages"].items() if record["present"]},
                   {package: record["appops"] for package, record in manifest["packages"].items() if record["present"]},
                   manifest["settings"])

    def restore(self):
        for package, state in self.states.items():
            if not self.device.package_paths(package):
                continue  # the restored software snapshot may require absence
            self.device.restore_permissions(package, state["permissions"])
            self.device.restore_appops(package, self.appops[package])
            self.device.restore_package_state(package, state)
            if self.device.state(package) != state or self.device.appops(package) != self.appops[package]:
                raise RegressionError(f"Temporary execution environment was not restored: {package}")
        self.device.restore_settings(self.settings)
        if self.device.settings() != self.settings:
            raise RegressionError("Temporary IME execution environment was not restored")
        self.restored = True


class Transaction:
    def __init__(self, device: Device, location: Path, manifest: dict, environment=None):
        self.device, self.location, self.manifest = device, location, manifest
        self.environment = environment

    def save(self):
        write_json(self.location / "manifest.json", self.manifest)

    @classmethod
    def snapshot(cls, device: Device, parent: Path, on_created=None):
        identity = device.check()
        environment = TransientEnvironment.capture(device)
        # Preflight both packages BEFORE stopping either one or installing anything.
        packages = {}
        for package in PACKAGES:
            paths = device.package_paths(package)
            packages[package] = {"present": bool(paths)}
            if paths:
                packages[package].update(paths=paths, roots=device.data_roots(package))
        location = Path(tempfile.mkdtemp(prefix="snapshot-", dir=private_directory(parent)))
        os.chmod(location, 0o700)
        manifest = {"schema": 1, "id": str(uuid.uuid4()), "created": time.time(), "device": identity,
                    "phase": "snapshotting", "packages": packages}
        transaction = cls(device, location, manifest, environment)
        transaction.save()
        try:
            if on_created:
                on_created(transaction)
            # IME isolation is an execution detail retained in RAM only.
            # Otherwise Android may restart its service during tar.
            device.quarantine_ime()
            for package, record in packages.items():
                if not record["present"]:
                    continue
                destination = private_directory(location / package)
                record["apks"] = []
                for index, remote in enumerate(record["paths"]):
                    local = destination / f"apk-{index}.apk"
                    device.adb_command("pull", remote, local, timeout=300)
                    digest = sha256(local)
                    remote_digest = device.shell("sha256sum", remote).split()[0]
                    if remote_digest != digest:
                        raise RegressionError("APK backup checksum mismatch")
                    record["apks"].append({"file": str(local.relative_to(location)), "sha256": digest})
                # Process quiescing precedes consistent CE/DE snapshots; this is
                # not installation or a clear/reset of the user's app data.
                device.stop(package)
                record["data"] = {}
                transaction.save()
                for kind, root in record["roots"].items():
                    if root is None:
                        continue
                    archive = destination / f"{kind}.tar"
                    inventory = device.archive(package, root, archive)
                    # Re-read before allowing mutation: detect an incomplete tar.
                    if inventory != archive_inventory(archive):
                        raise RegressionError("Private-data backup validation failed")
                    record["data"][kind] = {"file": str(archive.relative_to(location)), "sha256": sha256(archive), "inventory": inventory}
                    transaction.save()
            manifest["phase"] = "snapshot_ready"
            transaction.save()
            transaction.validate_backup()
            return transaction
        except BaseException:
            # Snapshot failure never authorizes test installation/data reset.
            # Preserve the journal if even restoring preflight stopped flags fails.
            failures = []
            with signal_handler(restoring=True):
                try:
                    environment.restore()
                except BaseException as error:
                    failures.append(str(error))
            if failures:
                manifest["phase"] = "snapshot_failed"
                transaction.save()
                recovery = shlex.join([sys.executable, str(ROOT / "tools/regression.py"), "restore", str(location), "--serial", device.serial])
                print(f"Snapshot failed; state recovery also failed. Backup retained. Restore with:\n{recovery}", file=sys.stderr)
            else:
                shutil.rmtree(location)
            raise

    @classmethod
    def load(cls, device: Device, location: Path):
        private_directory(location)
        manifest = json.loads((location / "manifest.json").read_text(encoding="utf-8"))
        if manifest.get("schema") != 1 or set(manifest.get("packages", {})) != set(PACKAGES):
            raise RegressionError("Unsupported backup manifest")
        expected_device = manifest.get("device", {})
        current_device = device.check()
        if not {"serial", "fingerprint"} <= expected_device.keys() or any(
            current_device.get(key) != value for key, value in expected_device.items()
        ):
            raise RegressionError("Restore requires the original device and Android build")
        environment = TransientEnvironment.from_legacy(device, manifest) if "settings" in manifest else TransientEnvironment.capture(device)
        transaction = cls(device, location, manifest, environment)
        if manifest.get("phase") not in ("snapshotting", "snapshot_failed"):
            transaction.validate_backup()
        return transaction

    def file(self, relative: str) -> Path:
        path = self.location / relative
        if path.is_symlink() or self.location.resolve() not in path.resolve().parents:
            raise RegressionError("Unsafe backup file path")
        return path

    def validate_backup(self):
        if self.manifest["phase"] not in ("snapshot_ready", "testing", "restoring", "restore_failed", "verified"):
            raise RegressionError("Backup is incomplete; no device test mutation is authorized")
        upgraded = False
        for record in self.manifest["packages"].values():
            if not record["present"]:
                continue
            if not record.get("apks") or "ce" not in record.get("data", {}):
                raise RegressionError("Backup is incomplete")
            for item in record["apks"] + list(record["data"].values()):
                if sha256(self.file(item["file"])) != item["sha256"]:
                    raise RegressionError("Backup checksum mismatch; no destructive recovery attempted")
            for item in record["data"].values():
                actual = archive_inventory(self.file(item["file"]))
                expected = item["inventory"]
                if expected and all("uid" not in entry and "gid" not in entry for entry in expected.values()):
                    # Older journal inventory omitted owners. Upgrade only after
                    # the original tar checksum above is verified; content/hash
                    # and every existing attribute must still match exactly.
                    without_owners = {name: {key: value for key, value in entry.items() if key not in ("uid", "gid")}
                                      for name, entry in actual.items()}
                    if without_owners != expected:
                        raise RegressionError("Backup content inventory mismatch")
                    item["inventory"] = actual
                    upgraded = True
                elif actual != expected:
                    raise RegressionError("Backup content inventory mismatch")
        if upgraded:
            self.save()

    def begin(self):
        self.validate_backup()
        self.manifest["phase"] = "testing"
        self.save()  # durable journal BEFORE the first install/data mutation

    def restore(self):
        dependency = self.manifest.get("dependent_backup")
        if dependency:
            # A round-trip verification interrupted inside its own transaction
            # must be recovered before removing the only outer recovery journal.
            child = self.location / dependency
            if self.location.resolve() not in child.resolve().parents or child.is_symlink():
                raise RegressionError("Unsafe dependent backup path")
            if child.exists():
                Transaction.load(self.device, child).restore()
            self.manifest.pop("dependent_backup")
            self.save()
        if self.manifest["phase"] in ("snapshotting", "snapshot_failed"):
            # A crash before the ready journal cannot have installed or reset
            # anything. Recover quiescing state only; incomplete tar must NEVER
            # be used to overwrite the original live installation.
            self.environment.restore()
            shutil.rmtree(self.location)
            return
        self.validate_backup()  # fail closed before overwriting anything
        self.manifest["phase"] = "restoring"
        self.save()
        errors = []
        # Quarantine before APK install and before overwriting any private file.
        # Temporary current-run IME configuration is resumed after data checks.
        try:
            self.device.quarantine_ime()
        except BaseException as error:
            self.manifest["phase"] = "restore_failed"
            self.save()
            raise RegressionError(f"Cannot quiesce IPA IME for safe restoration: {error}") from error
        for package, record in self.manifest["packages"].items():
            try:
                if not record["present"]:
                    if self.device.package_paths(package):
                        value = self.device.adb_command("uninstall", package).decode("utf-8", "replace")
                        if "Success" not in value:
                            raise RegressionError(f"Cannot remove test-created package: {package}")
                    continue
                self.device.stop(package)
                self.device.install([self.file(item["file"]) for item in record["apks"]])
                self.device.stop(package)
                roots = self.device.data_roots(package)
                self.device.clear_private_data(package, roots)
                for kind, item in record["data"].items():
                    if roots.get(kind) is None:
                        raise RegressionError(f"Original {kind} storage missing after APK restore")
                    self.device.restore_data(package, roots[kind], self.file(item["file"]))
            except BaseException as error:
                errors.append(f"{package}: {error}")
        # Verify durable data while still quiescent, before re-enabling a selected IME.
        if not errors:
            try:
                self.verify()
                self.environment.restore()
            except BaseException as error:
                errors.append(str(error))
        if errors:
            self.manifest["phase"] = "restore_failed"
            self.save()
            raise RegressionError("Restoration not verified: " + "; ".join(errors))
        self.manifest["phase"] = "verified"
        self.save()
        shutil.rmtree(self.location)  # ONLY after all verification has succeeded

    def verify(self):
        for package, record in self.manifest["packages"].items():
            paths = self.device.package_paths(package)
            if not record["present"]:
                if paths:
                    raise RegressionError(f"Test-created package remains installed: {package}")
                continue
            hashes = sorted(self.device.shell("sha256sum", path).split()[0] for path in paths)
            if hashes != sorted(item["sha256"] for item in record["apks"]):
                raise RegressionError(f"Installed APK checksum mismatch: {package}")
            roots = self.device.data_roots(package)
            for kind, root in roots.items():
                if root is None:
                    continue
                verification = self.location / "verification.tar"
                try:
                    actual = self.device.archive(package, root, verification)
                    expected = record["data"].get(kind, {}).get("inventory", {})
                    if actual != expected:
                        raise RegressionError(f"Private {kind} data does not match original: {package}")
                finally:
                    verification.unlink(missing_ok=True)


def load_suites(path: Path) -> dict[str, list[str]]:
    document = json.loads(path.read_text(encoding="utf-8"))
    suites = document.get("suites", {})
    if not suites:
        raise RegressionError("Suite manifest contains no suites")
    result = {}
    for name, definition in suites.items():
        classes = definition.get("classes") if isinstance(definition, dict) else definition
        if not isinstance(classes, list) or not classes or any(not isinstance(item, str) or not CLASS_RE.fullmatch(item) for item in classes):
            raise RegressionError(f"Invalid instrumentation classes in suite {name}")
        if len(classes) != len(set(classes)):
            raise RegressionError(f"Duplicate instrumentation classes in suite {name}")
        result[name] = classes
    return result


def instrumentation_passed(output: str) -> bool:
    # am instrument often exits 0 even after a failed test or crashed process.
    return (bool(re.search(r"^OK \([1-9][0-9]* tests?\)\s*$", output, re.MULTILINE))
            and not re.search(r"^INSTRUMENTATION_STATUS_CODE:\s*-[1-4]\s*$", output, re.MULTILINE)
            and not any(
        marker in output for marker in ("FAILURES!!!", "INSTRUMENTATION_FAILED", "Process crashed", "INSTRUMENTATION_ABORTED", "shortMsg=")
    ))


def instrumentation_metrics(output: str) -> dict:
    """Record completed work even when JUnit/am reports failure or skips."""
    statuses = [int(value) for value in re.findall(r"^INSTRUMENTATION_STATUS_CODE:\s*(-?[0-9]+)\s*$", output, re.MULTILINE)]
    passed = statuses.count(0)
    failed = statuses.count(-1) + statuses.count(-2)
    skipped = statuses.count(-3) + statuses.count(-4)
    summary = re.search(r"Tests run:\s*([0-9]+),\s*Failures:\s*([0-9]+)", output)
    ok = re.search(r"^OK \(([0-9]+) tests?\)", output, re.MULTILINE)
    reported = int(summary.group(1)) if summary else int(ok.group(1)) if ok else None
    if summary:
        failed = max(failed, int(summary.group(2)))
    if not statuses and reported is not None:
        passed = max(0, reported - failed)
    completed = passed + failed
    return {"tests": reported if reported is not None else completed + skipped,
            "executed": completed, "passed": passed, "failed": failed, "skipped": skipped}


def instrumentation_completed(output: str) -> bool:
    """Only a normally finished JUnit run is safe for optional continuation."""
    if any(marker in output for marker in ("INSTRUMENTATION_FAILED", "Process crashed", "INSTRUMENTATION_ABORTED", "shortMsg=")):
        return False
    failures = re.search(r"Tests run:\s*([1-9][0-9]*),\s*Failures:\s*[0-9]+", output)
    if "FAILURES!!!" in output and failures:
        return True
    return bool(re.search(r"^OK \([0-9]+ tests?\)\s*$", output, re.MULTILINE)) and bool(
        re.search(r"^INSTRUMENTATION_STATUS_CODE:\s*-[34]\s*$", output, re.MULTILINE)
    )


def verify_roundtrip(outer: Transaction):
    """Prove the installed APK+CE/DE path using synthetic data, then run tests."""
    device = outer.device
    device.seed_roundtrip(outer.manifest["id"])
    parent = private_directory(outer.location / "roundtrip")
    def journal(child):
        outer.manifest["dependent_backup"] = str(child.location.relative_to(outer.location))
        outer.save()
    child = Transaction.snapshot(device, parent, on_created=journal)
    child.begin()
    for package in PACKAGES:
        device.stop(package)
        device.clear_private_data(package)
    child.restore()
    outer.manifest.pop("dependent_backup", None)
    outer.manifest["roundtrip_verified"] = True
    outer.save()
    print("Installed APK/private CE+DE data round-trip verified; synthetic backup deleted.", flush=True)


def sdk_path() -> Path:
    for key in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        if os.environ.get(key):
            return Path(os.environ[key])
    local = ROOT / "local.properties"
    if local.exists():
        match = re.search(r"^sdk\.dir=(.+)$", local.read_text(), re.MULTILINE)
        if match:
            return Path(match.group(1).replace("\\:", ":").replace("\\ ", " "))
    raise RegressionError("Set ANDROID_HOME or sdk.dir in local.properties")


def validate_apk(path: Path, expected_package: str, sdk: Path):
    tools = sorted((sdk / "build-tools").glob("*/aapt2"))
    if not path.is_file() or not tools:
        raise RegressionError("Built debug APK/aapt2 missing; build before device mutation")
    value = command([tools[-1], "dump", "badging", path]).decode("utf-8", "replace")
    package = re.search(r"^package: name='([^']+)'", value, re.MULTILINE)
    if not package or package.group(1) != expected_package or "application-debuggable" not in value:
        raise RegressionError(f"Expected a debuggable {expected_package} APK: {path}")
    if expected_package == TEST_APP:
        tree = command([tools[-1], "dump", "xmltree", path, "--file", "AndroidManifest.xml"]).decode("utf-8", "replace")
        validate_instrumentation_manifest(tree)


def validate_instrumentation_manifest(tree: str):
    lines = tree.splitlines()
    blocks = [(index, len(line) - len(line.lstrip())) for index, line in enumerate(lines)
              if re.match(r"\s*E: instrumentation(?:\s|$)", line)]
    if len(blocks) != 1:
        raise RegressionError("Test APK must declare exactly one regression instrumentation")
    index, indent = blocks[0]
    attributes = {}
    for line in lines[index + 1:]:
        depth = len(line) - len(line.lstrip())
        if line.strip() and depth <= indent:
            break
        if depth != indent + 2:
            continue
        attribute = re.match(r'\s*A: (?:android:|http://schemas\.android\.com/apk/res/android:)(name|targetPackage)(?:\([^)]*\))?="([^"]+)"', line)
        if attribute:
            if attribute.group(1) in attributes:
                raise RegressionError("Duplicate instrumentation attribute")
            attributes[attribute.group(1)] = attribute.group(2)
    if attributes != {"name": APP + ".RegressionTestRunner", "targetPackage": APP}:
        raise RegressionError("Test APK must use the snapshot-gated RegressionTestRunner for IPA Board; rebuild it")


@contextlib.contextmanager
def signal_handler(restoring=False):
    def handler(signum, frame):
        if not restoring:
            raise InterruptedError(f"Received signal {signum}; restoring device state")
        print("Restoration in progress; interruption deferred. Do not disconnect the device.", file=sys.stderr)
    previous = {sig: signal.signal(sig, handler) for sig in (signal.SIGINT, signal.SIGTERM, signal.SIGHUP)}
    try:
        yield
    finally:
        for sig, old in previous.items():
            signal.signal(sig, old)


def run_suite(device: Device, classes: list[str], suite: str, apks: list[Path], parent: Path, timeout: int, roundtrip=False, keep_going=False) -> Path:
    transaction = None
    failure = None
    report = None
    started = time.monotonic()
    results = []
    completed_failures = []
    build = {"variant": "debug", "apks": [
        {"package": package, "sha256": sha256(apk)} for package, apk in zip(PACKAGES, apks)
    ]}
    metadata = {"revision": None, "dirty": None}
    try:
        metadata = {"revision": command(["git", "rev-parse", "HEAD"], cwd=ROOT).decode().strip(),
                    "dirty": bool(command(["git", "status", "--porcelain"], cwd=ROOT).strip())}
    except (OSError, RegressionError):
        pass
    with signal_handler():
        try:
            transaction = Transaction.snapshot(device, parent)
            print(f"Verified backup: {transaction.location}", flush=True)
            report = ROOT / "app/build/reports/regression" / transaction.manifest["id"]
            private_directory(report)
            transaction.begin()
            for apk in apks:
                device.install([apk])
            if roundtrip:
                verify_roundtrip(transaction)
            for package in PACKAGES:
                device.stop(package)
                device.clear_private_data(package)
                device.shell("pm", "enable", "--user", "0", package)
            for class_name in classes:
                print(f"Regression {suite}: {class_name}", flush=True)
                class_started = time.monotonic()
                result = {"class": class_name, "status": "environment_error", "tests": 0,
                          "executed": 0, "passed": 0, "failed": 0, "skipped": 0}
                results.append(result)
                try:
                    output = device.adb_command(
                        "shell", shlex.join(["am", "instrument", "--user", "0", "-w", "-r", "-e", "class", class_name,
                                             "-e", "regressionSnapshot", transaction.manifest["id"], RUNNER]), timeout=timeout,
                    ).decode("utf-8", "replace")
                    (report / (class_name.replace("#", "-") + ".txt")).write_text(output, encoding="utf-8")
                    result.update(instrumentation_metrics(output))
                    result["status"] = "skipped" if result["skipped"] and not result["failed"] else "failed"
                    if not instrumentation_passed(output):
                        if keep_going and instrumentation_completed(output):
                            result["error"] = "RegressionError"
                            completed_failures.append(class_name)
                            print(f"Completed with failure/skip: {class_name}; continuing to collect results.", flush=True)
                            continue
                        raise RegressionError(f"Instrumentation failed or ran zero tests: {class_name}; see {report}")
                    result["status"] = "passed"
                except BaseException as error:
                    result["error"] = type(error).__name__
                    if isinstance(error, subprocess.TimeoutExpired):
                        result["status"] = "timeout"
                    elif isinstance(error, InterruptedError):
                        result["status"] = "interrupted"
                    raise
                finally:
                    result["duration_seconds"] = round(time.monotonic() - class_started, 3)
            if completed_failures:
                raise RegressionError(f"{len(completed_failures)} completed regression class(es) failed or skipped; see {report}")
        except BaseException as error:
            failure = error
        finally:
            if transaction is not None:
                with signal_handler(restoring=True):
                    try:
                        dependency = transaction.manifest.get("dependent_backup")
                        if dependency and (transaction.location / dependency).exists():
                            raise RegressionError("Installed-state round-trip did not finish; both backups retained for recovery")
                        transaction.restore()
                        print("Original APKs and private user data verified; backup deleted.", flush=True)
                    except BaseException as restore_error:
                        restore_command = shlex.join([sys.executable, str(ROOT / "tools/regression.py"), "restore", str(transaction.location), "--serial", device.serial])
                        print(f"Backup retained. Restore with:\n{restore_command}", file=sys.stderr)
                        failure = RegressionError(f"{failure or 'Tests completed'}; {restore_error}")
                    finally:
                        # User excluded system settings from snapshots. Undo
                        # only this live process's temporary environment changes
                        # from RAM; recovery commands have their own RAM guard.
                        if not transaction.environment.restored:
                            try:
                                transaction.environment.restore()
                            except BaseException as environment_error:
                                failure = RegressionError(f"{failure or 'Tests completed'}; temporary environment cleanup failed: {environment_error}")
            if report is not None:
                write_json(report / "summary.json", {"suite": suite, "classes": classes, "keep_going": keep_going, "success": failure is None,
                    "restored": transaction is not None and not transaction.location.exists(), "error": type(failure).__name__ if failure else None,
                    "repository": metadata, "build": build, "device": transaction.manifest["device"], "results": results,
                    "roundtrip_verified": transaction.manifest.get("roundtrip_verified", False),
                    "duration_seconds": round(time.monotonic() - started, 3)})
    if failure is not None:
        raise failure
    return report


def main(argv=None) -> int:
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, default=ROOT / "tools/regression-suites.json")
    subparsers = parser.add_subparsers(dest="action", required=True)
    run = subparsers.add_parser("run")
    run.add_argument("--suite", default="smoke")
    run.add_argument("--serial", required=True)
    run.add_argument("--no-build", action="store_true", help="Use already-built, validated debug APKs")
    run.add_argument("--verify-roundtrip", action="store_true", help="Prove installed APK/private-data restoration with synthetic state inside the protected run")
    run.add_argument("--keep-going", action="store_true", help="Collect further classes after completed JUnit failures/skips; crashes/timeouts still stop and restore")
    run.add_argument("--timeout", type=int, default=1200, help="Maximum seconds per class, then restore")
    restore = subparsers.add_parser("restore")
    restore.add_argument("backup", type=Path)
    restore.add_argument("--serial", required=True)
    subparsers.add_parser("list")
    subparsers.add_parser("validate")
    subparsers.add_parser("pending")
    args = parser.parse_args(argv)
    try:
        if args.action == "pending":
            pending = []
            if backup_root().exists():
                for path in sorted(backup_root().glob("snapshot-*/manifest.json")):
                    value = json.loads(path.read_text())
                    pending.append({"path": str(path.parent), "phase": value.get("phase"), "serial": value.get("device", {}).get("serial")})
            print(json.dumps(pending, indent=2))
            return 0
        if args.action in ("list", "validate", "run"):
            suites = load_suites(args.manifest)
            validate_suites(suites, ROOT / "app/src/androidTest")
            if args.action != "run":
                print(json.dumps(suites, indent=2))
                return 0
            if args.suite not in suites or args.timeout <= 0:
                raise RegressionError("Unknown suite or invalid timeout")
            if not args.no_build:
                command([ROOT / "gradlew", ":app:assembleDebug", ":app:assembleDebugAndroidTest", "--offline", "--console=plain"], cwd=ROOT, timeout=1800, output=sys.stdout.buffer)
        sdk = sdk_path()
        device = Device(sdk / "platform-tools/adb", args.serial)
        if args.action == "restore":
            with backup_lock(args.backup.parent), signal_handler(restoring=True):
                Transaction.load(device, args.backup).restore()
            print("Original software state verified; backup deleted.")
            return 0
        apks = [ROOT / "app/build/outputs/apk/debug/app-debug.apk", ROOT / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"]
        for apk, package in zip(apks, PACKAGES):
            validate_apk(apk, package, sdk)
        with backup_lock(backup_root()):
            if list(backup_root().glob("snapshot-*/manifest.json")):
                raise RegressionError("An unfinished backup exists; run `pending` and restore it before new regression")
            report = run_suite(device, suites[args.suite], args.suite, apks, backup_root(), args.timeout,
                               roundtrip=args.verify_roundtrip, keep_going=args.keep_going)
        print(f"Regression passed: {report}")
        return 0
    except (RegressionError, OSError, ValueError, subprocess.TimeoutExpired, InterruptedError) as error:
        print(str(error), file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
