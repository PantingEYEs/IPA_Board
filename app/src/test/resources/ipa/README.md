# Reverse lookup format fixtures

These two small, synthetic dictionaries were created for IPA Board tests. They contain no
user data or copied production corpus. `reverse-v2.ipad` uses 24-bit indexes;
`reverse-v3.ipad` has identical mappings with 32-bit indexes.

The IPADWG01 header contains the magic, seven little-endian 32-bit fields
(version, node/arc/key/word/alphabet counts, root), and nine 64-bit section boundaries.
Sections contain the Unicode alphabet, node arc offsets, node terminal/subtree counts,
labeled arcs, posting offsets, delta-coded postings, word-block offsets and front-coded
UTF-8 word blocks. Terminal flags use the top bit of the index width. Posting codes use
low three bits for region, bit 3 for derived, and higher bits for the delta word ID;
a following byte stores frequency score. Word blocks restart every 16 entries.

The pool is ordered by Unicode scalar value: `café`, `hello`, `学校`, U+E000 and U+10000.
The last two protect against accidentally using UTF-16 lexical ordering.

| IPA | Word | Region | Derived | Score |
| --- | --- | --- | --- | --- |
| həˈləʊ | hello | en-GB | no | 120 |
| həˈloʊ | hello | en-US | no | 140 |
| həˈloʊ | hello | en-GB | yes | 60 |
| ɕɥe˧˥ | 学校 | zh-CN | no | 90 |
| ɕɥe˧˥ | 学校 | zh-TW | yes | 80 |
| ɑ + U+0301 + ː | café | en-US | no | 70 |
| ɑ + U+0301 + ː | U+E000 | ja-JP | no | 60 |
| ɑ + U+0301 + ː | U+10000 | ja-JP | no | 50 |

The IPA strings intentionally test stress, tone, length and combining marks; they are
synthetic test values, not pronunciation claims. The trie is stored bottom-up, with
children preceding parents; equivalent-suffix minimization is unnecessary for validity.
