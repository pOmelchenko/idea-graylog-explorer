#!/usr/bin/env python3
"""Launch an isolated IDE on Xvfb and exercise the actual plugin against a local fixture."""
import argparse
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import time
import zipfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from build import find_ide, java_bin


def terminate(process):
    if process and process.poll() is None:
        os.killpg(process.pid, signal.SIGTERM)
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait(timeout=5)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ide-home")
    parser.add_argument("--theme", choices=["light", "dark"], default="dark")
    parser.add_argument("--values-only", action="store_true")
    parser.add_argument("--sources-only", action="store_true")
    parser.add_argument("--with-legacy-prototype", action="store_true", help="Keep a disabled prototype installed to check migration conflicts")
    parser.add_argument("--reload", action="store_true", help="Exercise dynamic unload/update from a separate controller plugin")
    args = parser.parse_args()
    ide = find_ide(args.ide_home)
    if not shutil.which("Xvfb"):
        raise SystemExit("Xvfb is required for this Linux UI smoke test.")
    workspace = ROOT / "build/smoke"
    if workspace.exists():
        shutil.rmtree(workspace)
    report = ROOT / "build/reports/smoke" / ("reload" if args.reload else args.theme)
    report.mkdir(parents=True, exist_ok=True)
    for name in ["reload-steps.txt", "explorer-panel.png", "sources-window-panel.png", "sources-tree-panel.png", "php-values-editor.png", "legacy-conflict-result.txt", "result.txt", "startup.txt", "settings-result.txt", "settings-ide.png", "settings-project.png", "settings-failure.png", "variable-values-result.txt", "variable-values.png", "yii-result.txt", "yii-stack-trace.png",
                 "ui-details-result.txt", "ui-details-failure.png", "settings-sources.png", "settings-highlighting.png", "settings-search.png", "settings-network.png", "tree-focused.png", "tree-unfocused.png",
                 "query-completion.png", "query-level-values.png", "explorer-fields.png", "explorer-stack.png", "explorer-json.png", "explorer-exception-text.png",
                 "navigation-result.txt", "navigation-expanded.png", "navigation-collapsed.png", "source-tree-result.txt", "sources-tree.png", "sources-window.png", "source-tree-failure.png", "sources-result.txt", "sources-filter.png", "sources-selected.png", "sources-failure.png"]:
        (report / name).unlink(missing_ok=True)
    workspace.mkdir(parents=True)
    classes = workspace / "classes"
    classes.mkdir()
    jars = sorted((ide / "lib").glob("*.jar")) + [ide / "plugins/php-impl/lib/php-openapi.jar"]
    # Smoke runs against the finished production classes, but its own hook stays test-only.
    main_classes = ROOT / "build/classes/main"
    if not main_classes.exists():
        raise SystemExit("Run python3 build.py first.")
    classpath = os.pathsep.join(map(str, [main_classes, *jars]))
    sources = list((ROOT / "src/smoke/java").rglob("*.java")) + list((ROOT / "src/reload/java").rglob("*.java"))
    subprocess.run([java_bin(ide, "javac"), "--release", "21", "-proc:none", "-classpath", classpath,
                    "-d", str(classes), *map(str, sources)], check=True)
    lib = workspace / "plugins/graylog-jetbrains/lib"
    lib.mkdir(parents=True)
    version = ET.parse(ROOT / "src/main/resources/META-INF/plugin.xml").getroot().findtext("version")
    original = ROOT / f"build/libs/graylog-jetbrains-plugin-{version}.jar"
    if args.reload:
        # Production JAR stays untouched. The controller has its own classloader and no plugin dependency.
        shutil.copy2(original, lib / original.name)
        controller = workspace / "plugins/reload-controller/lib/controller.jar"
        controller.parent.mkdir(parents=True)
        with zipfile.ZipFile(controller, "w", zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("META-INF/plugin.xml", '<idea-plugin><id>dev.graylog.reload-test</id><name>Graylog reload test</name>'
                             '<version>1</version><vendor>Test fixture</vendor><depends>com.intellij.modules.platform</depends>'
                             '<extensions defaultExtensionNs="com.intellij"><postStartupActivity implementation="dev.graylog.reload.ReloadActivity"/></extensions></idea-plugin>')
            for file in (classes / "dev/graylog/reload").rglob("*.class"):
                archive.write(file, file.relative_to(classes).as_posix())
        upgrade = workspace / "upgrade/graylog-jetbrains/lib"
        upgrade.mkdir(parents=True)
        with zipfile.ZipFile(original) as source, zipfile.ZipFile(upgrade / f"graylog-jetbrains-plugin-{version}-reload-test.jar", "w", zipfile.ZIP_DEFLATED) as target:
            for item in source.infolist():
                value = source.read(item.filename)
                if item.filename == "META-INF/plugin.xml":
                    value = value.replace(f"<version>{version}</version>".encode(), f"<version>{version}-reload-test</version>".encode())
                target.writestr(item, value)
    else:
        with zipfile.ZipFile(original) as source, zipfile.ZipFile(lib / original.name, "w", zipfile.ZIP_DEFLATED) as target:
            for item in source.infolist():
                value = source.read(item.filename)
                if item.filename == "META-INF/plugin.xml":
                    value = value.replace(b"</extensions>", b'<postStartupActivity implementation="dev.graylog.jetbrains.SmokeActivity"/></extensions>')
                target.writestr(item, value)
            for file in (classes / "dev/graylog/jetbrains").rglob("*.class"):
                target.write(file, file.relative_to(classes).as_posix())
    if args.with_legacy_prototype:
        # Separate plugin directory, matching the real two-plugin migration scenario.
        legacy = workspace / "plugins/legacy-graylog-prototype/lib"
        legacy.mkdir(parents=True)
        descriptor = ('<idea-plugin><id>dev.graylog.jetbrains</id><name>Graylog Explorer (legacy fixture)</name>'
                      '<version>0.11.0</version><vendor>Test fixture</vendor><depends>com.intellij.modules.platform</depends>'
                      '<extensions defaultExtensionNs="com.intellij"><toolWindow id="Graylog" anchor="bottom" '
                      'factoryClass="dev.graylog.jetbrains.LegacyConflictFixture"/></extensions>'
                      '<actions><action id="Graylog.SearchSelection" class="dev.graylog.jetbrains.LegacyConflictFixture$Search" '
                      'text="Legacy Graylog search"/></actions></idea-plugin>')
        with zipfile.ZipFile(legacy / "legacy-fixture.jar", "w", zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("META-INF/plugin.xml", descriptor)
            for file in classes.rglob("LegacyConflictFixture*.class"):
                archive.write(file, file.relative_to(classes).as_posix())
        (workspace / "config").mkdir(exist_ok=True)
        (workspace / "config/disabled_plugins.txt").write_text("dev.graylog.jetbrains\n")
    project = workspace / "demo-project"
    (project / ".idea").mkdir(parents=True)
    (project / ".idea/.name").write_text("Graylog demo")
    server = None
    app = None
    display = None
    try:
        server = subprocess.Popen([sys.executable, str(ROOT / "scripts/mock_graylog.py"), "--port", "0", "--demo-project", str(project), "--extra-events", "120", "--yii-event"],
                                  stdout=subprocess.PIPE, text=True, start_new_session=True)
        api = server.stdout.readline().strip().removeprefix("Demo API: ")
        if not api.startswith("http://127.0.0.1:"):
            raise RuntimeError("Local fixture did not start")
        properties = workspace / "idea.properties"
        properties.write_text("\n".join(f"idea.{name}.path={workspace / name}" for name in ["config", "system", "plugins", "log"]) + "\n")
        config = workspace / "config/options"
        config.mkdir(parents=True)
        (config / "ide.general.xml").write_text('<application><component name="GeneralSettings"><option name="showTipsOnStartup" value="false"/></component></application>')
        options = workspace / "smoke.vmoptions"
        options.write_text("\n".join(["-Xmx1024m" if args.reload else "-Xmx1536m", "-Dgraylog.smoke=true", f"-Dgraylog.reload.plugin={workspace / 'plugins/graylog-jetbrains'}", f"-Dgraylog.reload.upgrade={workspace / 'upgrade/graylog-jetbrains'}", f"-Dgraylog.smoke.legacyInstalled={str(args.with_legacy_prototype).lower()}", f"-Dgraylog.smoke.valuesOnly={str(args.values_only).lower()}", f"-Dgraylog.smoke.sourcesOnly={str(args.sources_only).lower()}", f"-Dgraylog.smoke.api={api}",
                                    f"-Dgraylog.smoke.report={report}", f"-Dgraylog.smoke.theme={args.theme}", "-Didea.initially.ask.config=false",
                                    "-Didea.trust.all.projects=true", "-Dide.show.tips.on.startup.default.value=false",
                                    "-Djb.consents.confirmation.enabled=false", "-Dawt.toolkit.name=XToolkit", "-Dsplash=false"]) + "\n")
        import json
        info = json.loads((ide / "product-info.json").read_text())
        prefix = "PHPSTORM" if info.get("productCode") == "PS" else "IDEA"
        shell = "phpstorm.sh" if prefix == "PHPSTORM" else "idea.sh"
        env = os.environ.copy()
        # Keep the test on Xvfb even when the user's desktop session runs on Wayland.
        env.pop("WAYLAND_DISPLAY", None)
        env["XDG_SESSION_TYPE"] = "x11"
        # A private display also lets us capture startup failures without touching the user's desktop.
        read_fd, write_fd = os.pipe()
        display = subprocess.Popen(["Xvfb", "-displayfd", str(write_fd), "-screen", "0", "1800x1100x24", "-nolisten", "tcp"],
                                   pass_fds=(write_fd,), stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
        os.close(write_fd)
        with os.fdopen(read_fd) as stream:
            number = stream.readline().strip()
        if not number:
            raise RuntimeError("Xvfb did not start")
        env["DISPLAY"] = ":" + number
        env[prefix + "_PROPERTIES"] = str(properties)
        env[prefix + "_VM_OPTIONS"] = str(options)
        with (report / "launcher.log").open("w") as log:
            app = subprocess.Popen([str(ide / "bin" / shell), str(project)],
                                   stdout=log, stderr=subprocess.STDOUT, env=env, start_new_session=True)
            deadline = time.monotonic() + (240 if args.reload else 180)
            last_update = 0
            while time.monotonic() < deadline:
                result = report / "result.txt"
                if result.exists():
                    text = result.read_text()
                    print(text, flush=True)
                    if not text.startswith("PASS:"):
                        return 1
                    if args.reload:
                        print(f"Reload report: {report / 'reload-steps.txt'}", flush=True)
                        return 0
                    print(f"Screenshot: {report / 'ide-smoke.png'}", flush=True)
                    print(f"Yii trace: {report / 'yii-stack-trace.png'}", flush=True)
                    return 0
                if app.poll() is not None:
                    raise RuntimeError(f"IDE exited ({app.returncode}); see {report / 'launcher.log'}")
                if time.monotonic() - last_update >= 15:
                    print("Waiting for isolated IDE smoke test…", flush=True)
                    last_update = time.monotonic()
                    subprocess.run([java_bin(ide, "java"), "-Dawt.toolkit.name=XToolkit", "-classpath", str(classes), "ScreenCapture", str(report / "launch-state.png")],
                                   env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=10, check=False)
                time.sleep(1)
            raise RuntimeError(f"IDE smoke test timed out; see {report / 'launcher.log'} and {workspace / 'log/idea.log'}")
    finally:
        terminate(app)
        terminate(server)
        terminate(display)
        ide_log = workspace / "log/idea.log"
        if ide_log.exists():
            shutil.copy2(ide_log, report / "ide.log")


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except subprocess.CalledProcessError as error:
        raise SystemExit(error.returncode)
