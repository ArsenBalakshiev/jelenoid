#!/usr/bin/env python3
"""
Smoke-тест для Yandex-образов Jelenoid.

Использование:
    python test.py                       # headless-вариант
    python test.py --vnc                 # VNC-вариант (Xvfb + x11vnc)
    python test.py --skip-build          # использовать кэшированный образ
    python test.py --skip-build-base     # не пересобирать base-образ
    python test.py --keep                # не удалять контейнер после теста
    python test.py --version 26.4.1.1110-1       # конкретная deb-версия
    python test.py --driver-url <url>             # конкретный YandexDriver
    python test.py --http-port 24444 --cdp-port 27070 --vnc-port 25900

Выход: 0 - OK, 1 - тест упал, 2 - неверные аргументы.

Зависимости: Python 3.8+, Docker CLI в PATH, базовый образ jelenoid/selenium-base.
"""
from __future__ import annotations

import argparse
import atexit
import base64
import json
import shutil
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
BASE_DIR = SCRIPT_DIR.parent / "base"
BASE_IMAGE = "jelenoid/selenium-base:bookworm"
DEFAULT_YANDEX_VERSION = "latest-stable"
DEFAULT_IMAGE = "jelenoid-yandex:test"

if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))
import build  # noqa: E402  (same-folder module; added to sys.path above)


class C:
    RED = "\033[31m"
    GREEN = "\033[32m"
    BLUE = "\033[34m"
    YELLOW = "\033[33m"
    RESET = "\033[0m"


def red(s: str) -> str:
    return f"{C.RED}{s}{C.RESET}"


def green(s: str) -> str:
    return f"{C.GREEN}{s}{C.RESET}"


def blue(s: str) -> str:
    return f"{C.BLUE}{s}{C.RESET}"


def yellow(s: str) -> str:
    return f"{C.YELLOW}{s}{C.RESET}"


def info(msg: str) -> None:
    print(blue(f">>> {msg}"))


def passed(msg: str) -> None:
    print(green(f"PASS: {msg}"))


def warn(msg: str) -> None:
    print(yellow(f"WARN: {msg}"))


def fail(msg: str) -> "None":
    raise RuntimeError(msg)


def check_tool(name: str) -> None:
    if shutil.which(name) is None:
        fail(f"{name} not found in PATH")


def docker(args: list[str], *, check: bool = True, capture: bool = False) -> str:
    cmd = ["docker", *args]
    res = subprocess.run(
        cmd,
        check=False,
        text=True,
        stdout=subprocess.PIPE if capture else None,
        stderr=subprocess.PIPE if capture else None,
    )
    if check and res.returncode != 0:
        out = (res.stdout or "") + (res.stderr or "")
        fail(f"docker {args!r} failed (exit {res.returncode}): {out.strip()}")
    return (res.stdout or "") if capture else ""


def image_exists(image: str) -> bool:
    return subprocess.run(
        ["docker", "image", "inspect", image],
        check=False, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    ).returncode == 0


def wait_for_tcp(host: str, port: int, timeout: float) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
            s.settimeout(0.5)
            try:
                s.connect((host, port))
                return
            except OSError:
                time.sleep(0.3)
    fail(f"TCP {host}:{port} did not open within {timeout:.0f}s")


def wait_for_url(url: str, timeout: float, expected_substring: str | None = None) -> str:
    deadline = time.monotonic() + timeout
    last_err: Exception | None = None
    while time.monotonic() < deadline:
        try:
            with urllib.request.urlopen(url, timeout=1.0) as r:
                if r.status == 200:
                    body = r.read().decode("utf-8", errors="replace")
                    if expected_substring is None or expected_substring in body:
                        return body
        except (urllib.error.URLError, ConnectionError, OSError) as e:
            last_err = e
        time.sleep(0.3)
    fail(f"URL {url} did not respond OK within {timeout:.0f}s: {last_err}")


def http_post_json(url: str, payload: dict, timeout: float = 15.0) -> dict:
    body = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        url,
        data=body,
        method="POST",
        headers={"Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", errors="replace")
        fail(f"POST {url} -> HTTP {e.code}: {detail}")
    except urllib.error.URLError as e:
        fail(f"POST {url} -> {e}")


def http_delete(url: str, timeout: float = 10.0) -> None:
    req = urllib.request.Request(url, method="DELETE")
    try:
        with urllib.request.urlopen(req, timeout=timeout):
            return
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return
        fail(f"DELETE {url} -> HTTP {e.code}: {e.read().decode('utf-8', errors='replace')}")


def http_get_b64(url: str, timeout: float = 10.0) -> bytes:
    req = urllib.request.Request(url)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            data = json.loads(r.read().decode("utf-8"))
        return base64.b64decode(data["value"])
    except urllib.error.URLError as e:
        fail(f"GET {url} -> {e}")


def resolve_build_args(pinned_version: str, pinned_driver_url: str | None) -> tuple[str, str]:
    """Returns (YANDEX_VERSION, YANDEXDRIVER_URL) for docker build.
    Resolves the Yandex Browser version from the apt repo and the YandexDriver
    URL from the GitHub API (filtered by major.minor), unless both are pinned.
    Delegates to build.resolve_browser / build.resolve_yandexdriver so the
    resolution rules stay in one place.
    """
    info(f"resolving yandex version: {pinned_version}")
    browser = build.resolve_browser(pinned_version)
    info(f"  resolved browser: {browser['version']} (major.minor={browser['major_minor']})")
    driver = build.resolve_yandexdriver(browser["major_minor"], pinned_driver_url)
    info(f"  resolved driver:  {driver['url']}")
    return browser["version"], driver["url"]


def run(args: argparse.Namespace) -> int:
    is_vnc = args.vnc
    tag = args.tag
    if is_vnc and tag == "jelenoid-yandex:test":
        tag = "jelenoid-yandex:test-vnc"
    container = args.container
    http_port = args.http_port
    cdp_port = args.cdp_port
    vnc_port = args.vnc_port
    skip_build = args.skip_build

    check_tool("docker")

    def cleanup() -> None:
        if args.keep:
            info(f"container kept running: {container}")
            return
        subprocess.run(
            ["docker", "rm", "-f", container],
            check=False,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )

    atexit.register(cleanup)

    info(f"variant: {'vnc' if is_vnc else 'headless'}, image: {tag}, version: {args.version}")

    if args.skip_build_base:
        info(f"skip-build-base: assuming {BASE_IMAGE} exists")
        if not image_exists(BASE_IMAGE):
            fail(f"--skip-build-base set but {BASE_IMAGE} not present")
    elif not image_exists(BASE_IMAGE):
        info(f"building base image {BASE_IMAGE}")
        docker(
            [
                "build",
                "-t", BASE_IMAGE,
                "-f", str(BASE_DIR / "Dockerfile.base"),
                str(BASE_DIR),
            ]
        )

    if skip_build:
        info("skip-build: using existing image")
        if not image_exists(tag):
            fail(f"image {tag} not present (build it first or drop --skip-build)")
    else:
        info("building yandex image (this may take a few minutes)")
        dockerfile = "Dockerfile.vnc" if is_vnc else "Dockerfile"
        yandex_version, driver_url = resolve_build_args(args.version, args.driver_url)
        build_cmd = [
            "build",
            "--build-arg", f"YANDEX_VERSION={yandex_version}",
            "--build-arg", f"YANDEXDRIVER_URL={driver_url}",
            "-t", tag,
            "-f", str(SCRIPT_DIR / dockerfile),
            str(SCRIPT_DIR),
        ]
        docker(build_cmd)

    info(f"starting container {container} (http={http_port} cdp={cdp_port} vnc={vnc_port if is_vnc else 'n/a'})")
    run_args = [
        "run", "--rm", "-d",
        "--name", container,
        "--shm-size", "2g",
        "-p", f"{http_port}:4444",
        "-p", f"{cdp_port}:7070",
    ]
    if is_vnc:
        run_args += ["-p", f"{vnc_port}:5900"]
    run_args.append(tag)
    cid = docker(run_args, capture=True).strip()
    if not cid:
        fail("docker run did not return container id")
    info(f"container id: {cid[:12]}")

    try:
        info("checking yandex-browser / yandexdriver versions inside container")
        yandex_ver = docker(["exec", container, "yandex-browser", "--version"], capture=True).strip()
        passed(f"yandex-browser: {yandex_ver}")
        driver_ver = docker(["exec", container, "yandexdriver", "--version"], capture=True).strip()
        passed(f"yandexdriver: {driver_ver}")
        wrapper_ver = docker(["exec", container, "google-chrome", "--version"], capture=True).strip()
        passed(f"google-chrome wrapper: {wrapper_ver}")
        cd_wrapper_ver = docker(["exec", container, "chromedriver", "--version"], capture=True).strip()
        passed(f"chromedriver wrapper: {cd_wrapper_ver}")
        policies = docker(
            ["exec", container, "cat", "/etc/opt/yandex/browser/policies/managed/managed_policies.json"],
            capture=True,
        ).strip()
        if "SafeBrowsingProtectionLevel" not in policies:
            fail(f"managed_policies.json looks wrong: {policies}")
        passed(f"managed_policies.json: {policies}")

        info(f"waiting for TCP localhost:{http_port} (yandexdriver)")
        wait_for_tcp("127.0.0.1", http_port, timeout=30.0)

        info("checking /status (jelenoid-server/container-manager compatibility)")
        status_body = wait_for_url(
            f"http://127.0.0.1:{http_port}/status",
            timeout=30.0,
            expected_substring='"ready":true',
        )
        print(f"  {status_body.strip()}")
        passed("yandexdriver ready via /status")

        info("creating WebDriver session")
        resp = http_post_json(
            f"http://127.0.0.1:{http_port}/session",
            {
                "capabilities": {
                    "alwaysMatch": {
                        "browserName": "chrome",
                        "goog:chromeOptions": {"args": []},
                    }
                }
            },
        )
        sid = resp.get("value", {}).get("sessionId")
        if not sid:
            fail(f"no sessionId in response: {resp}")
        passed(f"session created: {sid}")

        info("executing script to verify JS context")
        r = http_post_json(
            f"http://127.0.0.1:{http_port}/session/{sid}/execute/sync",
            {"script": "return 1+1", "args": []},
        )
        if r.get("value") != 2:
            fail(f"JS execution did not return 2: {r}")
        passed("JS execution returned 2")

        info("checking CDP proxy port (container:7070)")
        wait_for_tcp("127.0.0.1", cdp_port, timeout=10.0)
        passed(f"cdp-proxy is listening on localhost:{cdp_port}")

        info("executing CDP command via yandexdriver /goog/cdp/execute")
        cdp_resp = http_post_json(
            f"http://127.0.0.1:{http_port}/session/{sid}/goog/cdp/execute",
            {"cmd": "Runtime.evaluate", "params": {"expression": "1+1"}},
        )
        cdp_value = cdp_resp.get("value", {}).get("result", {}).get("value")
        if cdp_value != 2:
            fail(f"CDP Runtime.evaluate did not return 2: {cdp_resp}")
        passed("CDP command returned 2")

        info("navigating to data: URL")
        data_url = (
            "data:text/html,"
            "<body style=\"background:rgb(255,0,0);margin:0\">"
            "<div style=\"color:white;font-size:96px;padding:40px\">JELENOID-OK</div>"
            "</body>"
        )
        http_post_json(
            f"http://127.0.0.1:{http_port}/session/{sid}/url",
            {"url": data_url},
        )

        if is_vnc:
            time.sleep(1.0)
            info("taking screenshot through yandexdriver (== VNC framebuffer)")
            png = http_get_b64(f"http://127.0.0.1:{http_port}/session/{sid}/screenshot")
            if len(png) < 1000:
                fail(f"screenshot too small ({len(png)} bytes) - display likely empty")
            out = SCRIPT_DIR / "last-vnc-screenshot.png"
            out.write_bytes(png)
            passed(f"screenshot saved: {out} ({len(png)} bytes)")

        info("closing session")
        http_delete(f"http://127.0.0.1:{http_port}/session/{sid}")
        passed("session closed")

    except Exception:
        info("dumping container logs (last 80 lines) for debugging")
        logs = docker(["logs", "--tail", "80", container], check=False, capture=True)
        print(logs)
        raise

    print()
    print(green("===== ALL CHECKS PASSED ====="))
    print(green(f"  variant:   {'vnc' if is_vnc else 'headless'}"))
    print(green(f"  image:     {tag}"))
    print(green(f"  http port: {http_port}"))
    print(green(f"  cdp port:  {cdp_port}"))
    if is_vnc:
        print(green(f"  vnc port:  {vnc_port} (password: selenoid)"))
    return 0


def main() -> int:
    p = argparse.ArgumentParser(description="Smoke-test for Jelenoid Yandex images")
    p.add_argument("--vnc", action="store_true", help="Test the VNC variant (Xvfb + x11vnc)")
    p.add_argument("--version", default=DEFAULT_YANDEX_VERSION,
                   help=f"Yandex Browser deb version to build with, or 'latest-stable' (default: {DEFAULT_YANDEX_VERSION})")
    p.add_argument("--driver-url", default=None,
                   help="Pin a specific YandexDriver linux.zip URL (otherwise resolved automatically by major.minor)")
    p.add_argument("--tag", default=DEFAULT_IMAGE,
                   help=f"Image tag (default: {DEFAULT_IMAGE}, vnc -> :test-vnc)")
    p.add_argument("--container", default="jelenoid-yandex-smoke",
                   help="Container name (default: jelenoid-yandex-smoke)")
    p.add_argument("--http-port", type=int, default=24444,
                   help="Host port for 4444 (default: 24444)")
    p.add_argument("--cdp-port", type=int, default=27070,
                   help="Host port for 7070 (default: 27070)")
    p.add_argument("--vnc-port", type=int, default=25900,
                   help="Host port for 5900 in vnc mode (default: 25900)")
    p.add_argument("--skip-build", action="store_true", help="Don't rebuild the yandex image")
    p.add_argument("--skip-build-base", action="store_true", help="Don't rebuild the base image")
    p.add_argument("--keep", action="store_true", help="Don't remove the container after test")
    args = p.parse_args()

    try:
        return run(args)
    except RuntimeError as e:
        print(red(f"FAIL: {e}"))
        return 1


if __name__ == "__main__":
    sys.exit(main())
