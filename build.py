#!/usr/bin/env python3
"""Build an installable plugin using an installed IDE SDK, without network access."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parent
def project_version():
    values = dict(line.split("=", 1) for line in (ROOT / "gradle.properties").read_text().splitlines() if "=" in line and not line.lstrip().startswith("#"))
    return values["pluginVersion"].strip()


VERSION = project_version()


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


def run(args):
    subprocess.run([str(a) for a in args], cwd=ROOT, check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ide-home")
    parser.add_argument("--skip-tests", action="store_true")
    args = parser.parse_args()
    ide = find_ide(args.ide_home)
    jars = sorted((ide / "lib").glob("*.jar"))
    php_api = ide / "plugins/php-impl/lib/php-openapi.jar"
    if not php_api.is_file():
        raise SystemExit("Building the optional PHP adapter requires a PhpStorm SDK (IDE_HOME).")
    jars.append(php_api)
    classpath = os.pathsep.join(map(str, jars))
    classes = ROOT / "build/classes/main"
    if classes.exists():
        shutil.rmtree(classes)
    classes.mkdir(parents=True)
    javac = java_bin(ide, "javac")
    run([javac, "-encoding", "UTF-8", "--release", "21", "-proc:none", "-classpath", classpath,
         "-d", classes, *sorted((ROOT / "src/main/java").rglob("*.java"))])
    if not args.skip_tests:
        tests = ROOT / "build/classes/test"
        if tests.exists():
            shutil.rmtree(tests)
        tests.mkdir(parents=True)
        testpath = str(classes) + os.pathsep + classpath
        run([javac, "-encoding", "UTF-8", "--release", "21", "-proc:none", "-classpath", testpath,
             "-d", tests, *sorted((ROOT / "src/test/java").rglob("*.java"))])
        run([java_bin(ide, "java"), "--add-modules=jdk.httpserver", "-ea", "-classpath",
             str(tests) + os.pathsep + testpath, "dev.graylog.jetbrains.CoreTests"])
    lib = ROOT / "build/libs"
    lib.mkdir(parents=True, exist_ok=True)
    jar = lib / f"graylog-jetbrains-plugin-{VERSION}.jar"
    with zipfile.ZipFile(jar, "w", zipfile.ZIP_DEFLATED) as archive:
        for base in [classes, ROOT / "src/main/resources"]:
            for source in sorted(base.rglob("*")):
                if source.is_file():
                    archive.write(source, source.relative_to(base).as_posix())
        archive.write(ROOT / "LICENSE", "META-INF/LICENSE")
    dist = ROOT / "build/distributions"
    dist.mkdir(parents=True, exist_ok=True)
    output = dist / f"graylog-jetbrains-{VERSION}.zip"
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.write(jar, "graylog-jetbrains/lib/" + jar.name)
    print(f"Built {output}")


if __name__ == "__main__":
    try:
        main()
    except subprocess.CalledProcessError as error:
        sys.exit(error.returncode)
