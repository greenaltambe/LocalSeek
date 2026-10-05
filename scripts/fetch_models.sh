#!/usr/bin/env bash
set -euo pipefail

# ==============================================================================
# fetch_models.sh - Downloads and verifies on-device ML models for LocalSeek
#
# Tracked-out models (via .gitignore):
# - clip_image_encoder_fp16.tflite (167.6 MB)
# - clip_text_encoder_fp16.tflite  (121.0 MB)
# ==============================================================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

CLIP_ASSETS_DIR="${ROOT_DIR}/clip_model/src/main/assets/models/clip"
DEBUG_CLIP_ASSETS_DIR="${ROOT_DIR}/app/src/debug/assets/models/clip"

mkdir -p "${CLIP_ASSETS_DIR}" "${DEBUG_CLIP_ASSETS_DIR}"

# Authoritative SHA-256 Checksums
EXPECTED_CLIP_IMAGE_SHA="a5cdd3e780a076900061a1a28972f4167d113ab086544463919d079e9785e549"
EXPECTED_CLIP_TEXT_SHA="4333bcc255a89956753aaa066366c3f76bdf601ddc0c1d1f9c382bb94cc587bb"
EXPECTED_VOCAB_SHA="278e68b6c6fa62f546bee6071ca33e266832edb789736bdf0bca7f97db764890"
EXPECTED_MERGES_SHA="f526393189112391ce6f9795d4695f704121ce452c3aad1f5335cc41337eba85"

# Upstream Source URLs (Default: HuggingFace / LocalSeek CDN / Mirror)
DEFAULT_HF_BASE="https://huggingface.co/openai/clip-vit-base-patch32/resolve/main"
MODEL_CDN_BASE="${LOCALSEEK_MODEL_CDN:-https://github.com/LocalSeek/models/releases/download/v1.0}"

check_sha256() {
    local file="$1"
    local expected="$2"
    if [ ! -f "$file" ]; then
        return 1
    fi
    local actual
    actual=$(sha256sum "$file" | awk '{print $1}')
    if [ "$actual" = "$expected" ]; then
        return 0
    else
        echo "Checksum mismatch for $file:"
        echo "  Expected: $expected"
        echo "  Actual:   $actual"
        return 1
    fi
}

download_file() {
    local url="$1"
    local dest="$2"
    echo "Downloading ${url} -> ${dest}..."
    if command -v curl >/dev/null 2>&1; then
        curl -fL --progress-bar "$url" -o "$dest"
    elif command -v wget >/dev/null 2>&1; then
        wget -q --show-progress "$url" -O "$dest"
    else
        echo "Error: Neither curl nor wget found in PATH." >&2
        exit 1
    fi
}

echo "=== LocalSeek ML Model Verification & Fetch Utility ==="

# 1. Check/Download vocab.json
VOCAB_FILE="${CLIP_ASSETS_DIR}/vocab.json"
if check_sha256 "${VOCAB_FILE}" "${EXPECTED_VOCAB_SHA}"; then
    echo "[OK] vocab.json verified."
else
    download_file "${DEFAULT_HF_BASE}/vocab.json" "${VOCAB_FILE}"
    check_sha256 "${VOCAB_FILE}" "${EXPECTED_VOCAB_SHA}" || { echo "Failed to verify vocab.json"; exit 1; }
fi
cp -f "${VOCAB_FILE}" "${DEBUG_CLIP_ASSETS_DIR}/vocab.json"

# 2. Check/Download merges.txt
MERGES_FILE="${CLIP_ASSETS_DIR}/merges.txt"
if check_sha256 "${MERGES_FILE}" "${EXPECTED_MERGES_SHA}"; then
    echo "[OK] merges.txt verified."
else
    download_file "${DEFAULT_HF_BASE}/merges.txt" "${MERGES_FILE}"
    check_sha256 "${MERGES_FILE}" "${EXPECTED_MERGES_SHA}" || { echo "Failed to verify merges.txt"; exit 1; }
fi
cp -f "${MERGES_FILE}" "${DEBUG_CLIP_ASSETS_DIR}/merges.txt"

# 3. Check/Download clip_image_encoder_fp16.tflite
IMAGE_MODEL="${CLIP_ASSETS_DIR}/clip_image_encoder_fp16.tflite"
if check_sha256 "${IMAGE_MODEL}" "${EXPECTED_CLIP_IMAGE_SHA}"; then
    echo "[OK] clip_image_encoder_fp16.tflite verified."
else
    # If debug assets copy exists, use it
    if check_sha256 "${DEBUG_CLIP_ASSETS_DIR}/clip_image_encoder_fp16.tflite" "${EXPECTED_CLIP_IMAGE_SHA}"; then
        echo "Copying clip_image_encoder_fp16.tflite from debug assets..."
        cp -f "${DEBUG_CLIP_ASSETS_DIR}/clip_image_encoder_fp16.tflite" "${IMAGE_MODEL}"
    else
        download_file "${MODEL_CDN_BASE}/clip_image_encoder_fp16.tflite" "${IMAGE_MODEL}"
    fi
    check_sha256 "${IMAGE_MODEL}" "${EXPECTED_CLIP_IMAGE_SHA}" || { echo "Failed to verify clip_image_encoder_fp16.tflite"; exit 1; }
fi
cp -f "${IMAGE_MODEL}" "${DEBUG_CLIP_ASSETS_DIR}/clip_image_encoder_fp16.tflite"

# 4. Check/Download clip_text_encoder_fp16.tflite
TEXT_MODEL="${CLIP_ASSETS_DIR}/clip_text_encoder_fp16.tflite"
if check_sha256 "${TEXT_MODEL}" "${EXPECTED_CLIP_TEXT_SHA}"; then
    echo "[OK] clip_text_encoder_fp16.tflite verified."
else
    # If debug assets copy exists, use it
    if check_sha256 "${DEBUG_CLIP_ASSETS_DIR}/clip_text_encoder_fp16.tflite" "${EXPECTED_CLIP_TEXT_SHA}"; then
        echo "Copying clip_text_encoder_fp16.tflite from debug assets..."
        cp -f "${DEBUG_CLIP_ASSETS_DIR}/clip_text_encoder_fp16.tflite" "${TEXT_MODEL}"
    else
        download_file "${MODEL_CDN_BASE}/clip_text_encoder_fp16.tflite" "${TEXT_MODEL}"
    fi
    check_sha256 "${TEXT_MODEL}" "${EXPECTED_CLIP_TEXT_SHA}" || { echo "Failed to verify clip_text_encoder_fp16.tflite"; exit 1; }
fi
cp -f "${TEXT_MODEL}" "${DEBUG_CLIP_ASSETS_DIR}/clip_text_encoder_fp16.tflite"

echo ""
echo "All model files are verified and correctly deployed to:"
echo "  - ${CLIP_ASSETS_DIR}"
echo "  - ${DEBUG_CLIP_ASSETS_DIR}"
