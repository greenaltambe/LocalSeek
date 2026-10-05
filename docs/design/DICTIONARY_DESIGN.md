# Offline dictionary (design only, not implemented)

Status: design. Recommendation: **ship after v1.0**. Nothing here touches retrieval, the search index, SearchEngine or the paper pipeline.

## Goal

Type `d run` or `define run` and get a dictionary card: word, part of speech, numbered definitions, example sentences.
Fully offline. LocalSeek keeps no INTERNET permission, so the data must arrive through Google Play, exactly like the CLIP pack.

## Shape

- **Optional on-demand Play Asset Delivery pack** `dictionary_pack` (install-time is out: it would add to every download).
  Managed by a `DictionaryPackManager` modelled on `ClipAssetPackManager` (states: NotInstalled, Downloading, Ready, Failed,
  WaitingForWifi). Settings > Search gets a "Dictionary" card with size, Wi-Fi hint, progress, cancel and "Remove".
- **Its own small SQLite file** `dictionary.db`, opened read-only. It is not the search index, not Room, not part of the
  retrieval stages and never joins the fusion pipeline.
  - `entry(id, lemma, pos, sense_no, definition, example)`; `entry_fts` (FTS5, prefix index on `lemma`).
  - Lookup: exact lemma first, then prefix matches, max 20 senses grouped by part of speech.
- **Entry point:** a prefix target `dict` (default prefixes `d` and `define`, user-editable like the others). The existing
  prefix chip shows a book icon; the tools layer answers (like the calculator), so ranking and benchmarks are unaffected.
- **Card:** word in `headlineSmall`, part-of-speech chip, up to 3 senses with an expand control, examples in italics,
  a one-line source credit ("Open English WordNet, CC BY 4.0"). Copy-definition on tap, same snackbar as the answer cards.
- Without the pack: the card says "Dictionary not installed" with a Download button; nothing else changes.

## Data candidates

| Source | Licence | Notes | Estimated size* |
|---|---|---|---|
| **Open English WordNet 2024 (OEWN)** | CC BY 4.0 | Maintained fork of Princeton WordNet, ~120k lemmas, definitions + examples. Attribution only. **Recommended.** | 25-35 MB sqlite, 8-12 MB in the pack |
| Princeton WordNet 3.1 | WordNet 3.0/3.1 licence (permissive, BSD-like; keep the copyright notice and licence text) | Older but well understood; same structure as OEWN. Fallback. | similar |
| Wiktionary-derived extracts (e.g. kaikki.org JSONL) | CC BY-SA 4.0 (+ GFDL) | Far richer (etymology, pronunciation, inflections) but share-alike: the data file would have to stay CC BY-SA and be credited; needs a legal read before bundling. Large. | 80-250 MB after trimming |
| GCIDE | GPL | Avoid: copyleft on the data file. | n/a |
| Webster's 1913 | Public domain | Archaic wording; poor quality for modern words. | n/a |

*Estimates from the corpus size, **not measured**; they must be confirmed once a build exists.

## Attribution (goes into THIRD_PARTY_NOTICES.md)

> **Open English WordNet** (https://en-word.net): English WordNet 2024 edition. Copyright the Open English WordNet contributors.
> Derived from Princeton WordNet 3.1 (c) 2011 Princeton University. Licensed under CC BY 4.0
> (https://creativecommons.org/licenses/by/4.0/). Changes: converted to a compact SQLite FTS5 file for offline lookup.

If Princeton WordNet is used instead, add the full WordNet licence text verbatim ("WordNet Release 3.1 ... Permission to use,
copy, modify and distribute this software and database and its documentation for any purpose and without fee or royalty is
hereby granted ..."). The in-app licences page already renders THIRD_PARTY_NOTICES.md.

## What I need from you

I cannot download from arbitrary sites. Please place **one** of these in `~/localseek-private/dictionary/` (outside the repo) and tell me which:

1. the OEWN 2024 release file, `english-wordnet-2024.xml.gz` (or the equivalent `.zip`) from the Open English WordNet GitHub
   releases page (exact asset name to be confirmed when you download it), or
2. the Princeton `WordNet-3.1` database package (`wn31.sql` or the `dict/` folder).

With that file I would add `tools/dict/build_dictionary_db.py` (no network, standard library only), generate `dictionary.db`,
report its real size, and then wire the pack, the `dict` prefix and the card. The pack itself is built through the existing
Play asset pack setup (a second `:dictionary_pack` module next to `:clip_model`).

## Risks and open points

- Pack size and Play delivery are unverified until built; keep it on-demand so the base download is unaffected.
- Sense ordering and part-of-speech labels follow WordNet; no ranking by frequency in v1.
- Licence text must be on the in-app licences page before the pack is published.
- Privacy: lookups stay in memory; the typed word is never stored, logged or added to recent searches when it came from the `d` prefix (decision to confirm).
