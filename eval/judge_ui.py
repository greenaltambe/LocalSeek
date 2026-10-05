#!/usr/bin/env python3
"""Local blind relevance-judging page for LocalSeek pools. Standard library only, localhost only.

  python3 eval/judge_ui.py --pool eval/canonical_pool.csv
  python3 eval/judge_ui.py --pool eval/pool_second.csv          # second assessor (use a COPY, not the first judge's file)
  python3 eval/judge_ui.py --pool eval/image_pool_local.csv --images-dir ~/private/pulled_images

Then open http://127.0.0.1:8765

Pool CSV columns: query_id,query_text,result_id,entity_type,title,snippet,relevance
Optional column: image_name (file name inside --images-dir) to show a picture.
Judgments are written straight into the `relevance` column (1 / 0 / blank), so
`python3 eval/judging.py to-qrels ...` works unchanged. A .bak copy is made on first save.
The page is blind: no arm names, and candidates are shuffled inside each query so rank order gives no hint.
Keys: 1 relevant | 0 not relevant | Backspace clear | J/K or arrows move | N next query with unjudged items.
"""
import argparse, csv, json, mimetypes, os, shutil, threading, zlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

LOCK = threading.Lock()
ROWS, FIELDS, ARGS = [], [], None


def load():
    global ROWS, FIELDS
    with open(ARGS.pool, encoding="utf-8-sig", newline="") as f:  # utf-8-sig: tolerate a BOM (Excel)
        r = csv.DictReader(f)
        FIELDS = list(r.fieldnames or [])
        ROWS = list(r)
    for c in ("query_id", "query_text", "result_id", "relevance"):
        if c not in FIELDS:
            raise SystemExit(f"pool is missing column {c!r}")


def save():
    bak = ARGS.pool + ".bak"
    if not os.path.exists(bak):
        shutil.copy2(ARGS.pool, bak)
    tmp = ARGS.pool + ".tmp"
    with open(tmp, "w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=FIELDS, extrasaction="ignore")
        w.writeheader()
        w.writerows(ROWS)
    os.replace(tmp, ARGS.pool)


def queries():
    out, idx, seen = [], {}, set()
    for r in ROWS:
        q = r["query_id"]
        if q not in idx:
            idx[q] = {"id": q, "text": r["query_text"], "n": 0, "done": 0}
            out.append(idx[q])
        key = (q, r["result_id"])
        if key in seen:
            continue
        seen.add(key)
        idx[q]["n"] += 1
        if (r.get("relevance") or "").strip() in ("0", "1"):
            idx[q]["done"] += 1
    return out


def rows_for(qid):
    rs, seen = [], set()
    for r in ROWS:  # duplicate (query_id,result_id) rows are shown once; a judgment updates all copies
        if r["query_id"] == qid and r["result_id"] not in seen:
            seen.add(r["result_id"])
            rs.append(r)
    rs.sort(key=lambda r: zlib.crc32((qid + "|" + r["result_id"]).encode()))
    return [{"rid": r["result_id"], "type": r.get("entity_type", ""), "title": r.get("title", ""),
             "snippet": r.get("snippet", ""), "image": r.get("image_name", ""),
             "rel": (r.get("relevance") or "").strip()} for r in rs]


PAGE = r"""<!doctype html><meta charset=utf-8><meta name=viewport content="width=device-width,initial-scale=1">
<title>LocalSeek judging</title>
<style>
:root{--bg:#f4f6f8;--fg:#17212b;--mut:#5b6b7a;--card:#fff;--line:#d8e0e7;--ok:#0f766e;--okb:#d5efeb;--no:#9f1d3b;--nob:#fbdde6;--sel:#1d4ed8}
@media(prefers-color-scheme:dark){:root{--bg:#0f171e;--fg:#e6edf3;--mut:#9aabba;--card:#17222b;--line:#2a3a47;--ok:#3cc3b4;--okb:#123a37;--no:#ff8fb0;--nob:#43202e;--sel:#7aa2ff}}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--fg);font:16px/1.5 system-ui,sans-serif;display:grid;grid-template-columns:260px 1fr;height:100vh}
aside{border-right:1px solid var(--line);overflow:auto;padding:10px}aside button{display:block;width:100%;text-align:left;border:0;background:none;color:var(--fg);padding:7px 8px;border-radius:6px;cursor:pointer;font:inherit;font-size:14px}
aside button.on{background:var(--card);outline:1px solid var(--sel)}aside small{color:var(--mut)}aside button.full small{color:var(--ok)}
main{overflow:auto;padding:18px 24px 80px}h1{font-size:20px;margin:0 0 4px}.bar{color:var(--mut);font-size:14px;margin-bottom:14px}
details{margin-bottom:14px;color:var(--mut);font-size:14px}.item{background:var(--card);border:2px solid var(--line);border-radius:10px;padding:12px 14px;margin-bottom:10px}
.item.sel{border-color:var(--sel)}.item.r1{background:var(--okb)}.item.r0{background:var(--nob)}.top{display:flex;gap:10px;align-items:center;flex-wrap:wrap}
.tag{font-size:12px;font-weight:600;letter-spacing:.04em;text-transform:uppercase;color:var(--mut);border:1px solid var(--line);border-radius:999px;padding:1px 8px}
.title{font-weight:600;overflow-wrap:anywhere}.snip{white-space:pre-wrap;overflow-wrap:anywhere;margin-top:6px;font-size:15px;max-height:14em;overflow:auto}
mark{background:#ffe38a;color:#000;border-radius:3px}.btns{margin-left:auto;display:flex;gap:8px}.btns button{font:inherit;font-weight:600;padding:5px 16px;border-radius:8px;border:2px solid var(--line);background:var(--card);color:var(--fg);cursor:pointer}
.btns .y.on{background:var(--ok);color:#fff;border-color:var(--ok)}.btns .n.on{background:var(--no);color:#fff;border-color:var(--no)}img{max-width:min(100%,520px);max-height:360px;border-radius:8px;margin-top:8px;display:block}
@media(max-width:800px){body{grid-template-columns:1fr;height:auto}aside{max-height:30vh}}
</style>
<aside id=side></aside>
<main><h1 id=qt></h1><div class=bar id=bar></div>
<details><summary>What counts as relevant?</summary>
<p><b>1</b> if a person typing this query would be glad to see this result: the exact item they were looking for (app, contact, file), or a document that is really about the topic. <b>0</b> if it only shares words, is the wrong kind of thing, or you would scroll past it.</p>
<p>Judge the result on its own, ignore its position, do not try to guess which system returned it. If you cannot decide, leave it blank, then come back. Be consistent: the same rule for every query.</p></details>
<div id=list></div></main>
<script>
let Q=[],cur=null,R=[],sel=0;
const $=s=>document.querySelector(s);
async function api(u,o){const r=await fetch(u,o);return r.json()}
function hl(text,words){const f=document.createDocumentFragment();if(!words.length){f.append(text);return f}
 const re=new RegExp('('+words.map(w=>w.replace(/[.*+?^${}()|[\]\\]/g,'\\$&')).join('|')+')','ig');
 text.split(re).forEach((p,i)=>{if(i%2){const m=document.createElement('mark');m.textContent=p;f.append(m)}else f.append(p)});return f}
async function loadQ(){Q=await api('/api/queries');side()}
function side(){const s=$('#side');s.textContent='';Q.forEach(q=>{const b=document.createElement('button');b.className=(cur===q.id?'on ':'')+(q.done===q.n?'full':'');
 b.append(q.text||q.id);const sm=document.createElement('small');sm.textContent='  '+q.done+'/'+q.n;b.append(sm);b.onclick=()=>open(q.id);s.append(b)})}
async function open(id){cur=id;sel=0;R=await api('/api/rows?q='+encodeURIComponent(id));const q=Q.find(x=>x.id===id);$('#qt').textContent=q.text||id;draw();side();scrollTo(0,0)}
function draw(){const q=Q.find(x=>x.id===cur),words=(q.text||'').split(/\s+/).filter(w=>w.length>1);
 const done=R.filter(r=>r.rel!=='').length;$('#bar').textContent=done+' of '+R.length+' judged for this query. Total: '+Q.reduce((a,x)=>a+x.done,0)+' of '+Q.reduce((a,x)=>a+x.n,0);
 const L=$('#list');L.textContent='';R.forEach((r,i)=>{const d=document.createElement('div');d.className='item'+(i===sel?' sel':'')+(r.rel==='1'?' r1':r.rel==='0'?' r0':'');d.id='i'+i;
  const t=document.createElement('div');t.className='top';const g=document.createElement('span');g.className='tag';g.textContent=r.type;t.append(g);
  const ti=document.createElement('span');ti.className='title';ti.append(hl(r.title,words));t.append(ti);
  const b=document.createElement('span');b.className='btns';[['1','y','Relevant 1'],['0','n','Not relevant 0']].forEach(([v,c,l])=>{const x=document.createElement('button');x.className=c+(r.rel===v?' on':'');x.textContent=l;x.onclick=()=>{sel=i;set(v)};b.append(x)});t.append(b);d.append(t);
  if(r.snippet){const s=document.createElement('div');s.className='snip';s.append(hl(r.snippet,words));d.append(s)}
  if(r.image){const im=document.createElement('img');im.loading='lazy';im.src='/img?n='+encodeURIComponent(r.image);d.append(im)}
  d.onclick=e=>{if(e.target.tagName!=='BUTTON'){sel=i;draw()}};L.append(d)});
 const e=$('#i'+sel);if(e)e.scrollIntoView({block:'nearest'})}
async function set(v){const r=R[sel];if(!r)return;r.rel=v;await api('/api/judge',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({q:cur,rid:r.rid,v})});
 const q=Q.find(x=>x.id===cur);q.done=R.filter(x=>x.rel!=='').length;if(v!==''&&sel<R.length-1)sel++;draw();side()}
function nextQ(){const i=Q.findIndex(x=>x.id===cur);const o=Q.slice(i+1).concat(Q.slice(0,i)).find(x=>x.done<x.n);if(o)open(o.id)}
addEventListener('keydown',e=>{if(e.metaKey||e.ctrlKey)return;const k=e.key.toLowerCase();
 if(k==='1')set('1');else if(k==='0')set('0');else if(k==='backspace'){e.preventDefault();set('')}
 else if(k==='j'||k==='arrowdown'){e.preventDefault();if(sel<R.length-1){sel++;draw()}}
 else if(k==='k'||k==='arrowup'){e.preventDefault();if(sel>0){sel--;draw()}}else if(k==='n')nextQ()});
loadQ().then(()=>{const f=Q.find(x=>x.done<x.n)||Q[0];if(f)open(f.id)});
</script>"""


class H(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def _send(self, body, ctype="application/json", code=200):
        b = body if isinstance(body, bytes) else body.encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(b)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(b)

    def do_GET(self):
        if not self._host_ok():
            return self._send("forbidden", "text/plain", 403)
        u = urlparse(self.path)
        qs = parse_qs(u.query)
        with LOCK:
            if u.path == "/":
                return self._send(PAGE, "text/html; charset=utf-8")
            if u.path == "/api/queries":
                return self._send(json.dumps(queries()))
            if u.path == "/api/rows":
                return self._send(json.dumps(rows_for(qs.get("q", [""])[0])))
        if u.path == "/img" and ARGS.images_dir:
            name = os.path.basename(qs.get("n", [""])[0])  # basename: no path traversal
            p = os.path.join(ARGS.images_dir, name)
            if name and not name.startswith(".") and os.path.isfile(p):
                with open(p, "rb") as f:
                    return self._send(f.read(), mimetypes.guess_type(p)[0] or "application/octet-stream")
        self._send("not found", "text/plain", 404)

    def _host_ok(self):
        # DNS-rebinding guard: only answer requests addressed to the loopback name we bind to
        return self.headers.get("Host", "").split(":")[0] in ("127.0.0.1", "localhost")

    def do_POST(self):
        if self.path != "/api/judge":
            return self._send("not found", "text/plain", 404)
        # Require JSON content type (forces a CORS preflight, so other web pages cannot POST blindly) and a loopback Host.
        if not self._host_ok() or not self.headers.get("Content-Type", "").startswith("application/json"):
            return self._send("forbidden", "text/plain", 403)
        try:
            d = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))))
            q, rid, v = d["q"], d["rid"], d["v"]
        except (ValueError, KeyError, TypeError):
            return self._send("bad request", "text/plain", 400)
        if v not in ("0", "1", ""):
            return self._send("bad value", "text/plain", 400)
        with LOCK:
            n = 0
            for r in ROWS:
                if r["query_id"] == q and r["result_id"] == rid:
                    r["relevance"] = v
                    n += 1
            save()
        self._send(json.dumps({"updated": n}))


def make_server(port):
    return ThreadingHTTPServer(("127.0.0.1", port), H)  # loopback only; port 0 = ephemeral


def main():
    global ARGS
    ap = argparse.ArgumentParser()
    ap.add_argument("--pool", required=True)
    ap.add_argument("--images-dir")
    ap.add_argument("--port", type=int, default=8765)
    ARGS = ap.parse_args()
    load()
    print(f"{len(ROWS)} rows, {len(queries())} queries. Open http://127.0.0.1:{ARGS.port}  (Ctrl-C to stop; progress is saved on every click)")
    ARGS.images_dir = os.path.expanduser(ARGS.images_dir) if ARGS.images_dir else None
    make_server(ARGS.port).serve_forever()


if __name__ == "__main__":
    main()
