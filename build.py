#!/usr/bin/env python3
"""build.py — on-device Android build for Termux, no Gradle.

Copy this file into a project root and run it there. It compiles the project
in place with the Termux toolchain:

  aapt2 compile   resources -> .build/res.zip
  aapt2 link      + android.jar -> .build/linked.apk and .build/gen/R.java
  javac           java/ and generated R.java -> .build/classes
  d8              .build/classes -> .build/dex/classes.dex
  package         classes.dex into the linked APK, 4-byte aligned
  apksigner       debug or release signature -> .build/<project>.apk

Usage:
  python3 build.py bootstrap [--api N] [--force]
  python3 build.py apk [--api N] [--optimize] [--ks K] [--alias A]
  python3 build.py test [class ...]
  python3 build.py clean

The project needs `AndroidManifest.xml` and `java/`; `res/`, `assets/`, and
`test/` are optional, and outputs stay under `.build/`. `bootstrap` checks the
toolchain, caches `android.jar` under $ANDROID_SKILL_HOME (default
`~/.pi/android`), and creates the debug keystore; it installs nothing, so run
`pkg install -y openjdk-21 aapt2 d8 apksigner` first.
"""

from __future__ import annotations

import argparse
import io
import os
import re
import shutil
import struct
import subprocess
import sys
import urllib.request
import xml.etree.ElementTree as ET
import zipfile
import zlib

DEFAULT_API = 35
DEFAULT_HOME = os.path.join("~", ".pi", "android")
REPOSITORY_URL = "https://dl.google.com/android/repository/repository2-3.xml"
REPOSITORY_BASE = "https://dl.google.com/android/repository/"
KEYSTORE_ALIAS = "androiddebugkey"
KEYSTORE_PASS = "android"
PREVIEW_TOKENS = ("beta", "alpha", "canary", "preview", " rc", "rc1", "rc2")
APK_TOOLS = ("javac", "keytool", "aapt2", "d8", "apksigner")
JAVA_RELEASE = "17"
ZIP_LOCAL_SIG = 0x04034B50
ZIP_CENTRAL_SIG = 0x02014B50
ZIP_EOCD_SIG = 0x06054B50

PROJECT = os.path.dirname(os.path.abspath(__file__))
BUILD = os.path.join(PROJECT, ".build")


def run(cmd, check=True):
    result = subprocess.run(cmd, capture_output=True, text=True)
    if check and result.returncode != 0:
        detail = (result.stderr or result.stdout or "").strip()
        raise SystemExit(f"command failed ({result.returncode}): {' '.join(cmd)}\n{detail}")
    return result


def missing_tools(names=APK_TOOLS):
    return [name for name in names if not shutil.which(name)]


def home_dir():
    raw = os.environ.get("ANDROID_SKILL_HOME") or DEFAULT_HOME
    return os.path.abspath(os.path.expanduser(raw))


def jar_path(home, api):
    return os.path.join(home, "platforms", f"android-{api}", "android.jar")


def keystore_path(home):
    return os.path.join(home, "keystore", "debug.keystore")


# ------------------------------------------------------------------ android.jar


def _local(tag):
    return tag.split("}")[-1]


def _child(element, name):
    for child in element:
        if _local(child.tag) == name:
            return child
    return None


def _text(element, name):
    child = _child(element, name)
    return child.text.strip() if child is not None and child.text else None


def platform_url(xml_text, api):
    """Archive URL of the newest stable base platform for one API level."""
    candidates = []
    for element in ET.fromstring(xml_text).iter():
        if _local(element.tag) != "remotePackage":
            continue
        path = element.get("path", "")
        if path != f"platforms;android-{api}":
            continue
        channel = _child(element, "channelRef")
        if (channel.get("ref") if channel is not None else "") != "channel-0":
            continue
        label = f"{path} {_text(element, 'display-name') or ''}".lower()
        if "-ext" in path or any(token.strip() in label for token in PREVIEW_TOKENS):
            continue
        revision = _child(element, "revision")
        version = tuple(
            int(_text(revision, key) or 0) for key in ("major", "minor", "micro")
        ) if revision is not None else (0, 0, 0)
        archives = _child(element, "archives")
        if archives is None:
            continue
        for archive in archives:
            if _local(archive.tag) != "archive":
                continue
            host = _text(archive, "host-os")
            complete = _child(archive, "complete")
            if complete is not None and host in (None, "linux"):
                candidates.append((version, _text(complete, "url")))
                break
    if not candidates:
        raise SystemExit(f"no stable platform found for API {api}")
    return max(candidates)[1]


def ensure_jar(home, api, force=False):
    """Download the platform ZIP and cache its android.jar."""
    target = jar_path(home, api)
    if os.path.exists(target) and not force:
        return target
    print(f"fetching android.jar for API {api}")
    xml_text = urllib.request.urlopen(REPOSITORY_URL, timeout=60).read().decode("utf-8")
    url = REPOSITORY_BASE + platform_url(xml_text, api)
    blob = urllib.request.urlopen(url, timeout=600).read()
    member = f"android-{api}/android.jar"
    with zipfile.ZipFile(io.BytesIO(blob)) as archive:
        try:
            data = archive.read(member)
        except KeyError:
            raise SystemExit(f"{member} not found in {url}") from None
    os.makedirs(os.path.dirname(target), exist_ok=True)
    with open(target, "wb") as fh:
        fh.write(data)
    return target


def ensure_keystore(home, force=False):
    target = keystore_path(home)
    if os.path.exists(target) and not force:
        return target
    if not shutil.which("keytool"):
        raise SystemExit("keytool is missing; run: pkg install openjdk-21")
    os.makedirs(os.path.dirname(target), exist_ok=True)
    run([
        "keytool", "-genkeypair",
        "-keystore", target,
        "-alias", KEYSTORE_ALIAS,
        "-storepass", KEYSTORE_PASS,
        "-keypass", KEYSTORE_PASS,
        "-dname", "CN=Android Debug,O=Android,C=US",
        "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
    ])
    return target


# ------------------------------------------------------------------ manifest


def manifest_field(text, name):
    match = re.search(rf'{name}="([^"]+)"', text)
    return match.group(1) if match else None


def manifest_package(text):
    package = manifest_field(text, "package")
    if not package:
        raise SystemExit("AndroidManifest.xml has no package attribute")
    return package


def sdk_levels(text, api):
    """Manifest min/target, defaulting min to 21 and target to the compile API."""
    return manifest_field(text, "minSdkVersion") or "21", manifest_field(text, "targetSdkVersion") or str(api)


# ------------------------------------------------------------------ packaging


def _dos_stamp():
    return 0, 0x21


def write_aligned_zip(path, entries):
    """Write a ZIP with stored entries starting on a four-byte boundary.

    Uncompressed members gain a zero-filled extra field sized so the payload
    offset lands on the boundary, which is what `zipalign` does. Termux has no
    zipalign package, so the archive is written here instead.
    """
    buf = bytearray()
    central = []
    offset = 0
    for name, data, method in entries:
        name_bytes = name.encode("utf-8")
        crc = zlib.crc32(data) & 0xFFFFFFFF
        if method == zipfile.ZIP_STORED:
            payload = data
        else:
            method = zipfile.ZIP_DEFLATED
            compressor = zlib.compressobj(9, zlib.DEFLATED, -15)
            payload = compressor.compress(data) + compressor.flush()
        base = offset + 30 + len(name_bytes)
        pad = (4 - base % 4) % 4
        if pad and pad < 4:
            pad += 4
        extra = b"\x00" * pad
        time_field, date_field = _dos_stamp()
        local = struct.pack(
            "<IHHHHHIIIHH", ZIP_LOCAL_SIG, 20, 0, method, time_field, date_field,
            crc, len(payload), len(data), len(name_bytes), len(extra),
        )
        buf += local + name_bytes + extra + payload
        central.append((name_bytes, method, crc, len(payload), len(data), offset, extra))
        offset += len(local) + len(name_bytes) + len(extra) + len(payload)
    cd_start = offset
    for name_bytes, method, crc, csize, usize, off, extra in central:
        time_field, date_field = _dos_stamp()
        buf += struct.pack(
            "<IHHHHHHIIIHHHHHII", ZIP_CENTRAL_SIG, 20, 20, 0, method, time_field,
            date_field, crc, csize, usize, len(name_bytes), len(extra), 0, 0, 0, 0, off,
        ) + name_bytes + extra
    cd_size = len(buf) - cd_start
    buf += struct.pack(
        "<IHHHHIIH", ZIP_EOCD_SIG, 0, 0, len(central), len(central), cd_size, cd_start, 0
    )
    with open(path, "wb") as fh:
        fh.write(bytes(buf))
    return path


def package_apk(linked_apk, dex_files, out_path):
    entries = []
    with zipfile.ZipFile(linked_apk) as source:
        for info in source.infolist():
            entries.append([info.filename, source.read(info.filename), info.compress_type])
    for index, dex in enumerate(dex_files):
        name = "classes.dex" if index == 0 else f"classes{index + 1}.dex"
        with open(dex, "rb") as fh:
            entries.append([name, fh.read(), zipfile.ZIP_STORED])
    return write_aligned_zip(out_path, entries)


# ------------------------------------------------------------------ build


def find_files(root, ext):
    found = []
    for base, _dirs, files in os.walk(root):
        for name in sorted(files):
            if name.endswith(ext):
                found.append(os.path.join(base, name))
    return found


def build_apk(api, optimize=False, keystore=None, alias=KEYSTORE_ALIAS,
              store_pass=KEYSTORE_PASS, key_pass=KEYSTORE_PASS):
    manifest = os.path.join(PROJECT, "AndroidManifest.xml")
    if not os.path.exists(manifest):
        raise SystemExit(f"no AndroidManifest.xml in {PROJECT}")
    text = open(manifest, encoding="utf-8").read()
    min_sdk, target_sdk = sdk_levels(text, api)
    name = os.path.basename(PROJECT)
    gen_dir = os.path.join(BUILD, "gen")
    classes_dir = os.path.join(BUILD, "classes")
    dex_dir = os.path.join(BUILD, "dex")
    for path in (gen_dir, classes_dir, dex_dir):
        shutil.rmtree(path, ignore_errors=True)
        os.makedirs(path)

    home = home_dir()
    jar = ensure_jar(home, api)
    res_dir = os.path.join(PROJECT, "res")
    assets_dir = os.path.join(PROJECT, "assets")
    linked = os.path.join(BUILD, "linked.apk")
    res_zip = None
    if os.path.isdir(res_dir):
        res_zip = os.path.join(BUILD, "res.zip")
        print("aapt2 compile")
        run(["aapt2", "compile", "--dir", res_dir, "-o", res_zip])
    print("aapt2 link")
    link = [
        "aapt2", "link", "-o", linked, "-I", jar,
        "--manifest", manifest, "--java", gen_dir,
        "--min-sdk-version", str(min_sdk), "--target-sdk-version", str(target_sdk),
    ]
    if manifest_field(text, "versionCode"):
        link += ["--version-code", manifest_field(text, "versionCode")]
    if manifest_field(text, "versionName"):
        link += ["--version-name", manifest_field(text, "versionName")]
    if os.path.isdir(assets_dir):
        link += ["-A", assets_dir]
    if res_zip:
        link.append(res_zip)
    run(link)

    sources = find_files(os.path.join(PROJECT, "java"), ".java") + find_files(gen_dir, ".java")
    if not sources:
        raise SystemExit("no Java sources under java/ or gen/")
    print("javac")
    run(["javac", "--release", JAVA_RELEASE, "-classpath", jar, "-d", classes_dir, *sources])

    class_files = find_files(classes_dir, ".class")
    if not class_files:
        raise SystemExit("javac produced no .class files")
    print("d8")
    dex_args = ["d8", "--lib", jar, "--min-api", str(min_sdk), "--output", dex_dir]
    if optimize:
        dex_args.append("--release")
    run(dex_args + class_files)

    dex_files = [os.path.join(dex_dir, n) for n in sorted(os.listdir(dex_dir)) if n.endswith(".dex")]
    if not dex_files:
        raise SystemExit("d8 produced no .dex files")
    print("package")
    unsigned = os.path.join(BUILD, f"{name}-unsigned.apk")
    package_apk(linked, dex_files, unsigned)

    signed = os.path.join(BUILD, f"{name}.apk")
    keystore = keystore or ensure_keystore(home)
    print("apksigner")
    run([
        "apksigner", "sign",
        "--ks", keystore, "--ks-key-alias", alias,
        "--ks-pass", f"pass:{store_pass}", "--key-pass", f"pass:{key_pass}",
        "--out", signed, unsigned,
    ])
    print(signed)
    return signed


# ------------------------------------------------------------------ host tests


_PACKAGE_RE = re.compile(r"^\s*package\s+([\w.]+)\s*;", re.MULTILINE)
_MAIN_RE = re.compile(r"\bstatic\s+void\s+main\s*\(")


def test_class(path):
    text = open(path, encoding="utf-8").read()
    if not _MAIN_RE.search(text):
        return None
    match = _PACKAGE_RE.search(text)
    stem = os.path.basename(path)[: -len(".java")]
    return f"{match.group(1)}.{stem}" if match else stem


def run_tests(names=None):
    java_dir = os.path.join(PROJECT, "java")
    if not os.path.isdir(java_dir):
        raise SystemExit(f"no java/ directory in {PROJECT}")
    tests = find_files(os.path.join(PROJECT, "test"), ".java")
    if not tests:
        raise SystemExit(f"no Java tests under {PROJECT}/test")
    out_dir = os.path.join(BUILD, "htest")
    os.makedirs(out_dir, exist_ok=True)
    print("javac host tests")
    run(["javac", "--release", JAVA_RELEASE, "-d", out_dir, "-sourcepath", java_dir, *tests])
    classes = names or [name for name in map(test_class, tests) if name]
    if not classes:
        raise SystemExit("no test class declares a main(); name one explicitly")
    ok = True
    for class_name in classes:
        print(f"java {class_name}")
        result = run(["java", "-cp", out_dir, class_name], check=False)
        output = (result.stdout or "") + (result.stderr or "")
        print(output, end="" if output.endswith("\n") else "\n")
        ok = ok and result.returncode == 0
    return 0 if ok else 1


# ------------------------------------------------------------------ CLI


def cmd_bootstrap(args):
    missing = missing_tools()
    if missing:
        raise SystemExit(
            "missing " + ", ".join(missing)
            + "; run: pkg install -y openjdk-21 aapt2 d8 apksigner"
        )
    home = home_dir()
    print(f"android.jar {ensure_jar(home, args.api, force=args.force)}")
    print(f"keystore    {ensure_keystore(home, force=args.force)}")
    return 0


def cmd_apk(args):
    missing = missing_tools()
    if missing:
        raise SystemExit("missing " + ", ".join(missing) + "; run: python3 build.py bootstrap")
    build_apk(args.api, optimize=args.optimize, keystore=args.ks, alias=args.alias,
              store_pass=args.store_pass, key_pass=args.key_pass)
    return 0


def cmd_test(args):
    if not shutil.which("javac"):
        raise SystemExit("javac is missing; run: pkg install openjdk-21")
    return run_tests(args.classes or None)


def cmd_clean(args):
    shutil.rmtree(BUILD, ignore_errors=True)
    return 0


def build_parser():
    parser = argparse.ArgumentParser(prog="build.py", description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="command", required=True)

    bootstrap = sub.add_parser("bootstrap", help="check tools, fetch android.jar, make a keystore")
    bootstrap.add_argument("--api", type=int, default=DEFAULT_API)
    bootstrap.add_argument("--force", action="store_true")
    bootstrap.set_defaults(func=cmd_bootstrap)

    apk = sub.add_parser("apk", help="compile, dex, package, and sign")
    apk.add_argument("--api", type=int, default=DEFAULT_API)
    apk.add_argument("--optimize", action="store_true", help="optimized dex (d8 --release)")
    apk.add_argument("--ks", default=None, help="keystore (default: debug)")
    apk.add_argument("--alias", default=KEYSTORE_ALIAS)
    apk.add_argument("--store-pass", default=KEYSTORE_PASS)
    apk.add_argument("--key-pass", default=KEYSTORE_PASS)
    apk.set_defaults(func=cmd_apk)

    test = sub.add_parser("test", help="compile and run host tests")
    test.add_argument("classes", nargs="*", help="test classes to run (default: every main())")
    test.set_defaults(func=cmd_test)

    clean = sub.add_parser("clean", help="remove .build/")
    clean.set_defaults(func=cmd_clean)
    return parser


def main(argv=None):
    args = build_parser().parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
