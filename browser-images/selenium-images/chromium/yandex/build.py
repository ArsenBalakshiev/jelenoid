#!/usr/bin/env python3
"""
Сборка и публикация Yandex-образов Jelenoid.

Резолвит последнюю Stable-версию Yandex Browser из официального apt-репозитория
и подбирает подходящий YandexDriver с GitHub (https://github.com/yandex/YandexDriver/releases).
Собирает два варианта:

  suomessa/jelenoid:yandex-<browser-version>     (headless, на базе apt-пакета yandex-browser-stable)
  suomessa/jelenoid:yandex-latest                (alias на latest)
  suomessa/jelenoid:yandex-<browser-version>-vnc (VNC: Xvfb + openbox + x11vnc)
  suomessa/jelenoid:yandex-latest-vnc            (VNC, alias)

Оба образа совместимы с jelenoid-server / Selenoid:
  - WebDriver endpoint на :4444 (/status, /session, /wd/hub/status, /wd/hub/session)
  - CDP-прокси на :7070 (/devtools/page/{sessionId})
  - VNC-вариант публикует :5900

Поскольку Yandex Browser - это Chromium 146+ с обвязкой Yandex, переиспользуются
те же cdp-proxy и wd-proxy, что и в chrome-варианте. YandexDriver бинарно-совместим
с chromedriver (W3C WebDriver + CDP).

CI usage:
    python build.py                          # build only, no push
    python build.py --push                   # build + push to docker hub
    python build.py --registry ghcr.io/myorg --push
    python build.py --skip-base              # assume jelenoid/selenium-base:bookworm already built
    python build.py --version 26.4.1.1110-1  # pin a specific deb version
    python build.py --driver-url <url>       # pin a specific YandexDriver zip URL

Exit codes: 0 = OK, 1 = build/push error, 2 = bad args.
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import urllib.error
import urllib.request
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
BASE_DIR = SCRIPT_DIR.parent / "base"
DEFAULT_BASE_IMAGE = "jelenoid/selenium-base:bookworm"
DEFAULT_REGISTRY = "suomessa/jelenoid"
YANDEX_PACKAGES_URL = "https://repo.yandex.ru/yandex-browser/deb/dists/stable/main/binary-amd64/Packages"
YANDEXDRIVER_API_URL = "https://api.github.com/repos/yandex/YandexDriver/releases"


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


def parse_version(v: str) -> tuple[int, ...]:
    """Parse 26.4.1.1110 → (26, 4, 1, 1110). Non-numeric suffixes raise."""
    parts: list[int] = []
    for chunk in v.split("."):
        if not chunk.isdigit():
            raise ValueError(f"non-numeric version chunk in {v!r}")
        parts.append(int(chunk))
    if not parts:
        raise ValueError(f"empty version: {v!r}")
    return tuple(parts)


def parse_apt_packages(text: str) -> dict[str, dict[str, str]]:
    """Parses a deb Packages file. Returns {package_name: {key: value, ...}}."""
    blocks: dict[str, dict[str, str]] = {}
    current: str | None = None
    for line in text.splitlines():
        if not line.strip():
            current = None
            continue
        if line.startswith(" "):
            continue
        if ":" not in line:
            continue
        key, _, value = line.partition(":")
        key = key.strip()
        value = value.strip()
        if key == "Package":
            current = value
            blocks.setdefault(current, {})
        elif current is not None:
            blocks[current][key] = value
    return blocks


def resolve_browser(pinned: str | None) -> dict:
    """Resolves Yandex Browser Stable from apt repo. Returns:
        {version, browser_revision, deb_revision, major_minor, deb_url}
    If `pinned` is given, just validates and returns derived fields.
    """
    if pinned and pinned != "latest-stable":
        m = re.match(r"^(\d+(?:\.\d+)+)(?:-(\d+))?$", pinned)
        if not m:
            raise RuntimeError(f"invalid --version format: {pinned!r} (expected e.g. 26.4.1.1110-1)")
        browser_rev = m.group(1)
        deb_rev = m.group(2) or "0"
        major_minor = ".".join(browser_rev.split(".")[:2])
        return {
            "version": pinned,
            "browser_revision": browser_rev,
            "deb_revision": deb_rev,
            "major_minor": major_minor,
            "deb_url": "",
        }

    info(f"resolving latest Yandex Browser from {YANDEX_PACKAGES_URL}")
    try:
        with urllib.request.urlopen(YANDEX_PACKAGES_URL, timeout=20) as r:
            text = r.read().decode("utf-8", errors="replace")
    except (urllib.error.URLError, TimeoutError) as e:
        raise RuntimeError(f"failed to fetch {YANDEX_PACKAGES_URL}: {e}")

    packages = parse_apt_packages(text)
    stable = packages.get("yandex-browser-stable")
    if not stable or "Version" not in stable or "Filename" not in stable:
        raise RuntimeError("yandex-browser-stable not found in Packages index")

    ver = stable["Version"]
    m = re.match(r"^(\d+(?:\.\d+)+)(?:-(\d+))?$", ver)
    if not m:
        raise RuntimeError(f"unexpected Version format in Packages: {ver!r}")
    browser_rev = m.group(1)
    deb_rev = m.group(2) or "0"
    major_minor = ".".join(browser_rev.split(".")[:2])
    deb_url = "https://repo.yandex.ru/yandex-browser/deb/" + stable["Filename"]

    info(f"  YANDEX_VERSION:          {ver}")
    info(f"  YANDEX_BROWSER_REVISION: {browser_rev}")
    info(f"  YANDEX_MAJOR_MINOR:      {major_minor}")
    return {
        "version": ver,
        "browser_revision": browser_rev,
        "deb_revision": deb_rev,
        "major_minor": major_minor,
        "deb_url": deb_url,
    }


def fetch_yandexdriver_releases() -> list[dict]:
    """Returns list of releases (raw dicts from GitHub API)."""
    info(f"listing YandexDriver releases from {YANDEXDRIVER_API_URL}")
    req = urllib.request.Request(
        YANDEXDRIVER_API_URL,
        headers={"Accept": "application/vnd.github+json"},
    )
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            data = json.load(r)
    except (urllib.error.URLError, json.JSONDecodeError) as e:
        raise RuntimeError(f"failed to fetch YandexDriver releases: {e}")
    if not isinstance(data, list):
        raise RuntimeError(f"unexpected response shape from {YANDEXDRIVER_API_URL}")
    return data


def resolve_yandexdriver(major_minor: str, pinned_url: str | None) -> dict:
    """Resolves the best YandexDriver release for a given browser major.minor.

    Returns {url, tag, file_version, build}. `pinned_url` overrides resolution.
    The selected release is the latest (by tag) with major.minor == `major_minor`
    AND a linux.zip asset.
    """
    if pinned_url:
        info(f"using pinned YandexDriver URL: {pinned_url}")
        m = re.search(r"/download/v([\d.]+)-stable/.*?-(\d+(?:\.\d+)+)-linux\.zip$", pinned_url)
        if not m:
            raise RuntimeError(
                f"could not parse --driver-url {pinned_url!r}: "
                "expected .../download/v<tag>-stable/yandexdriver-<version>-linux.zip"
            )
        return {
            "url": pinned_url,
            "tag": m.group(1),
            "file_version": m.group(2),
        }

    if not major_minor:
        raise RuntimeError("major_minor is empty; cannot resolve YandexDriver")

    releases = fetch_yandexdriver_releases()
    candidates: list[dict] = []
    for rel in releases:
        tag = rel.get("tag_name", "")
        # tag format: v26.4.1-stable → strip 'v' prefix and '-stable' suffix
        m = re.match(r"^v(\d+(?:\.\d+)+)-stable$", tag)
        if not m:
            continue
        tag_ver = m.group(1)
        tag_major_minor = ".".join(tag_ver.split(".")[:2])
        if tag_major_minor != major_minor:
            continue
        linux_asset = None
        for asset in rel.get("assets", []):
            name = asset.get("name", "")
            if name.endswith("-linux.zip") and name.startswith("yandexdriver-"):
                linux_asset = asset
                break
        if not linux_asset:
            continue
        m2 = re.match(r"^yandexdriver-(\d+(?:\.\d+)+)-linux\.zip$", linux_asset["name"])
        if not m2:
            continue
        candidates.append({
            "tag": tag_ver,
            "file_version": m2.group(1),
            "url": linux_asset["browser_download_url"],
        })

    if not candidates:
        raise RuntimeError(
            f"no YandexDriver release with linux.zip for major.minor={major_minor!r}"
        )

    candidates.sort(key=lambda c: parse_version(c["tag"]), reverse=True)
    chosen = candidates[0]
    info(f"selected YandexDriver v{chosen['tag']}-stable ({chosen['file_version']}) for major.minor={major_minor}")
    return chosen


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


def build_variant(variant: str, browser: dict, driver: dict, registry: str) -> list[str]:
    """Builds one variant (headless or vnc). Returns list of tags applied."""
    if variant == "headless":
        dockerfile = "Dockerfile"
        suffix = ""
    elif variant == "vnc":
        dockerfile = "Dockerfile.vnc"
        suffix = "-vnc"
    else:
        raise ValueError(variant)

    # Tag is keyed by browser revision (without deb revision) so that
    # 26.4.1.1110-1 and 26.4.1.1110-2 produce the same tag and overwrite.
    version_tag = browser["browser_revision"] or browser["version"]
    tag_versioned = f"{registry}:yandex-{version_tag}{suffix}"
    tag_latest = f"{registry}:yandex-latest{suffix}"
    build_tags = [tag_versioned, tag_latest]

    info(f"building {variant} -> {', '.join(build_tags)}")
    cmd = [
        "docker", "build",
        "--build-arg", f"YANDEX_VERSION={browser['version']}",
        "--build-arg", f"YANDEXDRIVER_URL={driver['url']}",
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


def normalize_registry(raw: str) -> str:
    """Mirrors the chrome build.py normalization: lowercase, append /jelenoid
    for short forms, etc. See browser-images/.../chrome/build.py for details.
    """
    reg = raw.rstrip("/")
    parts = reg.split("/")
    KNOWN_HOSTS = ("ghcr.io", "quay.io", "gcr.io", "registry.gitlab.com", "docker.io")
    if parts[0] in KNOWN_HOSTS:
        if len(parts) <= 2:
            reg = f"{reg}/jelenoid"
    else:
        if len(parts) == 1:
            reg = f"{reg}/jelenoid"
    if parts[0] in KNOWN_HOSTS and "/" in reg:
        host, _, rest = reg.partition("/")
        reg = f"{host}/" + "/".join(p.lower() for p in rest.split("/"))
    else:
        reg = reg.lower()
    return reg


def main() -> int:
    p = argparse.ArgumentParser(description="Build and optionally push Jelenoid Yandex images")
    p.add_argument("--registry", default=DEFAULT_REGISTRY,
                   help=f"image registry+namespace prefix. Default: {DEFAULT_REGISTRY}")
    p.add_argument("--version", default=None,
                   help="pin a specific Yandex Browser deb version "
                        "(e.g. 26.4.1.1110-1). Default: auto-detect latest stable from apt repo")
    p.add_argument("--driver-url", default=None,
                   help="pin a specific YandexDriver linux.zip URL. "
                        "Default: auto-resolve latest release with major.minor matching the browser")
    p.add_argument("--skip-base", action="store_true",
                   help=f"don't rebuild {DEFAULT_BASE_IMAGE}")
    p.add_argument("--no-vnc", action="store_true", help="build only the headless variant")
    p.add_argument("--push", action="store_true", help="push images to registry after build")
    p.add_argument("--dry-run", action="store_true", help="print what would be done, do nothing")
    args = p.parse_args()

    args.registry = normalize_registry(args.registry)

    try:
        browser = resolve_browser(args.version)
        if not browser["major_minor"]:
            raise RuntimeError("could not derive major.minor from resolved version")

        info(f"browser:  {browser['version']}  (major.minor={browser['major_minor']})")
        driver = resolve_yandexdriver(browser["major_minor"], args.driver_url)
        info(f"driver:   {driver['url']}")

        if args.dry_run:
            for v in (["headless"] if args.no_vnc else ["headless", "vnc"]):
                suffix = "" if v == "headless" else "-vnc"
                version_tag = browser["browser_revision"] or browser["version"]
                print(f"  would build: {args.registry}:yandex-{version_tag}{suffix}")
                print(f"  would build: {args.registry}:yandex-latest{suffix}")
            if args.push:
                print("  would push all built tags")
            return 0

        build_base(args.skip_base)

        all_tags: list[str] = []
        for variant in (["headless"] if args.no_vnc else ["headless", "vnc"]):
            tags = build_variant(variant, browser, driver, args.registry)
            all_tags.extend(tags)

        if args.push:
            push_tags(list(dict.fromkeys(all_tags)))
        else:
            info("skipping push (use --push to upload to registry)")

        print()
        print(green("=" * 60))
        print(green("BUILD SUCCESSFUL"))
        print(green("=" * 60))
        print(f"  browser:  {browser['version']}  (major.minor={browser['major_minor']})")
        print(f"  driver:   {driver['tag']}-stable ({driver['file_version']})")
        for t in dict.fromkeys(all_tags):
            print(f"  tag:      {t}")
        if not args.push:
            print()
            print(yellow("Images are local only. Re-run with --push to publish."))
        return 0

    except RuntimeError as e:
        print(red(f"FAIL: {e}"))
        return 1


if __name__ == "__main__":
    sys.exit(main())
