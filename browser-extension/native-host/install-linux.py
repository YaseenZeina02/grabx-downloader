#!/usr/bin/env python3
"""Register GrabX for the current Linux user; no sudo or shell-profile edits."""
import argparse
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import tempfile

BROWSERS = {'chrome': 'google-chrome', 'chromium': 'chromium',
            'brave': 'BraveSoftware/Brave-Browser', 'edge': 'microsoft-edge'}

def install(extension_id, distribution, browser, java_home=None, config_root=None, data_root=None):
    if not re.fullmatch('[a-p]{32}', extension_id):
        raise ValueError('Expected the 32-letter extension ID from the browser extensions page')
    distribution = Path(distribution).resolve(strict=True)
    if not (distribution / 'lib/GrabX.jar').is_file() or not list((distribution / 'lib').glob('javafx-graphics-*-linux*.jar')):
        raise ValueError('Build installDist on Linux first; this is not a Linux GrabX distribution')
    java = Path(java_home or os.environ.get('JAVA_HOME', '')) / 'bin/java' if java_home or os.environ.get('JAVA_HOME') else shutil.which('java')
    if not java:
        raise ValueError('Install Java 21+ or pass --java-home with your IntelliJ JDK')
    java = Path(java).resolve(strict=True)
    version = subprocess.run([str(java), '--version'], capture_output=True, text=True, timeout=10, check=True).stdout
    match = re.search(r'(?:openjdk|java) (\d+)', version)
    if not match or int(match[1]) < 21:
        raise ValueError('Java 21 or newer is required')
    config = Path(config_root or os.environ.get('XDG_CONFIG_HOME') or Path.home() / '.config').expanduser()
    data = Path(data_root or os.environ.get('XDG_DATA_HOME') or Path.home() / '.local/share').expanduser()
    if not config.is_absolute() or not data.is_absolute():
        raise ValueError('Config and data directories must be absolute')
    target = data / 'GrabX/browser-bridge'
    app = target / 'app'
    if target.resolve().is_relative_to(distribution):
        raise ValueError('Installation destination must not be inside the source distribution')
    target.mkdir(parents=True, exist_ok=True)
    if distribution != app.resolve():
        # Rebuild a separate directory so obsolete JAR versions do not accumulate.
        with tempfile.TemporaryDirectory(prefix='.install-', dir=target) as temporary:
            prepared = Path(temporary) / 'app'
            shutil.copytree(distribution, prepared)
            previous = Path(temporary) / 'previous'
            if app.exists(): app.rename(previous)
            try: prepared.rename(app)
            except Exception:
                if previous.exists(): previous.rename(app)
                raise
    wrapper = target / 'grabx-native-host'
    wrapper.write_text('#!/bin/sh\nexport GRABX_APP_HOME=' + shlex.quote(str(app)) + '\nexec '
                       + shlex.quote(str(java)) + ' -cp ' + shlex.quote(str(app / 'lib/*'))
                       + ' com.grabx.app.grabx.browser.BrowserNativeHostMain "$@"\n', encoding='utf-8')
    wrapper.chmod(0o700)
    manifest = config / BROWSERS[browser] / 'NativeMessagingHosts/com.grabx.browser_bridge.json'
    manifest.parent.mkdir(parents=True, exist_ok=True)
    manifest.write_text(json.dumps({'name': 'com.grabx.browser_bridge', 'description': 'GrabX browser bridge',
        'path': str(wrapper), 'type': 'stdio', 'allowed_origins': [f'chrome-extension://{extension_id}/']}, indent=2), encoding='utf-8')
    manifest.chmod(0o600)
    return manifest

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('extension_id')
    parser.add_argument('distribution', help='Linux build/install/GrabX directory')
    parser.add_argument('--browser', choices=BROWSERS, default='chrome')
    parser.add_argument('--java-home')
    parser.add_argument('--config-root', help='Default: XDG_CONFIG_HOME or ~/.config')
    parser.add_argument('--data-root', help='Default: XDG_DATA_HOME or ~/.local/share')
    args = parser.parse_args()
    try:
        manifest = install(**vars(args))
    except (ValueError, OSError, subprocess.SubprocessError) as error:
        parser.exit(1, str(error) + '\n')
    print(f'Installed: {manifest}\nReload the extension. Re-run this installer after rebuilding GrabX.')

if __name__ == '__main__':
    main()
