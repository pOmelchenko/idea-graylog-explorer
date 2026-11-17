#!/usr/bin/env python3
"""Build through Gradle using an installed IDE SDK; keeps one Java/Kotlin packaging pipeline."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parent


def project_version():
    values = dict(line.split("=", 1) for line in (ROOT / "gradle.properties").read_text().splitlines() if "=" in line and not line.lstrip().startswith("#"))
    return values["pluginVersion"].strip()


def find_ide(explicit=None):
    candidates = [explicit, os.environ.get("IDE_HOME")]
    toolbox = Path.home() / ".local/share/JetBrains/Toolbox/apps"
    candidates += [toolbox / "phpstorm", toolbox / "intellij-idea"]
    for candidate in candidates:
        if candidate:
            path = Path(candidate).expanduser().resolve()
            if (path / "Contents").is_dir():
                path /= "Contents"
            if (path / "lib").is_dir() and ((path / "product-info.json").is_file() or (path / "Resources/product-info.json").is_file()):
                return path
    raise SystemExit("IDE SDK not found. Set IDE_HOME or pass --ide-home /path/to/PhpStorm.")


def java_bin(ide, command):
    suffix = ".exe" if os.name == "nt" else ""
    candidates = [ide / "jbr/bin" / (command + suffix), ide / "jbr/Contents/Home/bin" / (command + suffix)]
    if os.environ.get("JAVA_HOME"):
        candidates.append(Path(os.environ["JAVA_HOME"]) / "bin" / (command + suffix))
    for candidate in candidates:
        if candidate.is_file():
            return str(candidate)
    found = shutil.which(command)
    if found:
        return found
    raise SystemExit(f"{command} not found. Set JAVA_HOME to JDK 25 or newer.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ide-home")
    parser.add_argument("--skip-tests", action="store_true")
    parser.add_argument("--offline", action="store_true", help="Use previously cached Gradle dependencies")
    args = parser.parse_args()
    ide = find_ide(args.ide_home)
    wrapper = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
    command = [str(wrapper), "--no-daemon", f"-PideHome={ide}", "buildPlugin"]
    if args.skip_tests:
        command += ["-x", "check"]
    if args.offline:
        command.append("--offline")
    env = os.environ.copy()
    env["JAVA_HOME"] = str(Path(java_bin(ide, "java")).resolve().parent.parent)
    subprocess.run(command, cwd=ROOT, env=env, check=True)
    print(f"Built {ROOT / 'build/distributions' / f'graylog-jetbrains-{project_version()}.zip'}")


if __name__ == "__main__":
    try:
        main()
    except subprocess.CalledProcessError as error:
        sys.exit(error.returncode)
