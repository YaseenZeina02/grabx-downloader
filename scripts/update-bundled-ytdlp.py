#!/usr/bin/env python3
"""Refresh Windows/Linux yt-dlp binaries using the official release checksums."""
import concurrent.futures
import hashlib
import pathlib
import urllib.request

VERSION = '2026.08.19'
ROOT = pathlib.Path(__file__).resolve().parents[1] / 'src/main/resources/tools/yt-dlp'
ASSETS = {
    'windows/x64/yt-dlp.exe': 'yt-dlp.exe',
    'windows/arm64/yt-dlp.exe': 'yt-dlp_arm64.exe',
    'windows/x86/yt-dlp.exe': 'yt-dlp_x86.exe',
    'linux/x64/yt-dlp': 'yt-dlp_linux',
    'linux/arm64/yt-dlp': 'yt-dlp_linux_aarch64',
}
BASE = f'https://github.com/yt-dlp/yt-dlp/releases/download/{VERSION}/'

def fetch(name):
    return urllib.request.urlopen(urllib.request.Request(BASE + name, headers={'User-Agent': 'GrabX-maintenance'}), timeout=60)

def main():
    with fetch('SHA2-256SUMS') as response:
        sums = {name.strip().lstrip('*'): digest for digest, name in
                (line.split(maxsplit=1) for line in response.read().decode().splitlines() if line.strip())}
    def download(item):
        relative, name = item
        target = ROOT / relative
        temporary = target.with_suffix(target.suffix + '.download')
        digest = hashlib.sha256()
        try:
            with fetch(name) as response, temporary.open('wb') as output:
                while chunk := response.read(1024 * 1024):
                    output.write(chunk); digest.update(chunk)
            if digest.hexdigest() != sums[name]:
                raise ValueError(f'Checksum mismatch: {name}')
            temporary.replace(target)
            print(f'Verified {relative}', flush=True)
        finally:
            temporary.unlink(missing_ok=True)
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
        list(pool.map(download, ASSETS.items()))
    (ROOT / 'bundled.properties').write_text('version=' + VERSION + '\n' + ''.join(
        f'{relative}={sums[name]}\n' for relative, name in ASSETS.items()), encoding='utf-8')

if __name__ == '__main__':
    main()
