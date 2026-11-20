"""Lightweight source/layout checks; not a substitute for building on Android SDK."""
from pathlib import Path
import re
root=Path(__file__).resolve().parents[1]
expected=[
 'settings.gradle.kts','build.gradle.kts','app/build.gradle.kts',
 'app/src/main/AndroidManifest.xml','app/src/main/java/nl/plantscan/ai/MainActivity.kt',
 'app/src/main/java/nl/plantscan/ai/PlantEngine.kt',
 'app/src/main/java/nl/plantscan/ai/ReportWriter.kt',
 'app/src/main/java/nl/plantscan/ai/SelectionViews.kt',
 '.github/workflows/android-apk.yml', 'tools/fetch_model.py',
]
for f in expected:
 p=root/f
 assert p.exists() and p.stat().st_size>0, f
print('Found',len(expected),'essential files')
assert 'IrisClassifier' in (root/expected[5]).read_text()
assert 'PdfDocument' in (root/expected[6]).read_text()
assert 'VideoScanner' in (root/expected[5]).read_text()
assert 'assembleDebug' in (root/expected[8]).read_text()
assert not (root/'app/src/main/assets/plants.tflite').exists(), "Do not include model binaries in public source ZIP"
print('Basic static checks passed. Full APK build still required.')
