"""Port of search/query/{QueryNormalizer,SmartTokenizer,EntityExtractor,QueryExpander}.kt and QueryProcessor.rawDenseQuery.
Only what the retrieval path uses (bm25Query with includeSynonyms=false, normalized query, raw dense query).
Checked against fixtures/query_processing.json."""
import re
import unicodedata

_WS = r"[ \t\n\x0B\f\r]"  # Java \s (ASCII)
CONTRACTIONS = {
    "don't": "do not", "won't": "will not", "can't": "cannot", "it's": "it is", "i'm": "i am", "we're": "we are",
    "they're": "they are", "you're": "you are", "hasn't": "has not", "haven't": "have not", "isn't": "is not",
    "aren't": "are not", "wasn't": "was not", "weren't": "were not", "let's": "let us", "that's": "that is",
    "who's": "who is", "what's": "what is", "where's": "where is", "when's": "when is", "why's": "why is",
    "how's": "how is", "i've": "i have", "we've": "we have", "they've": "they have", "you've": "you have",
    "should've": "should have", "would've": "would have", "could've": "could have",
}
_URL = re.compile(r"https?://[^ \t\n\x0B\f\r]+")
_EMAIL = re.compile(r"[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}")
_SPECIAL = re.compile(r"[^a-z0-9 \t\n\x0B\f\r'-]")
_MULTI = re.compile(_WS + "+")
_MARKS = re.compile(r"[̀-ͯ]|[᪰-᫿]|[᷀-᷿]|[⃐-⃿]|[︠-︯]")
_CONTRACTION_RES = [(re.compile(r"\b" + re.escape(k) + r"\b"), v) for k, v in CONTRACTIONS.items()]

STOPWORDS = {
    "the", "a", "an", "and", "or", "but", "in", "on", "at", "to", "for", "of", "with", "is", "are", "was", "were", "been",
    "be", "have", "has", "had", "do", "does", "did", "will", "would", "should", "could", "may", "might", "must", "can",
    "this", "that", "these", "those", "it", "its", "he", "she", "him", "her", "his", "they", "them", "their",
}

DOMAIN_EXPANSIONS = {
    "ml": "machine learning", "ai": "artificial intelligence", "dl": "deep learning", "nlp": "natural language processing",
    "cv": "computer vision", "api": "application programming interface", "rest": "representational state transfer",
    "crud": "create read update delete", "orm": "object relational mapping", "sql": "structured query language",
    "nosql": "non relational database", "cicd": "continuous integration deployment", "devops": "development operations",
    "ui": "user interface", "ux": "user experience", "db": "database", "regex": "regular expression",
    "gpu": "graphics processing unit", "cpu": "central processing unit", "ram": "random access memory",
    "ssd": "solid state drive", "hdd": "hard disk drive",
}
PROGRAMMING_LANGUAGES = [
    "kotlin", "java", "python", "javascript", "typescript", "c++", "cpp", "rust", "go", "golang", "swift", "ruby", "php",
    "scala", "r", "matlab", "c#", "csharp", "dart", "perl", "haskell", "lua", "sql", "html", "css",
]
TECHNICAL_DOMAINS = {
    "ml": "machine learning", "ai": "artificial intelligence", "dl": "deep learning", "nlp": "natural language processing",
    "cv": "computer vision", "api": "application programming interface", "rest": "rest api", "graphql": "graphql",
    "db": "database", "nosql": "nosql database", "ui": "user interface", "ux": "user experience",
    "frontend": "frontend development", "backend": "backend development", "fullstack": "fullstack development",
    "devops": "devops", "cicd": "continuous integration", "docker": "containerization", "kubernetes": "container orchestration",
    "aws": "cloud computing", "azure": "cloud computing", "gcp": "cloud computing",
}


def normalize(query):
    s = _URL.sub(" ", query)
    s = _EMAIL.sub(" ", s)
    s = unicodedata.normalize("NFD", s)
    s = _MARKS.sub("", s)
    s = s.lower()
    for rx, rep in _CONTRACTION_RES:
        s = rx.sub(rep, s)
    s = _SPECIAL.sub(" ", s)
    s = _MULTI.sub(" ", s).strip()
    return s


def tokenize(normalized):
    words = [w for w in re.split(_WS + "+", normalized) if w]
    return [w for w in words if not re.fullmatch(r"[^a-z0-9]+", w)]


def _entities(normalized):
    """(PROGRAMMING_LANGUAGE texts, TECHNICAL_DOMAIN (term, fullName) pairs) found in the normalised query."""
    langs = [l for l in PROGRAMMING_LANGUAGES if re.search(r"\b" + re.escape(l) + r"\b", normalized)]
    doms = [(t, f) for t, f in TECHNICAL_DOMAINS.items() if re.search(r"\b" + re.escape(t) + r"\b", normalized)]
    return langs, doms


def bm25_query(normalized):
    tokens = tokenize(normalized)
    original = [t for t in tokens if t not in STOPWORDS]
    expansions = {}
    boosted = set()
    for t in tokens:
        if t in DOMAIN_EXPANSIONS:
            expansions[t] = DOMAIN_EXPANSIONS[t]
            boosted.add(t)
            boosted.add(DOMAIN_EXPANSIONS[t])
    langs, doms = _entities(normalized)
    for l in langs:
        boosted.add(l)
    for t, f in doms:
        boosted.add(t)
        boosted.add(f)
    terms = []
    for t in original:
        terms.append(t)
        if t in boosted:
            terms.append(t)
    for e in expansions.values():
        terms.extend(w for w in e.split(" ") if w.strip())
    return " ".join(terms).strip()


def raw_dense_query(raw):
    """QueryProcessor.rawDenseQuery: trim, lower-case, collapse whitespace (the dense query mode of arms E11 and of this replication)."""
    return _MULTI.sub(" ", raw.strip().lower())


def search_query(raw):
    """Normalised query used for fusion title matching and reranking (SearchEngine: normalized.trim().lowercase())."""
    return normalize(raw).strip().lower()
