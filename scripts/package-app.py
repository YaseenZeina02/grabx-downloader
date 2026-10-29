#!/usr/bin/env python3
"""Build on the target OS; bundle Java and emit a native image/installer.

Tool binaries must be standalone, licensed for distribution and match the target CPU.
No package manager, runtime downloads or changes to the developer's installed app.
"""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]

def command(args):
    print('+', ' '.join(map(str, args)), flush=True)
    subprocess.run(list(map(str, args)), check=True, cwd=ROOT)

def mac_icon(source, work, name):
    if not source.is_file(): raise FileNotFoundError(f'Missing branded icon: {source}')
    iconset = work / f'{name}.iconset'
    iconset.mkdir()
    for size in [16, 32, 128, 256, 512]:
        for scale in [1, 2]:
            filename = f'icon_{size}x{size}' + ('@2x' if scale == 2 else '') + '.png'
            command(['sips', '-z', str(size * scale), str(size * scale), source, '--out', iconset / filename])
    icon = work / f'{name}.icns'
    command(['iconutil', '-c', 'icns', iconset, '-o', icon])
    return icon

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--type', choices=['app-image', 'dmg', 'pkg', 'exe', 'msi', 'deb', 'rpm'], default='app-image')
    parser.add_argument('--version', default='1.0.0')
    parser.add_argument('--tools', type=Path, help='Standalone ffmpeg and node/deno plus their license files')
    parser.add_argument('--allow-missing-tools', action='store_true', help='Create a partial media-tools build; setup will report missing tools')
    parser.add_argument('--skip-build', action='store_true')
    parser.add_argument('--icon', type=Path)
    parser.add_argument('--dest', type=Path, help='Output directory (use a versioned path to keep previous builds)')
    args = parser.parse_args()
    system = platform.system()
    valid = {'Darwin': {'app-image', 'dmg', 'pkg'}, 'Windows': {'app-image', 'exe', 'msi'}, 'Linux': {'app-image', 'deb', 'rpm'}}
    if args.type not in valid.get(system, set()):
        parser.error('Build this installer on its destination operating system.')
    java = Path(os.environ.get('JAVA_HOME', ''))
    suffix = '.exe' if system == 'Windows' else ''
    jpackage = java / 'bin' / ('jpackage' + suffix)
    jlink = java / 'bin' / ('jlink' + suffix)
    if not jpackage.is_file() or not jlink.is_file():
        parser.error('Set JAVA_HOME to a JDK 21 containing jpackage and jlink.')
    if not args.skip_build:
        command([ROOT / ('gradlew.bat' if system == 'Windows' else 'gradlew'), 'test', 'installDist'])
    distribution = ROOT / 'build/install/GrabX'
    if not distribution.is_dir():
        parser.error('Run installDist before packaging.')
    work = ROOT / 'build/packaging' / f'{system}-{platform.machine()}'
    if work.exists(): shutil.rmtree(work)
    inputs = work / 'input'
    shutil.copytree(distribution / 'lib', inputs)
    jars = list(inputs.glob('GrabX*.jar'))
    if len(jars) != 1: parser.error('Expected one GrabX application jar.')
    with zipfile.ZipFile(jars[0]) as application:
        if not all(name in application.namelist() for name in ['browser-extension/chromium.zip',
                'com/grabx/app/grabx/icons/grabx.png', 'com/grabx/app/grabx/icons/grabx-setup.png']):
            parser.error('Rebuild installDist: embedded browser extension or branded icons are missing.')
        extension = application.read('browser-extension/chromium.zip')
    with zipfile.ZipFile(io.BytesIO(extension)) as archive:
        extension_version = json.loads(archive.read('manifest.json'))['version']
    tools = inputs / 'tools'
    if args.tools: shutil.copytree(args.tools.resolve(), tools)
    present = lambda name: (tools / (name + suffix)).is_file()
    complete = present('ffmpeg') and (present('node') or present('deno'))
    if not complete and not args.allow_missing_tools:
        parser.error('A complete setup requires --tools containing standalone ffmpeg and Node 22+/Deno 2.3+ with licenses. Use --allow-missing-tools only for an explicitly partial build.')
    if complete:
        if not (tools / 'THIRD_PARTY_NOTICES.md').is_file():
            parser.error('Include THIRD_PARTY_NOTICES.md and licenses with the standalone tools.')
        ffmpeg_version = subprocess.check_output([str(tools / ('ffmpeg' + suffix)), '-version'], text=True, timeout=15)
        if not ffmpeg_version.startswith('ffmpeg version'):
            parser.error('The bundled FFmpeg executable is invalid.')
        name = 'node' if present('node') else 'deno'
        version = subprocess.check_output([str(tools / (name + suffix)), '--version'], text=True, timeout=15)
        match = re.search(r'(?:deno\s+|v)(\d+)\.(\d+)', version)
        if not match or (name == 'node' and int(match[1]) < 22) or (name == 'deno' and (int(match[1]), int(match[2])) < (2, 3)):
            parser.error('Bundle Node 22+ or Deno 2.3+ for full YouTube support.')
    manifest = {'version': args.version, 'platform': system, 'architecture': platform.machine(), 'mediaToolsIncluded': complete,
                'browserExtensionIncluded': True, 'extensionVersion': extension_version,
                'extensionArchiveSHA256': hashlib.sha256(extension).hexdigest(),
                'tools': {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in tools.iterdir() if p.is_file()} if tools.is_dir() else {}}
    if complete:
        cpu = {'amd64': 'x64', 'x86_64': 'x64', 'aarch64': 'arm64', 'arm64': 'arm64'}.get(platform.machine().lower())
        if cpu is None: parser.error('Unsupported processor for bundled tool repair.')
        target = {'Darwin': 'darwin', 'Windows': 'win', 'Linux': 'linux'}[system] + '-' + cpu
        archive = inputs / 'tool-repair.zip'
        files = {p.relative_to(tools).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
                 for p in sorted(tools.rglob('*')) if p.is_file()}
        with zipfile.ZipFile(archive, 'w', zipfile.ZIP_DEFLATED) as repair:
            for name in files: repair.write(tools / name, name)
        repair_manifest = {'target': target, 'sha256': hashlib.sha256(archive.read_bytes()).hexdigest(),
                           'files': files, 'executables': [name + suffix for name in ['ffmpeg', 'ffprobe', 'node', 'deno'] if present(name)]}
        (inputs / 'tool-repair.json').write_text(json.dumps(repair_manifest, indent=2))
        manifest['offlineToolRepair'] = True
    (inputs / 'build-info.json').write_text(json.dumps(manifest, indent=2))
    command([jlink, '--add-modules', 'java.se,jdk.crypto.ec,jdk.unsupported', '--strip-debug', '--no-header-files', '--no-man-pages', '--output', work / 'runtime'])
    native = work / 'native.properties'
    native.write_text('main-class=com.grabx.app.grabx.browser.BrowserNativeHostMain\n' + ('win-console=true\nwin-shortcut=false\nwin-menu=false\n' if system == 'Windows' else ''))
    app_icon = args.icon.resolve() if args.icon else None
    setup_icon = None
    if system == 'Darwin':
        icons = ROOT / 'src/main/resources/com/grabx/app/grabx/icons'
        if app_icon is None: app_icon = mac_icon(icons / 'grabx.png', work, 'GrabX')
        setup_icon = mac_icon(icons / 'grabx-setup.png', work, 'GrabX-Setup')
    elif system == 'Windows':
        icons = ROOT / 'src/main/resources/com/grabx/app/grabx/icons'
        if app_icon is None: app_icon = icons / 'grabx.ico'
        setup_icon = icons / 'grabx-setup.ico'
    setup = work / 'setup.properties'
    setup.write_text('main-class=com.grabx.app.grabx.Launcher\narguments=--setup\n' + ('win-shortcut=false\nwin-menu=true\n' if system == 'Windows' else '') + (f'icon={setup_icon.as_posix()}\n' if setup_icon else ''))
    output = args.dest.resolve() if args.dest else ROOT / 'build/installers' / f'{system}-{platform.machine()}' / args.type
    output.mkdir(parents=True, exist_ok=True)
    if (output / ('GrabX.app' if system == 'Darwin' else 'GrabX')).exists():
        parser.error(f'Output already exists: {output}. Move your previous build before rebuilding.')
    separate_dmg = system == 'Darwin' and args.type == 'dmg'
    image_output = work / 'image' if separate_dmg else output
    options = [jpackage, '--type', 'app-image' if separate_dmg else args.type, '--name', 'GrabX', '--app-version', args.version,
               '--vendor', 'GrabX', '--description', 'Your downloads. Beautifully in control.',
               '--input', inputs, '--main-jar', jars[0].name, '--main-class', 'com.grabx.app.grabx.Launcher',
               '--runtime-image', work / 'runtime', '--dest', image_output,
               '--java-options', '-Dgrabx.packaged=true', '--add-launcher', f'grabx-native-host={native}',
               '--add-launcher', f'GrabX Setup={setup}']
    if app_icon: options += ['--icon', app_icon]
    if system == 'Darwin': options += ['--mac-package-identifier', 'com.grabx.app']
    if system == 'Windows' and args.type != 'app-image':
        options += ['--win-per-user-install', '--win-dir-chooser', '--win-menu', '--win-shortcut', '--win-upgrade-uuid', '2d05d6ea-9272-4ba2-a5d4-26960fa16fd3']
    if system == 'Linux' and args.type != 'app-image': options += ['--linux-shortcut', '--linux-menu-group', 'Network', '--linux-app-category', 'net']
    command(options)
    if separate_dmg:
        command([jpackage, '--type', 'dmg', '--name', 'GrabX', '--app-version', args.version,
                 '--app-image', image_output / 'GrabX.app', '--icon', setup_icon, '--dest', output])
    (output / 'build-info.json').write_text(json.dumps(manifest, indent=2))
    print(f'Created {output}\nMedia tools bundled: {complete}\nLocal unsigned build. Public release signing/notarization is a separate release step.')

if __name__ == '__main__': main()
