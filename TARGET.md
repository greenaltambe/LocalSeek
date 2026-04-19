# Hybrid On-Device Semantic Search Engine (Android)
## Full System Specification (Implementation + Research Grade)

---

# 0. PRIMARY OBJECTIVE

Design and implement a **fully on-device hybrid search engine** for Android that:

- Retrieves relevant local files using **both lexical (BM25) and semantic (embedding) signals**
- Operates under **strict mobile constraints (CPU, memory, battery)**
- Achieves **sub-300ms query latency**
- Scales to **10k–100k+ document chunks**
- Requires **zero network connectivity**

---

# 1. CORE SYSTEM PRINCIPLES

### 1.1 Hybrid Retrieval is Mandatory
- BM25 handles **exact matches**
- Dense retrieval handles **semantic similarity**
- Neither alone is sufficient

---

### 1.2 Chunk-Level Indexing (NON-NEGOTIABLE)
- Each document is split into **overlapping chunks**
- Retrieval operates on chunks, not full documents

Reason:
- Embeddings degrade on long text
- Improves recall + precision

---

### 1.3 Late Fusion > Early Fusion
- Sparse and dense pipelines run independently
- Combine only at ranking stage (RRF)

---

### 1.4 Deterministic > Learned (for mobile)
- Prefer:
  - RRF
  - Weighted scoring
- Avoid:
  - Heavy neural rerankers unless justified

---

### 1.5 Memory-Aware Design
- Never load full dataset into RAM
- Use streaming / batching / ANN

---

# 2. END-TO-END PIPELINE

---

# STAGE 0: FILE INDEXING PIPELINE

## 0.1 File Discovery

### Mechanism:
- WorkManager (periodic)
- FileObserver (optional real-time)

### Scan Roots:
- /Download
- /Documents
- User-defined directories

### Strategy:
- Maintain index of:
  - filePath → lastModified
- Skip unchanged files

---

## 0.2 File Parsing

### Supported Formats:
| Type | Method |
|------|--------|
| PDF | PDFBox (first N pages) |
| TXT/MD | Direct read |
| Code files | Plain text |
| JSON/XML | Raw text |

### Constraints:
- Max read size: ~100KB
- Strip binary/garbage

---

## 0.3 TEXT CHUNKING (CRITICAL)

### Parameters:
- chunk_size = 120–200 tokens
- overlap = 30–50 tokens

### Algorithm:
1. Tokenize by whitespace
2. Slide window
3. Generate overlapping segments

### Output:
Each chunk becomes:


Chunk {
chunk_id
parent_file_path
chunk_index
text
}


---

## 0.4 EMBEDDING GENERATION

### Model:
- MiniLM-L6-v2 (TFLite INT8)

### Input:
- Max tokens: 256

### Processing:
1. Tokenize
2. Forward pass
3. Mean pooling
4. L2 normalization

### Output:
- 384-dimensional float vector

---

## 0.5 STORAGE DESIGN

### Table: documents

| Field | Description |
|------|------------|
| id | primary key |
| filePath | absolute path |
| chunkIndex | chunk position |
| title | filename |
| body | chunk text |
| fileType | extension |
| modifiedAt | timestamp |
| embedding | BLOB |

---

### Table: documents_fts (FTS5)

- Indexed fields:
  - title
  - body

---

## 0.6 OPTIONAL: VECTOR INDEX

### Option A (MVP)
- No ANN
- Use brute-force

### Option B (Advanced)
- FAISS via JNI

#### Index Types:
| Index | Use Case |
|------|---------|
| IVF-PQ | memory efficient |
| HNSW | high accuracy |

---

# STAGE 1: QUERY PROCESSING

## Input:
Raw user string

---

## Processing Steps:

### 1. Normalize
- lowercase
- trim whitespace

### 2. Token cleanup
- remove punctuation
- remove stopwords (optional)

### 3. Expand (optional v2)
- synonyms (WordNet)
- typo correction

---

## Output:
- cleaned query string
- tokens[]

---

# STAGE 2: SPARSE RETRIEVAL (BM25)

## Engine:
SQLite FTS5

---

## Query Construction:

Example:

"kotlin" AND "search"* AND "engine"*


---

## Ranking:

bm25(table, k1=1.2, b=0.75)


---

## Output:
Top-K (~50–100)

---

# STAGE 3: DENSE RETRIEVAL

## Step 1: Encode Query

- Use same MiniLM model

---

## Step 2: Retrieve Candidates

### Option A (Brute Force)

for each document:
score = cosine(query_vec, doc_vec)


### Option B (ANN)

faiss_index.search(query_vec, top_k)


---

## Step 3: Filtering

- Discard if:

cosine < 0.3


---

## Output:
Top-K (~50)

---

# STAGE 4: FUSION (RRF)

## Formula:

For each document:


score(d) = Σ (1 / (k + rank_i(d)))


Where:
- k = 60
- i = retrieval source (BM25, dense)

---

## Properties:
- No tuning required
- Robust to noise
- Handles missing results

---

# STAGE 5: RE-RANKING

## ❗ IMPORTANT DESIGN DECISION

### DO NOT USE BADLY TRAINED LAMBDAMART

---

## OPTION A (RECOMMENDED MVP)

### Weighted Scoring


final_score =
0.5 * bm25_norm +
0.4 * dense_score +
0.1 * recency_score


---

## Feature Definitions:

| Feature | Description |
|--------|------------|
| bm25_norm | normalized BM25 |
| dense_score | cosine similarity |
| recency_score | exp decay |

---

## OPTION B (ADVANCED)

### LambdaMART

#### Training Data:
- Real query logs OR
- Weak supervision

#### Features:
- bm25
- cosine
- recency
- fileType
- title match

---

# STAGE 6: DIVERSIFICATION (MMR)

## Formula:


MMR = λ * relevance - (1 - λ) * similarity


Where:
- λ = 0.7

---

## Purpose:
- Avoid duplicate chunks
- Improve coverage

---

# STAGE 7: RESULT AGGREGATION

## Problem:
Chunks ≠ files

---

## Solution:

Group by filePath:
- Take max score
- Merge snippets

---

## Output:
File-level ranked results

---

# STAGE 8: RESULT DISPLAY

## Features:
- Title
- Snippet
- File type
- Score
- Last modified

---

# STAGE 9: OPTIONAL LLM LAYER (OFFLINE)

## Goal:
Answer queries using retrieved content

---

## Options:

| Model | Notes |
|------|------|
| TinyLlama | small |
| Phi-2 | better reasoning |
| Distil models | lighter |

---

## Pipeline:


Top-K chunks → context → LLM → answer


---

# 3. PERFORMANCE TARGETS

| Metric | Target |
|------|-------|
| Query latency | <300ms |
| Index time | <1 min / 1000 files |
| Memory | <200MB |
| Battery | minimal |

---

# 4. FAILURE MODES TO AVOID

- No chunking → BAD embeddings
- Synthetic ML models → RANDOM ranking
- Full DB scan → SLOW
- No normalization → unstable scores

---

# 5. RESEARCH CONTRIBUTION ANGLE

This system contributes:

1. On-device hybrid retrieval
2. INT8 semantic embeddings
3. Efficient fusion strategies
4. Mobile-first IR architecture
5. Optional ANN on Android

---

# 6. DEVELOPMENT PRIORITY

### P0 (Critical)
- Chunking
- Remove LambdaMART
- Fix dense retrieval

### P1
- Better fusion scoring
- File-level aggregation

### P2
- FAISS integration

### P3
- LLM summarization

---

# FINAL SYSTEM SUMMARY

This is a **hybrid IR system optimized for mobile constraints**, combining:

- Classical IR (BM25)
- Neural IR (MiniLM)
- Efficient ranking (RRF + MMR)
- Optional ML reranking
- Optional generative layer

All executed **fully offline on Android**.
