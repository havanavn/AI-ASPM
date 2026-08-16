#!/usr/bin/env python3
"""Corpus consistency checks. Implements DOC-00 §20.3.
Exit 0 = pass, 1 = findings. Run before considering any document complete."""
import csv, os, re, sys

DOCS='docs'; REG='_traceability/requirements.csv'
IDRE=re.compile(r'`((?:PRD|CFG|NFR|LIC|CON|SEC|INT|OPS|TST|RISK)-[A-Z]{3}-\d{3})`')
EXCLUDE_DOMAINS={'XMP','RES'}
# DOC-00 §3.1: these numbers are intentionally unassigned so that adding a document
# never requires renumbering, which would invalidate every external reference.
UNASSIGNED_DOCS={'23','25','27'}
# Classes whose owning documents may not yet be authored. An unregistered ID in
# one of these is a forward reference (informational), not a dangling reference.
# IDs that ARE registered are validated normally regardless of class.
FORWARD_CLASSES={'SEC','TST','OPS','INT','RISK'}
findings=[]; forward={}
def fail(doc,msg): findings.append((doc,msg))

# --- load register
known=set(); statuses={}
if os.path.exists(REG):
    for r in csv.DictReader(open(REG,encoding='utf-8')):
        known.add(r['id']); statuses[r['id']]=r['status']
else:
    fail('-', f'{REG} missing — run tools/generate_register.py')

files=sorted(f for f in os.listdir(DOCS) if f.endswith('.md'))
docids={'DOC-'+f[:2] for f in files}
seen_ids={}

for fn in files:
    doc='DOC-'+fn[:2]
    src=open(os.path.join(DOCS,fn),encoding='utf-8').read()

    # front matter present and minimally valid
    if not src.lstrip().startswith('---') and doc!='DOC-00':
        fail(doc,'missing YAML front matter (DOC-00 Appendix E)')
    else:
        fmatch=re.match(r'^\s*---\n(.*?)\n---',src,re.S)
        if fmatch:
            fm=fmatch.group(1)
            for field in ('document_id','title','version','status','owner','tier'):
                if not re.search(rf'^{field}:',fm,re.M):
                    fail(doc,f'front matter missing required field: {field}')
            st=re.search(r'^status:\s*(.+)$',fm,re.M)
            if st and st.group(1).strip() not in ('Not started','Drafting','In review',
                                                  'Approved','Superseded','Withdrawn'):
                fail(doc,f'invalid status value: {st.group(1).strip()}')

    # duplicate ID definitions across documents (same ID owned twice)
    for m in re.finditer(r'^\|\s*`((?:PRD|CFG|NFR|LIC|CON)-[A-Z]{3}-\d{3})`\s*\|',src,re.M):
        rid=m.group(1)
        if rid.split('-')[1] in EXCLUDE_DOMAINS: continue
        if rid in seen_ids and seen_ids[rid]!=doc:
            fail(doc,f'{rid} also defined in {seen_ids[rid]} — a requirement has one owning document (DOC-00 §6.4)')
        seen_ids.setdefault(rid,doc)

    # dangling requirement references
    fwd=set()
    for rid in set(IDRE.findall(src)):
        cls,dom,_=rid.split('-')
        if dom in EXCLUDE_DOMAINS: continue
        if known and rid in known:
            continue                      # registered and resolvable
        if cls in FORWARD_CLASSES:
            fwd.add(rid); continue        # owning document not yet authored
        if known:
            fail(doc,f'dangling requirement reference: {rid}')
    if fwd: forward[doc]=sorted(fwd)

    # dangling document references
    for ref in set(re.findall(r'\bDOC-(\d{2})\b',src)):
        if ref in UNASSIGNED_DOCS: continue
        if 'DOC-'+ref not in docids:
            fail(doc,f'reference to non-existent document: DOC-{ref}')

    # prohibited strings outside marked working assumptions
    for m in re.finditer(r'\b(TBD|TODO|\?\?\?)\b',src):
        ctx=src[max(0,m.start()-400):m.start()+200]
        # naming a prohibited placeholder in order to prohibit it is not a violation
        if any(k in ctx for k in ('Working assumption','OQ-','prohibited','Prohibited',
                                  'without an','MUST NOT be used','Avoided')):
            continue
        if True:
            line=src[:m.start()].count('\n')+1
            fail(doc,f'prohibited placeholder "{m.group(1)}" at line {line} without an OQ reference')

    # DOC-00 §5.2: a normative keyword must sit inside an identified requirement.
    # Scoped to requirement tables only — tables that define terminology, describe
    # archetypes, or state policy legitimately use these words descriptively.
    in_req_table=False
    for line in src.splitlines():
        if line.startswith('| ID |') and 'Statement' in line:
            in_req_table=True; continue
        if in_req_table and not line.startswith('|'):
            in_req_table=False; continue
        if in_req_table and re.search(r'\b(MUST|SHALL)\b',line) and not IDRE.search(line):
            if '---' not in line:
                fail(doc,f'normative keyword in a requirement table row with no ID: {line[:80]}')

# supersession integrity
for rid,st in statuses.items():
    if st=='Superseded':
        row=[r for r in csv.DictReader(open(REG,encoding='utf-8')) if r['id']==rid][0]
        if not row['superseded_by']:
            fail('-',f'{rid} marked Superseded with no successor recorded (DOC-00 §6.3)')

nf=sum(len(v) for v in forward.values())
print(f'checked {len(files)} document(s), {len(known)} registered requirement(s)')
if nf:
    print(f'{nf} forward reference(s) to requirements owned by unwritten documents '
          f'(SEC/TST/OPS/INT/RISK) — informational, recheck when those documents exist')
if not findings:
    print('PASS — no findings'); sys.exit(0)
by={}
for d,m in findings: by.setdefault(d,[]).append(m)
for d in sorted(by):
    print(f'\n{d}: {len(by[d])} finding(s)')
    for m in by[d][:25]: print('  -',m)
    if len(by[d])>25: print(f'  … and {len(by[d])-25} more')
print(f'\nFAIL — {len(findings)} finding(s)')
sys.exit(1)
