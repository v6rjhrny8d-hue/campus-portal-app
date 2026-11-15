#!/usr/bin/env python3
"""Dependency-free Android CLI build; SDK 35 + Build Tools 35 + Java 17 required."""
import argparse
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def run(command, **kwargs):
    subprocess.run([str(x) for x in command], check=True, **kwargs)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--sdk', default=os.environ.get('ANDROID_HOME', str(ROOT / 'tools/sdk')))
    parser.add_argument('--keystore', required=True)
    parser.add_argument('--alias', default='campus-app')
    parser.add_argument('--output', default=str(ROOT / 'build/campus-portal-app-1.5.8.apk'))
    args = parser.parse_args()
    sdk = Path(args.sdk).resolve()
    android = sdk / 'platforms/android-35/android.jar'
    tools = sdk / 'build-tools/35.0.0'
    if not android.exists() or not (tools / 'aapt2').exists():
        parser.error('需要 Android SDK Platform 35 和 Build Tools 35.0.0')
    if not os.environ.get('CAMPUS_SIGNING_PASSWORD'):
        parser.error('请通过 CAMPUS_SIGNING_PASSWORD 环境变量提供签名密码')
    build = ROOT / 'build'
    for folder in ('classes', 'dex', 'generated'):
        target = build / folder
        if target.exists(): shutil.rmtree(target)
        target.mkdir(parents=True)
    source = ROOT / 'app/src/main'
    run([tools / 'aapt2', 'compile', '--dir', source / 'res', '-o', build / 'resources.zip'])
    run([tools / 'aapt2', 'link', '-I', android, '--manifest', source / 'AndroidManifest.xml',
         '--java', build / 'generated', '-o', build / 'unsigned.apk', build / 'resources.zip'])
    java = sorted((source / 'java').rglob('*.java')) + sorted((build / 'generated').rglob('*.java'))
    # --release supplies complete Java 8 compiler bootstrap stubs for lambda desugaring.
    run(['java', 'com.sun.tools.javac.Main', '--release', '8', '-classpath', android, '-d', build / 'classes', *java])
    run(['java', '-cp', tools / 'lib/d8.jar', 'com.android.tools.r8.D8', '--min-api', '26',
         '--lib', android, '--output', build / 'dex', *sorted((build / 'classes').rglob('*.class'))])
    with zipfile.ZipFile(build / 'unsigned.apk', 'a', zipfile.ZIP_STORED) as archive:
        for dex in sorted((build / 'dex').glob('*.dex')): archive.write(dex, dex.name)
    env = dict(os.environ)
    env['LD_LIBRARY_PATH'] = str(tools / 'lib64') + (':' + env['LD_LIBRARY_PATH'] if env.get('LD_LIBRARY_PATH') else '')
    run([tools / 'zipalign', '-f', '4', build / 'unsigned.apk', build / 'aligned.apk'], env=env)
    output = Path(args.output).resolve(); output.parent.mkdir(parents=True, exist_ok=True)
    output.with_name(output.name + '.idsig').unlink(missing_ok=True)
    run(['java', '-jar', tools / 'lib/apksigner.jar', 'sign', '--ks', Path(args.keystore).resolve(),
         '--ks-key-alias', args.alias, '--ks-pass', 'env:CAMPUS_SIGNING_PASSWORD', '--key-pass', 'env:CAMPUS_SIGNING_PASSWORD',
         '--v1-signing-enabled', 'true', '--v2-signing-enabled', 'true', '--v3-signing-enabled', 'true',
         '--v4-signing-enabled', 'false', '--out', output, build / 'aligned.apk'])
    run(['java', '-jar', tools / 'lib/apksigner.jar', 'verify', '--verbose', output])
    print('APK:', output)
    print('SHA256:', hashlib.sha256(output.read_bytes()).hexdigest())


if __name__ == '__main__': main()
