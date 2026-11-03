import hashlib
import importlib.util
import pathlib
import tempfile
import unittest

path = pathlib.Path(__file__).with_name('fetch_competitors.py')
spec = importlib.util.spec_from_file_location('fetch_competitors', path)
fetch = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fetch)


class CacheTest(unittest.TestCase):
    def test_hash_of_file_matches_python_hashlib(self):
        with tempfile.TemporaryDirectory() as directory:
            p = pathlib.Path(directory) / 'asset.apk'
            p.write_bytes(b'abcd' * 1024)
            self.assertEqual(fetch.checksum_of(p), hashlib.sha256(p.read_bytes()).hexdigest())

    def test_cache_requires_verified_same_url_and_digest(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = pathlib.Path(directory) / 'demo.apk'
            apk.write_bytes(b'a' * 1_100_000)
            digest = fetch.checksum_of(apk)
            result = fetch.fetch_apk('https://example.org/demo.apk', apk, digest)
            self.assertEqual(result['status'], 'cached')
            self.assertTrue(result['sha256_verified'])
            old = {'url': 'https://example.org/demo.apk', 'sha256': digest}
            result = fetch.fetch_apk('https://example.org/demo.apk', apk, cached=old)
            self.assertEqual(result['status'], 'cached')
            self.assertFalse(result['sha256_verified'])


if __name__ == '__main__':
    unittest.main()
