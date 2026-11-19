#!/usr/bin/env python3
"""Validate release metadata and prepare the verified Gradle ZIP for distribution."""

import argparse
import hashlib
import io
from pathlib import Path
import re
import shutil
import sys
import xml.etree.ElementTree as ET
import zipfile

VERSION = re.compile(r"(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?")
PLUGIN_ID = "dev.omelchenko.graylogexplorer"


def metadata(root: Path, tag: str = "") -> dict[str, str]:
    versions = re.findall(r"^\s*pluginVersion\s*=\s*(.*?)\s*$",
                          (root / "gradle.properties").read_text(), re.MULTILINE)
    if len(versions) != 1 or not VERSION.fullmatch(versions[0]):
        raise ValueError("Expected one pluginVersion in N.N.N[-prerelease] format")
    version = versions[0]
    expected = "v" + version
    if tag and tag != expected:
        raise ValueError(f"Tag {tag!r} must match pluginVersion: {expected}")
    return {"version": version, "tag": expected,
            "zip_name": f"graylog-jetbrains-{version}.zip",
            "prerelease": str("-" in version).lower()}


def package(root: Path) -> Path:
    info = metadata(root)
    source = root / "build/distributions" / info["zip_name"]
    descriptors = []
    with zipfile.ZipFile(source) as archive:
        if archive.testzip() is not None:
            raise ValueError("Corrupt distribution ZIP")
        for name in archive.namelist():
            if not name.endswith(".jar") or "/lib/" not in name:
                continue
            with zipfile.ZipFile(io.BytesIO(archive.read(name))) as jar:
                if "META-INF/plugin.xml" in jar.namelist():
                    descriptors.append(ET.fromstring(jar.read("META-INF/plugin.xml")))
    if len(descriptors) != 1:
        raise ValueError("Expected exactly one plugin descriptor in the distribution")
    descriptor = descriptors[0]
    if descriptor.findtext("id") != PLUGIN_ID or descriptor.findtext("version") != info["version"]:
        raise ValueError("Packaged plugin ID/version does not match the release")
    output = root / "build/release"
    if output.exists():
        shutil.rmtree(output)
    output.mkdir(parents=True)
    destination = output / source.name
    shutil.copyfile(source, destination)
    digest = hashlib.sha256(destination.read_bytes()).hexdigest()
    (output / (source.name + ".sha256")).write_text(f"{digest}  {source.name}\n", encoding="utf-8")
    return destination


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=["prepare", "package"])
    parser.add_argument("--tag", default="")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    try:
        if args.operation == "prepare":
            for key, value in metadata(root, args.tag).items():
                print(f"{key}={value}")
        else:
            print(package(root))
    except (ValueError, OSError, zipfile.BadZipFile, ET.ParseError) as error:
        print(f"Release preparation failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())

