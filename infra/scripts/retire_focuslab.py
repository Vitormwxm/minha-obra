#!/usr/bin/env python3
"""Retire only the reviewed FocusLab deployment; never connect over SSH.

Default: read-only inventory and plan. Destruction requires all three flags:
  --apply --discard-data --deploy-disabled

The operator has authorized discarding FocusLab data. No backup is required.
--deploy-disabled asserts that the old GitHub deployment has already been disabled.
Unknown resources are preserved. Images, global TLS, Certbot, SSH and Python
services are never removed or stopped by this script.
"""

import argparse
from contextlib import contextmanager
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import sys
import threading
from dataclasses import dataclass, field


PROJECT = "focuslab-principal"
RELEASE = "8f9744c6b77d0ced8b19e645b4433cefc238f35f"
TARGETS = {
    "focuslab-frontend": ("frontend", f"focuslab-frontend:{RELEASE}"),
    "focuslab-backend": ("backend", f"focuslab-backend:{RELEASE}"),
    "focuslab-postgres": ("db", "postgres:15"),
}
VOLUMES = {
    "focuslab-principal_postgres_data": (PROJECT, "postgres_data"),
    "focuslab_caddy_config": ("focuslab", "caddy_config"),
    "focuslab_caddy_data": ("focuslab", "caddy_data"),
    "focuslab_flashcards_data": ("focuslab", "flashcards_data"),
    "focuslab_postgres_data": ("focuslab", "postgres_data"),
}
NETWORKS = {
    "focuslab-principal_default": (PROJECT, "default"),
    "focuslab_default": ("focuslab", "default"),
}
DIRECTORIES = (
    Path("/var/www/apps/focuslab-principal"),
    Path("/var/www/apps/focuslab"),
)
HOOK = Path("/etc/letsencrypt/renewal-hooks/deploy/focuslab-nginx")
EXPECTED_HOOK = b'''#!/usr/bin/env bash
set -euo pipefail

# Install as a Certbot deploy hook. Do not reload this site for other lineages.
if [[ -n "${RENEWED_LINEAGE:-}" && "$(basename -- "$RENEWED_LINEAGE")" != 'focuslabapp.com' ]]; then
  exit 0
fi
docker exec focuslab-frontend nginx -t
docker exec focuslab-frontend nginx -s reload
'''
HEX_ID = re.compile(r"^[0-9a-f]{64}$")
CONTAINER_FORMAT = (
    '{"id":{{json .Id}},"name":{{json .Name}},'
    '"image":{{json .Config.Image}},"status":{{json .State.Status}},'
    '"project":{{json (index .Config.Labels "com.docker.compose.project")}},'
    '"service":{{json (index .Config.Labels "com.docker.compose.service")}},'
    '"mounts":{{json .Mounts}},"networks":{{json .NetworkSettings.Networks}},'
    '"ports":{{json .HostConfig.PortBindings}}}'
)
VOLUME_FORMAT = (
    '{"name":{{json .Name}},"created":{{json .CreatedAt}},"driver":{{json .Driver}},'
    '"mountpoint":{{json .Mountpoint}},'
    '"device":{{if eq .Driver "local"}}{{if .Options}}{{json (index .Options "device")}}{{else}}null{{end}}{{else}}null{{end}},'
    '"custom_options":{{if .Options}}true{{else}}false{{end}},'
    '"project":{{json (index .Labels "com.docker.compose.project")}},'
    '"resource":{{json (index .Labels "com.docker.compose.volume")}}}'
)
NETWORK_FORMAT = (
    '{"id":{{json .Id}},"name":{{json .Name}},"driver":{{json .Driver}},'
    '"project":{{json (index .Labels "com.docker.compose.project")}},'
    '"resource":{{json (index .Labels "com.docker.compose.network")}},'
    '"containers":{{json .Containers}}}'
)


class Refusal(RuntimeError):
    """The observed deployment does not match the reviewed allowlist."""


def run(*args):
    result = subprocess.run(args, capture_output=True, text=True, check=False)
    if result.returncode:
        # Do not relay arbitrary daemon/process output, which might contain secrets.
        raise Refusal(f"Command failed: {' '.join(args[:3])} (exit {result.returncode})")
    return result.stdout.strip()


def json_run(*args):
    try:
        return json.loads(run(*args))
    except json.JSONDecodeError as exc:
        raise Refusal("Unexpected JSON from inventory command") from exc


def check_runtime(apply):
    if run("uname", "-s") != "Linux":
        raise Refusal("Run this script on the Ubuntu VPS itself")
    if run("lsb_release", "-is") != "Ubuntu" or run("lsb_release", "-rs") != "22.04":
        raise Refusal("Expected the reviewed Ubuntu 22.04 VPS")
    for variable in ("DOCKER_HOST", "DOCKER_CONTEXT", "DOCKER_TLS_VERIFY", "DOCKER_CERT_PATH"):
        if os.environ.get(variable):
            raise Refusal(f"Unset {variable}; only the local default Docker socket is allowed")
    endpoint = run("docker", "context", "inspect", "default", "--format", "{{.Endpoints.docker.Host}}")
    if endpoint != "unix:///var/run/docker.sock" or run("docker", "context", "show") != "default":
        raise Refusal("Expected the default local Docker socket")
    if run("docker", "version", "--format", "{{.Server.Version}}") != "29.1.3":
        raise Refusal("Docker version changed from the reviewed 29.1.3; review inventory first")
    if apply and run("id", "-u") != "0":
        raise Refusal("Apply must run as root on the VPS")
    if apply and not shutil.rmtree.avoids_symlink_attacks:
        raise Refusal("This Python runtime does not support protected directory removal")
    if apply and threading.active_count() != 1:
        raise Refusal("Directory retirement requires this single-threaded CLI process")


def inspect_container(container_id):
    if not HEX_ID.fullmatch(container_id):
        raise Refusal("Docker returned an invalid full container ID")
    item = json_run("docker", "inspect", "--format", CONTAINER_FORMAT, container_id)
    if item.get("id") != container_id:
        raise Refusal("Container identity changed during inspection")
    item["name"] = item["name"].removeprefix("/")
    return item


@dataclass
class Inventory:
    containers: dict = field(default_factory=dict)
    volumes: dict = field(default_factory=dict)
    networks: dict = field(default_factory=dict)


def inventory():
    result = Inventory()
    # Includes exited containers: their mounts still protect data from deletion.
    for container_id in run("docker", "ps", "-aq", "--no-trunc").splitlines():
        item = inspect_container(container_id)
        if item["name"] in result.containers:
            raise Refusal("Duplicate container name in inventory")
        result.containers[item["name"]] = item
    all_volumes = set(run("docker", "volume", "ls", "--format", "{{.Name}}").splitlines())
    # Inspect every volume: an unrelated local-driver bind volume can back onto
    # FocusLab storage even when Docker records no direct reference to its name.
    for name in sorted(all_volumes):
        result.volumes[name] = json_run("docker", "volume", "inspect", "--format", VOLUME_FORMAT, name)
    all_networks = set(run("docker", "network", "ls", "--format", "{{.Name}}").splitlines())
    for name in NETWORKS:
        if name in all_networks:
            result.networks[name] = json_run("docker", "network", "inspect", "--format", NETWORK_FORMAT, name)
            result.networks[name]["containers"] = result.networks[name].get("containers") or {}
    return result


def require_target(item):
    name = item["name"]
    service, image = TARGETS[name]
    if item.get("project") != PROJECT or item.get("service") != service or item.get("image") != image:
        raise Refusal(f"Identity/labels/image mismatch: {name}; nothing will be removed")
    if not HEX_ID.fullmatch(item["id"]):
        raise Refusal(f"Invalid ID: {name}")
    if set(item.get("networks") or {}) != {"focuslab-principal_default"}:
        raise Refusal(f"Unexpected network membership: {name}")
    mounts = item.get("mounts") or []
    if name == "focuslab-postgres":
        if len(mounts) != 1 or not (
            mounts[0].get("Type") == "volume"
            and mounts[0].get("Name") == "focuslab-principal_postgres_data"
            and mounts[0].get("Destination") == "/var/lib/postgresql/data"
        ):
            raise Refusal("PostgreSQL storage differs from the reviewed volume")
    elif name == "focuslab-backend" and mounts:
        raise Refusal("Backend has unexpected persistent mounts")
    elif name == "focuslab-frontend":
        expected = {
            ("/etc/letsencrypt", "/etc/nginx/ssl"),
            ("/var/www/certbot", "/var/www/certbot"),
        }
        actual = {(m.get("Source"), m.get("Destination")) for m in mounts}
        if len(mounts) != 2 or actual != expected or any(m.get("Type") != "bind" or m.get("RW") is not False for m in mounts):
            raise Refusal("Frontend TLS/ACME mounts differ from the reviewed read-only binds")
    published = {
        (port, binding.get("HostIp"), binding.get("HostPort"))
        for port, bindings in (item.get("ports") or {}).items()
        for binding in (bindings or [])
    }
    if name == "focuslab-postgres" and published != {("5432/tcp", "127.0.0.1", "5432")}:
        raise Refusal("PostgreSQL port binding differs from the reviewed localhost binding")
    if name == "focuslab-backend" and published:
        raise Refusal("Backend unexpectedly publishes a host port")
    if name == "focuslab-frontend":
        # Docker's HostConfig uses an empty HostIp for an all-interface binding.
        allowed = {(f"{p}/tcp", ip, str(p)) for p in (80, 443) for ip in ("", "0.0.0.0", "::")}
        if not published <= allowed or not all(any(binding[0] == f"{p}/tcp" for binding in published) for p in (80, 443)):
            raise Refusal("Frontend port bindings differ from the reviewed ports 80/443")


def consumers(inv, kind, name):
    found = set()
    storage = volume_paths(inv.volumes.get(name, {})) if kind == "volume" else set()
    for item in inv.containers.values():
        if kind == "volume" and any(
            (m.get("Type") == "volume" and m.get("Name") == name)
            or any(overlaps(source, target) for source in mount_paths(m, inv) for target in storage)
            for m in item.get("mounts", [])
        ):
            found.add(item["name"])
        if kind == "network" and name in (item.get("networks") or {}):
            found.add(item["name"])
    if kind == "network" and name in inv.networks:
        ids = {c["id"]: c["name"] for c in inv.containers.values()}
        for container_id, endpoint in (inv.networks[name].get("containers") or {}).items():
            found.add(ids.get(container_id, endpoint.get("Name") or container_id))
    if kind == "volume":
        # Preserve backing data referenced even by an unused foreign bind volume.
        for other_name, volume in inv.volumes.items():
            if other_name != name and any(overlaps(left, right) for left in volume_paths(volume) for right in storage):
                found.add(f"volume:{other_name}")
    return found


def owned_resource(item, name, expected):
    driver = "local" if name in VOLUMES else "bridge"
    return (
        item.get("name") == name
        and (item.get("project"), item.get("resource")) == expected
        and item.get("driver") == driver
        and not item.get("custom_options", False)
        and (name not in VOLUMES or storage_path(item.get("mountpoint")) is not None)
    )


def overlaps(left, right):
    return left == right or left in right.parents or right in left.parents


def storage_path(value):
    if not isinstance(value, str) or not value or not (Path(value).is_absolute() or value.startswith("/")):
        return None
    return Path(value).resolve()


def volume_paths(volume):
    # Only the necessary local backing path is projected, never the Options map.
    return {path for value in (volume.get("mountpoint"), volume.get("device") if volume.get("driver") == "local" else None) if (path := storage_path(value)) is not None}


def mount_paths(mount, inv):
    paths = set()
    source = storage_path(mount.get("Source"))
    if source is not None:
        paths.add(source)
    if mount.get("Type") == "volume":
        paths.update(volume_paths(inv.volumes.get(mount.get("Name"), {})))
    return paths


def mount_targets():
    data = json_run("findmnt", "--json", "--list", "--output", "TARGET")
    return [Path(item["target"]) for item in data.get("filesystems", [])]


def safe_directory(path, inv):
    if path not in DIRECTORIES:
        raise Refusal(f"Directory is not allowlisted: {path}")
    if path.is_symlink() or path.resolve() != path:
        raise Refusal(f"Directory or an ancestor is a symlink: {path}")
    if not path.exists():
        return False
    if not path.is_dir():
        raise Refusal(f"Expected a directory: {path}")
    for target in mount_targets():
        if target == path or path in target.parents:
            raise Refusal(f"A filesystem is mounted at or inside {path}")
    for item in inv.containers.values():
        for mount in item.get("mounts", []):
            if any(overlaps(source, path) for source in mount_paths(mount, inv)):
                raise Refusal(f"Directory is referenced by container {item['name']}: {path}")
    for name, volume in inv.volumes.items():
        if any(overlaps(source, path) for source in volume_paths(volume)):
            raise Refusal(f"Directory backs Docker volume {name}: {path}")
    return True


def known_hook():
    if HOOK.is_symlink() or HOOK.resolve() != HOOK:
        return False
    if not HOOK.is_file() or HOOK.stat().st_size > 8192:
        return False
    # Read only this exact, known hook. Never print contents or scan other hooks.
    return HOOK.read_bytes() == EXPECTED_HOOK


@dataclass
class Plan:
    targets: list = field(default_factory=list)
    volumes: list = field(default_factory=list)
    networks: list = field(default_factory=list)
    directories: list = field(default_factory=list)
    hook: bool = False
    preserved: list = field(default_factory=list)
    blockers: list = field(default_factory=list)


def build_plan(inv):
    plan = Plan()
    for name in TARGETS:
        if name in inv.containers:
            try:
                require_target(inv.containers[name])
                plan.targets.append(name)
            except Refusal as exc:
                plan.blockers.append(str(exc))
    plan.preserved.extend(f"container {name}" for name in inv.containers if name not in TARGETS)
    for kind, resources, allowlist, selected in (
        ("volume", inv.volumes, VOLUMES, plan.volumes),
        ("network", inv.networks, NETWORKS, plan.networks),
    ):
        for name, item in resources.items():
            if name not in allowlist:
                plan.preserved.append(f"{kind} {name}: outside the exact allowlist")
                continue
            external = consumers(inv, kind, name) - set(plan.targets)
            owned = owned_resource(item, name, allowlist[name])
            if owned and not external:
                selected.append(name)
            else:
                reason = "unexpected/missing Compose labels" if not owned else f"consumers: {', '.join(sorted(external))}"
                plan.preserved.append(f"{kind} {name}: {reason}")
                # A shared current database/network blocks retiring its services too.
                if name.startswith("focuslab-principal_"):
                    plan.blockers.append(f"Current {kind} is not exclusively owned: {name} ({reason})")
    for item in inv.containers.values():
        if item["name"] in plan.targets:
            for mount in item.get("mounts", []):
                if mount.get("Type") == "volume" and mount.get("Name") not in plan.volumes:
                    plan.blockers.append("Target storage is not verified for exclusive disposal")
            for name in item.get("networks") or {}:
                if name not in plan.networks:
                    plan.blockers.append("Target network is not verified for exclusive disposal")
    for path in DIRECTORIES:
        try:
            if safe_directory(path, inv):
                plan.directories.append(path)
        except Refusal as exc:
            plan.preserved.append(str(exc))
    plan.hook = known_hook()
    if not plan.hook and (HOOK.exists() or HOOK.is_symlink()):
        plan.preserved.append(f"Unrecognized/symlink Certbot hook: {HOOK}")
    return plan


def show_inventory(inv):
    print("READ-ONLY INVENTORY (includes exited containers; no env/keys/logs)")
    for item in inv.containers.values():
        print(json.dumps({key: item.get(key) for key in ("id", "name", "image", "status", "project", "service", "mounts", "ports")}, ensure_ascii=False))
        print("  networks=" + ",".join(sorted(item.get("networks") or {})))
    for kind, resources, allowlist in (("volume", inv.volumes, VOLUMES), ("network", inv.networks, NETWORKS)):
        for name in allowlist:
            item = resources.get(name)
            if item is None:
                print(f"{kind} {name}: absent")
            else:
                print(f"{kind} {name}: project={item.get('project')!r} resource={item.get('resource')!r} consumers={','.join(sorted(consumers(inv, kind, name))) or 'none'}")
    print("Images are listed for manual review only; this script never removes images.")


def show_plan(plan):
    print("\nRETIREMENT PLAN")
    for name in plan.targets:
        print(f"REMOVE container {name} (stop; rm without -v)")
    for name in plan.volumes:
        print(f"DISCARD volume {name} (only after all references disappear)")
    for name in plan.networks:
        print(f"REMOVE network {name} (only after all endpoints disappear)")
    for path in plan.directories:
        print(f"DISCARD exact directory {path}")
    if plan.hook:
        print(f"REMOVE verified FocusLab-only hook {HOOK}")
    for item in plan.preserved:
        print(f"PRESERVE {item}")
    for reason in plan.blockers:
        print(f"BLOCKED {reason}")
    print("PRESERVE /etc/letsencrypt, /var/www/certbot, all certificates, Certbot/timers, SSH, Python :8443, all images and all resources outside the exact allowlists.")
    print("The operator authorized discarding FocusLab data; no backup is required.")


def check_same_target(before):
    current = inspect_container(before["id"])
    require_target(current)
    # Status can change after stopping, but identity, storage and networking cannot.
    for key in ("id", "name", "image", "project", "service", "mounts"):
        if current.get(key) != before.get(key):
            raise Refusal(f"Container changed during retirement: {before['name']}")


def remove_resource(kind, name, before):
    inv = inventory()
    allowlist, items = (VOLUMES, inv.volumes) if kind == "volume" else (NETWORKS, inv.networks)
    if name not in allowlist:
        raise Refusal("Resource is not allowlisted")
    item = items.get(name)
    if item is None:
        return
    if item != before or not owned_resource(item, name, allowlist[name]) or consumers(inv, kind, name):
        raise Refusal(f"Resource changed or acquired a consumer: {kind} {name}")
    # Docker itself also refuses referenced volumes/networks; never force deletion.
    run("docker", kind, "rm", name)


def remove_directory(path):
    if threading.active_count() != 1 or not shutil.rmtree.avoids_symlink_attacks:
        raise Refusal("Protected directory removal requires the single-threaded Linux CLI")
    if not safe_directory(path, inventory()):
        return
    # Python 3.10 has fd-safe rmtree traversal but no dir_fd keyword. Anchor cwd
    # to the opened parent instead; this CLI is single-threaded and restores cwd
    # even on error. rmtree refuses a substituted root symlink and never follows
    # nested symlinks. No Python upgrade is needed on Ubuntu 22.04.
    parent_fd = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    cwd_fd = None
    try:
        cwd_fd = os.open(".", os.O_RDONLY | os.O_DIRECTORY)
        os.fchdir(parent_fd)
        shutil.rmtree(path.name)
    finally:
        if cwd_fd is not None:
            os.fchdir(cwd_fd)
            os.close(cwd_fd)
        os.close(parent_fd)


@contextmanager
def deployment_lock():
    # Match the former deployer's flock and hold it through the entire retirement.
    # A disabled workflow does not prove an already-started SSH process has exited.
    import fcntl

    root = DIRECTORIES[0]
    if not root.exists() and not root.is_symlink():
        yield
        return
    if root.is_symlink() or root.resolve() != root or not root.is_dir():
        raise Refusal("Deployment root is not the reviewed real directory")
    root_fd = os.open(root, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    lock_fd = None
    try:
        lock_fd = os.open(".deploy.lock", os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600, dir_fd=root_fd)
        if not stat.S_ISREG(os.fstat(lock_fd).st_mode):
            raise Refusal("The deployment lock is not a regular file")
        try:
            fcntl.flock(lock_fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as exc:
            raise Refusal("A FocusLab deployment still holds .deploy.lock; no cleanup was started") from exc
        yield
    finally:
        if lock_fd is not None:
            os.close(lock_fd)
        os.close(root_fd)


def apply_plan(inv, plan):
    if plan.blockers:
        raise Refusal("Resolve the printed inventory blockers before applying")
    with deployment_lock():
        apply_locked_plan(inv, plan)


def apply_locked_plan(inv, plan):
    fresh = inventory()
    if fresh != inv:
        raise Refusal("Docker inventory changed since the plan; rerun to review")
    # Recheck all safety conditions before the first mutation.
    if build_plan(fresh) != plan:
        raise Refusal("Filesystem or resource ownership changed since the plan")
    for name in plan.targets:  # frontend, backend, then PostgreSQL
        before = inv.containers[name]
        current = inventory()
        require_target(current.containers.get(name, {}))
        for mount in before.get("mounts", []):
            if mount.get("Type") == "volume" and consumers(current, "volume", mount["Name"]) - set(plan.targets):
                raise Refusal("A new external storage consumer appeared")
        for network in before.get("networks") or {}:
            if consumers(current, "network", network) - set(plan.targets):
                raise Refusal("A new external network consumer appeared")
        check_same_target(before)
        run("docker", "stop", "--time", "30", before["id"])
        check_same_target(before)
        run("docker", "rm", before["id"])
    for name in plan.volumes:
        remove_resource("volume", name, inv.volumes[name])
    for name in plan.networks:
        # Endpoints disappear as the verified containers are removed.
        before = dict(inv.networks[name], containers={})
        remove_resource("network", name, before)
    if plan.hook:
        if not known_hook():
            raise Refusal("Certbot hook changed; it was preserved")
        HOOK.unlink()
    for path in plan.directories:
        remove_directory(path)
    print("\nRetirement completed for the resources printed as removable. Preserved resources remain untouched.")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--apply", action="store_true", help="Execute the printed, strictly validated plan")
    parser.add_argument("--discard-data", action="store_true", help="Apply the operator's explicit decision to discard FocusLab data")
    parser.add_argument("--deploy-disabled", action="store_true", help="Assert that the old GitHub deployment is already disabled")
    args = parser.parse_args(argv)
    if args.apply and not (args.discard_data and args.deploy_disabled):
        parser.error("--apply requires --discard-data and --deploy-disabled; disable the old GitHub deployment first")
    check_runtime(args.apply)
    inv = inventory()
    plan = build_plan(inv)
    show_inventory(inv)
    show_plan(plan)
    if args.apply:
        apply_plan(inv, plan)
    else:
        print("\nPLAN ONLY: no service, volume, directory or hook was changed.")
        print("After disabling the old GitHub deployment and reviewing this inventory: sudo bash retire-focuslab.sh --apply --discard-data --deploy-disabled")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (Refusal, OSError, KeyError, TypeError, ValueError) as exc:
        print(f"REFUSED: {exc}", file=sys.stderr)
        sys.exit(1)
