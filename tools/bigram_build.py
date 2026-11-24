import re, json, collections, glob
tokrx = re.compile(r"[a-zа-яёіїєґ'’-]+|[.!?\n]")
def text_pairs(text, rx, bi, w=1):
    prev=None
    for t in tokrx.findall(text.lower()):
        if t in '.!?\n': prev=None; continue
        t=t.replace('’',"'").strip("-'")
        if not t or not rx.match(t): prev=None; continue
        if prev: bi[(prev,t)]+=w
        prev=t
def conllu_pairs(fn, rx, bi, w=1):
    sent=[]
    for line in open(fn,encoding='utf-8'):
        if line.startswith('#'): continue
        if not line.strip():
            for a,b in zip(sent,sent[1:]):
                if a and b: bi[(a,b)]+=w
            sent=[]; continue
        c=line.split('\t')
        if '-' in c[0] or '.' in c[0]: continue
        x=c[1].lower().replace('’',"'"); sent.append(x if rx.match(x) else None)
ru_rx=re.compile(r"^[а-яё]+(-[а-яё]+)?$"); uk_rx=re.compile(r"^[а-щьюяєіїґ']+(-[а-щьюяєіїґ']+)?$")
bi=collections.Counter(); n=0
with open('dialogs.part',encoding='utf-8',errors='ignore') as f:
    for line in f:
        try: o=json.loads(line)
        except Exception: continue
        text_pairs((o.get('question') or '')+'\n'+(o.get('answer') or ''), ru_rx, bi, 3); n+=1
print('dialog lines',n)
for fn in ['ru_taiga-ud-train-a.conllu','ru_taiga-ud-train-b.conllu','ru_syntagrus-ud-train-a.conllu','ru_syntagrus-ud-train-b.conllu','ru_gsd-ud-train.conllu']:
    conllu_pairs(fn, ru_rx, bi, 1)
items=[(k,v) for k,v in bi.items() if v>=4]; items.sort(key=lambda x:-x[1]); items=items[:180000]
open('/home/claude/nanopero/app/src/main/assets/dict/ru_bi.txt','w',encoding='utf-8').write(''.join(f"{a} {b}\t{v}\n" for (a,b),v in items))
print('ru pairs',len(items))
nxt=collections.defaultdict(list)
for (a,b),v in items: nxt[a].append(b)
for w in ['как','спасибо','до','что','привет','давай','я','ну']: print(w,'->',nxt[w][:6])
bi=collections.Counter()
for fn in glob.glob('brownuk/data/good/*.txt')+glob.glob('brownuk/data/so-so/*.txt'):
    text_pairs(open(fn,encoding='utf-8',errors='ignore').read(), uk_rx, bi, 1)
for fn in ['uk_iu-ud-train.conllu','uk_parlamint-ud-train.conllu']:
    conllu_pairs(fn, uk_rx, bi, 2)
items=[(k,v) for k,v in bi.items() if v>=2]; items.sort(key=lambda x:-x[1]); items=items[:90000]
open('/home/claude/nanopero/app/src/main/assets/dict/uk_bi.txt','w',encoding='utf-8').write(''.join(f"{a} {b}\t{v}\n" for (a,b),v in items))
print('uk pairs',len(items))
nxt=collections.defaultdict(list)
for (a,b),v in items: nxt[a].append(b)
for w in ['як','дякую','до','що','я']: print(w,'->',nxt[w][:6])
