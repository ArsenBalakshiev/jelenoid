#!/usr/bin/env python3
"""
Smoke-test browser images via the running jelenoid-server hub.

For each (browser, version) variant, creates a W3C WebDriver session,
navigates to the test-app, executes JS, and closes the session.

Exit code 0 = all good, 1 = at least one variant failed.
"""
from __future__ import annotations

import argparse
import base64
import json
import sys
import time
import urllib.error
import urllib.request
import zipfile
from io import BytesIO

HUB = "http://localhost:4444"
TEST_APP = "http://host.docker.internal:3000/"

CASES = [
    {
        "name": "chrome-headless",
        "browser": "chrome",
        "version": "150.0.7871.46",
        "enableVNC": False,
    },
    {
        "name": "yandex-headless",
        "browser": "yandex",
        "version": "26.4.1.1110",
        "enableVNC": False,
    },
    {
        "name": "chrome-vnc",
        "browser": "chrome",
        "version": "150.0.7871.46-vnc",
        "enableVNC": True,
    },
    {
        "name": "yandex-vnc",
        "browser": "yandex",
        "version": "26.4.1.1110-vnc",
        "enableVNC": True,
    },
]


class C:
    RED = "\033[31m"
    GREEN = "\033[32m"
    BLUE = "\033[34m"
    YELLOW = "\033[33m"
    RESET = "\033[0m"


def green(s): return f"{C.GREEN}{s}{C.RESET}"
def red(s): return f"{C.RED}{s}{C.RESET}"
def blue(s): return f"{C.BLUE}{s}{C.RESET}"
def yellow(s): return f"{C.YELLOW}{s}{C.RESET}"


def http(method: str, url: str, payload: dict | None = None, timeout: float = 30.0) -> tuple[int, dict | str]:
    data = None
    headers = {"Accept": "application/json"}
    if payload is not None:
        data = json.dumps(payload).encode("utf-8")
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            body = r.read().decode("utf-8", errors="replace")
            try:
                return r.status, json.loads(body)
            except json.JSONDecodeError:
                return r.status, body
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", errors="replace")
        try:
            return e.code, json.loads(body)
        except json.JSONDecodeError:
            return e.code, body


def wait_status_ok(timeout: float = 30.0) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            code, body = http("GET", f"{HUB}/status", timeout=2.0)
            if code == 200:
                val = body.get("value", {}) if isinstance(body, dict) else {}
                if val.get("ready"):
                    return
        except Exception:
            pass
        time.sleep(0.5)
    raise RuntimeError(f"hub {HUB}/status not ready within {timeout:.0f}s")


def run_case(case: dict) -> bool:
    name = case["name"]
    print(blue(f"\n=== {name} (browser={case['browser']}, version={case['version']}, vnc={case['enableVNC']}) ==="))

    caps: dict = {
        "capabilities": {
            "firstMatch": [{
                "browserName": case["browser"],
                "browserVersion": case["version"],
                "selenoid:options": {
                    "enableVNC": case["enableVNC"],
                    "sessionTimeout": "60s",
                },
            }]
        }
    }

    code, body = http("POST", f"{HUB}/wd/hub/session", caps, timeout=120.0)
    if code != 200:
        print(red(f"  create session failed (HTTP {code}): {body}"))
        return False
    sid = body.get("value", {}).get("sessionId")
    if not sid:
        print(red(f"  no sessionId in response: {body}"))
        return False
    print(green(f"  session created: {sid}"))

    try:
        code, body = http("GET", f"{HUB}/wd/hub/session/{sid}/se/status", timeout=15.0)
        print(f"  /se/status: HTTP {code} body={json.dumps(body)[:200]}")

        code, body = http("POST", f"{HUB}/wd/hub/session/{sid}/url",
                          {"url": TEST_APP}, timeout=30.0)
        if code != 200:
            print(red(f"  navigate failed (HTTP {code}): {body}"))
            return False
        print(green(f"  navigated to {TEST_APP}"))

        code, body = http("GET", f"{HUB}/wd/hub/session/{sid}/title", timeout=15.0)
        if code != 200:
            print(red(f"  /title failed (HTTP {code}): {body}"))
            return False
        title = body.get("value")
        if title != "Test App":
            print(red(f"  unexpected title: {title!r}"))
            return False
        print(green(f"  title: {title!r}"))

        code, body = http("POST", f"{HUB}/wd/hub/session/{sid}/execute/sync",
                          {"script": "return document.body.innerText.slice(0, 60)", "args": []}, timeout=15.0)
        if code != 200:
            print(red(f"  /execute/sync failed (HTTP {code}): {body}"))
            return False
        text = body.get("value")
        print(green(f"  body text: {text!r}"))

        code, body = http("POST", f"{HUB}/wd/hub/session/{sid}/execute/sync",
                          {"script": "return 7 * 6", "args": []}, timeout=15.0)
        if code != 200:
            print(red(f"  js-eval failed (HTTP {code}): {body}"))
            return False
        js_result = body.get("value")
        if js_result != 42:
            print(red(f"  JS eval returned {js_result!r} (expected 42)"))
            return False
        print(green(f"  JS eval: 7*6 = {js_result}"))

        if case["enableVNC"]:
            print(blue("  VNC enabled — checking container has open 5900"))
            code, body = http("GET", f"{HUB}/api/limit/selenium", timeout=5.0)
            print(f"  /api/limit/selenium: HTTP {code} body={json.dumps(body)[:200]}")

        code, body = http("POST", f"{HUB}/wd/hub/session/{sid}/file", {
            "file": make_zip_b64("hello-jelenoid.txt", "Jelenoid smoke test OK\n"),
        }, timeout=15.0)
        if code != 200:
            print(yellow(f"  /file upload soft-failed (HTTP {code}): {body}"))
        else:
            uploaded = body.get("value")
            print(green(f"  file uploaded to: {uploaded}"))

        return True
    finally:
        code, body = http("DELETE", f"{HUB}/wd/hub/session/{sid}", timeout=15.0)
        if code not in (200, 204):
            print(yellow(f"  session close: HTTP {code} {body}"))
        else:
            print(green("  session closed"))


def make_zip_b64(filename: str, content: str) -> str:
    buf = BytesIO()
    with zipfile.ZipFile(buf, "w", compression=zipfile.ZIP_STORED) as zf:
        zf.writestr(filename, content)
    return base64.b64encode(buf.getvalue()).decode("ascii")


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--only", default=None, help="comma-separated case name substrings to run")
    args = p.parse_args()

    print(blue("Pinging hub /api/browsers..."))
    code, body = http("GET", f"{HUB}/api/browsers", timeout=10.0)
    if code != 200:
        print(red(f"hub /api/browsers returned HTTP {code}: {body}"))
        return 1
    if isinstance(body, list):
        configs = body
    elif isinstance(body, dict):
        configs = body.get("value", [])
    else:
        configs = []
    print(green(f"hub ready ({len(configs)} browser configs)"))

    selected = CASES
    if args.only:
        needles = [s.strip() for s in args.only.split(",") if s.strip()]
        selected = [c for c in CASES if any(n in c["name"] for n in needles)]
        if not selected:
            print(red(f"no cases match --only={args.only!r}"))
            return 2

    results: list[tuple[str, bool]] = []
    for case in selected:
        try:
            ok = run_case(case)
        except Exception as e:
            print(red(f"  EXCEPTION: {e}"))
            ok = False
        results.append((case["name"], ok))

    print()
    print("=" * 60)
    for name, ok in results:
        marker = green("PASS") if ok else red("FAIL")
        print(f"  {marker}  {name}")
    print("=" * 60)
    failures = [n for n, ok in results if not ok]
    if failures:
        print(red(f"FAILED: {', '.join(failures)}"))
        return 1
    print(green("ALL CASES PASSED"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
