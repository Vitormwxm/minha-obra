import base64
import importlib.util
import os
from pathlib import Path
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / "deploy" / "initialize_auth_key.py"
spec = importlib.util.spec_from_file_location("auth_key", SCRIPT)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class KeyValidationTests(unittest.TestCase):
    def test_rejects_duplicate_empty_or_invalid_keys(self):
        good = base64.b64encode(bytes(range(32)))
        for content in [b"APP_TOTP_ENCRYPTION_KEY=\n", b"APP_TOTP_ENCRYPTION_KEY=REPLACE\n",
                        b"APP_TOTP_ENCRYPTION_KEY=" + base64.b64encode(b"short"),
                        b"APP_TOTP_ENCRYPTION_KEY=" + good + b"\nAPP_TOTP_ENCRYPTION_KEY=" + good]:
            with self.subTest(content_length=len(content)), self.assertRaises(ValueError):
                module.has_valid_key(content)

    def test_accepts_compose_quoted_valid_key(self):
        good = base64.b64encode(bytes(range(32)))
        self.assertTrue(module.has_valid_key(b"APP_TOTP_ENCRYPTION_KEY='" + good + b"'\n"))
        self.assertTrue(module.has_valid_key(b"export APP_TOTP_ENCRYPTION_KEY=" + good + b"\n"))


@unittest.skipUnless(os.name == "posix", "VPS helper requires POSIX file locks")
class KeyFilesystemTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name) / ".env"
        self.original = b"APP_DOMAIN=example.test\nPOSTGRES_PASSWORD=fixture-password"
        self.path.write_bytes(self.original)
        self.path.chmod(0o600)

    def test_initializes_random_key_once_and_preserves_every_existing_byte(self):
        self.assertTrue(module.initialize(self.path))
        first = self.path.read_bytes()
        self.assertTrue(first.startswith(self.original + b"\n"))
        self.assertTrue(module.has_valid_key(first))
        self.assertFalse(module.initialize(self.path))
        self.assertEqual(first, self.path.read_bytes())
        self.assertEqual(0o600, self.path.stat().st_mode & 0o777)

    def test_does_not_replace_invalid_existing_key(self):
        content = self.original + b"\nAPP_TOTP_ENCRYPTION_KEY=invalid\n"
        self.path.write_bytes(content)
        with self.assertRaises(ValueError):
            module.initialize(self.path)
        self.assertEqual(content, self.path.read_bytes())

    def test_does_not_follow_symlink_or_modify_another_file(self):
        link = self.path.with_name("alias.env")
        link.symlink_to(self.path)
        with self.assertRaises(ValueError):
            module.initialize(link)
        self.assertEqual(self.original, self.path.read_bytes())

    def test_rejects_hardlinks_and_world_readable_config(self):
        alias = self.path.with_name("hardlink.env")
        os.link(self.path, alias)
        with self.assertRaises(ValueError):
            module.initialize(self.path)
        alias.unlink()
        self.path.chmod(0o644)
        with self.assertRaises(ValueError):
            module.initialize(self.path)
        self.assertEqual(self.original, self.path.read_bytes())

    def test_readonly_existing_valid_key_is_preserved(self):
        module.initialize(self.path)
        content = self.path.read_bytes()
        self.path.chmod(0o400)
        self.assertFalse(module.initialize(self.path))
        self.assertEqual(content, self.path.read_bytes())

    def test_missing_key_after_initialization_is_not_silently_regenerated(self):
        module.initialize(self.path)
        self.path.write_bytes(self.original)
        with self.assertRaises(ValueError):
            module.initialize(self.path)
        self.assertEqual(self.original, self.path.read_bytes())

    def test_changed_key_after_initialization_requires_operator_recovery(self):
        module.initialize(self.path)
        replacement = self.original + b"\nAPP_TOTP_ENCRYPTION_KEY=" + base64.b64encode(bytes(range(32))) + b"\n"
        self.path.write_bytes(replacement)
        with self.assertRaises(ValueError):
            module.initialize(self.path)
        self.assertEqual(replacement, self.path.read_bytes())

    def test_rejects_symlink_for_private_marker(self):
        other = self.path.with_name("other-file")
        other.write_bytes(b"Do not change")
        self.path.with_name(".auth-key.sha256").symlink_to(other)
        with self.assertRaises((OSError, ValueError)):
            module.initialize(self.path)
        self.assertEqual(self.original, self.path.read_bytes())
        self.assertEqual(b"Do not change", other.read_bytes())


if __name__ == "__main__":
    unittest.main()
