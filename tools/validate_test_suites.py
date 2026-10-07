#!/usr/bin/env python3
"""Check that source instrumentation tests cannot silently fall out of regression suites."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import sys
from typing import Mapping

ROOT = Path(__file__).resolve().parents[1]
PRIMARY_SUITES = ("platform", "engines", "ui", "e2e", "semantic")
CLASS_NAME = re.compile(r"[A-Za-z_]\w*(?:\.[A-Za-z_]\w*)+(?:\$[A-Za-z_]\w*)*\Z")
CLASS_DECLARATION = re.compile(r"\bclass\s+([A-Za-z_]\w*)")
ANNOTATION = re.compile(r"@([A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*)")


class SuiteValidationError(ValueError):
    pass


def _code_only(source: str) -> str:
    """Mask literals/comments, preserving offsets and nested Kotlin block comments."""
    result = list(source)
    index = 0
    while index < len(source):
        start = index
        if source.startswith("//", index):
            end = source.find("\n", index)
            index = len(source) if end < 0 else end
        elif source.startswith("/*", index):
            depth = 1
            index += 2
            while index < len(source) and depth:
                if source.startswith("/*", index):
                    depth += 1
                    index += 2
                elif source.startswith("*/", index):
                    depth -= 1
                    index += 2
                else:
                    index += 1
        elif source.startswith('"""', index):
            end = source.find('"""', index + 3)
            index = len(source) if end < 0 else end + 3
        elif source[index] in ('"', "'"):
            quote = source[index]
            index += 1
            while index < len(source):
                if source[index] == "\\":
                    index += 2
                elif source[index] == quote:
                    index += 1
                    break
                else:
                    index += 1
        else:
            index += 1
            continue
        for offset in range(start, min(index, len(source))):
            if source[offset] != "\n":
                result[offset] = " "
    return "".join(result)


def discover_test_classes(source_root: Path) -> dict[str, Path]:
    """Discover named Kotlin/Java classes owning JUnit @Test methods (including nested classes)."""
    if not source_root.is_dir():
        raise SuiteValidationError(f"Instrumentation source directory is missing: {source_root}")
    discovered: dict[str, Path] = {}
    files = sorted(path for path in source_root.rglob("*") if path.suffix in (".kt", ".java"))
    for path in files:
        code = _code_only(path.read_text(encoding="utf-8"))
        annotation_names = {"Test", "org.junit.Test", "org.junit.jupiter.api.Test"}
        annotation_names.update(re.findall(r"\bimport\s+org\.junit(?:\.jupiter\.api)?\.Test\s+as\s+(\w+)", code))
        annotations = [match for match in ANNOTATION.finditer(code) if match.group(1) in annotation_names]
        if not annotations:
            continue
        package = re.search(r"\bpackage\s+([A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*)", code)
        if package is None:
            raise SuiteValidationError(f"A test source needs a named package: {path}")
        stack = []
        closing = {}
        for offset, char in enumerate(code):
            if char == "{":
                stack.append(offset)
            elif char == "}" and stack:
                closing[stack.pop()] = offset
        declarations = list(CLASS_DECLARATION.finditer(code))
        classes = []
        for position, declaration in enumerate(declarations):
            limit = declarations[position + 1].start() if position + 1 < len(declarations) else len(code)
            parens = brackets = 0
            for offset in range(declaration.end(), limit):
                char = code[offset]
                if char == "(":
                    parens += 1
                elif char == ")":
                    parens -= 1
                elif char == "[":
                    brackets += 1
                elif char == "]":
                    brackets -= 1
                elif char == "{" and parens == brackets == 0:
                    if offset not in closing:
                        raise SuiteValidationError(f"Unclosed test class in {path}")
                    classes.append((offset, closing[offset], declaration.group(1)))
                    break
        for annotation in annotations:
            owners = sorted(item for item in classes if item[0] < annotation.start() < item[1])
            if not owners:
                raise SuiteValidationError(f"JUnit annotation outside a named class in {path}")
            name = package.group(1) + "." + "$".join(owner[2] for owner in owners)
            previous = discovered.get(name)
            if previous is not None and previous != path:
                raise SuiteValidationError(f"Test class declared in multiple source files: {name}")
            discovered[name] = path
    if not discovered:
        raise SuiteValidationError(f"No JUnit test classes found in {source_root}")
    return discovered


def validate_suites(suites: Mapping[str, list[str]], source_root: Path) -> dict[str, str]:
    """Return each source test's primary owner, or fail with every detected registration error."""
    if not isinstance(suites, Mapping):
        raise SuiteValidationError("Suite manifest must contain a suite mapping")
    required = set(PRIMARY_SUITES) | {"full", "smoke"}
    missing_suites = required - set(suites)
    if missing_suites:
        raise SuiteValidationError("Required suites missing: " + ", ".join(sorted(missing_suites)))
    errors = []
    for name, classes in suites.items():
        if not isinstance(classes, list) or any(not isinstance(item, str) or not CLASS_NAME.fullmatch(item) for item in classes):
            raise SuiteValidationError(f"Suite {name} must register whole, fully qualified test class names")
        if len(classes) != len(set(classes)):
            errors.append(f"Duplicate classes inside suite {name}")
    discovered = discover_test_classes(source_root)
    owners: dict[str, str] = {}
    for suite in PRIMARY_SUITES:
        for name in suites[suite]:
            if name in owners:
                errors.append(f"Test class has multiple primary suites: {name} ({owners[name]}, {suite})")
            else:
                owners[name] = suite
    unregistered = set(discovered) - set(owners)
    if unregistered:
        errors.append("Source tests missing a primary suite: " + ", ".join(sorted(unregistered)))
    full = set(suites["full"])
    expected_full = set(owners)
    if full != expected_full:
        errors.append("full must exactly equal the primary-suite union; missing: " +
                      ", ".join(sorted(expected_full - full)) + "; extra: " + ", ".join(sorted(full - expected_full)))
    if not set(suites["smoke"]) <= full:
        errors.append("smoke must be a subset of full: " + ", ".join(sorted(set(suites["smoke"]) - full)))
    for suite, classes in suites.items():
        nonexistent = set(classes) - set(discovered)
        if nonexistent:
            errors.append(f"Suite {suite} names missing or non-test source classes: " + ", ".join(sorted(nonexistent)))
    if errors:
        raise SuiteValidationError("\n".join(errors))
    return owners


def load_manifest(path: Path) -> dict[str, list[str]]:
    document = json.loads(path.read_text(encoding="utf-8"))
    definitions = document.get("suites")
    if not isinstance(definitions, dict):
        raise SuiteValidationError("Suite manifest must contain a suite mapping")
    return {name: definition.get("classes") if isinstance(definition, dict) else definition
            for name, definition in definitions.items()}


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, default=ROOT / "tools/regression-suites.json")
    parser.add_argument("--source-root", type=Path, default=ROOT / "app/src/androidTest")
    args = parser.parse_args(argv)
    try:
        owners = validate_suites(load_manifest(args.manifest), args.source_root)
    except (SuiteValidationError, OSError, ValueError, TypeError, AttributeError) as error:
        print(f"Test suite registration failed: {error}", file=sys.stderr)
        return 1
    print(f"Registered {len(owners)} instrumentation test classes in {len(PRIMARY_SUITES)} primary suites.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
