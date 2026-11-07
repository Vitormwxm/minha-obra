#!/usr/bin/env python3
"""Plan/remove only unconsumed FocusLab application images with SHA commit tags.

Run after retire-focuslab.sh. Default is read-only. Apply requires:
  --apply --discard-data --deploy-disabled

No base images, dangling images, unknown namespaces, global cache, containers,
volumes, networks, TLS, SSH or Python processes are modified.
"""

import argparse
from dataclasses import dataclass, field
import json
import re
import sys

import retire_focuslab as base


IMAGE_ID = re.compile(r"^sha256:[0-9a-f]{64}$")
OWNED_TAG = re.compile(r"^focuslab-(?:backend|frontend):[0-9a-f]{40}$")
# Legacy references observed on this VPS after the core retirement. Both the
# immutable image ID and its single exact tag must match; latest alone is not proof.
REVIEWED_LEGACY = {
    "sha256:a1bf4f1cff4797b010dcb551a23a78b923637c7860af73d38237da53ed0944bf": "focuslab-principal-backend:latest",
    "sha256:f2e824e14eb90de0bb3ac2383d78e5355949042c7b5ae6af81604c1df8974391": "focuslab_app:latest",
}
IMAGE_FORMAT = '{"id":{{json .Id}},"tags":{{json .RepoTags}},"digests":{{json .RepoDigests}},"size":{{json .Size}}}'
CONSUMER_FORMAT = '{"id":{{json .Id}},"name":{{json .Name}},"image_id":{{json .Image}}}'


@dataclass
class ImageInventory:
    images: dict = field(default_factory=dict)
    containers: dict = field(default_factory=dict)


def inspect_image(image_id):
    if not IMAGE_ID.fullmatch(image_id):
        raise base.Refusal("Docker returned an invalid full image ID")
    item = base.json_run("docker", "image", "inspect", "--format", IMAGE_FORMAT, image_id)
    if item.get("id") != image_id:
        raise base.Refusal("Image identity changed during inspection")
    return item


def image_inventory():
    result = ImageInventory()
    for image_id in sorted(set(base.run("docker", "image", "ls", "-aq", "--no-trunc").splitlines())):
        result.images[image_id] = inspect_image(image_id)
    # Include stopped containers, including the two unknown image f98... consumers.
    for container_id in base.run("docker", "ps", "-aq", "--no-trunc").splitlines():
        if not base.HEX_ID.fullmatch(container_id):
            raise base.Refusal("Invalid container ID during image consumer discovery")
        item = base.json_run("docker", "inspect", "--format", CONSUMER_FORMAT, container_id)
        if item.get("id") != container_id:
            raise base.Refusal("Container changed during image consumer discovery")
        result.containers[container_id] = item
    return result


def image_consumers(inv, image_id):
    return sorted(item["name"].removeprefix("/") for item in inv.containers.values() if item.get("image_id") == image_id)


def preservation_reason(inv, item):
    tags = item.get("tags") or []
    if not tags or not all(OWNED_TAG.fullmatch(tag) or REVIEWED_LEGACY.get(item["id"]) == tag for tag in tags):
        return "unproven/dangling/shared repository tags"
    if item.get("digests"):
        return "repository digest references require separate ownership review"
    users = image_consumers(inv, item["id"])
    if users:
        return "container consumers (including exited): " + ", ".join(users)
    if len(tags) != 1:
        # Docker refuses image-ID deletion with multiple aliases unless forced.
        # Keep these aliases intact; this script never force-removes or untags.
        return "multiple approved tags; preserve aliases for explicit review"
    return None


def image_plan(inv):
    return [image_id for image_id, item in inv.images.items() if preservation_reason(inv, item) is None]


def show(inv, selected):
    print("FOCUSLAB IMAGE PLAN (read-only by default)")
    hidden = 0
    for image_id, item in inv.images.items():
        reason = preservation_reason(inv, item)
        if reason is not None and not any(tag.startswith("focuslab") for tag in item.get("tags") or []):
            hidden += 1
            continue
        print(json.dumps({"id": image_id, "tags": item.get("tags") or [], "size_bytes": item.get("size"), "consumers": image_consumers(inv, image_id)}))
        print("  " + ("REMOVE by exact ID, without --force" if reason is None else "PRESERVE " + reason))
    print(f"Preserved {hidden} other images outside the FocusLab allowlist (not enumerated).")
    print(f"Eligible images: {len(selected)}. Layer sharing means image size is not a promise of reclaimed disk space.")


def apply_images(inv, selected):
    with base.deployment_lock():
        if image_inventory() != inv:
            raise base.Refusal("Image/consumer inventory changed since the plan")
        for image_id in selected:
            current = image_inventory()
            item = current.images.get(image_id)
            if item != inv.images[image_id] or preservation_reason(current, item) is not None:
                raise base.Refusal("Image tags or consumers changed; image preserved")
            # No --force: Docker independently prevents deleting referenced images.
            base.run("docker", "image", "rm", image_id)
    print("Eligible FocusLab application images removed. Other images and global caches preserved.")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--discard-data", action="store_true")
    parser.add_argument("--deploy-disabled", action="store_true")
    args = parser.parse_args(argv)
    if args.apply and not (args.discard_data and args.deploy_disabled):
        parser.error("--apply requires --discard-data and --deploy-disabled")
    base.check_runtime(args.apply)
    inv = image_inventory()
    selected = image_plan(inv)
    show(inv, selected)
    if args.apply:
        apply_images(inv, selected)
    else:
        print("PLAN ONLY: no images changed. After the reviewed core retirement: sudo bash retire-focuslab-images.sh --apply --discard-data --deploy-disabled")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (base.Refusal, OSError, KeyError, TypeError, ValueError) as exc:
        print(f"REFUSED: {exc}", file=sys.stderr)
        sys.exit(1)
