"""cross-encoder/ms-marco-MiniLM-L-6-v2 as the app uses it: pair truncation of BertTokenizer.tokenizePair (256 tokens), token type
ids all 0 (the on-device model has no segment input, eval/parity/RESULTS.md), output logit -> sigmoid."""
import numpy as np

MAX_LEN = 256
MODEL = "cross-encoder/ms-marco-MiniLM-L-6-v2"


def pair_ids(tok_a, tok_b, cls_id, sep_id, max_length=MAX_LEN):
    """tok_a / tok_b: word-piece token id lists without special tokens. Budget logic of BertTokenizer.tokenizePair."""
    avail = max_length - 3
    budget_a = avail // 2
    budget_b = avail - budget_a
    if len(tok_a) <= budget_a:
        a, b = tok_a, tok_b[: max(avail - len(tok_a), 0)]
    elif len(tok_b) <= budget_b:
        b, a = tok_b, tok_a[: max(avail - len(tok_b), 0)]
    else:
        a, b = tok_a[:budget_a], tok_b[:budget_b]
    return [cls_id] + list(a) + [sep_id] + list(b) + [sep_id]


class CrossEncoderPort:
    def __init__(self, device="cuda"):
        import torch
        from transformers import AutoModelForSequenceClassification, AutoTokenizer
        self.torch = torch
        self.device = device
        self.tok = AutoTokenizer.from_pretrained(MODEL)
        self.model = AutoModelForSequenceClassification.from_pretrained(MODEL).to(device).eval()

    def score(self, pairs, batch_size=64):
        """pairs: [(query, text)] -> sigmoid(logit) float32 array."""
        torch = self.torch
        ids = []
        for q, d in pairs:
            ta = self.tok.convert_tokens_to_ids(self.tok.tokenize(q.lower()))
            tb = self.tok.convert_tokens_to_ids(self.tok.tokenize(d.lower()))
            ids.append(pair_ids(ta, tb, self.tok.cls_token_id, self.tok.sep_token_id))
        out = []
        with torch.no_grad():
            for s in range(0, len(ids), batch_size):
                batch = ids[s:s + batch_size]
                m = max(len(x) for x in batch)
                inp = torch.zeros((len(batch), m), dtype=torch.long)
                att = torch.zeros((len(batch), m), dtype=torch.long)
                for i, x in enumerate(batch):
                    inp[i, : len(x)] = torch.tensor(x)
                    att[i, : len(x)] = 1
                logits = self.model(input_ids=inp.to(self.device), attention_mask=att.to(self.device),
                                    token_type_ids=torch.zeros_like(inp).to(self.device)).logits[:, 0]
                out.append(torch.sigmoid(logits.double()).float().cpu().numpy())
        return np.concatenate(out) if out else np.zeros(0, dtype=np.float32)
