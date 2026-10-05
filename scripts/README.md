# scripts/

- `fetch_models.sh`: downloads the CLIP model files (not stored in git) and verifies their SHA-256 sums.
- `monitor_index.sh`: polls the on-device index size while a long indexing run is in progress (needs one adb device; reads counts only, deletes nothing; writes `index_progress.csv` and `monitor_index.log` here, both git-ignored).
