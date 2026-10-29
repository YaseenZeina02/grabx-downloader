#!/usr/bin/env python3
"""Fresh-user tests using the packaged runtime, relocated files and restricted PATH."""
import argparse, json, os, shutil, struct, subprocess, tempfile, time
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('image',type=Path);parser.add_argument('--windows-register',action='store_true');args=parser.parse_args()
    windows=os.name=='nt'; suffix='.exe' if windows else ''
    with tempfile.TemporaryDirectory(prefix='grabx-fresh-') as scratch:
        root=Path(scratch); app=root/'Fresh copy with spaces'/'GrabX';shutil.copytree(args.image.resolve(),app)
        content=app/'app' if windows else app/'Contents/app'
        runtime=app/'runtime/bin/java.exe' if windows else app/'Contents/runtime/Contents/Home/bin/java'
        native=app/'grabx-native-host.exe' if windows else app/'Contents/MacOS/grabx-native-host'
        launcher=app/'GrabX.exe' if windows else app/'Contents/MacOS/GrabX'
        helper=root/'helper';helper.mkdir()
        javac=Path(os.environ['JAVA_HOME'])/'bin'/('javac'+suffix)
        subprocess.run([str(javac),'-encoding','UTF-8','-cp',str(content/'*'),'-d',str(helper),str(ROOT/'scripts/PackagedAppSmoke.java')],check=True)
        home=root/'empty-home';home.mkdir()
        env={k:v for k,v in os.environ.items() if k not in ['JAVA_HOME','CLASSPATH','JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS']}
        env.update(HOME=str(home),USERPROFILE=str(home),LOCALAPPDATA=str(home/'AppData/Local'),APPDATA=str(home/'AppData/Roaming'))
        env['PATH']=str(Path(os.environ.get('SystemRoot','C:/Windows'))/'System32') if windows else '/usr/bin:/bin'
        flags=['-Dgrabx.packaged=true',f'-Duser.home={home}',f'-Djava.util.prefs.userRoot={home}/prefs',f'-Djpackage.app-path={launcher}']
        subprocess.run([str(runtime),*flags,'-cp',str(content/'*')+os.pathsep+str(helper),'PackagedAppSmoke',*(['register'] if args.windows_register else [])],env=env,cwd=root,check=True,timeout=300)
        env['JAVA_TOOL_OPTIONS']=' '.join('"'+flag+'"' for flag in flags[:3])
        payload=json.dumps({'type':'status'}).encode()
        reply=subprocess.run([str(native)],input=struct.pack('=I',len(payload))+payload,capture_output=True,env=env,cwd=root,check=True,timeout=30)
        assert len(reply.stdout)>4,reply.stderr.decode(errors='replace')
        assert struct.unpack('=I',reply.stdout[:4])[0]==len(reply.stdout)-4
        assert json.loads(reply.stdout[4:])['ok'] is True
        process=subprocess.Popen([str(launcher),'--setup'],env=env,cwd=root,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
        try:
            time.sleep(7)
            if process.poll() is not None: raise AssertionError(process.communicate())
            print('PASS: fresh packaged Setup stayed open without system Java/Node/FFmpeg')
        finally:
            if process.poll() is None:
                process.terminate()
                try: process.communicate(timeout=15)
                except subprocess.TimeoutExpired: process.kill();process.communicate()
        if windows and args.windows_register:
            import winreg
            for browser in ['Google\\Chrome','BraveSoftware\\Brave-Browser','Microsoft\\Edge','Chromium']:
                key='Software\\'+browser+'\\NativeMessagingHosts\\com.grabx.browser_bridge'
                with winreg.OpenKey(winreg.HKEY_CURRENT_USER,key) as entry:
                    manifest=Path(winreg.QueryValueEx(entry,'')[0]);assert manifest.is_file()
                winreg.DeleteKey(winreg.HKEY_CURRENT_USER,key)
        print('PASS: relocated native launcher, clean framed protocol and optional per-user browser registrations')
if __name__=='__main__': main()
