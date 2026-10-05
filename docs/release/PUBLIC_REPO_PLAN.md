# Public repository plan (AE4)

> Status note (task AQ, 2026-10-05): this tree was built from the private repository's `main` with `git archive`, excluding `tools/`, `CLAUDE.md`, `eval/legacy/qrels.txt` and `eval/legacy/results/per_run_metrics.csv`. The private-to-public commit mapping is in [COMMIT_MAP.md](COMMIT_MAP.md). The text below is the plan as written before; paths named in it that are not in this tree are the excluded ones.

The GitHub repository is empty, so the first push defines public history. The private history contains files that must never be public (see below), so the plan is a **fresh public repository with a cleaned tree and a single initial commit**. The private repository is not rewritten and nothing has been pushed.

## 1. Must not go public

| path | why |
|---|---|
| `eval/results/per_run_metrics.csv` | 21 raw query texts |
| `eval/qrels.txt` | 233 contact ids, 132 file ids, 39 app ids of the owner's phone |
| `.idea/` | IDE state, local paths, plugin data |
| `.artifacts/` | assistant working artefacts |
| `tools/` (`tools/prompts/`, `tools/overnight/`, `tools/purge_and_recreate.sh`) | prompts, device-specific scripts, one script that deletes app data |
| `CLAUDE.md`, `CONTEXT.md`, `CURRENT.md`, `TARGET.md` | assistant/working notes |
| `PROJECT_AUDIT.md`, `OVERNIGHT_REPORT.md`, `OVERNIGHT2_REPORT.md`, `VERIFIED_PROJECT_STATE.md`, `IMPLEMENTATION_MASTER_PLAN.md`, `FINAL_IMPLEMENTATION_PLAN.md`, `FINAL_RELEASE_AND_PUBLICATION_REVIEW.md`, `LocalSeek_Release_and_Paper_Plan.md`, `LocalSeek_E1-E8_Independent_Methodology_Audit.md` | root-level planning and audit notes, not meant for readers |
| anything git-ignored today (`*.db`, `queries*.csv`, `sample_targets*.csv`, `eval/canonical_pool.*`, `eval/pool_*.csv`, `eval/results*/` exports, `*redactions*.csv`, model binaries) | personal data / size |
| any file with a personal absolute path | none found in the candidate tree (scan below) |

Kept: source (`app/`, `clip_model/` without the `.tflite` files, `gradle/`, build files), `docs/` (store, release, investigations with aggregate numbers only, design, paper), `eval/` code and tests, `scripts/fetch_models.sh`, `README.md`, `PRIVACY.md`, `THIRD_PARTY_NOTICES.md`, `LICENSE`, `.gitignore`.

## 2. What to publish instead of the private data

- **Anonymised qrels and run files**: produce them with `eval/anonymize_export.py` using a **private per-release salt** (kept outside the repository). It replaces contact and redacted query texts, replaces titles and snippets, hashes result ids with SHA-256 + salt, and transforms qrels with the same salt (`--qrels eval/qrels.txt --output-qrels ...`). Publish the outputs under `eval/results/` in the public repository, never the raw files.
- **Counts-only documents**: the existing `docs/investigations/*.md` contain aggregate numbers only; add new analyses the same way.
- The pre-registration text (`docs/investigations/PHASE2_PREREG.md`, on branch `phase2`) is registered on OSF first and added afterwards.

## 3. Model files

The `.tflite` files are not in git (size). A public clone builds the app but not the asset pack until the models are present. `scripts/fetch_models.sh` is the download-and-verify entry point: the owner must fill in (a) the conversion or download source for each model and (b) the **SHA-256 values** (to be written by the owner later; the script and `eval/parity/` hold the conversion tooling). Until then the README must say that the models are obtained separately.

## 4. Licences

- Code: **Apache-2.0** (`LICENSE`, already in the tree) plus a `NOTICE` file.
- Documentation (`README.md`, `PRIVACY.md`, `docs/`): **CC BY 4.0** (`LICENSE-DOCS.md`; the candidate tree points to the legal code URL, the owner may add the full text).
- Models and libraries: as listed in `THIRD_PARTY_NOTICES.md` (CLIP ViT-B/32: MIT; cross-encoder ms-marco-MiniLM-L-6-v2 and all-MiniLM-L6-v2: Apache-2.0; LiteRT, PDFBox-Android: Apache-2.0; Bouncy Castle: MIT-style).

## 5. Commit and tag mapping (to fill in after the first push)

| private | public |
|---|---|
| `paper-v1.2` = `3e73f3a` (benchmark `gitSha` `3e73f3ac49c4`) | public tag: _to be filled in after the first push_ |
| `paper-v1`, `paper-v1.1` (final) | _to be filled in_ |
| first public commit | _to be filled in_ |

Because the public history is new, private commit ids do not exist publicly; the mapping note (private id, benchmark gitSha, public tag) belongs in the public README or a `docs/release/` file once the tag exists. Do not create tags in the private repository for this.

## 6. Candidate tree and scan (done, not pushed)

Candidate tree: `../LocalSeek-public` (one commit, 401 files, `LICENSE`, `NOTICE`, `LICENSE-DOCS.md` added). Scan: `python3 eval/public_tree_scan.py ../LocalSeek-public --qrels eval/qrels.txt --allow docs/release/public_scan_allowlist.txt`

| check | count |
|---|---|
| files scanned | 375 |
| private identifiers from the old qrels (302 literals: ids, package names, lookup-key tails) | 0 |
| email-like strings | 0 outside the allow-list |
| phone-number-like strings | 0 outside the allow-list |
| absolute home paths | 0 outside the allow-list |
| keystore / secret patterns | 0 outside the allow-list |
| allow-listed (reviewed): email 7, phone 16, home path 1, secret 1 | all inside 6 unit-test files with invented values (e.g. contact-action tests, tokenizer and anonymiser tests, the scanner's own test) |

Update 2026-10-05 (repo-cleanup): the root notes, `LocalSeek_*` files, `tools/purge_and_recreate.sh` and the old paper drafts were archived and removed from the tree; the legacy v1 evaluation files now live in `eval/legacy/`. Items below that name them are outdated.

Known leftovers to fix in the public copy before pushing (not private, but dangling): `README.md` line 61 and `docs/release/APK_SIZE_REPORT.md` mention the excluded `LocalSeek_*` markdown files; `eval/README.md`, `docs/paper/04_methodology.md` and `eval/judging.py` mention `eval/qrels.txt`, which is replaced by the anonymised qrels; `.gitignore` still lists private-path patterns (harmless). The handle `greenaltambe` appears in `PRIVACY.md`, the About screen and `docs/index.html` (public contact/links; confirm you want that). The first public commit carries the git author configured in the private repo; change it if you want a different identity.

## 7. Commands for the owner (review, then push; nothing below has been run)

```
cd ../LocalSeek-public
git log --stat | head -30                 # one commit, no private paths
git ls-files | grep -E "qrels.txt|per_run_metrics|\.idea|tools/|CLAUDE|\.tflite|\.db$" || echo "none of the excluded files"
python3 ../LocalSeek/eval/public_tree_scan.py . --qrels ../LocalSeek/eval/legacy/qrels.txt --allow ../LocalSeek/docs/release/public_scan_allowlist.txt
# fix the leftovers listed above, then amend or add a commit:
git add -A && git commit -m "Fix references for the public tree"
# build check from the clean tree (models absent: the asset pack step needs them):
./gradlew :app:testDebugUnitTest :app:assembleDebug
# create the empty GitHub repository first (no README, no licence), then:
git remote add origin <ssh url of the empty GitHub repository>
git push -u origin main
# only after the push: tag and fill in section 5
```
