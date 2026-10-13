"""Expose screenshots of fictitious test data for visual review in the job log."""
import base64
from pathlib import Path

root = Path("app/build/outputs/device-screenshots")
for name in ("vandaag", "maandagenda", "weekagenda", "kindmodus"):
    matches = list(root.rglob(name + ".png"))
    if matches:
        png = matches[0].read_bytes()
        if len(png) <= 2_000_000:
            print("YSHOTT_PREVIEW:" + name + ":" + base64.b64encode(png).decode("ascii"))
