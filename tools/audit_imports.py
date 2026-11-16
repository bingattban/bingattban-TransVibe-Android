#!/usr/bin/env python3
from pathlib import Path
import re, sys
root = Path(__file__).resolve().parents[1]
problems=[]
for p in (root/'app/src/main/java').rglob('*.kt'):
    text=p.read_text(encoding='utf-8')
    body='\n'.join(line for line in text.splitlines() if not line.startswith('import '))
    for line in text.splitlines():
        if not line.startswith('import ') or line.endswith('.*'): continue
        target=line[len('import '):].strip()
        alias=None
        if ' as ' in target:
            target, alias = target.split(' as ',1)
        name=alias or target.rsplit('.',1)[-1]
        if name in {'getValue','setValue','invoke'}: continue
        if not re.search(r'\b'+re.escape(name)+r'\b', body):
            problems.append((p.relative_to(root),line))
if problems:
    for p,line in problems: print(f'{p}: {line}')
    sys.exit(1)
print('PASS: no obvious unused imports in owned Kotlin source')
