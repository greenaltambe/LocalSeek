# Public replication data and environment

BEIR test sets downloaded from Hugging Face (`BeIR/<name>` corpus and queries parquet, `BeIR/<name>-qrels` test.tsv) on 2026-10-05 into `~/localseek-public-data/beir/` (outside the repository). Only public data; nothing from the phone.

| dataset | documents | test queries with qrels | qrel rows |
|---|---|---|---|
| scifact | 5,183 | 300 | 339 |
| nfcorpus | 3,633 | 323 | 12,334 |
| fiqa | 57,638 | 648 | 1,706 |
| scidocs | 25,657 | 1,000 | 29,928 |
| trec-covid | 171,332 | 50 | 66,336 |

## Files (size in bytes, SHA-256)

| repo | file | bytes | sha256 |
|---|---|---|---|
| BeIR/scifact | `corpus/corpus-00000-of-00001.parquet` | 4,469,916 | `243324b35f03d82bd6d98a5f575966876e86cad7ce16e5333a35b1b793dc4f45` |
| BeIR/scifact | `queries/queries-00000-of-00001.parquet` | 64,982 | `1c37956c5dc8b810b60302323c24d1a9e79e26411ba8f5ad9d0888642e2a9034` |
| BeIR/scifact-qrels | `test.tsv` | 5,389 | `0864bb985e0ca2367ba217977e72004d549054b2b06666ed9d4825ac7c21284c` |
| BeIR/nfcorpus | `corpus/corpus-00000-of-00001.parquet` | 3,157,570 | `f2a1c0b570a5efdf23cfa36f5e573d062cc113c6e7251e22c69b323a33ea895e` |
| BeIR/nfcorpus | `queries/queries-00000-of-00001.parquet` | 80,929 | `edd2b878b8130fb3b5e3c4b9428ddc1e67fbd6eedf8ae099fca3b87838ba6b29` |
| BeIR/nfcorpus-qrels | `test.tsv` | 279,572 | `f8fba6ef3d4dd9c3a242a8ba4ae38276fc3622fce7dcbae764766d564542fd2a` |
| BeIR/fiqa | `corpus/corpus-00000-of-00001.parquet` | 27,700,817 | `b36bb07dab3bfad488190818828e749a52faf5ba391c1d51dac8b06064576f99` |
| BeIR/fiqa | `queries/queries-00000-of-00001.parquet` | 321,680 | `e9b3863724ffd538d8efff19684ef8ae495bdbddd96b35e81bdc39e3e22860d6` |
| BeIR/fiqa-qrels | `test.tsv` | 25,256 | `6adc2a640dcdd22bb8b3858f89107adef2a7c3db20a63550dfa7a0f71e379e44` |
| BeIR/scidocs | `corpus/corpus-00000-of-00001.parquet` | 18,673,258 | `e1855cbf47a19e4ff1fc870674c3c441bc5a32a76ecc151851d4452a9aa43a28` |
| BeIR/scidocs | `queries/queries-00000-of-00001.parquet` | 92,568 | `f9ad7adff7d396e652789de371f69b5a3adfa8f39e1f37fff728c231e7db3202` |
| BeIR/scidocs-qrels | `test.tsv` | 2,543,906 | `dcd3d7f77417294bb6f338537f3c28d8a5ea72b30fe6633f407fa85528767e35` |
| BeIR/trec-covid | `corpus/corpus-00000-of-00001.parquet` | 110,609,513 | `d76cea1b2304dbe67a1a54f7376a61de294976682a1d7d58d82de27141f3ba4a` |
| BeIR/trec-covid | `queries/queries-00000-of-00001.parquet` | 4,865 | `80bd564b1218a519ef0a396fa7b874941b7188d8240933e8d6fa867d7db59d6f` |
| BeIR/trec-covid-qrels | `test.tsv` | 980,831 | `10669ab7d526cb04f52079139fd88c3d467a0776441b046567f540582798982b` |

Download script: `eval/public_replication/download_beir.py`. Total size on disk about 162 MB.

## Environment

- CPU: AMD Ryzen 5 5600H (6 cores, 12 threads); RAM: 15 GiB; GPU: NVIDIA GeForce RTX 3050 Laptop, 4 GiB (CUDA used for encoding and reranking).
- Python 3.12 virtualenv `eval/public_replication/.venv` (git-ignored), created with `uv`; torch 2.14.1+cu130, sentence-transformers 6.1.0, datasets 5.0.1, model2vec 0.9.0. Full list: `requirements.lock`.
- Hugging Face login present (`hf auth whoami`), needed only for the gated `google/embeddinggemma-300m`.
