"""Projected Docker mocks protect shared and stopped-container image consumers."""

import contextlib
import copy
import importlib.util
import io
import json
from pathlib import Path
import sys
import unittest
from unittest.mock import patch


SCRIPTS = Path(__file__).resolve().parents[1] / "scripts"
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))
SPEC = importlib.util.spec_from_file_location("retire_focuslab_images", SCRIPTS / "retire_focuslab_images.py")
images = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = images
SPEC.loader.exec_module(images)


def item(index, tags, digests=None):
    return {"id": "sha256:" + str(index) * 64, "tags": tags, "digests": digests or [], "size": 123456}


def fixture():
    inv = images.ImageInventory()
    for image in (
        item(1, ["focuslab-backend:" + "a" * 40]),
        item(2, ["focuslab-frontend:" + "b" * 40]),
        item(3, ["postgres:15"]),
        item(4, None),
        item(5, ["focuslab-backend:" + "c" * 40, "other-app:latest"]),
        item(6, ["focuslab-frontend:" + "d" * 40]),
        item(7, ["focuslab-backend:" + "e" * 40], ["other-app@sha256:" + "f" * 64]),
        item(8, ["focuslab-backend:" + "a" * 40, "focuslab-backend:" + "b" * 40]),
    ):
        inv.images[image["id"]] = image
    inv.containers["9" * 64] = {"id": "9" * 64, "name": "/sweet_golick", "image_id": "sha256:" + "6" * 64}
    return inv


class ImageTests(unittest.TestCase):
    def setUp(self):
        self.inv = fixture()

    def test_only_commit_tagged_exclusive_unconsumed_images_are_eligible(self):
        self.assertEqual(images.image_plan(self.inv), ["sha256:" + digit * 64 for digit in ("1", "2")])

    def test_unknown_namespace_local_latest_and_invalid_sha_are_preserved(self):
        for tag in ("focuslab-principal-backend:" + "a" * 40, "focuslab-backend:local", "focuslab-backend:latest", "focuslab-frontend:" + "a" * 39):
            image = item(1, [tag])
            self.assertIsNotNone(images.preservation_reason(self.inv, image))

    def test_stopped_container_image_id_protects_image_regardless_of_tag(self):
        image = self.inv.images["sha256:" + "6" * 64]
        self.assertIn("sweet_golick", images.preservation_reason(self.inv, image))

    def test_apply_removes_only_exact_selected_ids_without_force(self):
        current = copy.deepcopy(self.inv)
        calls = []

        def remove(*args):
            self.assertEqual(args[:3], ("docker", "image", "rm"))
            self.assertEqual(len(args), 4)
            self.assertIn(args[3], images.image_plan(current))
            calls.append(args)
            del current.images[args[3]]
            return args[3]

        with patch.object(images, "image_inventory", side_effect=lambda: copy.deepcopy(current)), patch.object(images.base, "deployment_lock", contextlib.nullcontext), patch.object(images.base, "run", side_effect=remove), contextlib.redirect_stdout(io.StringIO()):
            images.apply_images(self.inv, images.image_plan(self.inv))
        self.assertEqual([call[-1] for call in calls], ["sha256:" + digit * 64 for digit in ("1", "2")])
        self.assertIn("sha256:" + "6" * 64, current.images)
        self.assertIn("sha256:" + "3" * 64, current.images)

    def test_new_consumer_between_plan_and_apply_aborts_without_deletion(self):
        changed = copy.deepcopy(self.inv)
        changed.containers["a" * 64] = {"id": "a" * 64, "name": "another-app", "image_id": "sha256:" + "1" * 64}
        with patch.object(images, "image_inventory", return_value=changed), patch.object(images.base, "deployment_lock", contextlib.nullcontext), patch.object(images.base, "run") as run:
            with self.assertRaises(images.base.Refusal):
                images.apply_images(self.inv, images.image_plan(self.inv))
            run.assert_not_called()

    def test_missing_apply_flags_fail_before_inspection(self):
        with patch.object(images.base, "run") as run, contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit):
                images.main(["--apply", "--discard-data"])
            run.assert_not_called()

    def test_default_cli_has_no_mutation(self):
        with patch.object(images.base, "check_runtime"), patch.object(images, "image_inventory", return_value=self.inv), patch.object(images, "apply_images") as apply, contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(images.main([]), 0)
            apply.assert_not_called()

    def test_inventory_uses_projected_image_and_container_ids(self):
        calls = []

        def run(*args):
            calls.append(args)
            if args == ("docker", "image", "ls", "-aq", "--no-trunc"):
                return "\n".join(self.inv.images)
            if args == ("docker", "ps", "-aq", "--no-trunc"):
                return "\n".join(self.inv.containers)
            if args[:3] == ("docker", "image", "inspect"):
                self.assertEqual(args[3:5], ("--format", images.IMAGE_FORMAT))
                return json.dumps(self.inv.images[args[5]])
            self.assertEqual(args[:3], ("docker", "inspect", "--format"))
            self.assertEqual(args[3], images.CONSUMER_FORMAT)
            return json.dumps(self.inv.containers[args[4]])

        with patch.object(images.base, "run", side_effect=run):
            result = images.image_inventory()
        self.assertEqual(result, self.inv)
        self.assertFalse(any(token in ("prune", "--force", "-f", "rm") for call in calls for token in call))


if __name__ == "__main__":
    unittest.main()
