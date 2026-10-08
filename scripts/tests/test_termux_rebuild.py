import importlib.util
from pathlib import Path
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location('rebuild', Path(__file__).parents[1] / 'rebuild-termux-aar.py')
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)

class RepackTests(unittest.TestCase):
    def test_preserve_and_deterministic(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d)
            with zipfile.ZipFile(p/'in.aar', 'w') as z:
                z.writestr('classes.jar', b'unchanged')
                z.writestr('assets/future', b'future')
                z.writestr('jni/armeabi-v7a/', b'')
                z.writestr('jni/arm64-v8a/libtermux.so', b'old')
            libs = {abi: b'new' for abi in m.ABIS}
            m.repack(p/'in.aar', p/'a.aar', libs, {})
            m.repack(p/'in.aar', p/'b.aar', libs, {})
            self.assertEqual((p/'a.aar').read_bytes(), (p/'b.aar').read_bytes())
            with zipfile.ZipFile(p/'a.aar') as z:
                self.assertEqual(z.read('classes.jar'), b'unchanged')
                self.assertEqual(z.read('assets/future'), b'future')
                self.assertNotIn('jni/armeabi-v7a/', z.namelist())
    def test_unsafe_paths(self):
        for name in ('../evil', '/evil', 'a/../evil', 'a\\evil'):
            with self.assertRaises(ValueError): m.safe_name(name)
    def test_wrong_pin(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d)/'file'; p.write_bytes(b'wrong')
            with self.assertRaises(ValueError): m.check_hash(p, '0'*64)

if __name__ == '__main__': unittest.main()
