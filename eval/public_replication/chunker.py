"""Port of indexing/TextChunker.kt (150 words, overlap 40). Checked against fixtures/chunker.json."""
import re
from dataclasses import dataclass

_WS = re.compile(r"[ \t\n\x0B\f\r]+")  # Java \s (ASCII)


@dataclass
class Chunk:
    text: str
    title: str
    start: int
    end: int
    index: int
    embed: bool = True  # False for the title-only chunk (the app stores no embedding for it)


def _tokens(text):
    return [t for t in _WS.split(text) if t and any(not c.isspace() for c in t)]


def chunk_document(text, title=None, chunk_size=150, overlap=40):
    tokens = _tokens(text)
    blank_title = title is None or not any(not c.isspace() for c in title)
    if not tokens and blank_title:
        return []
    if not tokens:
        return [Chunk(text=title, title=title, start=0, end=0, index=0, embed=False)]
    step = max(chunk_size - overlap, 1)
    chunks, pos, idx = [], 0, 0
    while pos < len(tokens):
        end = min(pos + chunk_size, len(tokens))
        ct = " ".join(tokens[pos:end])
        if idx == 0 and not blank_title:
            ct = f"{title}. {ct}"
        chunks.append(Chunk(text=ct, title=title or "", start=pos, end=end, index=idx))
        pos += step
        idx += 1
    return chunks
