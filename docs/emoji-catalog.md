# Emoji catalog and future updates

The bundled `app/src/main/assets/emoji/emoji-test.txt` is the official final Unicode Emoji 18.0 data, retrieved on 2026-09-28. It contains 3,963 fully-qualified emoji plus 9 standalone emoji components. These 3,972 entries form the complete RGI set. Minimally-qualified and unqualified lines are alternate presentations and are deliberately omitted from the picker. Skin tones, genders, ZWJ sequences, keycaps, national and subdivision flags are retained as complete strings.

Provenance, SHA-256, counts and schema version are in `assets/emoji/metadata.json`; Unicode License V3 is included in `assets/emoji/LICENSE.txt`. Latest upstream release: https://www.unicode.org/Public/emoji/latest/ReadMe.txt and https://www.unicode.org/Public/emoji/latest/emoji-test.txt.

The picker uses a recycled GridView and an optional category filter. Data loading runs off the IME main thread and is cached per service. Selecting an entry first commits pending composition literally, then inserts the exact Unicode string. The panel stays open for repeated entry; Return or system Back restores the keyboard. Rendering uses Android's font; unavailable glyphs display their Unicode names and remain selectable. Adding newer catalog data does not upgrade the system font or the receiving app's font.

## Reserved update integration

No network permission, update button, downloader or automatic update is implemented in this release.

- `EmojiCatalogSource.load()` is the data-source boundary. The current source is `BundledEmojiCatalogSource`. A future implementation can prefer a verified installed file and fall back to the bundled asset.
- `BundledEmojiCatalogSource.LATEST_DATA_URL` and `METADATA_ASSET_PATH` identify upstream data and local metadata.
- `EmojiCatalogParser.parse(Reader)` accepts the official format and returns version, category, subgroup, name, per-entry version and complete sequence.
- `EmojiCatalogRepository.invalidate()` is the cache refresh hook after a successful installation. The IME must be notified to invalidate its repository and reload an open Emoji panel; swapping only a settings-process cache is insufficient.
- `TODO(emoji-update)` marks the update integration point. The future app button should download off-main with size/time limits, validate the official version/counts and catalog before writing, atomically install it in app-private storage, retain the old valid data on failure, and publish a revision notification to the IME. Licensing/provenance metadata should accompany every installed version.

`KeyAction.EMOJI` is serialized as `"emoji"`; it is a panel action rather than an editor key event. Existing layouts can assign it using the app's Key type selector.
