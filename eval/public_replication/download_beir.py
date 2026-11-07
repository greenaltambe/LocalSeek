"""Download the BEIR test sets used by the public replication from Hugging Face into ~/localseek-public-data/beir/
and print path, size and SHA-256 of each file (used to write DATA.md). Only corpus, queries and the test qrels are fetched."""
import hashlib, os, sys
from huggingface_hub import hf_hub_download

def _data_dir():
    """Public datasets folder: $LOCALSEEK_DATA if set, else ../data/localseek-public-data next to the repository, else ~/localseek-public-data."""
    env = os.environ.get("LOCALSEEK_DATA")
    if env:
        return os.path.expanduser(env)
    repo = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
    sibling = os.path.join(os.path.dirname(repo), "data", "localseek-public-data")
    return sibling if os.path.isdir(sibling) else os.path.expanduser("~/localseek-public-data")


ROOT = os.path.join(_data_dir(), "beir")
DATASETS = ["scifact", "nfcorpus", "fiqa", "scidocs", "trec-covid"]

def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()

def main():
    rows = []
    for d in DATASETS:
        for repo, fn in [(f"BeIR/{d}", "corpus/corpus-00000-of-00001.parquet"),
                         (f"BeIR/{d}", "queries/queries-00000-of-00001.parquet"),
                         (f"BeIR/{d}-qrels", "test.tsv")]:
            p = hf_hub_download(repo, fn, repo_type="dataset", local_dir=os.path.join(ROOT, repo.split("/")[1]))
            rows.append((repo, fn, os.path.getsize(p), sha256(p)))
            print(*rows[-1], sep="\t", flush=True)

if __name__ == "__main__":
    main()
