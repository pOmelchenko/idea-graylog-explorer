"""Release boundaries: tag/version agreement, archive identity and downloadable checksums."""

import hashlib
import io
from pathlib import Path
import tempfile
import unittest
import zipfile

from release import metadata, package, PLUGIN_ID


class ReleaseTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.properties("0.17.4")

    def properties(self, version):
        (self.root / "gradle.properties").write_text(f"pluginVersion={version}\n")

    def distribution(self, *, version="0.17.4", plugin_id=PLUGIN_ID, count=1):
        jar = io.BytesIO()
        with zipfile.ZipFile(jar, "w") as archive:
            archive.writestr("META-INF/plugin.xml",
                             f"<idea-plugin><id>{plugin_id}</id><version>{version}</version></idea-plugin>")
        destination = self.root / "build/distributions" / metadata(self.root)["zip_name"]
        destination.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(destination, "w") as archive:
            for index in range(count):
                archive.writestr(f"graylog/lib/plugin-{index}.jar", jar.getvalue())
        return destination

    def test_stable_and_prerelease(self):
        self.assertEqual("false", metadata(self.root, "v0.17.4")["prerelease"])
        self.properties("0.18.0-beta.1")
        self.assertEqual("true", metadata(self.root, "v0.18.0-beta.1")["prerelease"])
        self.assertEqual("graylog-jetbrains-0.18.0-beta.1.zip", metadata(self.root)["zip_name"])

    def test_tag_must_match_committed_version(self):
        for tag in ["v0.17.3", "0.17.4", "v0.17.4-beta.1", "v0.17.4\nbad=value"]:
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                metadata(self.root, tag)

    def test_invalid_or_duplicate_versions(self):
        for version in ["", "v0.17.4", "../0.17.4", "01.2.3", "1.2.3 beta", "1.2.3\npluginVersion=1.2.3"]:
            self.properties(version)
            with self.subTest(version=version), self.assertRaises(ValueError):
                metadata(self.root)

    def test_asset_is_unmodified_and_checksum_verifies(self):
        source = self.distribution()
        expected = source.read_bytes()
        destination = package(self.root)
        self.assertEqual(expected, destination.read_bytes())
        checksum = destination.with_name(destination.name + ".sha256").read_text()
        self.assertEqual(f"{hashlib.sha256(expected).hexdigest()}  {destination.name}\n", checksum)
        (destination.parent / "stale.zip").write_bytes(b"old")
        package(self.root)
        self.assertFalse((destination.parent / "stale.zip").exists())

    def test_wrong_or_ambiguous_archive_is_rejected(self):
        for options in [{"version": "0.17.3"}, {"plugin_id": "wrong.id"}, {"count": 0}, {"count": 2}]:
            self.distribution(**options)
            with self.subTest(options=options), self.assertRaises(ValueError):
                package(self.root)

    def test_missing_or_corrupt_zip_is_rejected(self):
        with self.assertRaises(FileNotFoundError):
            package(self.root)
        source = self.distribution()
        source.write_bytes(b"not a zip")
        with self.assertRaises(zipfile.BadZipFile):
            package(self.root)


if __name__ == "__main__":
    unittest.main()

