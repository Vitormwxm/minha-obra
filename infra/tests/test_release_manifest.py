import json
import pathlib
import subprocess
import sys
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "infra/deploy/write_release_env.py"
SHA = "a" * 40
REPO = "Vitormwxm/minha-obra"


class ReleaseManifestTests(unittest.TestCase):
    def invoke(self, mutate=None, sha=SHA):
        manifest = {
            "source_sha": SHA,
            "source_run_id": "12345",
            "backend_image": "ghcr.io/vitormwxm/minha-obra-backend@sha256:" + "1" * 64,
            "frontend_image": "ghcr.io/vitormwxm/minha-obra-frontend@sha256:" + "2" * 64,
        }
        if mutate:
            mutate(manifest)
        with tempfile.TemporaryDirectory() as temporary:
            directory = pathlib.Path(temporary)
            source = directory / "release.json"
            output = directory / "release.env"
            source.write_text(json.dumps(manifest), encoding="utf-8")
            result = subprocess.run(
                [sys.executable, str(SCRIPT), str(source), str(output),
                 "--sha", sha, "--repository", REPO],
                capture_output=True, text=True,
            )
            text = output.read_text(encoding="utf-8") if output.exists() else None
        return result, text

    def test_approved_commit_produces_only_two_immutable_image_variables(self):
        result, text = self.invoke()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(len(text.splitlines()), 2)
        self.assertTrue(text.startswith("BACKEND_IMAGE=ghcr.io/vitormwxm/"))
        self.assertNotIn("PASSWORD", text)

    def test_another_commit_never_generates_deploy_configuration(self):
        result, text = self.invoke(lambda value: value.update(source_sha="b" * 40))
        self.assertNotEqual(result.returncode, 0)
        self.assertIsNone(text)

    def test_mutable_tag_is_rejected(self):
        result, text = self.invoke(lambda value: value.update(backend_image="ghcr.io/vitormwxm/minha-obra-backend:latest"))
        self.assertNotEqual(result.returncode, 0)
        self.assertIsNone(text)

    def test_foreign_registry_and_repository_are_rejected(self):
        result, text = self.invoke(lambda value: value.update(frontend_image="evil.example/frontend@sha256:" + "2" * 64))
        self.assertNotEqual(result.returncode, 0)
        self.assertIsNone(text)

    def test_environment_line_injection_is_rejected(self):
        result, text = self.invoke(lambda value: value.update(backend_image=value["backend_image"] + "\nPOSTGRES_PASSWORD=changed"))
        self.assertNotEqual(result.returncode, 0)
        self.assertIsNone(text)

    def test_missing_ci_provenance_is_rejected(self):
        result, text = self.invoke(lambda value: value.pop("source_run_id"))
        self.assertNotEqual(result.returncode, 0)
        self.assertIsNone(text)

    def test_non_sha_input_is_rejected(self):
        result, text = self.invoke(sha="../../main")
        self.assertNotEqual(result.returncode, 0)
        self.assertIsNone(text)


if __name__ == "__main__":
    unittest.main()
