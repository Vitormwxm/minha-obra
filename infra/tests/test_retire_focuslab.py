"""Safety regression tests; no Docker daemon, SSH or production files are used."""

import contextlib
import copy
import importlib.util
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch


MODULE_PATH = Path(__file__).resolve().parents[1] / "scripts" / "retire_focuslab.py"
SPEC = importlib.util.spec_from_file_location("retire_focuslab", MODULE_PATH)
retire = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = retire
SPEC.loader.exec_module(retire)


def fixture():
    inv = retire.Inventory()
    for index, (name, (service, image)) in enumerate(retire.TARGETS.items(), 1):
        inv.containers[name] = {
            "id": str(index) * 64, "name": name, "image": image,
            "status": "running", "project": retire.PROJECT, "service": service,
            "mounts": [], "networks": {"focuslab-principal_default": {}}, "ports": {},
        }
    inv.containers["focuslab-postgres"]["mounts"] = [{
        "Type": "volume", "Name": "focuslab-principal_postgres_data",
        "Source": "/var/lib/docker/volumes/focuslab-principal_postgres_data/_data",
        "Destination": "/var/lib/postgresql/data", "RW": True,
    }]
    inv.containers["focuslab-postgres"]["ports"] = {
        "5432/tcp": [{"HostIp": "127.0.0.1", "HostPort": "5432"}],
    }
    frontend = inv.containers["focuslab-frontend"]
    frontend["mounts"] = [
        {"Type": "bind", "Source": "/etc/letsencrypt", "Destination": "/etc/nginx/ssl", "RW": False},
        {"Type": "bind", "Source": "/var/www/certbot", "Destination": "/var/www/certbot", "RW": False},
    ]
    frontend["ports"] = {
        f"{port}/tcp": [{"HostIp": "0.0.0.0", "HostPort": str(port)}] for port in (80, 443)
    }
    for index, name in enumerate(("sweet_golick", "hardcore_mcnulty"), 4):
        inv.containers[name] = {
            "id": str(index) * 64, "name": name, "image": "f98ac8855de9",
            "status": "exited", "project": None, "service": None,
            "mounts": [], "networks": {}, "ports": {},
        }
    for name, (project, resource) in retire.VOLUMES.items():
        inv.volumes[name] = {
            "name": name, "project": project, "resource": resource,
            "created": "2026-09-27T00:00:00Z", "driver": "local", "custom_options": False,
            "mountpoint": f"/var/lib/docker/volumes/{name}/_data", "device": None,
        }
    for index, (name, (project, resource)) in enumerate(retire.NETWORKS.items(), 6):
        inv.networks[name] = {
            "id": str(index) * 64, "name": name, "project": project,
            "resource": resource, "driver": "bridge", "containers": {},
        }
    inv.networks["focuslab-principal_default"]["containers"] = {
        item["id"]: {"Name": name} for name, item in inv.containers.items() if name in retire.TARGETS
    }
    return inv


class DockerModel:
    """Implements only the projected read commands and exact permitted mutations."""

    def __init__(self, inv):
        self.inv = copy.deepcopy(inv)
        self.calls = []
        self.mutations = []

    def run(self, *args):
        self.calls.append(args)
        if args == ("docker", "ps", "-aq", "--no-trunc"):
            return "\n".join(item["id"] for item in self.inv.containers.values())
        if args[:3] == ("docker", "inspect", "--format"):
            assert args[3] == retire.CONTAINER_FORMAT
            item = next(c for c in self.inv.containers.values() if c["id"] == args[4])
            return json.dumps(dict(item, name="/" + item["name"]))
        if args[:3] in (("docker", "volume", "ls"), ("docker", "network", "ls")):
            source = self.inv.volumes if args[1] == "volume" else self.inv.networks
            return "\n".join(source)
        if args[:3] in (("docker", "volume", "inspect"), ("docker", "network", "inspect")):
            source = self.inv.volumes if args[1] == "volume" else self.inv.networks
            expected = retire.VOLUME_FORMAT if args[1] == "volume" else retire.NETWORK_FORMAT
            assert args[3:5] == ("--format", expected)
            return json.dumps(source[args[5]])
        if args[:2] == ("docker", "stop"):
            assert args[2:4] == ("--time", "30")
            item = next(c for c in self.inv.containers.values() if c["id"] == args[4])
            assert item["name"] in retire.TARGETS
            item["status"] = "exited"
            self.mutations.append(args)
            return item["id"]
        if args[:2] == ("docker", "rm"):
            assert len(args) == 3
            item = next(c for c in self.inv.containers.values() if c["id"] == args[2])
            assert item["name"] in retire.TARGETS
            assert item["status"] == "exited"
            del self.inv.containers[item["name"]]
            for network in self.inv.networks.values():
                network["containers"].pop(item["id"], None)
            self.mutations.append(args)
            return item["id"]
        if args[:3] in (("docker", "volume", "rm"), ("docker", "network", "rm")):
            assert len(args) == 4
            kind, name = args[1], args[3]
            source = self.inv.volumes if kind == "volume" else self.inv.networks
            assert not retire.consumers(self.inv, kind, name)
            assert name in (retire.VOLUMES if kind == "volume" else retire.NETWORKS)
            del source[name]
            self.mutations.append(args)
            return name
        raise AssertionError(f"Unexpected command: {args}")


class RetirementTests(unittest.TestCase):
    def setUp(self):
        self.inv = fixture()
        self.stack = contextlib.ExitStack()
        self.addCleanup(self.stack.close)
        self.stack.enter_context(patch.object(retire, "safe_directory", return_value=False))
        self.stack.enter_context(patch.object(retire, "known_hook", return_value=False))
        self.stack.enter_context(patch.object(retire, "deployment_lock", contextlib.nullcontext))
        self.stack.enter_context(contextlib.redirect_stdout(io.StringIO()))

    def test_inventory_includes_exited_and_never_reads_env_or_full_inspect(self):
        model = DockerModel(self.inv)
        with patch.object(retire, "run", side_effect=model.run):
            result = retire.inventory()
        self.assertIn("sweet_golick", result.containers)
        self.assertIn("hardcore_mcnulty", result.containers)
        self.assertEqual(model.mutations, [])
        self.assertNotIn(".Config.Env", retire.CONTAINER_FORMAT)
        self.assertNotIn(".Config.Cmd", retire.CONTAINER_FORMAT)
        self.assertTrue(all("--format" in call for call in model.calls if call[:2] == ("docker", "inspect")))

    def test_default_cli_is_plan_only(self):
        model = DockerModel(self.inv)
        with patch.object(retire, "check_runtime"), patch.object(retire, "run", side_effect=model.run):
            self.assertEqual(retire.main([]), 0)
        self.assertEqual(model.mutations, [])

    def test_apply_requires_both_explicit_flags_before_any_command(self):
        for flags in (["--apply"], ["--apply", "--discard-data"], ["--apply", "--deploy-disabled"]):
            with self.subTest(flags=flags), patch.object(retire, "run") as run, contextlib.redirect_stderr(io.StringIO()):
                with self.assertRaises(SystemExit):
                    retire.main(flags)
                run.assert_not_called()

    def test_apply_removes_only_verified_names_and_preserves_unknown_containers(self):
        model = DockerModel(self.inv)
        with patch.object(retire, "run", side_effect=model.run):
            baseline = retire.inventory()
            plan = retire.build_plan(baseline)
            self.assertFalse(plan.blockers)
            retire.apply_plan(baseline, plan)
        self.assertEqual(set(model.inv.containers), {"sweet_golick", "hardcore_mcnulty"})
        self.assertFalse(model.inv.volumes)
        self.assertFalse(model.inv.networks)
        stops = [call[-1] for call in model.mutations if call[:2] == ("docker", "stop")]
        self.assertEqual(stops, [self.inv.containers[name]["id"] for name in retire.TARGETS])
        self.assertFalse(any(token in ("prune", "--force", "-f", "-v", "down") for call in model.mutations for token in call))

    def test_mismatched_identity_mounts_or_ports_block_all_mutation(self):
        changes = (
            ("focuslab-backend", "project", "another-app"),
            ("focuslab-backend", "service", "other-service"),
            ("focuslab-backend", "image", "other-app:latest"),
            ("focuslab-postgres", "mounts", []),
            ("focuslab-postgres", "ports", {"5432/tcp": [{"HostIp": "0.0.0.0", "HostPort": "5432"}]}),
            ("focuslab-frontend", "networks", {"shared": {}}),
        )
        for name, key, value in changes:
            with self.subTest(key=key):
                inv = copy.deepcopy(self.inv)
                inv.containers[name][key] = value
                plan = retire.build_plan(inv)
                self.assertTrue(plan.blockers)
                with patch.object(retire, "run") as run, self.assertRaises(retire.Refusal):
                    retire.apply_plan(inv, plan)
                run.assert_not_called()

    def test_stopped_foreign_volume_consumer_blocks_current_database_retirement(self):
        self.inv.containers["sweet_golick"]["mounts"] = copy.deepcopy(self.inv.containers["focuslab-postgres"]["mounts"])
        plan = retire.build_plan(self.inv)
        self.assertTrue(plan.blockers)
        self.assertNotIn("focuslab-principal_postgres_data", plan.volumes)

    def test_foreign_bind_and_volume_source_aliases_block_before_any_mutation(self):
        name = "focuslab-principal_postgres_data"
        mountpoint = self.inv.volumes[name]["mountpoint"]
        for mount_type in ("bind", "volume"):
            for source in (mountpoint, mountpoint + "/subdirectory", mountpoint.rsplit("/", 1)[0]):
                with self.subTest(mount_type=mount_type, source=source):
                    inv = copy.deepcopy(self.inv)
                    inv.containers["sweet_golick"]["mounts"] = [{"Type": mount_type, "Source": source, "Name": "unrelated-volume"}]
                    plan = retire.build_plan(inv)
                    self.assertTrue(plan.blockers)
                    self.assertNotIn(name, plan.volumes)
                    with patch.object(retire, "run") as run, self.assertRaises(retire.Refusal):
                        retire.apply_plan(inv, plan)
                    run.assert_not_called()

    def test_unused_foreign_bind_volume_backing_on_target_data_blocks_deletion(self):
        name = "focuslab-principal_postgres_data"
        self.inv.volumes["other-app-data"] = {
            "name": "other-app-data", "driver": "local", "custom_options": True,
            "mountpoint": "/var/lib/docker/volumes/other-app-data/_data",
            "device": self.inv.volumes[name]["mountpoint"] + "/nested",
        }
        plan = retire.build_plan(self.inv)
        self.assertTrue(plan.blockers)
        self.assertNotIn(name, plan.volumes)

    def test_inventory_inspects_foreign_volume_backing_paths_without_options_map(self):
        self.inv.volumes["another-app"] = {
            "name": "another-app", "driver": "local", "custom_options": True,
            "mountpoint": "/var/lib/docker/volumes/another-app/_data",
            "device": "/var/www/apps/focuslab/other-app-data",
        }
        model = DockerModel(self.inv)
        with patch.object(retire, "run", side_effect=model.run):
            result = retire.inventory()
        self.assertIn("another-app", result.volumes)
        self.assertIn('index .Options "device"', retire.VOLUME_FORMAT)
        self.assertNotIn("json .Options", retire.VOLUME_FORMAT)
        self.assertNotIn("another-app", retire.build_plan(result).volumes)

    def test_stopped_foreign_network_consumer_blocks_current_retirement(self):
        self.inv.containers["hardcore_mcnulty"]["networks"] = {"focuslab-principal_default": {}}
        self.assertTrue(retire.build_plan(self.inv).blockers)

    def test_network_endpoint_missing_from_ps_still_blocks_deletion(self):
        self.inv.networks["focuslab-principal_default"]["containers"]["a" * 64] = {"Name": "unobserved-app"}
        self.assertTrue(retire.build_plan(self.inv).blockers)

    def test_unverified_or_shared_legacy_resources_are_preserved(self):
        self.inv.volumes["focuslab_caddy_data"]["project"] = None
        self.inv.volumes["focuslab_caddy_config"]["custom_options"] = True
        self.inv.containers["sweet_golick"]["mounts"] = [{"Type": "volume", "Name": "focuslab_flashcards_data"}]
        self.inv.networks["focuslab_default"]["project"] = "another-app"
        plan = retire.build_plan(self.inv)
        for name in ("focuslab_caddy_data", "focuslab_caddy_config", "focuslab_flashcards_data"):
            self.assertNotIn(name, plan.volumes)
        self.assertNotIn("focuslab_default", plan.networks)
        self.assertFalse(plan.blockers)

    def test_changed_inventory_aborts_before_first_mutation(self):
        plan = retire.build_plan(self.inv)
        changed = copy.deepcopy(self.inv)
        changed.containers["focuslab-backend"]["id"] = "b" * 64
        with patch.object(retire, "inventory", return_value=changed), patch.object(retire, "run") as run:
            with self.assertRaises(retire.Refusal):
                retire.apply_plan(self.inv, plan)
            run.assert_not_called()

    def test_new_consumer_or_replaced_volume_prevents_resource_removal(self):
        name = "focuslab_postgres_data"
        for replacement in (False, True):
            changed = copy.deepcopy(self.inv)
            if replacement:
                changed.volumes[name]["created"] = "2026-11-06T23:00:00Z"
            else:
                changed.containers["sweet_golick"]["mounts"] = [{"Type": "volume", "Name": name}]
            with self.subTest(replacement=replacement), patch.object(retire, "inventory", return_value=changed), patch.object(retire, "run") as run:
                with self.assertRaises(retire.Refusal):
                    retire.remove_resource("volume", name, self.inv.volumes[name])
                run.assert_not_called()

    def test_new_bind_alias_before_volume_rm_is_refused(self):
        name = "focuslab_postgres_data"
        changed = copy.deepcopy(self.inv)
        changed.containers["hardcore_mcnulty"]["mounts"] = [{
            "Type": "bind", "Source": changed.volumes[name]["mountpoint"] + "/new-shared-data",
        }]
        with patch.object(retire, "inventory", return_value=changed), patch.object(retire, "run") as run:
            with self.assertRaises(retire.Refusal):
                retire.remove_resource("volume", name, self.inv.volumes[name])
            run.assert_not_called()

    def test_short_container_ids_are_rejected(self):
        with patch.object(retire, "run") as run, self.assertRaises(retire.Refusal):
            retire.inspect_container("f98ac8855de9")
        run.assert_not_called()

    def test_empty_host_ip_is_the_reviewed_docker_wildcard_binding(self):
        for binding in self.inv.containers["focuslab-frontend"]["ports"].values():
            binding[0]["HostIp"] = ""
        self.assertFalse(retire.build_plan(self.inv).blockers)


class FilesystemSafetyTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve() / "focuslab-principal"
        self.root.mkdir()
        self.stack = contextlib.ExitStack()
        self.addCleanup(self.stack.close)
        self.stack.enter_context(patch.object(retire, "DIRECTORIES", (self.root,)))
        self.stack.enter_context(patch.object(retire, "mount_targets", return_value=[]))

    def test_only_exact_resolved_directory_is_allowed(self):
        self.assertTrue(retire.safe_directory(self.root, retire.Inventory()))
        for path in (self.root.parent, self.root / "child"):
            with self.assertRaises(retire.Refusal):
                retire.safe_directory(path, retire.Inventory())

    def test_foreign_bind_including_parent_blocks_directory_removal(self):
        for mount_type in ("bind", "volume"):
            for source in (self.root / "data", self.root.parent):
                inv = retire.Inventory(containers={"other": {"name": "other", "mounts": [{"Type": mount_type, "Source": str(source)}]}})
                with self.subTest(mount_type=mount_type), self.assertRaises(retire.Refusal):
                    retire.safe_directory(self.root, inv)

    def test_foreign_local_bind_volume_device_protects_directory_even_if_unused(self):
        inv = retire.Inventory(volumes={"other-data": {
            "name": "other-data", "driver": "local", "custom_options": True,
            "mountpoint": str(self.root.parent / "docker-volume-mountpoint"),
            "device": str(self.root / "shared-storage"),
        }})
        with self.assertRaises(retire.Refusal):
            retire.safe_directory(self.root, inv)

    def test_volume_device_alias_is_checked_for_containers_with_other_sources(self):
        inv = retire.Inventory(
            containers={"other": {"name": "other", "status": "exited", "mounts": [{
                "Type": "volume", "Name": "other-data", "Source": str(self.root.parent / "docker-volume-mountpoint"),
            }]}},
            volumes={"other-data": {
                "name": "other-data", "driver": "local", "mountpoint": str(self.root.parent / "docker-volume-mountpoint"),
                "device": str(self.root),
            }},
        )
        with self.assertRaises(retire.Refusal):
            retire.safe_directory(self.root, inv)

    def test_nested_mount_blocks_directory_removal(self):
        with patch.object(retire, "mount_targets", return_value=[self.root / "mounted-data"]):
            with self.assertRaises(retire.Refusal):
                retire.safe_directory(self.root, retire.Inventory())

    def test_directory_symlink_is_preserved(self):
        link = self.root.parent / "focuslab-link"
        try:
            link.symlink_to(self.root, target_is_directory=True)
        except OSError:
            self.skipTest("Creating symlinks requires platform privileges")
        with patch.object(retire, "DIRECTORIES", (link,)), self.assertRaises(retire.Refusal):
            retire.safe_directory(link, retire.Inventory())

    def test_symlink_alias_to_volume_data_is_detected_as_a_consumer(self):
        link = self.root.parent / "data-alias"
        try:
            link.symlink_to(self.root, target_is_directory=True)
        except OSError:
            self.skipTest("Creating symlinks requires platform privileges")
        inv = retire.Inventory(
            containers={"foreign": {"name": "foreign", "status": "exited", "mounts": [{"Type": "bind", "Source": str(link)}]}},
            volumes={"target": {"name": "target", "driver": "local", "mountpoint": str(self.root)}},
        )
        self.assertEqual(retire.consumers(inv, "volume", "target"), {"foreign"})

    def test_only_exact_known_hook_can_be_removed(self):
        hook = self.root / "focuslab-nginx"
        with patch.object(retire, "HOOK", hook):
            hook.write_bytes(retire.EXPECTED_HOOK)
            self.assertTrue(retire.known_hook())
            hook.write_bytes(retire.EXPECTED_HOOK + b"\n# custom hook: preserve\n")
            self.assertFalse(retire.known_hook())

    @unittest.skipUnless(sys.platform == "linux", "fcntl locks are specific to the target Linux VPS")
    def test_active_deploy_lock_blocks_retirement(self):
        import fcntl
        with (self.root / ".deploy.lock").open("wb") as existing:
            fcntl.flock(existing, fcntl.LOCK_EX | fcntl.LOCK_NB)
            with self.assertRaises(retire.Refusal), retire.deployment_lock():
                self.fail("Must not enter cleanup while deployment is active")

    @unittest.skipUnless(sys.platform == "linux", "fd-anchored removal runs on the target Linux VPS")
    def test_real_directory_removal_uses_python310_signature_and_restores_cwd(self):
        outside = self.root.parent / "preserved"
        outside.mkdir()
        sentinel = outside / "keep.txt"
        sentinel.write_text("keep", encoding="utf-8")
        (self.root / "nested").mkdir()
        (self.root / "nested" / "discard.txt").write_text("discard", encoding="utf-8")
        (self.root / "outside-symlink").symlink_to(outside, target_is_directory=True)
        original_cwd = Path.cwd()
        real_rmtree = retire.shutil.rmtree
        calls = []

        # Deliberately supports only Python 3.10's positional path API. A dir_fd
        # keyword would fail this test before the real protected traversal runs.
        def python310_rmtree(path):
            calls.append((path, Path.cwd()))
            real_rmtree(path)

        python310_rmtree.avoids_symlink_attacks = True
        with patch.object(retire, "inventory", return_value=retire.Inventory()), patch.object(retire.shutil, "rmtree", python310_rmtree):
            retire.remove_directory(self.root)
        self.assertEqual(calls, [(self.root.name, self.root.parent)])
        self.assertFalse(self.root.exists())
        self.assertEqual(sentinel.read_text(encoding="utf-8"), "keep")
        self.assertEqual(Path.cwd(), original_cwd)

    @unittest.skipUnless(sys.platform == "linux", "fd-anchored removal runs on the target Linux VPS")
    def test_directory_removal_restores_cwd_on_error(self):
        original_cwd = Path.cwd()

        def failing_rmtree(path):
            raise OSError("simulated deletion failure")

        failing_rmtree.avoids_symlink_attacks = True
        with patch.object(retire, "inventory", return_value=retire.Inventory()), patch.object(retire.shutil, "rmtree", failing_rmtree):
            with self.assertRaises(OSError):
                retire.remove_directory(self.root)
        self.assertEqual(Path.cwd(), original_cwd)
        self.assertTrue(self.root.exists())


class RuntimeSafetyTests(unittest.TestCase):
    def test_local_linux_and_docker_version_are_required(self):
        responses = {
            ("uname", "-s"): "Linux",
            ("lsb_release", "-is"): "Ubuntu",
            ("lsb_release", "-rs"): "22.04",
            ("docker", "context", "inspect", "default", "--format", "{{.Endpoints.docker.Host}}"): "unix:///var/run/docker.sock",
            ("docker", "context", "show"): "default",
            ("docker", "version", "--format", "{{.Server.Version}}"): "29.1.3",
        }
        with patch.dict(retire.os.environ, {}, clear=True), patch.object(retire, "run", side_effect=lambda *args: responses[args]):
            retire.check_runtime(False)
            responses[("docker", "context", "show")] = "remote"
            with self.assertRaises(retire.Refusal):
                retire.check_runtime(False)

    def test_remote_docker_environment_is_rejected_without_printing_value(self):
        with patch.dict(retire.os.environ, {"DOCKER_HOST": "ssh://private-secret"}, clear=True), patch.object(retire, "run", side_effect=["Linux", "Ubuntu", "22.04"]):
            with self.assertRaises(retire.Refusal) as error:
                retire.check_runtime(False)
            self.assertNotIn("private-secret", str(error.exception))


if __name__ == "__main__":
    unittest.main()
