# Emoji catalog and future updates

The bundled `app/src/main/assets/emoji/emoji-test.txt` is the official final Unicode Emoji 18.0 data, retrieved on 2026-09-28. It contains 3,963 fully-qualified emoji plus 9 standalone emoji components. These 3,972 entries form the complete RGI set. Minimally-qualified and unqualified lines are alternate presentations and are deliberately omitted from the picker. Skin tones, genders, ZWJ sequences, keycaps, national and subdivision flags are retained as complete strings.

Provenance, SHA-256, counts and schema version are in `assets/emoji/metadata.json`; Unicode License V3 is included in `assets/emoji/LICENSE.txt`. Latest upstream release: https://www.unicode.org/Public/emoji/latest/ReadMe.txt and https://www.unicode.org/Public/emoji/latest/emoji-test.txt.

The picker uses a recycled GridView and an optional category filter. Data loading runs off the IME main thread and is cached per service. Selecting an entry first commits pending composition literally, then inserts the exact Unicode string. The panel stays open for repeated entry; Return or system Back restores the keyboard. Rendering uses Android's font; unavailable glyphs display their Unicode names and remain selectable. Adding newer catalog data does not upgrade the system font or the receiving app's font.

## Manual catalog updates

The Emoji management screen provides a manual update button. `EmojiUpdateManager` downloads the Unicode latest catalog in a background executor, checks that parsing produces a nonempty catalog, stores it in app-private files, and invalidates the shared repository cache. The app declares INTERNET permission; bundled data remains available offline.

`InstalledEmojiCatalogSource` prefers a valid installed catalog and falls back to bundled data when the installed file cannot be parsed. Updating catalog data does not update Android fonts. Usage counts provide a frequently used emoji list.

Current implementation limitations: the downloader has connection/read timeouts but no download size limit, and file replacement does not check rename success. Bundled metadata and licensing describe the bundled catalog; updates do not refresh provenance metadata.

`KeyAction.EMOJI` is serialized as `"emoji"`; it is a panel action rather than an editor key event. Existing layouts can assign it using the app's Key type selector.
