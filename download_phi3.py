"""
Download and prepare Phi-3-mini GGUF model for LocalSeek.
"""

from pathlib import Path
from huggingface_hub import hf_hub_download

OUTPUT_DIR = Path("./phi3_model")
PRIMARY_REPO = "microsoft/Phi-3-mini-4k-instruct-gguf"
PRIMARY_FILE = "Phi-3-mini-4k-instruct-q4.gguf"
FALLBACK_REPO = "bartowski/Phi-3-mini-4k-instruct-GGUF"
FALLBACK_FILE = "Phi-3-mini-4k-instruct-Q4_K_M.gguf"


def try_download(repo: str, filename: str) -> Path | None:
    try:
        path = hf_hub_download(
            repo_id=repo,
            filename=filename,
            local_dir=str(OUTPUT_DIR),
            local_dir_use_symlinks=False,
        )
        return Path(path)
    except Exception as exc:
        print(f"Download failed from {repo}/{filename}: {exc}")
        return None


def main() -> None:
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)

    model_path = try_download(PRIMARY_REPO, PRIMARY_FILE)
    if model_path is None:
        model_path = try_download(FALLBACK_REPO, FALLBACK_FILE)

    if model_path is None:
        raise SystemExit("Failed to download any Phi-3 GGUF model")

    size_mb = model_path.stat().st_size / (1024 * 1024)
    print(f"Model ready: {model_path}")
    print(f"Size: {size_mb:.1f} MB")
    print("Copy to app/src/main/assets/models/phi3.gguf")


if __name__ == "__main__":
    main()

