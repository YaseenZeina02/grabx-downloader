import importlib.util
from pathlib import Path
import struct
import hashlib
import io
import tempfile
import unittest
from unittest.mock import patch
spec=importlib.util.spec_from_file_location('windows_tools',Path(__file__).resolve().parents[1]/'prepare-windows-tools.py')
tools=importlib.util.module_from_spec(spec);spec.loader.exec_module(tools)
class WindowsToolTests(unittest.TestCase):
    def test_checksum_is_required_and_verified_cache_avoids_network(self):
        payload=b'verified archive fixture'
        spec={'url':'https://example.invalid/tool.zip','sha256':hashlib.sha256(payload).hexdigest()}
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            with patch.object(tools.urllib.request,'urlopen',side_effect=lambda *a,**k:io.BytesIO(payload)) as request:
                result=tools.download(spec,root)
                self.assertEqual(result.read_bytes(),payload)
                self.assertEqual(tools.download(spec,root),result)
                self.assertEqual(request.call_count,1)
                result.write_bytes(b'corrupt cached data')
                self.assertEqual(tools.download(spec,root).read_bytes(),payload)
                self.assertEqual(request.call_count,2)
            result.unlink()
            with patch.object(tools.urllib.request,'urlopen',return_value=io.BytesIO(b'bad payload')):
                with self.assertRaises(ValueError):tools.download(spec,root)
            self.assertFalse(result.exists())
            self.assertFalse(result.with_suffix('.download').exists())
    def test_rejects_non_pe_and_non_x64_tools(self):
        with tempfile.TemporaryDirectory() as root:
            binary=Path(root)/'tool.exe';binary.write_bytes(b'not a Windows executable')
            with self.assertRaises(ValueError):tools.verify_x64(binary)
            image=bytearray(256);image[:2]=b'MZ';struct.pack_into('<I',image,0x3c,128);image[128:132]=b'PE\0\0';image[132:134]=b'\x64\x86';binary.write_bytes(image)
            tools.verify_x64(binary)
            image[132:134]=b'\x64\xaa';binary.write_bytes(image)
            with self.assertRaises(ValueError):tools.verify_x64(binary)
if __name__=='__main__':unittest.main()
