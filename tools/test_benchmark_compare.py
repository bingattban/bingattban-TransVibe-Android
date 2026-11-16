#!/usr/bin/env python3
"""Deterministic tests for the on-device Whisper benchmark comparison contract."""
import unittest
from benchmark_compare import compare, validate


def trial(backend, sample, run, elapsed, *, model='small', wer=.10, thermal=0, sha='f'*64):
    return dict(schemaVersion=1, device='arm64-reference-device', modelId=model,
                modelSha256=sha, sampleId=sample, sampleSha256='a'*64,
                backend=backend, threads=2, audioDurationMs=40000,
                recognitionMs=elapsed, totalProcessingMs=elapsed,
                thermalStatusStart=thermal, thermalStatusEnd=thermal,
                peakPssKb=100000, crashedOrOom=False, wer=wer,
                repeatIndex=run)


class BenchmarkComparisonTest(unittest.TestCase):
    def test_speedup_requires_three_trials_of_same_sample(self):
        baseline=[trial('CPU', 'synthetic-a', i, 10000) for i in range(3)]
        candidate=[trial('CPU_DOTPROD','synthetic-a', i, 8000) for i in range(3)]
        result=compare(baseline,candidate)
        self.assertEqual('improved',result['decision'])
        self.assertAlmostEqual(1.25,result['groups'][0]['speedup'],places=4)

    def test_rejects_less_than_three_repeats(self):
        result=compare([trial('CPU','s',i,10000) for i in range(2)],
                       [trial('GPU','s',i,7000) for i in range(2)])
        self.assertEqual('inconclusive',result['decision'])

    def test_detects_regression(self):
        a=[trial('CPU','s',i,10000) for i in range(3)]
        b=[trial('CPU_DOTPROD','s',i,13000) for i in range(3)]
        self.assertEqual('regressed',compare(a,b)['decision'])

    def test_rejects_quality_regression(self):
        a=[trial('CPU','s',i,10000,wer=.1) for i in range(3)]
        b=[trial('GPU','s',i,8000,wer=.18) for i in range(3)]
        self.assertEqual('regressed',compare(a,b)['decision'])

    def test_missing_quality_requires_validation(self):
        a=[trial('CPU','s',i,10000,wer=None) for i in range(3)]
        b=[trial('GPU','s',i,8000,wer=None) for i in range(3)]
        self.assertEqual('inconclusive',compare(a,b)['decision'])

    def test_mismatched_model_or_audio_never_compared(self):
        a=[trial('CPU','s',i,10000) for i in range(3)]
        b=[trial('GPU','s',i,8000,model='base') for i in range(3)]
        self.assertEqual('inconclusive',compare(a,b)['decision'])
        b=[dict(trial('GPU','s',i,8000),sampleSha256='b'*64) for i in range(3)]
        self.assertEqual('inconclusive',compare(a,b)['decision'])

    def test_thermal_or_crash_is_unsafe(self):
        a=[trial('CPU','s',i,10000) for i in range(3)]
        b=[trial('GPU','s',i,7000,thermal=3) for i in range(3)]
        self.assertEqual('inconclusive',compare(a,b)['decision'])
        b[0]['crashedOrOom']=True
        self.assertEqual('inconclusive',compare(a,b)['decision'])

    def test_invalid_sample_is_rejected_without_text_fields(self):
        with self.assertRaises(ValueError):
            validate(dict(trial('CPU','s',0,10000), transcript='private text'))
        with self.assertRaises(ValueError):
            validate(dict(trial('CPU','s',0,10000), recognitionMs=-1))

    def test_candidate_thread_optimization_is_comparable(self):
        a=[trial('CPU','s',i,10000) for i in range(3)]
        b=[dict(trial('CPU','s',i,9500), threads=3) for i in range(3)]
        self.assertEqual('no_significant_change',compare(a,b)['decision'])

if __name__ == '__main__':
    unittest.main()
