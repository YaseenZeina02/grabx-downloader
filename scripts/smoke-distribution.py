#!/usr/bin/env python3
"""Check native libraries and the native-messaging launcher without starting the UI."""
import argparse
import json
import os
from pathlib import Path
import platform
import shutil
import struct
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument('distribution', type=Path)
parser.add_argument('--launcher', type=Path, help='Optional installed bridge wrapper to exercise')
args = parser.parse_args()
dist = args.distribution.resolve()
classifier = {'Windows':'win', 'Linux':'linux', 'Darwin':'mac'}[platform.system()]
assert list((dist / 'lib').glob(f'javafx-graphics-*-{classifier}*.jar')), 'Distribution was built for another OS'
java = Path(os.environ['JAVA_HOME']) / 'bin' / ('java.exe' if os.name == 'nt' else 'java') if os.environ.get('JAVA_HOME') else shutil.which('java')
assert java, 'Java 21+ is required'
if args.launcher:
    command = [str(args.launcher.resolve())]
    if os.name == 'nt': command = '"' + os.environ.get('COMSPEC', 'cmd.exe') + '" /d /s /c ""' + command[0] + '""'
else:
    command = [str(java), '-cp', str(dist / 'lib/*'), 'com.grabx.app.grabx.browser.BrowserNativeHostMain']
message = json.dumps({'type':'status'}).encode()
result = subprocess.run(command, input=struct.pack('=I', len(message)) + message, capture_output=True, timeout=30, check=True)
assert len(result.stdout) >= 4, result.stderr.decode(errors='replace')
length = struct.unpack('=I', result.stdout[:4])[0]
assert length == len(result.stdout)-4, 'stdout was not a single clean native-messaging frame'
reply = json.loads(result.stdout[4:])
assert reply['ok'] is True and isinstance(reply['running'], bool), reply
print(f'PASS {platform.system()}: native libraries and UTF-8 framed native host')
