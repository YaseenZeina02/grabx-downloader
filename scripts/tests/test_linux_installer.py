import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

source = Path(__file__).resolve().parents[2] / 'browser-extension/native-host/install-linux.py'
spec = importlib.util.spec_from_file_location('linux_installer', source)
installer = importlib.util.module_from_spec(spec); spec.loader.exec_module(installer)

class InstallerTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="grabx-linux ' ")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.dist = self.root/'source'
        (self.dist/'lib').mkdir(parents=True)
        (self.dist/'lib/GrabX.jar').write_text('fixture')
        (self.dist/'lib/javafx-graphics-21-linux.jar').write_text('fixture')
        self.jdk = self.root/'JDK with spaces'
        (self.jdk/'bin').mkdir(parents=True)
        java = self.jdk/'bin/java'
        java.write_text('#!/bin/sh\nif [ "$1" = "--version" ]; then echo "openjdk 21.0.9"; else printf "%s" "$GRABX_APP_HOME"; fi\n')
        java.chmod(0o700)
    def install(self, **overrides):
        args = dict(extension_id='a'*32, distribution=self.dist, browser='chrome', java_home=self.jdk,
                    config_root=self.root/'config', data_root=self.root/'data')
        args.update(overrides)
        return installer.install(**args)
    def test_browser_paths_and_quoted_launcher(self):
        for browser, folder in installer.BROWSERS.items():
            manifest = self.install(browser=browser)
            self.assertEqual(manifest.parent.parent, self.root/'config'/folder)
            content = json.loads(manifest.read_text())
            self.assertEqual(content['allowed_origins'], ['chrome-extension://'+'a'*32+'/'])
            output = subprocess.check_output([content['path']], text=True)
            self.assertEqual(output, str(self.root/'data/GrabX/browser-bridge/app'))
    def test_reinstall_removes_stale_jars_only_inside_staged_app(self):
        self.install()
        old = self.root/'data/GrabX/browser-bridge/app/lib/old.jar'; old.write_text('old')
        self.install()
        self.assertFalse(old.exists())
        self.assertTrue((self.dist/'lib/GrabX.jar').exists())
    def test_rejects_wrong_platform_invalid_id_and_recursive_destination(self):
        with self.assertRaises(ValueError): self.install(extension_id='not-an-id')
        with self.assertRaises(ValueError): self.install(data_root=self.dist/'inside')
        (self.dist/'lib/javafx-graphics-21-linux.jar').unlink()
        with self.assertRaises(ValueError): self.install()

if __name__ == '__main__': unittest.main()
