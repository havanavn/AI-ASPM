#!/usr/bin/env python3
"""Regenerate _traceability/requirements.csv from the documentation corpus.
Implements DOC-00 §20.2. Run after any change to requirements."""
import csv, re, sys, os
from collections import Counter

DOCS = 'docs'
OUT = '_traceability/requirements.csv'
VMAP = {'AT':'AUTOMATED_TEST','MT':'MANUAL_TEST','CR':'CODE_REVIEW','AR':'ARCHITECTURE_REVIEW',
        'PT':'PENETRATION_TEST','DM':'DEMONSTRATION','DI':'DOCUMENT_INSPECTION'}
PMAP = {'M':'MUST_HAVE','S':'SHOULD_HAVE','C':'COULD_HAVE'}
IDRE = r'(?:PRD|CFG|NFR|LIC|CON|SEC|INT|OPS|TST|RISK)-[A-Z]{3}-\d{3}'
# Excluded from the register:
#   XMP — reserved for illustrative examples (DOC-00 Appendix A)
#   RES — provisional domain code, fully reconciled at DOC-01 §10.12.5
EXCLUDE_DOMAINS = {'XMP', 'RES'}
# DOC-00 defines conventions and owns no requirements.
EXCLUDE_DOCS = {'DOC-00'}

# Supersession records must be maintained by hand — they encode decisions, not text.
SUPERSEDED = {
    'PRD-SBM-001': ('Superseded', 'PRD-SBM-014'),
    'PRD-SBM-006': ('Superseded', 'PRD-SBM-016'),
    'PRD-SBM-008': ('Withdrawn',  ''),
}
SUPERSEDES = {v[1]: k for k, v in SUPERSEDED.items() if v[1]}

def clean(t):
    t = re.sub(r'`([^`]*)`', r'\1', t)
    t = re.sub(r'\*\*([^*]*)\*\*', r'\1', t)
    return ' '.join(t.split()).strip()

def vcodes(text):
    out = []
    for c in re.findall(r'\b(AT|MT|CR|AR|PT|DM|DI)\b', text):
        if VMAP[c] not in out:
            out.append(VMAP[c])
    return '|'.join(out)

def parse(path, docid):
    src = open(path, encoding='utf-8').read()
    rows, seen = [], set()
    for line in src.splitlines():
        if not line.startswith('|'):
            continue
        cells = [c.strip() for c in line.strip().strip('|').split('|')]
        m = re.fullmatch(rf'`({IDRE})`', cells[0]) if cells else None
        if not m:
            continue
        rid = m.group(1)
        if rid in seen or rid.split('-')[1] in EXCLUDE_DOMAINS:
            continue
        if len(cells) > 2 and (cells[2].startswith('Superseded') or cells[2].startswith('Withdrawn')):
            continue
        pri = ver = ''
        for c in cells[2:]:
            c2 = c.strip()
            if c2 in PMAP and not pri: pri = PMAP[c2]
            elif re.fullmatch(r'(?:MUST|SHOULD|COULD)_HAVE', c2) and not pri: pri = c2
            elif not ver and len(c2) <= 12:
                v = vcodes(c2)
                if v: ver = v
        if rid.startswith('CON-'):
            pri, ver = 'MUST_HAVE', 'DOCUMENT_INSPECTION'
        if rid.startswith('RISK-'):
            # RISK records are accepted residual risks, not requirements. They carry
            # a revisit trigger rather than a priority and verification method.
            pri, ver = 'N/A_RISK_RECORD', 'CONDITIONAL_REVIEW'
        seen.add(rid)
        rows.append([rid, clean(cells[1]) if len(cells) > 1 else '', pri, ver, docid, 'compact_table'])

    for m in re.finditer(rf'#{{3,5}}\s+`({IDRE})`\s+—\s+([^\n]+)\n(.*?)(?=\n#{{2,5}}\s|\n\| ID \|)', src, re.S):
        rid, title, body = m.group(1), m.group(2), m.group(3)
        if rid in seen or rid.split('-')[1] in EXCLUDE_DOMAINS:
            continue
        st = re.search(r'\*\*Statement\.\*\*\s*(.+?)(?=\n\n)', body, re.S)
        pr = re.search(r'\*\*Priority\.\*\*\s*([A-Z_]+)', body)
        vf = re.search(r'\*\*Verification\.\*\*\s*([^\n]+)', body)
        seen.add(rid)
        rows.append([rid, clean(st.group(1)) if st else clean(title),
                     pr.group(1) if pr else '', vcodes(vf.group(1)) if vf else '',
                     docid, 'expanded'])
    return rows

all_rows = []
for fn in sorted(os.listdir(DOCS)):
    if not fn.endswith('.md'):
        continue
    docid = 'DOC-' + fn[:2]
    if docid in EXCLUDE_DOCS:
        continue
    all_rows += parse(os.path.join(DOCS, fn), docid)

all_rows.sort(key=lambda r: (r[0].split('-')[0], r[0].split('-')[1], r[0]))
os.makedirs('_traceability', exist_ok=True)
with open(OUT, 'w', newline='', encoding='utf-8') as f:
    w = csv.writer(f)
    w.writerow(['id','class','domain','seq','owning_doc','statement','priority','verification',
                'status','supersedes','superseded_by','extraction_source'])
    for rid, stmt, pri, ver, docid, form in all_rows:
        c, d, s = rid.split('-')
        status = 'Accepted' if rid.startswith('RISK-') else 'Active'
        w.writerow([rid, c, d, s, docid, stmt, pri, ver, status,
                    SUPERSEDES.get(rid, ''), '', form])
    for rid, (status, succ) in SUPERSEDED.items():
        c, d, s = rid.split('-')
        w.writerow([rid, c, d, s, 'DOC-01',
                    'Superseded — substance changed under ADR-013/023/024',
                    '', '', status, '', succ, 'supersession_record'])

gaps_p = [r[0] for r in all_rows if not r[2]]
gaps_v = [r[0] for r in all_rows if not r[3]]
print(f'{OUT}: {len(all_rows)} active + {len(SUPERSEDED)} superseded/withdrawn '
      f'= {len(all_rows)+len(SUPERSEDED)} issued')
print('by class:', dict(sorted(Counter(r[0].split('-')[0] for r in all_rows).items())))
reqs = [r for r in all_rows if not r[0].startswith('RISK-')]
print(f'requirements: {len(reqs)} | accepted risk records: {len(all_rows)-len(reqs)}')
print('priority:', dict(Counter(r[2] for r in reqs)))
if gaps_p: print('WARN missing priority:', gaps_p)
if gaps_v: print('WARN missing verification:', gaps_v)
if not gaps_p and not gaps_v: print('no gaps')
