import hashlib
import json
from pathlib import Path, PurePosixPath
import subprocess
import tarfile
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
TESTS = Path(__file__).resolve().parent
ANDROID_STUBS = sorted((TESTS / "android").rglob("*.java"))
HOST_TEST = TESTS / "ExtractorHostTests.java"
EXTRACTOR = ROOT / "src/dev/wind/agent/runtime/linux/TrustedRootfsExtractor.java"


def guest_member(path, members):
    pending = path.split("/")
    resolved = []
    followed = 0
    while pending:
        part = pending.pop(0)
        if part in ("", "."):
            continue
        if part == "..":
            if not resolved:
                return None
            resolved.pop()
            continue
        resolved.append(part)
        member = members.get("/".join(resolved))
        if member is not None and member.issym():
            followed += 1
            if followed > 64:
                return None
            resolved.pop()
            if member.linkname.startswith("/"):
                resolved = []
            pending = member.linkname.split("/") + pending
    return members.get("/".join(resolved))


class ExtractorHostTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix="r0-extractor-tests-")
        cls.classes = Path(cls.temp.name) / "classes"
        cls.classes.mkdir()
        subprocess.run(
            ["javac", "-encoding", "UTF-8", "-source", "8", "-target", "8", "-d", str(cls.classes),
             *map(str, ANDROID_STUBS), str(EXTRACTOR), str(HOST_TEST)],
            check=True,
        )
        completed = subprocess.run(
            ["java", "-cp", str(cls.classes), "dev.wind.agent.runtime.linux.ExtractorHostTests"],
            check=True, text=True, capture_output=True,
        )
        print(completed.stdout, end="")

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def test_java_host_harness_completed(self):
        self.assertTrue((self.classes / "dev/wind/agent/runtime/linux/ExtractorHostTests.class").is_file())


class BuiltBundleAudit(unittest.TestCase):
    def test_generated_bundle_matches_manifest_and_reader_contract(self):
        asset_dir = ROOT / ".build-linux-r0/assets/embedded/linux-r0/x86_64"
        manifest_path = asset_dir / "manifest.json"
        bundle = asset_dir / "rootfs.tar.gz"
        if not manifest_path.is_file() or not bundle.is_file():
            self.skipTest("generated x86_64 R0 fixture is not present")
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        digest = hashlib.sha256()
        with bundle.open("rb") as stream:
            for block in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(block)
        self.assertEqual(bundle.stat().st_size, manifest["compressedBytes"])
        self.assertEqual(digest.hexdigest(), manifest["sha256"])

        count = expanded = pax_count = 0
        kinds = {"file": 0, "dir": 0, "symlink": 0, "hardlink": 0}
        seen = {}
        duplicate_directories = []
        names = set()
        members_by_name = {}
        links = []
        with tarfile.open(bundle, mode="r|gz") as archive:
            for member in archive:
                count += 1
                expanded += member.size
                name = member.name.removeprefix("./")
                names.add(name)
                members_by_name[name] = member
                if member.pax_headers:
                    pax_count += 1
                if member.isdir():
                    kinds["dir"] += 1
                elif member.issym():
                    kinds["symlink"] += 1
                    links.append((name, member.linkname, True))
                elif member.islnk():
                    kinds["hardlink"] += 1
                    links.append((name, member.linkname, False))
                elif member.isreg():
                    kinds["file"] += 1
                else:
                    self.fail(f"unsupported generated TAR entry type {member.type!r}: {name}")
                if name in seen:
                    if not member.isdir() or seen[name] != "dir":
                        self.fail(f"duplicate non-directory path in generated TAR: {name}")
                    duplicate_directories.append(name)
                else:
                    seen[name] = "dir" if member.isdir() else "other"

        self.assertEqual(count, manifest["entries"])
        self.assertEqual(expanded, manifest["expandedBytes"])
        for required in ("bin/sh", "bin/uname", "opt/jdk/bin/java", "opt/jdk/bin/javac", "opt/vega-r0/probe.sh"):
            resolved = guest_member(required, members_by_name)
            self.assertIsNotNone(resolved, f"missing guest rootfs path: {required}")
            self.assertTrue(resolved.isreg(), f"guest path is not a regular file: {required}")
        self.assertFalse(any(name.startswith("jdk-17.0.16+8/") for name in names), "JDK archive root was not stripped")
        for link_name, target, is_symlink in links:
            if not is_symlink and target not in names:
                self.fail(f"generated hardlink target missing: {link_name} -> {target}")
            if is_symlink:
                parts = [] if target.startswith("/") else list(PurePosixPath(link_name).parent.parts)
                for part in target.split("/"):
                    if part in ("", "."):
                        continue
                    if part == "..":
                        if not parts:
                            self.fail(f"generated symlink escapes rootfs: {link_name} -> {target}")
                        parts.pop()
                    else:
                        parts.append(part)
        print(
            "R0 bundle audit: "
            f"entries={count} expandedBytes={expanded} paxMembers={pax_count} "
            f"symlinks={kinds['symlink']} hardlinks={kinds['hardlink']} "
            f"duplicateDirectories={sorted(set(duplicate_directories))}"
        )


if __name__ == "__main__":
    unittest.main(verbosity=2)
