# Play Store listing draft

Facts here come from the manifest and code on branch `overnight2`. Features marked _(verify on device)_ are
implemented in code but I could not exercise them on a phone while writing this (the device was unauthorised
overnight); check them before they go in the public listing.

## Name candidates

1. LocalSeek (current `app_name`; check Play for name clashes: VERIFY)
2. LocalSeek: Private Device Search
3. LocalSeek: Offline Search for Files, Apps & Contacts

## Short description (max 80 characters)

- `Search your files, apps, contacts and photos on-device. No network, no account.` (79 chars)
- Alternative: `Private, offline search for your documents, apps, contacts and photos.` (70 chars)

## Full description (draft)

LocalSeek is a search engine for your own phone. Type what you remember (a phrase from a PDF, a
recipe, a contact's name, an app) and find it in one list, grouped into Apps, Contacts, Files and Images.

Everything happens on your device. LocalSeek has no internet permission: it cannot send your files,
contacts or search queries anywhere. There is no account, no analytics and no ads. Cloud backup and
device-to-device transfer are disabled for the app.

What it searches
- Documents in your Documents and Download folders: PDF and plain-text files such as txt, md, csv, json, code and notes
  (by filename and by content; PDFs are read up to 10 pages; Office formats such as Word are not read)
- Installed apps
- Contacts (optional; needs the contacts permission): with call, message and messenger shortcuts
- Photos, by describing what is in them (optional; needs photo permission and a one-time model download
  handled by Google Play, about 290 MB, Wi-Fi recommended)

How it works
- Combines keyword search (SQLite FTS5) with on-device semantic search (MiniLM embeddings), so "tax form"
  can find a file that never uses those exact words.
- An optional on-device cross-encoder re-ranker improves the order of the top results.
- Models run on-device with LiteRT. The index lives only in the app's private storage; uninstalling the
  app or clearing its storage deletes it.

First run
- A short, skippable tour explains what LocalSeek does and asks for each optional permission one at a time,
  with a reason and a "Not now" button. The app works with whatever you grant; apps, tools and web
  shortcuts need no permission at all.

Extras _(verify on device)_: built-in calculator, unit converter and date helper; web-search shortcuts that
open your browser (LocalSeek itself never connects); pinned items; settings search; light, dark and Material
You themes; an optional one-handed layout with the search bar at the bottom; Quick Settings tile and home
screen widget; settings export/import (never includes your index, files or contact details; contact pins are
not exported).

## Honest feature list

- On-device hybrid search over files, apps, contacts and images
- Background indexing that resumes and re-checks periodically (every 6 hours)
- Optional cross-encoder re-ranking of top results
- Optional image search via an on-demand Play Asset Delivery pack
- No network permission, no backup of the index, About page with licenses and privacy summary

## Do NOT claim (not true or not verified)

- "Searches your whole phone": only Documents and Download are scanned today (`FileIndexer.scanRoots`).
- "Works in any language": models are English-oriented MiniLM/BERT vocabularies (VERIFY before claiming).
- Any accuracy or speed numbers until the paper benchmark is finished.
- "Open source" until a licence is chosen and history is cleaned.
- "16 KB ready on all devices": arm64-v8a is verified; x86_64 native libs are still 4 KB aligned
  (see `docs/investigations/T9_INVESTIGATIONS.md`).

## Keywords / tags (Play has no keyword field; use in title/description naturally)

offline search, local search, file search, document search, PDF search, on-device search, private
search, contact search, app launcher search, semantic search, photo search, no internet, no ads

## Screenshots

See `SCREENSHOT_PLAN.md` for the eight screens, exact states and generic demo data. Feature graphic
1024x500 and 512x512 icon still to produce (the new adaptive icon vectors are in `app/src/main/res/drawable`).
