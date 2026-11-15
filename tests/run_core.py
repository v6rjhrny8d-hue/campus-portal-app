#!/usr/bin/env python3
"""Offline tests; org.json's JVM jar is a test-only dependency, never in the APK."""
import os
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[1]
jar = Path(os.environ.get('JSON_TEST_JAR', str(root / 'tools/json-test.jar')))
if not jar.is_file():
    raise SystemExit('Set JSON_TEST_JAR to org.json:json:20240303 (test-only dependency).')
out = root / 'build/test-classes'
out.mkdir(parents=True, exist_ok=True)
java = root / 'app/src/main/java/org/campusportal/app'
sources = sorted((java / 'core').glob('*.java')) + [java / 'Config.java', root / 'tests/CoreTests.java', root / 'tests/ReliabilityTests.java']
subprocess.run(['java', 'com.sun.tools.javac.Main', '--release', '8', '-cp', str(jar), '-d', str(out), *map(str,sources)], check=True)
subprocess.run(['java', '-cp', str(out)+os.pathsep+str(jar), 'org.campusportal.app.tests.CoreTests'], check=True)
