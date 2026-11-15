#!/usr/bin/env python3
"""Build and run native smoke tests on a disposable Android emulator, never a physical phone."""
import argparse
import http.server
import os
from pathlib import Path
import subprocess
import threading
import zipfile

root=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser()
p.add_argument('--sdk',default=os.environ.get('ANDROID_HOME',str(root/'tools/sdk')))
p.add_argument('--adb',required=True)
p.add_argument('--serial',required=True)
p.add_argument('--apk',required=True)
p.add_argument('--upgrade-from')
p.add_argument('--keystore',required=True)
p.add_argument('--alias',default='campus-app')
a=p.parse_args()
if not a.serial.startswith('emulator-'):p.error('Only a disposable emulator is supported; these tests replace its app settings.')
if not os.environ.get('CAMPUS_SIGNING_PASSWORD'):p.error('CAMPUS_SIGNING_PASSWORD required for matching instrumentation signature')
sdk=Path(a.sdk).resolve();tools=sdk/'build-tools/35.0.0';android=sdk/'platforms/android-35/android.jar'
build=root/'build/android-test';build.mkdir(parents=True,exist_ok=True)
for name in ['classes','dex']:(build/name).mkdir(exist_ok=True)
def run(args,**kwargs):return subprocess.run([str(x) for x in args],check=True,**kwargs)
def adb(*args,**kwargs):return run([a.adb,'-s',a.serial,*args],**kwargs)
run([tools/'aapt2','link','-I',android,'--manifest',root/'tests/android/AndroidManifest.xml','-o',build/'unsigned.apk'])
run(['java','com.sun.tools.javac.Main','--release','8','-cp',str(android)+os.pathsep+str(root/'build/classes'),'-d',build/'classes',root/'tests/android/SmokeInstrumentation.java'])
with zipfile.ZipFile(build/'target-classes.jar','w') as z:
    for file in (root/'build/classes').rglob('*.class'):z.write(file,file.relative_to(root/'build/classes'))
run(['java','-cp',tools/'lib/d8.jar','com.android.tools.r8.D8','--min-api','26','--lib',android,'--classpath',build/'target-classes.jar','--output',build/'dex',*sorted((build/'classes').rglob('*.class'))])
with zipfile.ZipFile(build/'unsigned.apk','a') as z:
    for file in (build/'dex').glob('*.dex'):z.write(file,file.name)
env=dict(os.environ);env['LD_LIBRARY_PATH']=str(tools/'lib64')+':'+env.get('LD_LIBRARY_PATH','')
run([tools/'zipalign','-f','4',build/'unsigned.apk',build/'aligned.apk'],env=env)
(build/'test.apk.idsig').unlink(missing_ok=True)
run(['java','-jar',tools/'lib/apksigner.jar','sign','--ks',Path(a.keystore).resolve(),'--ks-key-alias',a.alias,'--ks-pass','env:CAMPUS_SIGNING_PASSWORD','--key-pass','env:CAMPUS_SIGNING_PASSWORD','--v1-signing-enabled','true','--v2-signing-enabled','true','--v3-signing-enabled','true','--v4-signing-enabled','false','--out',build/'test.apk',build/'aligned.apk'])
run(['java','-jar',tools/'lib/apksigner.jar','verify',build/'test.apk'])
payload=(build/'test.apk').read_bytes()
with zipfile.ZipFile(build/'test.apk') as archive:
    if archive.testzip() is not None:raise RuntimeError('Invalid instrumentation APK')
(build/'test-complete.apk').write_bytes(payload)
(build/'test-complete.apk').replace(build/'test.apk')
import hashlib
print('Target APK SHA256:',hashlib.sha256(Path(a.apk).read_bytes()).hexdigest(),flush=True)
if a.upgrade_from:
    adb('install','--no-incremental','-r','-g',Path(a.upgrade_from).resolve())
    adb('install','--no-incremental','-r',build/'test.apk')
    result=adb('shell','am','instrument','-w','-e','upgradeMode','seed','org.campusportal.app.tests/org.campusportal.app.tests.SmokeInstrumentation',capture_output=True,text=True)
    print(result.stdout,flush=True)
    if 'PASS:' not in result.stdout or 'FAIL' in result.stdout:raise SystemExit(1)
adb('install','--no-incremental','-r','-g',Path(a.apk).resolve())
adb('install','--no-incremental','-r',build/'test.apk')
upgrade_ok=True
if a.upgrade_from:
    result=adb('shell','am','instrument','-w','-e','upgradeMode','verify','org.campusportal.app.tests/org.campusportal.app.tests.SmokeInstrumentation',capture_output=True,text=True)
    print(result.stdout,flush=True)
    upgrade_ok='PASS:' in result.stdout and 'FAIL' not in result.stdout
adb('shell','cmd','uimode','night','no')
adb('shell','settings','put','system','font_scale','1.0')
adb('shell','am','force-stop','org.campusportal.app')
api=int(adb('shell','getprop','ro.build.version.sdk',capture_output=True,text=True).stdout.strip())
if api>=31:adb('shell','appops','set','org.campusportal.app','SCHEDULE_EXACT_ALARM','allow')
adb('shell','settings','put','system','screen_off_timeout','1800000')
adb('shell','svc','power','stayon','true')
adb('shell','input','keyevent','224')
adb('shell','wm','dismiss-keyguard')
adb('shell','rm','-f','/data/local/tmp/webview-command-line')
class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        body=b'CAMPUS_TEST_WIFI';self.send_response(200);self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body)
    def log_message(self,*args):pass
server=http.server.ThreadingHTTPServer(('127.0.0.1',18764),Handler)
threading.Thread(target=server.serve_forever,daemon=True).start()
try:
    result=adb('shell','am','instrument','-w','org.campusportal.app.tests/org.campusportal.app.tests.SmokeInstrumentation',capture_output=True,text=True)
    import base64
    visible=[]
    for line in result.stdout.splitlines():
        marker='INSTRUMENTATION_STATUS: campus_image='
        if line.startswith(marker):
            name,data=line[len(marker):].split('|',1)
            image=root/'build'/Path(name).name
            image.write_bytes(base64.b64decode(data))
            print('Captured native UI:',image.name)
        else:visible.append(line)
    print('\n'.join(visible))
    if 'PASS:' not in result.stdout or 'FAIL' in result.stdout or not upgrade_ok:raise SystemExit(1)

finally:server.shutdown()
