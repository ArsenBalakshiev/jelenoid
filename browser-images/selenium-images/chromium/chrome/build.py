#!/usr/bin/env python3
"""
Сборка и публикация Chrome-образов Jelenoid.

Резолвит последнюю Stable-версию Chrome с googlechromelabs и собирает
оба варианта (headless + VNC) с тегами:

  suomessa/jelenoid:chrome-<version>            (headless, конкретная версия)
  suomessa/jelenoid:chrome-latest               (headless, alias на latest stable)
  suomessa/jelenoid:chrome-<version>-vnc        (VNC)
  suomessa/jelenoid:chrome-latest-vnc           (VNC, alias)

CI usage:
    python build.py              # build only, no push
    python build.py --push       # build + push to docker hub
    python build.py --registry ghcr.io/myorg --push
    python build.py --skip-base  # assume jelenoid/selenium-base:bookworm already built
    python build.py --version 150.0.7871.24   # pin a specific version

Exit codes: 0 = OK, 1 = build/push error, 2 = bad args.
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
BASE_DIR = SCRIPT_DIR.parent / "base"
DEFAULT_BASE_IMAGE = "jelenoid/selenium-base:bookworm"
DEFAULT_REGISTRY = "suomessa/jelenoid"
JSON_URL = "https://googlechromelabs.github.io/chrome-for-testing/last-known-good-versions-with-downloads.json"


class C:
    RED = "\033[31m"
    GREEN = "\033[32m"
    BLUE = "\033[34m"
    YELLOW = "\033[33m"
    RESET = "\033[0m"


def red(s): return f"{C.RED}{s}{C.RESET}"
def green(s): return f"{C.GREEN}{s}{C.RESET}"
def blue(s): return f"{C.BLUE}{s}{C.RESET}"
def yellow(s): return f"{C.YELLOW}{s}{C.RESET}"


def info(msg): print(blue(f">>> {msg}"))
def ok(msg): print(green(f"  [OK] {msg}"))
def warn(msg): print(yellow(f"  [WARN] {msg}"))


def run(cmd: list[str], *, check: bool = True, capture: bool = False) -> str:
    res = subprocess.run(cmd, check=False, text=True,
                         stdout=subprocess.PIPE if capture else None,
                         stderr=subprocess.PIPE if capture else None)
    if check and res.returncode != 0:
        out = (res.stdout or "") + (res.stderr or "")
        raise RuntimeError(f"command failed (exit {res.returncode}): {' '.join(cmd)}\n{out.strip()}")
    return (res.stdout or "") if capture else ""


def docker_image_exists(image: str) -> bool:
    return subprocess.run(
        ["docker", "image", "inspect", image],
        check=False, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    ).returncode == 0


def resolve_latest_stable() -> dict:
    """Returns {version, chrome_url, chromedriver_url} for current Stable."""
    info(f"resolving latest stable from {JSON_URL}")
    try:
        with urllib.request.urlopen(JSON_URL, timeout=15) as r:
            data = json.load(r)
    except (urllib.error.URLError, json.JSONDecodeError) as e:
        raise RuntimeError(f"failed to fetch chrome versions json: {e}")
    stable = data["channels"]["Stable"]
    version = stable["version"]
    def url_for(kind: str) -> str:
        for d in stable["downloads"][kind]:
            if d["platform"] == "linux64":
                return d["url"]
        raise RuntimeError(f"no linux64 entry for {kind}")
    return {
        "version": version,
        "chrome_url": url_for("chrome"),
        "chromedriver_url": url_for("chromedriver"),
    }


def build_base(skip: bool) -> None:
    if skip:
        if not docker_image_exists(DEFAULT_BASE_IMAGE):
            raise RuntimeError(f"--skip-base set but {DEFAULT_BASE_IMAGE} not present")
        ok(f"{DEFAULT_BASE_IMAGE} already present")
        return
    if docker_image_exists(DEFAULT_BASE_IMAGE):
        ok(f"{DEFAULT_BASE_IMAGE} already present (skipping rebuild)")
        return
    info(f"building base image {DEFAULT_BASE_IMAGE}")
    run(["docker", "build", "-t", DEFAULT_BASE_IMAGE,
         "-f", str(BASE_DIR / "Dockerfile.base"), str(BASE_DIR)])
    ok(f"built {DEFAULT_BASE_IMAGE}")


def build_variant(variant: str, version: str, registry: str) -> list[str]:
    """Builds one variant (headless or vnc). Returns list of tags applied."""
    if variant == "headless":
        dockerfile = "Dockerfile"
        suffix = ""
    elif variant == "vnc":
        dockerfile = "Dockerfile.vnc"
        suffix = "-vnc"
    else:
        raise ValueError(variant)

    tag_versioned = f"{registry}:chrome-{version}{suffix}"
    tag_latest = f"{registry}:chrome-latest{suffix}"
    build_tags = [tag_versioned]
    if not suffix:
        # `chrome-latest` is the alias; only the headless variant gets a primary "latest" tag.
        # VNC variant gets its own `:chrome-latest-vnc`.
        pass
    # we always tag both versioned + latest
    build_tags.append(tag_latest)

    info(f"building {variant} -> {', '.join(build_tags)}")
    cmd = [
        "docker", "build",
        "--build-arg", f"CHROME_VERSION={version}",
        "-t", tag_versioned,
        "-t", tag_latest,
        "-f", str(SCRIPT_DIR / dockerfile),
        str(SCRIPT_DIR),
    ]
    run(cmd)
    for t in build_tags:
        ok(t)
    return build_tags


def push_tags(tags: list[str]) -> None:
    for t in tags:
        info(f"pushing {t}")
        run(["docker", "push", t])
        ok(f"pushed {t}")


def main() -> int:
    p = argparse.ArgumentParser(description="Build and optionally push Jelenoid Chrome images")
    p.add_argument("--registry", default=DEFAULT_REGISTRY,
                   help="image registry+namespace prefix. Examples: "
                        "'suomessa/jelenoid' (Docker Hub), 'ghcr.io/balakshievas/jelenoid', "
                        "'ghcr.io/balakshievas' (auto-appends '/jelenoid'), "
                        f"or just 'myorg' (treated as 'myorg/jelenoid'). Default: {DEFAULT_REGISTRY}")
    p.add_argument("--version", default=None,
                   help="pin a specific Chrome version (default: auto-detect latest stable)")
    p.add_argument("--skip-base", action="store_true",
                   help=f"don't rebuild {DEFAULT_BASE_IMAGE}")
    p.add_argument("--no-vnc", action="store_true", help="build only the headless variant")
    p.add_argument("--push", action="store_true", help="push images to registry after build")
    p.add_argument("--dry-run", action="store_true", help="print what would be done, do nothing")
    args = p.parse_args()

    # Normalize registry. Rules:
    #   "<host>/<ns>"            -> "<host>/<ns>/jelenoid"   (e.g. ghcr.io/user)
    #   "<host>/<ns>/<repo>"     -> unchanged                 (e.g. ghcr.io/user/jelenoid)
    #   "<ns>/<repo>"            -> unchanged                 (Docker Hub form: user/repo)
    #   "<ns>"                   -> "<ns>/jelenoid"
    # OCI requires all repository name parts to be lowercase, so we lowercase
    # the namespace (e.g. "ArsenBalakshiev" -> "arsenbalakshiev"). GHCR stores
    # case-preserving display names, but docker push enforces lowercase.
    reg = args.registry.rstrip("/")
    parts = reg.split("/")
    KNOWN_HOSTS = ("ghcr.io", "quay.io", "gcr.io", "registry.gitlab.com", "docker.io")
    if parts[0] in KNOWN_HOSTS:
        if len(parts) <= 2:
            reg = f"{reg}/jelenoid"
    else:
        if len(parts) == 1:
            reg = f"{reg}/jelenoid"
    # OCI requires all repository name parts to be lowercase. The host
    # (e.g. "ghcr.io") is always lowercase anyway. Lowercase everything
    # after the host for known registries, and the whole thing for
    # Docker-Hub-style input.
    if parts[0] in KNOWN_HOSTS and "/" in reg:
        host, _, rest = reg.partition("/")
        reg = f"{host}/" + "/".join(p.lower() for p in rest.split("/"))
    else:
        reg = reg.lower()
    args.registry = reg

    try:
        # 1. Resolve version
        if args.version:
            version = args.version
            info(f"using pinned version: {version}")
        else:
            resolved = resolve_latest_stable()
            version = resolved["version"]
            info(f"latest stable: {version}")
            info(f"  chrome:       {resolved['chrome_url']}")
            info(f"  chromedriver: {resolved['chromedriver_url']}")

        if args.dry_run:
            for v in (["headless"] if args.no_vnc else ["headless", "vnc"]):
                suffix = "" if v == "headless" else "-vnc"
                print(f"  would build: {args.registry}:chrome-{version}{suffix}")
                print(f"  would build: {args.registry}:chrome-latest{suffix}")
            if args.push:
                print("  would push all built tags")
            return 0

        # 2. Build base
        build_base(args.skip_base)

        # 3. Build variants
        all_tags: list[str] = []
        for variant in (["headless"] if args.no_vnc else ["headless", "vnc"]):
            tags = build_variant(variant, version, args.registry)
            all_tags.extend(tags)

        # 4. Push if requested
        if args.push:
            # de-dup preserving order
            seen = set()
            unique = [t for t in all_tags if not (t in seen or seen.add(t))]
            push_tags(unique)
        else:
            info("skipping push (use --push to upload to registry)")

        print()
        print(green("=" * 60))
        print(green("BUILD SUCCESSFUL"))
        print(green("=" * 60))
        print(f"  version: {version}")
        for t in dict.fromkeys(all_tags):
            print(f"  tag:     {t}")
        if not args.push:
            print()
            print(yellow("Images are local only. Re-run with --push to publish."))
        return 0

    except RuntimeError as e:
        print(red(f"FAIL: {e}"))
        return 1


if __name__ == "__main__":
    sys.exit(main())
