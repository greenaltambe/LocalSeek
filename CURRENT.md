# Current Implementation State — Hybrid Android Search
## Full System Audit (Code-Level + Failure Analysis)

---

# 0. SYSTEM STATUS

## Maturity Level:
⚠️ Early Prototype (Functional but unstable)

## Observed Issues:
- Poor semantic relevance
- Occasional crashes (likely memory / heavy operations)
- Inconsistent ranking quality
- High latency under scale

---

# 1. DATABASE LAYER

## 1.1 Architecture

- Room Database
- SQLite (Bundled driver → ensures FTS5 support)

### Tables:
1. documents
2. documents_fts (FTS5 virtual table)

---

## 1.2 Schema — documents

| Field | Type | Notes |
|------|------|------|
| id | Long | PK |
| filePath | String | Absolute path |
| title | String | File name |
| body | String | FULL document text ❗ |
| fileType | String | extension |
| modifiedAt | Long | timestamp |
| sizeBytes | Long | file size |
| embedding | FloatArray (BLOB) | 384-d vector |

---

## 1.3 Schema — documents_fts

- Linked via contentEntity
- Indexed fields:
  - title
  - body

---

## 1.4 CRITICAL DESIGN FLAW

### ❗ NO CHUNKING

Current:

1 file → 1 row → 1 embedding


Impact:
- Embedding represents entire document → semantic dilution
- Large files dominate signal
- Query matching becomes noisy

---

## 1.5 Memory Risk

- Embeddings stored per document
- Dense retrieval loads ALL embeddings

Risk:
- O(N) memory usage
- Possible OOM on large datasets

---

# 2. INDEXING PIPELINE

## 2.1 Entry Point

- FileIndexer.runFullIndex()

---

## 2.2 File Discovery

### Sources:
- Documents
- Downloads
- /sdcard fallback paths

### Strategy:
- Walk entire directory tree

---

## 2.3 Incremental Logic

if existingModifiedAt == file.lastModified():
skip


✔ Efficient

---

## 2.4 Parsing

### Component:
DocumentParser

---

### Behavior:

#### Text files:
- Reads first 100KB

#### PDF:
- First 10 pages

---

### Limitations:

- No semantic segmentation
- No metadata extraction
- No language handling

---

## 2.5 Embedding Generation

### Component:
DenseEncoder

---

### Pipeline:

text.take(1500)

Impact:
- Arbitrary cutoff
- Important content lost

---

#### 3. Potential Model Mismatch

- Assumes output shape [1, 384]
- Some MiniLM variants output [1, 256, 384]

Fallback logic exists but unsafe

---

#### 4. No batching

- One inference per file
- Slow indexing

---

## 2.6 Storage

- Each document stored once
- Embedding saved as BLOB

---

# 3. SPARSE RETRIEVAL (BM25)

## 3.1 Implementation

### DAO Query:

SELECT ...
bm25(documents_fts, 1.2, 0.75)
ORDER BY score ASC


---

## 3.2 Query Construction


"term1"* AND "term2"* ...


---

## 3.3 Strengths

✔ Correct BM25 usage  
✔ Prefix matching improves recall  
✔ Fast execution (SQLite optimized)

---

## 3.4 Weaknesses

- No phrase boosting
- No field weighting (title vs body)

---

# 4. DENSE RETRIEVAL

## 4.1 Current Algorithm

query_vec = encode(query)

for doc in all_docs:
score = cosine(query_vec, doc_vec)


---

## 4.2 Data Source

dao.getAllEmbeddings()


Returns:
- ALL documents with embeddings

---

## 4.3 CRITICAL ISSUES

### 4.3.1 O(N) Complexity

- Linear scan
- Not scalable

---

### 4.3.2 Memory Explosion

- Loads ALL embeddings into memory

---

### 4.3.3 Threshold Filtering


if score < 0.3 → discard

Problems:
- Arbitrary threshold
- Sensitive to embedding quality
- Can remove relevant results

---

### 4.3.4 Embedding Quality Unverified

No validation of:
- distribution
- normalization correctness

---

### 4.3.5 Debug Logs Indicate Risk

- Checking for all-zero vectors
- Suggests model inference issues possible

---

# 5. HYBRID RETRIEVAL

## 5.1 Implementation

- Parallel execution:
  - BM25
  - Dense

---

## 5.2 Fusion

### RRF:

score = 1 / (k + rank)
k = 60


✔ Correct implementation

---

## 5.3 Issue

- Garbage in → garbage out

If dense results are poor:
- RRF polluted

---

# 6. RE-RANKING (LAMBDA MART)

## 6.1 Implementation

- ONNX Runtime
- Features:
  - bm25Score
  - denseScore
  - daysSinceModified

---

## 6.2 Training Data


Synthetic random data

---

## 6.3 CRITICAL FAILURE

### ❗ Model is meaningless

Why:
- No real relevance labels
- Random feature-label mapping

Effect:
- Random ranking
- Worse than baseline

---

## 6.4 Additional Issues

- Feature scaling undefined
- No normalization consistency

---

# 7. MMR DIVERSIFICATION

## 7.1 Implementation

✔ Correct

---

## 7.2 Algorithm


MMR = λ * relevance - (1-λ) * similarity
λ = 0.7


---

## 7.3 Strength

- Reduces duplicate results

---

## 7.4 Limitation

- Works on chunk-level — but system is document-level
- Less effective without chunking

---

# 8. UI LAYER

## 8.1 Architecture

- Jetpack Compose
- ViewModel-driven state

---

## 8.2 Behavior

- Debounced search (200ms)
- Displays:
  - title
  - snippet
  - score
  - latency

---

## 8.3 Code Reference

UI rendering and state flow handled cleanly  
:contentReference[oaicite:0]{index=0}

---

## 8.4 Strengths

✔ Clean UX  
✔ Latency visibility  
✔ Stable rendering  

---

# 9. WORK SCHEDULING

## 9.1 WorkManager

- Immediate indexing
- Periodic (6 hours)

---

## 9.2 Constraints

- Battery not low

---

## 9.3 Strength

✔ Production-ready approach

---

# 10. CRASH ANALYSIS (LIKELY ROOT CAUSES)

## 10.1 OOM (Most Probable)

Cause:
- Loading all embeddings
- Large documents

---

## 10.2 TFLite Issues

Cause:
- Input mismatch
- Incorrect tensor expectations

---

## 10.3 Long Blocking Operations

Cause:
- File parsing
- Embedding generation

---

# 11. QUALITY FAILURE ANALYSIS

## Why results are poor:

### 1. No Chunking
→ embeddings meaningless

---

### 2. Synthetic LambdaMART
→ destroys ranking

---

### 3. Dense Retrieval Noise
→ weak signal

---

### 4. Threshold Filtering
→ removes valid results

---

### 5. Token Truncation
→ context loss

---

# 12. PERFORMANCE BOTTLENECKS

| Component | Issue |
|----------|------|
| Dense Retrieval | O(N) |
| Indexing | No batching |
| Parsing | Blocking |
| Memory | Full load |

---

# 13. WHAT WORKS WELL

✔ BM25 pipeline  
✔ RRF fusion  
✔ MMR diversification  
✔ Database design  
✔ WorkManager indexing  
✔ UI system  

---

# 14. WHAT IS FUNDAMENTALLY BROKEN

❌ Chunking missing  
❌ LambdaMART invalid  
❌ Dense retrieval inefficient  
❌ Embedding pipeline questionable  

---

# 15. GAP VS TARGET SYSTEM

| Component | Status |
|----------|-------|
| Chunking | ❌ Missing |
| ANN (FAISS) | ❌ Missing |
| Proper reranking | ❌ Broken |
| Query processing | ⚠ Minimal |
| Aggregation | ❌ Missing |

---

# 16. IMMEDIATE FIX PRIORITY

### P0 (BLOCKING)

1. Implement chunking
2. Remove LambdaMART
3. Fix dense retrieval memory usage

---

### P1

4. Add better scoring fusion
5. Validate embeddings

---

### P2

6. Introduce ANN (FAISS optional)

---

# FINAL SYSTEM DIAGNOSIS

This system has:

✔ Correct architecture direction  
❌ Incorrect execution in key areas  

Biggest root cause:
> Embedding pipeline + ranking layer are fundamentally flawed

# 17. ML MODEL GENERATION PIPELINE (CRITICAL — NOW FULLY DOCUMENTED)

This system uses **two offline-generated ML artifacts**:

1. MiniLM TFLite model (semantic embeddings)
2. LambdaMART ONNX model (reranking)

---

# 17.1 MiniLM Embedding Model (TFLite)

## Source Model

- HuggingFace:
  sentence-transformers/all-MiniLM-L6-v2

---

## Conversion Pipeline

### Step 1: Load Model
- TFAutoModel
- AutoTokenizer

---

### Step 2: Custom Wrapper

Purpose:
- Convert token-level embeddings → sentence embedding

#### Key operations:

token_embeddings = outputs.last_hidden_state


Mean pooling:

sum_embeddings = Σ(token_embeddings * attention_mask)
sentence_embedding = sum_embeddings / valid_tokens


Normalization:

embedding = L2_normalize(sentence_embedding)


---

## Step 3: TFLite Conversion

### FP32 Model

converter = tf.lite.TFLiteConverter.from_concrete_functions(...)

---

### INT8 Model (Dynamic Range Quantization)


converter.optimizations = [tf.lite.Optimize.DEFAULT]


---

## ⚠️ CRITICAL ISSUES

### 1. NOT TRUE INT8 INFERENCE
Fact:
- This is **dynamic range quantization**, not full INT8

Meaning:
- Weights → INT8
- Activations → FP32

Impact:
- Limited speedup
- Larger memory than expected

---

### 2. SELECT_TF_OPS USED

tf.lite.OpsSet.SELECT_TF_OPS


Impact:
- Requires Flex delegate
- Increases binary size
- Slower inference on mobile

---

### 3. TOKENIZER MISMATCH RISK

Saved:
vocab.txt


But:
- HuggingFace tokenizer ≠ your custom tokenizer fully

Risk:
- Token mismatch
- Embedding degradation

---

### 4. INPUT SIGNATURE ASSUMPTION

Model expects:

input_ids: [1, 256]
attention_mask: [1, 256]


Your Android code:
- Sometimes sends 3 inputs (token_type_ids)

Fallback logic exists → fragile

---

### 5. POOLING CORRECTNESS

✔ Mean pooling implemented correctly  
✔ L2 normalization applied (important for cosine)

---

## FINAL ASSESSMENT

| Component | Status |
|----------|-------|
| Model choice | ✅ Good |
| Pooling | ✅ Correct |
| Quantization | ⚠ Partial |
| Tokenization | ⚠ Risk |
| TFLite ops | ⚠ Heavy |

---

# 17.2 LambdaMART Model (ONNX)

## Training Pipeline

### Data Generation

features = random
labels = heuristic rules

Features:
| Index | Meaning |
|------|--------|
| 0 | BM25 score |
| 1 | Dense score |
| 2 | Recency |

---

### Label Generation

Artificial logic:
if bm25 < -8 and dense > 0.7 → label 2
elif bm25 < -5 or dense > 0.5 → label 1
...


---

### Training


LGBMRanker(objective="lambdarank")


Grouped by:

query_groups = [100, 100, ...]




---

### Conversion


onnxmltools.convert_lightgbm(...)




Output:
- reranker.onnx

---

## ❗ CRITICAL FAILURE ANALYSIS

### 1. SYNTHETIC DATA

Fact:
- No real query-document relevance

Impact:
- Model learns **fake patterns**

---

### 2. FEATURE DISTRIBUTION MISMATCH

Training:
- random uniform distribution

Inference:
- real BM25 + cosine

→ Distribution shift

---

### 3. LABEL NOISE

Labels derived from:
- arbitrary thresholds

→ Not aligned with user intent

---

### 4. NO NORMALIZATION CONSISTENCY

Training:
- raw features

Inference:
- partially normalized scores

→ Model input mismatch

---

### 5. SMALL FEATURE SET

Only 3 features:
- insufficient for ranking

---

## RESULT

The model behaves as:


random nonlinear scoring function



NOT a true ranking model

---

## FINAL ASSESSMENT

| Component | Status |
|----------|-------|
| Framework | ✅ Correct |
| Conversion | ✅ Correct |
| Training data | ❌ Invalid |
| Model quality | ❌ Broken |
| Usefulness | ❌ Harmful |

---

# 17.3 SYSTEM-LEVEL IMPACT

## MiniLM Issues Cause:
- Slight semantic degradation
- Possible inference overhead

---

## LambdaMART Issues Cause:
- Completely unstable ranking
- Overrides correct RRF ordering

---

## Combined Effect

Even if retrieval is good:


LambdaMART → destroys final ranking


---

# 17.4 REQUIRED ACTION

### Immediate (P0)

- REMOVE LambdaMART entirely

---

### Medium (P1)

- Validate MiniLM embeddings:
  - Check cosine distribution
  - Ensure non-zero vectors

---

### Advanced (P2)

- Rebuild MiniLM without SELECT_TF_OPS
- OR use pre-optimized mobile model

---

# FINAL CONCLUSION

The ML pipeline exists, but:

- MiniLM → usable with caveats  
- LambdaMART → fundamentally invalid  

This is a **key reason for poor results**.

# 18. SYSTEM CHANGELOG

## Phase 0 - Cleanup (2026-04-19)

### Removed
- ❌ LambdaMART reranker (synthetic training data)
- ❌ ONNX Runtime dependency
- ❌ `app/src/main/assets/reranker.onnx`

### Disabled
- ⏸ Dense retrieval in live search path (`ENABLE_DENSE = false`)

### Added
- ✅ Performance logging framework (`PerformanceLogger`)
- ✅ Weighted fusion placeholder (`0.6 * BM25 + 0.4 * Dense`) for when dense is re-enabled
- ✅ Debug UI toggle for score visibility
- ✅ Query token + top-5 result validation logs

### Baseline Performance
- Query latency: TBD (capture from `[PERF]` logs on device)
- Memory: TBD MB (capture from `[PERF]` logs on device)
- Index time: TBD for 1000 files

### Build Verification
- ✅ `:app:assembleDebug` passes after removing LambdaMART/ONNX

---

## Phase 1 - Chunking (2026-04-19)

### Database Changes
- ✅ New table: `document_chunks` (holds text segments)
- ✅ New table: `chunks_fts` (FTS5 for BM25)
- ✅ Migration: v10 -> v11 (creates chunk tables and backfills legacy body text into chunks)

### Chunking Strategy
- **Algorithm:** Sliding window
- **Parameters:**
  - chunk_size = 150 tokens
  - overlap = 40 tokens
- **Rationale:** Preserves local context while improving retrieval granularity

### Indexing Pipeline Changes
- Documents table now stores metadata + optional file embedding (`body` written as empty)
- Chunks table stores searchable content
- 1 file -> N chunks, with per-file chunk counts logged during indexing

### Search Changes
- BM25 now operates on chunks (`chunks_fts`)
- Results are aggregated back to file-level
- Snippet = top 3 relevant chunks per file

### Validation Run (Host)
- ✅ Build: `:app:assembleDebug` succeeded
- ✅ Unit tests: `:app:testDebugUnitTest` succeeded
- ✅ New validation tests passed (`Phase1ValidationTest`): 2/2
  - `textChunker_generatesOverlappingChunksWithOffsets`
  - `chunkAggregator_groupsByParentFile_andKeepsTopThreeSnippets`

### Performance Impact
- Index time: TBD (requires device indexing run with real files)
- Storage: TBD (requires DB size comparison before/after reindex)
- Search quality: TBD (requires query relevance evaluation set)

### Pending Device Verification
- Run indexing on sample set (10 PDFs, 5 text files)
- Capture per-file chunk counts from `FileIndexer` logs (`Chunked <file>: <N> chunks`)
- Capture query/perf logs (`[PERF]`, `[VALIDATION]`) during BM25 chunk search

### Database Migration Fix (Hotfix - 2026-04-20)

**Issue:** App crash risk with `IllegalStateException: migration from 1 to 2 was required but not found` on legacy installs.

**Root Cause:** Chunk tables were introduced in earlier phases, while some older DB states can still request a `1 -> 2` path.

**Fix Applied:**

1. **Updated AppDatabase migration graph:**
   - Added legacy migration: `Migration1To2`
   - Kept existing migrations: `Migration10To11`, `Migration11To12`
   - Ensured `chunkDao()` is exposed in `AppDatabase`

2. **Migration SQL includes:**
   - Create `document_chunks` table with foreign key constraint
   - Create FTS5 virtual table `chunks_fts`
   - Add FTS sync triggers (insert/update/delete)
   - Create index on `parentFileId`

3. **Temporary development safeguard:**
   - Added `.fallbackToDestructiveMigration()` in database builder

**Migration Strategy:**
- **New installs:** create latest schema (`v12`) fresh
- **Legacy upgrades:** explicit `1 -> 2` supported; newer migrations remain registered
- **Fallback:** destructive reset if an unsupported migration hop is encountered

**Impact:**
- ✅ Prevents migration-not-found crash for known legacy case
- ✅ Keeps chunking schema initialization consistent
- ⚠️ `.fallbackToDestructiveMigration()` is development-only and should be removed before production

**Status:** ✅ Fixed

---

## Phase 2 - Optimized Embedding Model (2026-04-19)

### Model Changes
- ❌ Removed: legacy model reference in code (`minilm_int8.tflite`)
- ✅ Added/used: `minilm_optimized.tflite`
- ✅ Updated tokenizer vocabulary usage (`vocab.txt`) for 128-token inputs

### Encoder Runtime Changes
- ✅ DenseEncoder now loads `minilm_optimized.tflite`
- ✅ NNAPI enabled (`setUseNNAPI(true)`)
- ✅ Threads set to 4
- ✅ Max sequence length reduced to 128 tokens
- ✅ Output embedding L2-normalized in encoder

### Indexing Pipeline Changes
- ✅ Added `encodeBatch(texts, batchSize = 8)` in `DenseEncoder`
- ✅ `FileIndexer` now batch-encodes chunk text embeddings
- ✅ Embedded chunks are inserted in one DB write (`chunkDao.insertAll(...)`)

### Validation Status
- ✅ Host build passed (`:app:assembleDebug`)
- ✅ Host unit tests passed (`:app:testDebugUnitTest`)
- ⏳ Device validation pending for:
  - per-chunk inference latency target (<50ms)
  - NNAPI backend confirmation from runtime logs on target device
  - embedding norm sampling on indexed chunk set

### Performance Impact
- Indexing speedup: TBD (requires before/after timed device runs)
- Memory reduction: TBD (requires runtime memory profiling)
- Battery impact: TBD (requires on-device power sampling)

---

## Phase 3 - Efficient Dense Retrieval (2026-04-19)

### Architecture Change
- ❌ Removed: all-embeddings-in-memory dense scoring path
- ✅ Added: streaming dense retrieval over paginated chunk embeddings

### Implementation
- **Page size:** 500 chunks (default)
- **Threshold:** cosine > 0.3
- **Memory profile:** bounded by page size + top-K heap (no full embedding table load)
- Dense retrieval flag re-enabled in search flow (`ENABLE_DENSE = true`)

### Verification Status
- ✅ Host build/test passes after streaming retriever refactor
- ⏳ Device measurements pending for:
  - memory ceiling during search (<150MB target)
  - latency target (<200ms at 10k chunks)
  - qualitative semantic relevance spot checks

### Future Work
- [ ] FAISS ANN index (10x speedup potential)
- [ ] Approximate top-K / early termination
- [ ] Device-level profiling dashboard for dense page scan timing

---

## Phase 4 - Fusion & Ranking (2026-04-19)

### Replaced Components
- ❌ Simplistic weighted fusion fallback only
- ❌ Legacy RRF-only path as primary strategy
- ✅ Weighted multi-signal fusion with normalization (`FusionRanker`)

### Scoring Signals
| Signal | Weight | Normalization |
|--------|--------|---------------|
| BM25 | 0.45 | MinMax |
| Dense | 0.35 | MinMax |
| Recency | 0.10 | Exponential decay |
| Title match | 0.10 | Boolean boost |

### Diversification
- **Algorithm:** MMR (`lambda = 0.7`)
- **Purpose:** Reduce redundant semantically similar results
- **Behavior:** Re-ranks top fused candidates with embedding cosine penalty when vectors are available

### Implementation Notes
- Added `ScoreNormalizer.minMaxNorm` and `ScoreNormalizer.standardize`
- Added `FusionRanker.rank(query, candidates)` and `FusionRanker.diversify(...)`
- `SearchViewModel` now builds unified BM25+dense candidates and applies fusion + MMR
- Dense results now include best chunk embedding for diversification

### Validation Status
- ✅ Host build passed (`:app:assembleDebug`)
- ✅ Host unit tests passed (`:app:testDebugUnitTest`)
- ✅ Added Phase 4 tests (`Phase4FusionTest`) for normalization and title boost ranking

### Performance
- Ranking time: TBD (needs on-device micro-benchmark)
- Quality delta: TBD (needs user relevance eval / nDCG pass)

---

## Phase 5 - Aggregation & UI (2026-04-19)

### Result Structure
- **Changed:** Chunk-level list -> File-level list in UI state
- **Aggregation:** Best score per file via `ResultAggregator.aggregateToFiles(...)`
- **Snippets:** Top 3 chunk previews per file
- **Highlighting:** Query terms wrapped with `**term**` markers in snippet text

### UI Improvements
- ✅ Dedicated file card component: `FileResultCard`
- ✅ File type badge on each result card
- ✅ Metadata footer (modified date + score or file size)
- ✅ Multi-snippet rendering per file result

### Filters
- ✅ File type filter implemented (All / PDF / TXT / MD chips)
- ✅ Date-range filter API implemented in `SearchViewModel` (`onDateRangeFilterChanged`)
- ⏳ Date-range chip controls in UI not added yet

### Validation Status
- ✅ Host build passed (`:app:assembleDebug`)
- ✅ Host unit tests passed (`:app:testDebugUnitTest`)
- ✅ Existing Phase 1/4 tests updated and passing after data-model changes

### UX Impact
- Clarity: improved with file-level grouping and richer context snippets
- Usability: easier document selection due to badges, metadata, and file-type chips

---

## Phase 6 - Performance (2026-04-20)

### Optimizations Applied
- ✅ Parallel BM25 + Dense execution in `SearchViewModel` using coroutines (`async`)
- ✅ Database indexes added for `document_chunks(parentFileId)` and `document_chunks(embedding)`
- ✅ LRU query cache (`QueryCache`, max 50 entries)
- ✅ Early termination hook for dense retrieval (`DenseRetriever.shouldSkipDense(...)`)

### Instrumentation
- ✅ Added stage timers (`measureSuspendTime`) with `[PERF]` log labels
- ✅ Cache-hit fast path returns pre-aggregated file results with minimal UI latency

### Latency Breakdown
| Stage | Time |
|-------|------|
| BM25 | TBD (device profile) |
| Dense | TBD (device profile) |
| Fusion | TBD (device profile) |
| **Total** | **TBD** |

### Cache Performance
- Hit rate: TBD (requires session log sampling)
- Avg cached response: currently hardcoded to ~2ms UI latency marker

### Scalability Tested
- Host build/test: ✅ pass
- Device-scale profiling target (50k chunks): ⏳ pending

---

## Phase 7 - Testing & Validation (2026-04-20)

### Test Coverage
- Unit tests added for chunking behavior (`ChunkerTest`)
- Unit tests added for evaluation metrics (`MapEvaluationSuiteTest`)
- Instrumentation integration test scaffold added (`SearchE2ETest`)
- Memory stability regression test added in instrumentation (`SearchE2ETest.noMemoryLeakOnRepeatedSearches`)

### Test Results
| Metric | Result |
|--------|--------|
| Unit pass rate | 100% on host (`:app:testDebugUnitTest`) |
| AndroidTest pass rate | TBD (requires connected device/emulator run) |
| Avg latency | TBD (device runtime measurement required) |
| Memory stable | TBD (device runtime measurement required) |
| MAP score | >0.7 in fixture-based unit test |

### Quality Metrics
- Precision@5: TBD (requires labeled device-backed retrieval run)
- Recall@10: TBD (requires labeled device-backed retrieval run)
- User satisfaction: TBD (needs real users)

---

## Phase 8A - Production Query Processing (2026-04-20)

### Architecture
Raw Query -> Smart Normalization -> Tokenization -> Entity Extraction -> Query Expansion -> Intent Classification -> Enhanced Query

### Implemented Components
- ✅ `search/query/QueryNormalizer.kt` (rule-based normalization, URL/email stripping, contraction expansion)
- ✅ `search/query/SmartTokenizer.kt` (vocab-backed tokenization + key-term extraction)
- ✅ `search/query/EntityExtractor.kt` (regex + dictionary entities)
- ✅ `search/query/QueryExpander.kt` (domain expansion, synonym expansion, filters)
- ✅ `search/query/IntentClassifier.kt` (lightweight rule-based intents)
- ✅ `search/query/QueryProcessor.kt` (orchestrates end-to-end query processing)

### Search Integration
- ✅ `SearchViewModel` now processes raw input through `QueryProcessor`
- ✅ BM25 retrieval receives expanded BM25-safe query terms
- ✅ Dense retrieval receives expanded dense query string
- ✅ Entity-derived filters (file type/date) are mapped into existing filter pipeline

### Test Coverage Added
- ✅ Unit: `QueryProcessingCoreTest` (normalization, entity extraction, intent classification)
- ✅ Instrumentation: `QueryProcessorInstrumentationTest` (intent/entity/latency assertion scaffold)

### Validation Status
- ✅ Host build + unit tests pass
- ⏳ Instrumentation runtime assertions pending connected device execution
- ⚠️ `<50ms` target enforced in instrumentation test; verify on target device class

---

## Phase 8B-Revised - Kotlin LSH ANN (2026-04-20)

### Objective
- Replace FAISS-native dependency path with pure Kotlin ANN while keeping dense retrieval scalable

### Architecture Changes
- ❌ Removed FAISS-first runtime path from dense retrieval orchestration
- ✅ Added `search/vector/LshIndexManager.kt` (random-projection LSH)
- ✅ `DenseRetriever` now uses LSH ANN as primary search path with brute-force fallback
- ✅ `FileIndexer` now logs/rebuilds ANN index (LSH) after full indexing

### Build/Dependency Changes
- ✅ Removed JitPack repository from `settings.gradle.kts`
- ✅ Kept runtime dependency surface lean (no JNI/NDK/FAISS requirement)

### LSH Configuration
- Tables: 10
- Hash bits: 12
- Projection dim: 64
- Embedding dim: 384
- Persistence: `filesDir/lsh_index.bin`

### Testing
- ✅ Updated instrumentation test (`FaissPerformanceTest`) to validate LSH latency target (<300ms)
- ⏳ Device profiling still required for measured p50/p95 values at 10k+ chunks

---

## Phase 8C-Part2 - Cross-Encoder Reranking (2026-04-20)

### Objective
- Improve top-result precision by reranking fused candidates with a cross-encoder model

### Implemented Components
- ✅ Added `CrossEncoder` runtime wrapper in `ml/DenseEncoder.kt`
  - Optional asset load (`models/cross_encoder.tflite`)
  - Graceful fallback when model is absent
- ✅ Added `retrieval/CrossEncoderReranker.kt`
  - Rerank cap: top-100
  - Return cap: top-20
  - Timeout guard: 500ms
  - Score cache for repeated query-document pairs

### Search Pipeline Integration
- ✅ `SearchViewModel` now runs reranking after fusion/MMR stage
- ✅ Rerank latency is included in performance logging via fusion stage accounting
- ✅ If cross-encoder is unavailable or times out, fused ranking is returned safely

### Validation Status
- ✅ Code path integrated with fallback safety
- ⏳ Final quality metrics (nDCG/MRR lift) pending labeled evaluation run


---

## Phase 8C-Upgrade - Adaptive LSH Enhancements (2026-04-20)

### Objective
- Upgrade LSH from fixed parameters to adaptive behavior without major refactoring

### Implemented Features
- ✅ Adaptive configuration model (`LshConfig`) added in `search/vector/LshIndexManager.kt`
  - Dataset-size adaptive table/hash/projection sizing
  - Candidate budget control via `searchCandidates`
  - Memory mode switch: `IN_MEMORY` / `STREAMING`
- ✅ Battery-aware behavior (`BatteryMonitor` + battery-adjusted runtime config)
  - Reduces active tables/candidates under low battery
  - Can force streaming mode for power saving
- ✅ Memory-efficient streaming mode
  - New DAO API: `ChunkDao.getEmbedding(chunkId)`
  - On-demand vector fetch for candidate scoring
  - `DenseRetriever` now passes `chunkDao` into LSH search

### LSH Manager Updates
- Dynamic hash tables and projection matrices sized from active config
- `buildIndex(...)` now accepts optional custom config and logs selected profile
- Search now applies runtime battery-aware limits for table count and candidate cap
- Index persistence includes adaptive config metadata alongside vectors
- Added guard to avoid clobbering persisted index when incremental save has no cached vectors

### Logging
- ✅ Adaptive config logs include dataset size + battery + selected parameters
- ✅ Build summary logs include mode, build time, avg bucket size, and index size
- ✅ Search logs include candidate limiting and active mode

### Validation Status
- ✅ Host compile passes after adaptive changes
- ✅ Host unit tests pass after adaptive changes
- ⏳ Device scenario verification pending:
  - small dataset profile (<10k)
  - large dataset with low battery (streaming)
  - high battery quality mode

---

## Phase 9 - Professional UI/UX Polish (2026-04-21)

### Design System
- ✅ Expanded Material 3 color system in `app/src/main/java/com/augt/localseek/ui/theme/Theme.kt` (full light/dark token mapping)
- ✅ Expanded typography scale in `app/src/main/java/com/augt/localseek/ui/theme/Type.kt` to full display/headline/title/body/label set

### Search Screen UX
- ✅ Reworked `app/src/main/java/com/augt/localseek/ui/SearchScreen.kt` to explicit state-driven rendering:
  - idle state with suggestion chips
  - loading state with stage/progress indicators
  - empty and error states
  - success list with result count and latency chip
- ✅ Added applied-filter chip row with remove actions
- ✅ Added top app bar action for score visibility toggle

### Result Card Polish
- ✅ Upgraded `app/src/main/java/com/augt/localseek/ui/SearchResultCard.kt`:
  - richer file-type iconography and color coding
  - snippet expansion (show more)
  - metadata row (date + size)
  - highlighted snippet rendering for `**term**` tokens
  - optional relevance chip when score is high

### ViewModel / State Integration
- ✅ `SearchUiState` extended with:
  - `loadingStage`
  - `loadingProgress`
  - `errorMessage`
- ✅ `SearchViewModel` updated for UI compatibility methods used by the polished screen:
  - `updateQuery(...)`
  - `search()`
  - `removeFilter(...)`
  - `openFile(...)` (logging stub)
- ✅ Search flow now updates loading stage/progress checkpoints throughout query process

### Validation Status
- ✅ Kotlin compile verified: `:app:compileDebugKotlin` (success)
- ⚠️ Minor deprecation warnings remain in `SearchResultCard.kt` for `Icons.Filled.Article` / `Icons.Filled.InsertDriveFile` on current Compose API level
- ⏳ On-device UX validation pending (dark mode pass, accessibility contrast checks, and interaction polish)

---

## Phase 9 - Part 2 Settings, Dashboard, Branding (2026-04-21)

### Settings System
- ✅ Added `app/src/main/java/com/augt/localseek/ui/settings/SettingsScreen.kt` with advanced toggles/sliders/selectors
- ✅ Added persistent settings model and state:
  - `app/src/main/java/com/augt/localseek/ui/settings/SettingsModels.kt`
  - `app/src/main/java/com/augt/localseek/ui/settings/SettingsRepository.kt` (DataStore Preferences)
  - `app/src/main/java/com/augt/localseek/ui/settings/SettingsViewModel.kt`
- ✅ Added index health summary card (files/chunks/index size/last update)

### Performance Dashboard
- ✅ Added metrics UI:
  - `app/src/main/java/com/augt/localseek/ui/performance/PerformanceDashboard.kt`
  - `app/src/main/java/com/augt/localseek/ui/performance/PerformanceModels.kt`
  - `app/src/main/java/com/augt/localseek/ui/performance/PerformanceViewModel.kt`
- ✅ Added in-memory performance history feed:
  - `app/src/main/java/com/augt/localseek/logging/PerformanceHistoryStore.kt`
  - `app/src/main/java/com/augt/localseek/logging/PerformanceLogger.kt` now pushes query metrics to store
- ✅ Dashboard now shows latency cards, breakdown, quality stats, LSH config summary, and recent query list

### Navigation + Error Handling
- ✅ Updated `app/src/main/java/com/augt/localseek/SearchApp.kt` with route switching (`SEARCH`, `SETTINGS`, `PERFORMANCE`)
- ✅ Added `app/src/main/java/com/augt/localseek/ui/common/ErrorBoundary.kt` and wrapped root app content

### Branding and Startup UX
- ✅ Added splash screen dependency and DataStore/charts dependencies in `app/build.gradle.kts`
- ✅ Updated app icon foreground/background drawables:
  - `app/src/main/res/drawable/ic_launcher_foreground.xml`
  - `app/src/main/res/drawable/ic_launcher_background.xml`
- ✅ Added launcher/splash color: `app/src/main/res/values/colors.xml` (`ic_launcher_background`)
- ✅ Splash theme finalized in `app/src/main/res/values/themes.xml`
- ✅ `AndroidManifest.xml` now applies `Theme.LocalSeek.Splash` to `MainActivity`
- ✅ `MainActivity` installs splash via `installSplashScreen()` before `super.onCreate(...)`

### Validation Status
- ✅ Kotlin compile verified after Phase 9 Part 2 changes: `:app:compileDebugKotlin`
- ⚠️ Remaining warnings are Compose deprecations in settings/performance icons and `Divider` usage; functional behavior unaffected
- ⏳ Device validation pending for splash rendering timing and settings persistence across app restarts

---

## Phase 10 - LLM Scaffold Continuation (2026-04-22)

### Context
- Continued unfinished on-device LLM integration by stabilizing core interface and adding a lightweight fallback implementation.

### Implemented
- ✅ Updated `app/src/main/java/com/augt/localseek/ml/llm/OnDeviceLLM.kt`
  - Added safe defaults in `LLMResponse` (`answer = ""`, `latencyMs = 0L`)
  - Added `LLMResponse.failure(...)` helper for consistent error construction
- ✅ Added `app/src/main/java/com/augt/localseek/ml/llm/ExtractiveOnDeviceLLM.kt`
  - Pure Kotlin extractive fallback answer generator (no model dependency)
  - Ranks chunks by query-term overlap and returns top supporting snippets
- ✅ Added unit tests in `app/src/test/java/com/augt/localseek/ml/llm/ExtractiveOnDeviceLLMTest.kt`
  - Validates success path with relevant chunks
  - Validates error path when context is empty

### Validation
- ✅ Host compile check passes: `:app:compileDebugKotlin`
- ⏳ Full unit test run after this change pending (recommended before commit)

---
