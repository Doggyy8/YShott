#!/usr/bin/env python3
"""Download and verify Iris 9 assets used for the free offline species model."""
import hashlib
import json
import os
import pathlib
import urllib.request

BASE = 'https://huggingface.co/brunopaiva15/iris/resolve/main/'
DEST = pathlib.Path(__file__).resolve().parents[1] / 'app/src/main/assets'
EXPECTED_SHA256 = 'a919ae6a24f7e7717a36d5213a800a3410f7cee0f694f5014ba8eb07f0a6caa3'
DEST.mkdir(parents=True, exist_ok=True)
for filename in ('plants.tflite','labels.txt','model.json',
                 'iris10.tflite','iris10-references.bin','iris10.json'):
    target = DEST / filename
    if not target.is_file():
        print('Download',filename,flush=True)
        request = urllib.request.Request(BASE + filename + '?download=true', headers={'User-Agent':'PlantScanAI/0.1'})
        with urllib.request.urlopen(request, timeout=180) as source, target.open('wb') as output:
            while True:
                chunk = source.read(1024 * 256)
                if not chunk: break
                output.write(chunk)
    print(filename, target.stat().st_size,'bytes')
actual = hashlib.sha256((DEST / 'plants.tflite').read_bytes()).hexdigest()
if actual != EXPECTED_SHA256:
    raise SystemExit(f'Model hash mismatch {actual}. Expected {EXPECTED_SHA256}')
encoder = DEST/'iris10.tflite'
encoder_sha = hashlib.sha256(encoder.read_bytes()).hexdigest()
expected_encoder_sha = '5d4fc8a654722fe802fc7528f5f71349d785a419fa0780bc06f510fd5d1f4598'
if encoder_sha != expected_encoder_sha:
    raise SystemExit(f'Iris 10 encoder hash mismatch {encoder_sha}')
ten = json.loads((DEST/'iris10.json').read_text())
expected_bytes = len(ten['lignes']) * int(ten['dim']) * 2
assert (DEST/'iris10-references.bin').stat().st_size == expected_bytes
assert len(ten['iris9']) == 1569
assert len(ten['especes']) == 1567
meta = json.loads((DEST/'model.json').read_text())
labels = (DEST/'labels.txt').read_text().split()
assert len(labels) == meta['classes'] == 1569
print('Model verified', meta['version'], len(labels), 'species')
