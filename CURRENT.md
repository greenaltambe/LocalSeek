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

