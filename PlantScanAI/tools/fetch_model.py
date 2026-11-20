#!/usr/bin/env python3
"""Download and verify the free Iris 10 fused offline plant identifier."""
import hashlib
import json
import pathlib
import urllib.request

BASE = "https://huggingface.co/brunopaiva15/iris/resolve/main/"
DEST = pathlib.Path(__file__).resolve().parents[1] / "app/src/main/assets"
EXPECTED = {
    "plants.tflite": "a919ae6a24f7e7717a36d5213a800a3410f7cee0f694f5014ba8eb07f0a6caa3",
    "iris10.tflite": "5d4fc8a654722fe802fc7528f5f71349d785a419fa0780bc06f510fd5d1f4598",
}
FILES = ("plants.tflite", "labels.txt", "model.json",
         "iris10.tflite", "iris10-references.bin", "iris10.json")
DEST.mkdir(parents=True, exist_ok=True)
for name in FILES:
    path = DEST / name
    if not path.exists():
        print("Download:", name, flush=True)
        req = urllib.request.Request(BASE + name + "?download=true",
                                     headers={"User-Agent": "PlantScanAI/0.2"})
        with urllib.request.urlopen(req, timeout=180) as r, path.open("wb") as out:
            while True:
                data = r.read(256 * 1024)
                if not data: break
                out.write(data)
    print("Loaded", name, path.stat().st_size, flush=True)
    if name in EXPECTED:
        sha = hashlib.sha256(path.read_bytes()).hexdigest()
        if sha != EXPECTED[name]:
            raise SystemExit(f"Incorrect SHA256 for {name}: {sha}")

meta9 = json.loads((DEST / "model.json").read_text())
meta10 = json.loads((DEST / "iris10.json").read_text())
labels = (DEST / "labels.txt").read_text().split()
assert len(labels) == meta9["classes"] == 1569
assert meta10["dim"] == 1024
assert len(meta10["iris9"]) == len(labels)
assert (DEST / "iris10-references.bin").stat().st_size == len(meta10["lignes"]) * meta10["dim"] * 2
assert len(meta10["especes"]) >= 1500
print("Validated Iris 10+9 ensemble, species:", len(meta10["especes"]), flush=True)
