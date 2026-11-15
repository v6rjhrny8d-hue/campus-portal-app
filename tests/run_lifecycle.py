#!/usr/bin/env python3
"""Run Android 15 lifecycle simulations. Requires test-only Robolectric dependencies and built classes."""
import argparse
import os
from pathlib import Path
import subprocess
import zipfile

root = Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser()
p.add_argument('--deps', default=str(root / 'build/lifecycle-libs'))
p.add_argument('--runtime-dir', required=True, help='Directory containing android-all-instrumented API 35 i7 jar')
p.add_argument('--android-all', required=True, help='android-all 15-robolectric-13954326.jar')
a = p.parse_args()
deps = Path(a.deps).resolve()
for aar in deps.glob('*.aar'):
    with zipfile.ZipFile(aar) as archive:
        (deps / (aar.stem + '-classes.jar')).write_bytes(archive.read('classes.jar'))
classes = root / 'build/lifecycle-classes'
classes.mkdir(parents=True, exist_ok=True)
if not (root / 'build/classes/org/campusportal/app/WorkService.class').exists():
    p.error('Build the app first with tools/build.py')
cp = os.pathsep.join(map(str, [classes, root / 'build/classes', Path(a.android_all).resolve(), *sorted(deps.glob('*.jar'))]))
subprocess.run(['java', 'com.sun.tools.javac.Main', '--release', '8', '-cp', cp, '-d', str(classes),
    *map(str, sorted((root / 'tests/lifecycle/java').rglob('*.java')))], check=True)
opens = ['java.base/java.lang', 'java.base/java.util', 'java.base/java.io', 'java.base/java.net',
         'java.base/java.security', 'java.base/java.text', 'java.base/jdk.internal.access',
         'java.desktop/java.awt.font', 'jdk.compiler/com.sun.tools.javac.api']
subprocess.run(['java', *['--add-opens=' + module + '=ALL-UNNAMED' for module in opens],
    '-Drobolectric.offline=true', '-Drobolectric.dependency.dir=' + str(Path(a.runtime_dir).resolve()),
    '-cp', cp, 'org.junit.runner.JUnitCore', 'org.campusportal.app.LifecycleTest',
    'org.campusportal.app.WifiSessionTest', 'org.campusportal.app.NetworkFlowTest',
    'org.campusportal.app.UiExperienceTest', 'org.campusportal.app.SheetUiTest', 'org.campusportal.app.CompatibilityTest'], check=True)
