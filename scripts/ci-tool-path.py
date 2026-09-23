"""Expose the current platform's bundled extractor to offline integration tests."""
import os
from pathlib import Path
import platform
import zipfile
root = Path('src/main/resources/tools/yt-dlp').resolve()
cpu = 'arm64' if platform.machine().lower() in ('aarch64','arm64') else 'x64'
if platform.system() == 'Darwin':
    dest = Path(os.environ['RUNNER_TEMP']) / 'grabx-ytdlp'
    with zipfile.ZipFile(root/'mac/runtime-2026.08.19.zip') as archive: archive.extractall(dest)
    executable = dest/'yt-dlp_macos'
elif platform.system() == 'Windows': executable = root/'windows'/cpu/'yt-dlp.exe'
else: executable = root/'linux'/cpu/'yt-dlp'
if os.name != 'nt': executable.chmod(0o700)
with open(os.environ['GITHUB_ENV'], 'a', encoding='utf-8') as output:
    output.write(f'GRABX_TEST_YTDLP={executable}\n')
