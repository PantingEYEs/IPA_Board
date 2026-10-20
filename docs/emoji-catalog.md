# Emoji catalog and future updates

The bundled `app/src/main/assets/emoji/emoji-test.txt` is the pinned Unicode Emoji 18.0 catalog, retrieved on 2026-09-28. The picker includes its 3,963 fully-qualified emoji and 9 standalone emoji components, for 3,972 entries. Minimally-qualified and unqualified lines are alternate presentations and are deliberately omitted. Skin tones, genders, ZWJ sequences, keycaps, national and subdivision flags are retained as complete strings. This bundled version does not describe a user's subsequently downloaded catalog or claim to be the latest upstream release.

Provenance, SHA-256, counts and schema version are in `assets/emoji/metadata.json`; Unicode License V3 is included in `assets/emoji/LICENSE.txt`. Latest upstream release: https://www.unicode.org/Public/emoji/latest/ReadMe.txt and https://www.unicode.org/Public/emoji/latest/emoji-test.txt.

The picker uses one recycled RecyclerView grid containing a frequently used section followed by every official Unicode group, with full-width group-name headings. The category menu jumps to a heading instead of filtering entries; there is no separate All item. Ordinary scrolling updates the current category in the status area and the menu selection according to the top visible section. Empty usage history keeps a navigable common section with an empty message. Selecting emoji refreshes that prefix while preserving the current visible item and offset; even a short final section can align its heading at the top.

Data loading runs off the IME main thread and is cached per service. Selecting an entry first commits pending composition literally, then inserts the exact Unicode string. The panel stays open for repeated entry; Return or system Back restores the keyboard. Rendering uses Android's font; unavailable glyphs display their Unicode names and remain selectable. Adding newer catalog data does not upgrade the system font or the receiving app's font.

Touch handling retains the kaomoji panel's tap-versus-scroll behavior. RecyclerView intercepts vertical drags before a cell can complete its click; a completed tap resolves the current adapter position and selects that entry once, while dragging or cancelling a touch inserts nothing. Fast scrolling remains disabled because its expanded touch target overlaps the rightmost emoji column (#3). Cell recycling and the standard vertical scroll indicator remain in use.

## Manual catalog updates

The Emoji management screen provides a manual update button. `EmojiUpdateManager` downloads the Unicode latest catalog in a background executor, checks that parsing produces a nonempty catalog, stores it in app-private files, and invalidates the shared repository cache. The app declares INTERNET permission; bundled data remains available offline.

`InstalledEmojiCatalogSource` prefers a valid installed catalog and falls back to bundled data when the installed file cannot be parsed. Updating catalog data does not update Android fonts. Usage counts provide a frequently used emoji list.

Current implementation limitations: the downloader has connection/read timeouts but no download size limit, and file replacement does not check rename success. Bundled metadata and licensing describe the bundled catalog; updates do not refresh provenance metadata.

`KeyAction.EMOJI` is serialized as `"emoji"`; it is a panel action rather than an editor key event. Existing layouts can assign it using the app's Key type selector.
