#!/usr/bin/env python3
"""Fetch pinned Windows x64 media tools, verify hashes/PE architecture, preserve notices."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import struct
import urllib.request
import zipfile
ROOT = Path(__file__).resolve().parents[1]

def verify_x64(path):
    with path.open('rb') as source:
        if source.read(2) != b'MZ': raise ValueError(f'Not a Windows executable: {path.name}')
        source.seek(0x3c); header = struct.unpack('<I', source.read(4))[0]
        source.seek(header)
        if source.read(4) != b'PE\0\0' or source.read(2) != b'\x64\x86':
            raise ValueError(f'Not an x64 PE executable: {path.name}')

def download(spec, cache):
    cache.mkdir(parents=True, exist_ok=True)
    path = cache / spec['url'].rsplit('/', 1)[1]
    cached_digest = None
    if path.exists():
        with path.open('rb') as cached: cached_digest = hashlib.file_digest(cached, 'sha256').hexdigest()
    if cached_digest != spec['sha256']:
        temporary = path.with_suffix('.download')
        request = urllib.request.Request(spec['url'], headers={'User-Agent':'GrabX-build/1.0'})
        with urllib.request.urlopen(request, timeout=120) as response, temporary.open('wb') as output:
            shutil.copyfileobj(response, output)
        with temporary.open('rb') as data:
            if hashlib.file_digest(data, 'sha256').hexdigest() != spec['sha256']:
                temporary.unlink(); raise ValueError('Tool archive checksum mismatch')
        temporary.replace(path)
    return path

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--dest', type=Path, default=ROOT/'build/windows-tools')
    args=parser.parse_args(); args.dest.mkdir(parents=True, exist_ok=True)
    specs=json.loads((ROOT/'packaging/windows/tools.json').read_text())
    provenance={}
    for name,spec in specs.items():
        archive=download(spec, ROOT/'build/downloads/windows-tools')
        with zipfile.ZipFile(archive) as bundle:
            executables=['node.exe'] if name=='node' else ['ffmpeg.exe','ffprobe.exe']
            for executable in executables:
                matches=[p for p in bundle.namelist() if p.endswith('/bin/'+executable) or (name=='node' and p.endswith('/'+executable))]
                if len(matches)!=1: raise ValueError(f'Expected one {executable}')
                target=args.dest/executable
                with bundle.open(matches[0]) as source,target.open('wb') as output: shutil.copyfileobj(source,output)
                verify_x64(target)
            notices=args.dest/'licenses'/name
            for index,member in enumerate(bundle.namelist()):
                lower=member.lower()
                if not member.endswith('/') and any(token in lower for token in ['license','copying','readme','buildconf']):
                    notices.mkdir(parents=True,exist_ok=True)
                    with bundle.open(member) as source,(notices/f'{index}-{Path(member).name}').open('wb') as output: shutil.copyfileobj(source,output)
            if not notices.is_dir(): raise ValueError(f'Missing {name} license notices')
        provenance[name]=spec
    (args.dest/'provenance.json').write_text(json.dumps(provenance,indent=2),encoding='utf-8')
    (args.dest/'THIRD_PARTY_NOTICES.md').write_text('''# GrabX bundled Windows tools

Node.js 22.23.4 (MIT and included third-party notices).
FFmpeg BtbN 8.1 LGPL static Windows x64 build; FFprobe included. No GPL flavor selected.
Tools run as separate processes. Exact upstream archive URLs, SHA-256, build and
source references are recorded in provenance.json. Upstream archive license/readme
files are preserved under licenses/. yt-dlp has its own embedded notices.
''',encoding='utf-8')
    print('Verified standalone Windows x64 tools:', args.dest)
if __name__=='__main__': main()
