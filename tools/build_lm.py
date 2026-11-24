"""Языковая модель клавиатуры НаноПеро (v2): корпуса + Википедия, подсчёт через numpy.
Формат тот же, что в build_lm.py (NPLM1)."""
import sys, json, re, glob, struct, math, array
import numpy as np, pyarrow.parquet as pq
lang=sys.argv[1]; maxpairs=int(sys.argv[2]); wiki_tokens=int(sys.argv[3])
D='/home/claude/dl/'
words=[l.split('\t')[0] for l in open(f'/home/claude/nanopero/app/src/main/assets/dict/{lang}.txt',encoding='utf8') if '\t' in l]
wid={w:i for i,w in enumerate(words)}; nW=len(words)
tok=re.compile(r"[a-zа-яёіїєґ'’]+|[.!?,;:\n\"()«»—–…]")
BR=frozenset('.!?,;:\n"()«»—–…')
chunks=[]; weights=[]; uni=np.zeros(nW,dtype=np.int64)
def ids_of(text):
    out=[]
    for w in tok.findall(text.lower()):
        if w in BR: out.append(-1); continue
        out.append(wid.get(w.replace('’',"'").strip("'"),-1))
    out.append(-1)
    return out
buf=[]; bufw=[1]
def flush(weight):
    global buf
    if not buf: return
    a=np.array(buf,dtype=np.int64); buf=[]
    ok=a>=0
    np.add.at(uni, a[ok], weight)
    m=ok[:-1]&ok[1:]
    keys=a[:-1][m]*nW+a[1:][m]
    if keys.size:
        k,c=np.unique(keys,return_counts=True)
        chunks.append((k,c*weight))
def feed_text(t, weight=1):
    buf.extend(ids_of(t))
    if len(buf)>3_000_000: flush(weight)
def feed_seq(seq, weight=1):
    for w in seq: buf.append(-1 if w is None else wid.get(w,-1))
    buf.append(-1)
    if len(buf)>3_000_000: flush(weight)
def conllu(fn, skip_last=0, frac=None):
    sents=[]; cur=[]
    for line in open(fn,encoding='utf-8'):
        if line.startswith('#'): continue
        if not line.strip():
            if cur: sents.append(cur)
            cur=[]; continue
        c=line.split('\t')
        if '-' in c[0] or '.' in c[0]: continue
        cur.append(None if c[3]=='PUNCT' else c[1].lower().replace('’',"'"))
    if frac: sents=sents[:int(len(sents)*frac)]
    if skip_last: sents=sents[:-skip_last]
    return sents
if lang=='ru':
    lines=open(D+'dialogs.part',encoding='utf-8',errors='ignore').read().splitlines()
    for l in lines[:int(len(lines)*0.97)]:
        try: o=json.loads(l)
        except: continue
        feed_text((o.get('question') or '')+'\n'+(o.get('answer') or ''), 2)
    flush(2)   # разговорный стиль — с двойным весом
    for fn in ['ru_taiga-ud-train-a.conllu','ru_taiga-ud-train-b.conllu','ru_syntagrus-ud-train-a.conllu','ru_syntagrus-ud-train-b.conllu','ru_gsd-ud-train.conllu']:
        for s in conllu(D+fn): feed_seq(s)
    flush(1)
elif lang=='uk':
    for fn in glob.glob(D+'brownuk/data/good/*.txt')+glob.glob(D+'brownuk/data/so-so/*.txt'):
        feed_text(open(fn,encoding='utf-8',errors='ignore').read())
    for s in conllu(D+'uk_parlamint-ud-train.conllu',frac=0.97)+conllu(D+'uk_iu-ud-train.conllu',400): feed_seq(s)
    flush(1)
else:
    for s in conllu(D+'en_gum-ud-train.conllu',1200)+conllu(D+'en_ewt-ud-train.conllu',800): feed_seq(s)
    flush(1)
# Википедия
pf=pq.ParquetFile(f'/home/claude/wiki/{lang}.parquet'); seen=0
for batch in pf.iter_batches(batch_size=2000, columns=['text']):
    for t in batch.column(0).to_pylist():
        # только связный текст: без таблиц и списков ссылок
        para=[p for p in t.split('\n') if len(p)>80]
        if not para: continue
        x='\n'.join(para); feed_text(x); seen+=len(x)//7
    if seen>wiki_tokens: break
flush(1)
N=int(uni.sum())
print(lang,'tokens',N, 'chunks',len(chunks))
k=np.concatenate([c[0] for c in chunks]); c=np.concatenate([c[1] for c in chunks])
order=np.argsort(k,kind='stable'); k=k[order]; c=c[order]
uk_,idx=np.unique(k,return_index=True); cs=np.add.reduceat(c,idx)
print('distinct pairs',uk_.size)
keep=cs>=2; uk_=uk_[keep]; cs=cs[keep]
if uk_.size>maxpairs:
    top=np.argpartition(-cs,maxpairs)[:maxpairs]; top.sort(); uk_=uk_[top]; cs=cs[top]
a=(uk_//nW).astype(np.int64); b=(uk_%nW).astype(np.int32)
off=np.zeros(nW+1,dtype=np.int32); np.add.at(off, a+1, 1); off=np.cumsum(off).astype(np.int32)
cnt=np.minimum(255, np.round(16*np.log(cs))).astype(np.uint8)
uni32=np.minimum(uni, 2**32-1).astype(np.uint32)
with open(f'/home/claude/nanopero/app/src/main/assets/dict/{lang}.lm','wb') as f:
    f.write(b'NPLM1'); f.write(struct.pack('<iq',nW,N))
    f.write(uni32.tobytes()); f.write(off.tobytes()); f.write(b.tobytes()); f.write(cnt.tobytes())
print(lang,'pairs kept',uk_.size)
