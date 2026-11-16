#!/usr/bin/env python3
"""Compare repeated real-device Whisper benchmarks without treating CI CPU time as phone speed.

Input: JSON array or JSONL of privacy-safe *measurements*, not transcripts or user media.
Exit 0 only for comparable results without performance/quality regression;
exit 2 for inconclusive / insufficient evidence; exit 3 for regression.
"""
import argparse
import json
import math
import pathlib
import statistics
import sys
from collections import defaultdict

ALLOWED = frozenset({
    'schemaVersion', 'device', 'modelId', 'modelSha256', 'sampleId', 'sampleSha256',
    'backend', 'threads', 'audioDurationMs', 'recognitionMs', 'totalProcessingMs',
    'thermalStatusStart', 'thermalStatusEnd', 'peakPssKb', 'crashedOrOom',
    'wer', 'cer', 'repeatIndex', 'nativeSha256', 'runLabel',
})
REQUIRED = {'schemaVersion','device','modelId','modelSha256','sampleId',
            'sampleSha256','backend','threads','audioDurationMs','recognitionMs',
            'totalProcessingMs','thermalStatusStart','thermalStatusEnd',
            'peakPssKb','crashedOrOom','wer','repeatIndex'}


def validate(row):
    if not isinstance(row, dict):
        raise ValueError('Every benchmark must be an object')
    unknown = set(row) - ALLOWED
    missing = REQUIRED - set(row)
    if unknown or missing:
        raise ValueError(f'Unexpected/missing fields: {sorted(unknown)} / {sorted(missing)}')
    if row['schemaVersion'] != 1:
        raise ValueError('Unsupported schemaVersion')
    for key in ('device','modelId','sampleId','backend'):
        if not isinstance(row[key],str) or not row[key] or len(row[key]) > 120:
            raise ValueError(f'Invalid {key}')
    for key in ('modelSha256', 'sampleSha256'):
        val=row[key]
        if not isinstance(val,str) or len(val)!=64 or any(ch not in '0123456789abcdefABCDEF' for ch in val):
            raise ValueError(f'Invalid {key}')
    for key in ('threads','audioDurationMs','recognitionMs','totalProcessingMs','peakPssKb','repeatIndex'):
        if type(row[key]) is not int or row[key] < (0 if key in ('peakPssKb','repeatIndex') else 1):
            raise ValueError(f'Invalid {key}')
    if row['threads'] > 32 or row['audioDurationMs'] > 24*3600*1000:
        raise ValueError('Unreasonable resource values')
    if row['recognitionMs'] > row['totalProcessingMs']:
        raise ValueError('ASR time exceeds total time')
    if type(row['crashedOrOom']) is not bool:
        raise ValueError('Invalid crashedOrOom')
    for key in ('thermalStatusStart','thermalStatusEnd'):
        if type(row[key]) is not int or not 0 <= row[key] <= 6:
            raise ValueError(f'Invalid {key}')
    for key in ('wer','cer'):
        val=row.get(key)
        if val is not None and (type(val) not in (float,int) or not math.isfinite(val) or not 0 <= val <= 10):
            raise ValueError(f'Invalid {key}')
    return row


def load(path):
    contents=pathlib.Path(path).read_text(encoding='utf-8')
    if not contents.strip():
        raise ValueError('Empty benchmark input')
    if contents.lstrip().startswith('['):
        rows=json.loads(contents)
    else:
        rows=[json.loads(line) for line in contents.splitlines() if line.strip()]
    if not isinstance(rows,list):
        raise ValueError('Expected JSON array or JSONL')
    return [validate(row) for row in rows]


def _key(row):
    # Same audio/model identity and device; backend, threads and native binary may change.
    return (row['device'],row['modelId'],row['modelSha256'],row['sampleId'],
            row['sampleSha256'],row['audioDurationMs'])


def _group(rows):
    groups=defaultdict(list)
    for row in rows:
        validate(row)
        groups[_key(row)].append(row)
    return groups


def compare(baseline, candidate, min_repeats=3, max_slowdown=0.10, max_quality_delta=0.02):
    if min_repeats < 3:
        raise ValueError('At least three repeats are required')
    left,right=_group(baseline),_group(candidate)
    comparable=sorted(set(left) & set(right))
    groups=[]
    for key in comparable:
        a,b=left[key],right[key]
        reasons=[]
        if len(a) < min_repeats or len(b) < min_repeats:
            reasons.append('fewer_than_three_repeats')
        if len({x['repeatIndex'] for x in a}) != len(a) or len({x['repeatIndex'] for x in b}) != len(b):
            reasons.append('duplicate_repeat_index')
        if any(x['crashedOrOom'] for x in a+b):
            reasons.append('crash_or_oom')
        if any(max(x['thermalStatusStart'],x['thermalStatusEnd']) > 2 for x in a+b):
            reasons.append('thermal_throttling_possible')
        if any(x['wer'] is None for x in a+b):
            reasons.append('quality_not_measured')
        if any(x.get('cer') is None for x in a+b) and any(x.get('cer') is not None for x in a+b):
            reasons.append('incomplete_cer_measurements')
        base_rtf=statistics.median(x['recognitionMs']/x['audioDurationMs'] for x in a)
        cand_rtf=statistics.median(x['recognitionMs']/x['audioDurationMs'] for x in b)
        base_wer=statistics.median(x['wer'] for x in a if x['wer'] is not None) if all(x['wer'] is not None for x in a) else None
        cand_wer=statistics.median(x['wer'] for x in b if x['wer'] is not None) if all(x['wer'] is not None for x in b) else None
        quality_regression=(base_wer is not None and cand_wer is not None and cand_wer > base_wer+max_quality_delta)
        if all(x.get('cer') is not None for x in a+b):
            quality_regression |= statistics.median(x['cer'] for x in b)>statistics.median(x['cer'] for x in a)+max_quality_delta
        regression=cand_rtf>base_rtf*(1+max_slowdown) or quality_regression
        speedup=base_rtf/cand_rtf
        decision='inconclusive' if reasons else 'regressed' if regression else 'improved' if speedup>=1.1 else 'no_significant_change'
        groups.append({'sampleId':key[3], 'modelId':key[1], 'device':key[0],
                       'baselineBackend':a[0]['backend'], 'candidateBackend':b[0]['backend'],
                       'baselineThreads':a[0]['threads'], 'candidateThreads':b[0]['threads'],
                       'baselineRtf':round(base_rtf,6), 'candidateRtf':round(cand_rtf,6),
                       'speedup':round(speedup,6), 'baselineWer':base_wer,
                       'candidateWer':cand_wer,'decision':decision,'reasons':reasons})
    decisions={g['decision'] for g in groups}
    decision='inconclusive' if not groups or 'inconclusive' in decisions else (
        'regressed' if 'regressed' in decisions else
        'improved' if decisions == {'improved'} else 'no_significant_change')
    return {'schemaVersion':1,'decision':decision,'comparableGroups':len(groups),
            'unmatchedBaselineGroups':len(set(left)-set(right)),
            'unmatchedCandidateGroups':len(set(right)-set(left)), 'groups':groups}


def main(argv=None):
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--baseline',required=True)
    parser.add_argument('--candidate',required=True)
    parser.add_argument('--output')
    args=parser.parse_args(argv)
    try:
        result=compare(load(args.baseline),load(args.candidate))
    except (ValueError,OSError,json.JSONDecodeError) as exc:
        print(f'Benchmark input error: {exc}',file=sys.stderr)
        return 2
    report=json.dumps(result,indent=2,ensure_ascii=False,sort_keys=True)+'\n'
    if args.output:
        pathlib.Path(args.output).write_text(report,encoding='utf-8')
    print(report,end='')
    return {'improved':0,'no_significant_change':0,'inconclusive':2,'regressed':3}[result['decision']]


if __name__=='__main__':
    sys.exit(main())
