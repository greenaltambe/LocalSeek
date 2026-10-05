# Third-Party Notices and Machine Learning Model Licenses

LocalSeek incorporates on-device machine learning models and open-source software libraries.
This document details the original upstream source, precision, artifact locations, and licensing terms for each model.

---

## 1. CLIP ViT-B/32 On-Device Encoders
- **Original Source**: OpenAI CLIP (`openai/clip-vit-base-patch32`)
- **Upstream Repository**: [https://github.com/openai/CLIP](https://github.com/openai/CLIP) / [Hugging Face Hub](https://huggingface.co/openai/clip-vit-base-patch32)
- **License**: MIT License
- **Copyright**: (c) 2021 OpenAI
- **Precision & Deployment**:
  - `clip_image_encoder_fp16.tflite` (167.6 MB): Converted from OpenAI ViT-B/32 vision backbone to TFLite FP16. Output: 512-dimensional normalized embedding.
  - `clip_text_encoder_fp16.tflite` (121.0 MB): Converted from OpenAI CLIP text transformer to TFLite FP16. Output: 512-dimensional normalized embedding.
  - `vocab.json` & `merges.txt`: Byte-pair encoding tokenizer assets (49,408 vocabulary).
- **Delivery Mechanism**: Packaged in on-demand Play Asset Delivery pack `:clip_model` to preserve base APK lightness.

```
The MIT License (MIT)

Copyright (c) 2021 OpenAI

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

## 2. Cross-Encoder Reranker
- **Original Source**: `cross-encoder/ms-marco-MiniLM-L-6-v2`
- **Upstream Repository**: [https://huggingface.co/cross-encoder/ms-marco-MiniLM-L-6-v2](https://huggingface.co/cross-encoder/ms-marco-MiniLM-L-6-v2)
- **License**: Apache License, Version 2.0
- **Copyright**: (c) 2020 Nils Reimers, Hugging Face
- **Precision & Deployment**:
  - `app/src/main/assets/models/cross_encoder.tflite` (43.21 MB): FP16 TFLite model exported with ONNX opset 20 preserving the native ONNX `Gelu` operator. Takes `input_ids [1, 256]`, `attention_mask [1, 256]`, and `token_type_ids [1, 256]` (segment IDs: 0 for query, 1 for document, 0 for padding). Output: scalar relevance logit.
  - Parity against PyTorch reference: Spearman 1.000000, Pearson 1.000000, AUC within 0.0000.

---

## 3. MiniLM Dense Embedding Model
- **Original Source**: `sentence-transformers/all-MiniLM-L6-v2`
- **Upstream Repository**: [https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2](https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2)
- **License**: Apache License, Version 2.0
- **Copyright**: (c) 2020 Nils Reimers, Hugging Face
- **Precision & Deployment**:
  - `app/src/main/assets/minilm_optimized.tflite` (22.7 MB): 384-dimensional dense sentence embeddings.

---

## 4. Google LiteRT (TensorFlow Lite Runtime)
- **Source**: `com.google.ai.edge.litert:litert:1.0.1`
- **Upstream Repository**: [https://github.com/google-ai-edge/LiteRT](https://github.com/google-ai-edge/LiteRT)
- **License**: Apache License, Version 2.0
- **Copyright**: (c) 2024 Google LLC
- **Notes**: 16 KB page-aligned native runtime (`libtensorflowlite_jni.so`) satisfying Android 15+ 16 KB kernel page size compatibility (`zipalign -c -P 16 -v 4`).

---

## 5. Other Libraries
- **PDFBox-Android** (`com.tom-roush:pdfbox-android`, port of Apache PDFBox): Apache License 2.0. Used to extract text from PDF files.
- **Bouncy Castle** (bundled with PDFBox-Android, used for encrypted PDFs): Bouncy Castle License (MIT-style).
- **AndroidX** (Core, Lifecycle, Activity, Compose UI/Material 3, Room, SQLite, WorkManager, DataStore, Browser, SplashScreen): Apache License 2.0.
- **Kotlin** and **kotlinx.coroutines**: Apache License 2.0.
- **Play Asset Delivery** (`com.google.android.play:asset-delivery-ktx`): Android Software Development Kit License (used only to install the optional image search pack).

---

## 6. Apache License, Version 2.0 (Full Text for Apache-licensed components)

```
                              Apache License
                        Version 2.0, January 2004
                     http://www.apache.org/licenses/

TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION

1. Definitions.
"License" shall mean the terms and conditions for use, reproduction, and distribution as defined by Sections 1 through 9 of this document.
"Licensor" shall mean the copyright owner or entity authorized by the copyright owner that is granting the License.
"Legal Entity" shall mean the union of the acting entity and all other entities that control, are controlled by, or are under common control with that entity.
"You" (or "Your") shall mean an individual or Legal Entity exercising permissions granted by this License.
"Source" form shall mean the preferred form for making modifications, including but not limited to software source code, documentation source, and configuration files.
"Object" form shall mean any form resulting from mechanical transformation or translation of a Source form, including but not limited to compiled object code, generated documentation, and conversions to other media types.
"Work" shall mean the work of authorship, whether in Source or Object form, made available under the License.
"Derivative Works" shall mean any work, whether in Source or Object form, that is based on (or derived from) the Work.

2. Grant of Copyright License.
Subject to the terms and conditions of this License, each Contributor hereby grants to You a perpetual, worldwide, non-exclusive, no-charge, royalty-free, irrevocable copyright license to use, reproduce, prepare Derivative Works of, publicly display, publicly perform, sublicense, and distribute the Work and such Derivative Works in Source or Object form.

3. Grant of Patent License.
Subject to the terms and conditions of this License, each Contributor hereby grants to You a perpetual, worldwide, non-exclusive, no-charge, royalty-free, irrevocable patent license to make, have made, use, offer to sell, sell, import, and otherwise transfer the Work.

4. Redistribution.
You may reproduce and distribute copies of the Work or Derivative Works thereof in any medium, with or without modifications, and in Source or Object form, provided that You meet the following conditions:
(a) You must give any other recipients of the Work or Derivative Works a copy of this License; and
(b) You must cause any modified files to carry prominent notices stating that You changed the files; and
(c) You must retain, in the Source form of any Derivative Works that You distribute, all copyright, patent, trademark, and attribution notices from the Source form of the Work.

5. Submission of Contributions.
Unless You explicitly state otherwise, any Contribution intentionally submitted for inclusion in the Work by You to the Licensor shall be under the terms and conditions of this License, without any additional terms or conditions.

6. Trademarks.
This License does not grant permission to use the trade names, trademarks, service marks, or product names of the Licensor.

7. Disclaimer of Warranty.
Unless required by applicable law or agreed to in writing, Licensor provides the Work on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.

8. Limitation of Liability.
In no event and under no legal theory shall any Contributor be liable to You for damages, including any direct, indirect, special, incidental, or consequential damages of any character arising as a result of this License or out of the use or inability to use the Work.
```
