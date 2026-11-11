# Keyboard page group folders

Keyboard Management → Group Settings provides **Import group** and **Export group**. The existing Page Settings import and export buttons continue to operate on individual pages.

For import, select the folder containing the group's JSON files. After validation, confirm the group name and page count. Import creates a new group and independent page files; existing groups, shared pages, and settings are preserved. Name conflicts receive numeric suffixes, and all references are updated to the new filenames. The imported group becomes active.

For export, select a destination parent folder. The app creates a new folder named after the selected group, with a numeric suffix if needed. All member pages are copied, followed by the group metadata. Missing or invalid source pages prevent export. If the provider cannot complete the write, the app attempts to remove only its own partial output and reports any incomplete cleanup.

## Folder format

```text
My group/
  First.json
  Second.json
  group_config.json
```

Only direct JSON children are read. Other files and nested folders are ignored. Each page supports the existing plain layout format or supported version 1–4 export wrapper. Group import ignores legacy page-level appearance; group appearance comes from the metadata or defaults.

The optional `group_config.json` has this structure:

```json
{
  "format": "ipa-board-page-group",
  "schemaVersion": 1,
  "name": "My group",
  "appearance": {
    "backgroundColor": "#000000",
    "symbolColor": "#FFFFFF",
    "popupBackgroundColor": "#FFFFFF",
    "popupSymbolColor": "#000000",
    "pointerPathColor": "#FFFFFF",
    "pointerAxisColor": "#FFFFFF",
    "pointerCrosshairColor": "#FF0000",
    "heightDp": 210,
    "fontSizeSp": 18,
    "showGrid": true,
    "showPointerLocation": false,
    "usePointerDifferenceBlend": false
  },
  "pages": ["Second.json", "First.json"],
  "activePage": "First.json"
}
```

The folder name is authoritative; the optional metadata `name` is informational. `pages` lists every page filename exactly once in display order. `activePage` is a member filename or `null`; an empty group uses `null`. The appearance object preserves the keyboard, popup and pointer colors, keyboard height, font size, grid and pointer options.

Without metadata, pages retain the source provider's enumeration order, use `GroupAppearance` defaults, and select the first page. Providers may enumerate differently; include metadata whenever exact order matters. Multiple case variants of the reserved metadata filename are rejected. A page already named `group_config.json` is renamed in the exported copy, with metadata references adjusted.

The import limits are 1 MiB per page or metadata file, 32 MiB total, and 256 pages. Invalid metadata, invalid layouts, unreadable files and incomplete provider listings are rejected before application data changes. Storage failures roll back new files and affected preferences; a recovery failure is reported separately.

Rollback handles errors while the process is running. Forced process termination or power loss can leave partial new files; an interrupted operation is not automatically repeated. Check the group list or destination before retrying.

## Bundled initialization

Each direct subdirectory under `app/src/main/assets/initialization/keyboards/` is a group folder in the same format. Fresh initialization validates all bundled groups, then imports them together and selects the first group. Existing page/group state or the previous initialization marker prevents replay, including after the user deletes presets. The default Shortcut initialization remains independent and runs before keyboard initialization.

The bundled presets contain three groups and fifteen pages supplied by the maintainer: Default (4), IPA (1), and Uncommon (10). Upgrading an existing installation preserves its configuration; use group import to add a preset explicitly.

## Validation

The `page-group-transfer` regression suite covers the folder contract, initialization and upgrade preservation, document-provider failures, and the management flow. Device checks run only through `tools/regression.py` with the required backup and verified restoration. Tests use owned files, fake document stores and intercepted pickers; they do not browse personal document folders.
