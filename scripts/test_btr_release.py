#!/usr/bin/env python3
"""Offline release-policy checks; never contacts GitHub or uses a signing key."""
import importlib.util
from contextlib import contextmanager
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import subprocess
import unittest
from unittest.mock import patch
import zipfile

spec = importlib.util.spec_from_file_location("btr_release", Path(__file__).with_name("btr_release.py"))
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


def metadata():
    return {"schema": 1, "repository": release.REPOSITORY, "mode": "release",
            "trusted_sha": "a" * 40, "candidate_sha": "b" * 40, "blbl_sha": "c" * 40,
            "btr_observed_sha": "d" * 40, "btr_native_reference_sha": "e" * 40,
            "bundle_sha256": "f" * 64, "version_code": 1794042000,
            "release_tag": "v20261107.090000", "tv_version_name": "0.1.29-btr.20261107.090000",
            "phone_version_name": "0.1.29-btr-phone.20261107.090000"}


class ReleasePolicyTest(unittest.TestCase):
    def test_report_publish_never_invokes_release_tools(self):
        with tempfile.TemporaryDirectory() as directory:
            root, meta = Path(directory), metadata()
            meta["mode"] = "report"
            release.write_json(root / "release.json", meta)
            args = SimpleNamespace(metadata=root / "release.json", metadata_sha256=release.digest(root / "release.json"),
                                   repo=root / "trusted", work=root / "candidate", bundle=root / "source.bundle", signed=None)
            with patch.object(release, "git", return_value=SimpleNamespace(stdout=meta["trusted_sha"], returncode=0)) as git_calls, \
                 patch.object(release, "clone_source"), \
                 patch.object(release, "api", return_value={"object": {"sha": meta["trusted_sha"]}}), \
                 patch.object(release, "run") as tools, patch.object(release, "summary"), \
                 patch.dict(release.os.environ, {"GH_TOKEN": "fake-test-token"}):
                release.publish(args)
                tools.assert_not_called()
                self.assertTrue(any("push" in c.args for c in git_calls.call_args_list))

    def test_failed_upload_does_not_publish_draft_or_overwrite_assets(self):
        with tempfile.TemporaryDirectory() as directory:
            root, meta = Path(directory), metadata()
            signed = root / "signed"
            signed.mkdir()
            for profile, abi in release.PROFILES:
                (signed / release.asset_name(meta, profile, abi)).write_bytes(b"signed-apk-placeholder")
            release.create_manifest(meta, signed)
            release.write_json(root / "release.json", meta)
            args = SimpleNamespace(metadata=root / "release.json", metadata_sha256=release.digest(root / "release.json"),
                                   repo=root / "trusted", work=root / "candidate", bundle=root / "source.bundle", signed=signed)
            calls = []

            def github_tools(*command, **kwargs):
                calls.append(command)
                if command[:3] == ("gh", "release", "upload"):
                    raise subprocess.CalledProcessError(1, command, stderr="upload failed")
                return SimpleNamespace(stdout="", returncode=0)

            def github_api(path, missing_ok=False):
                return {"object": {"sha": meta["trusted_sha"]}} if path.endswith("heads/main") else None

            with patch.object(release, "git", return_value=SimpleNamespace(stdout=meta["trusted_sha"], returncode=0)), \
                 patch.object(release, "clone_source"), patch.object(release, "api", side_effect=github_api), \
                 patch.object(release, "run", side_effect=github_tools), patch.object(release, "summary"), \
                 patch.dict(release.os.environ, {"GH_TOKEN": "fake-test-token"}):
                with self.assertRaises(subprocess.CalledProcessError):
                    release.publish(args)
            self.assertTrue(any(c[:3] == ("gh", "release", "create") and "--draft" in c for c in calls))
            self.assertFalse(any(c[:3] == ("gh", "release", "edit") for c in calls))
            self.assertFalse(any("--clobber" in c for c in calls))

    def test_gradle_failure_without_captured_stderr_keeps_failure_summary(self):
        argv = ["btr_release.py", "build", "--metadata", "unused", "--metadata-sha256", "0" * 64,
                "--bundle", "unused", "--work", "unused", "--out", "unused"]
        error = subprocess.CalledProcessError(1, ["bash", "gradlew"], stderr=None)
        with patch.object(release.sys, "argv", argv), patch.object(release, "build", side_effect=error), \
             patch.object(release, "summary") as messages:
            with self.assertRaises(SystemExit) as failure:
                release.main()
            self.assertEqual(failure.exception.code, 1)
            self.assertIn("status 1", messages.call_args.args[0])

    @contextmanager
    def upstream_fixture(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            blbl, btr, fork = root / "blbl", root / "btr", root / "fork"
            for repo in (blbl, btr):
                repo.mkdir()
                release.git(repo, "init", "-b", "main")
                release.git(repo, "config", "user.name", "test")
                release.git(repo, "config", "user.email", "test@example.invalid")
                (repo / "README.md").write_text("Author's original README and disclaimer.\n")
                (repo / "code.txt").write_text("base\n")
                release.git(repo, "add", ".")
                release.git(repo, "commit", "-m", "base")
            blbl_base = release.git(blbl, "rev-parse", "HEAD").stdout.strip()
            btr_base = release.git(btr, "rev-parse", "HEAD").stdout.strip()
            release.run("git", "clone", str(blbl), str(fork))
            release.git(fork, "config", "user.name", "test")
            release.git(fork, "config", "user.email", "test@example.invalid")
            for path in release.PROTECTED[:-1]:
                target = fork / path
                if path == ".github/workflows":
                    target.mkdir(parents=True)
                    (target / "trusted.yml").write_text("name: trusted\n")
                else:
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_text("# trusted helper placeholder\n")
            release.write_json(fork / release.STATE, {
                "schema": 1,
                "blbl": {"repository": release.BLBL, "branch": "main", "merged_sha": blbl_base, "legal_review_sha": blbl_base},
                "btr": {"repository": release.BTR, "branch": "main", "observed_sha": btr_base,
                        "native_reference_sha": btr_base, "legal_review_sha": btr_base}})
            release.git(fork, "add", ".")
            release.git(fork, "commit", "-m", "trusted fork")
            trusted = release.git(fork, "rev-parse", "HEAD").stdout.strip()
            real_git = release.git

            def local_git(repo, *args, **kwargs):
                args = tuple(str(a).replace(f"https://github.com/{release.BLBL}.git", str(blbl))
                             .replace(f"https://github.com/{release.BTR}.git", str(btr)) for a in args)
                return real_git(repo, *args, **kwargs)

            def local_api(path, missing_ok=False):
                if path == f"repos/{release.BLBL}/commits/main":
                    return {"sha": real_git(blbl, "rev-parse", "HEAD").stdout.strip()}
                if path == f"repos/{release.BTR}/commits/main":
                    return {"sha": real_git(btr, "rev-parse", "HEAD").stdout.strip()}
                if path.startswith(f"repos/{release.REPOSITORY}/git/ref/tags/"):
                    return None
                raise AssertionError("Unexpected network request " + path)

            with patch.object(release, "git", side_effect=local_git), patch.object(release, "api", side_effect=local_api), patch.object(release, "summary"):
                yield root, blbl, btr, fork, trusted, btr_base

    def test_btr_only_prepare_records_report_and_bundle_without_release(self):
        with self.upstream_fixture() as (root, _, btr, fork, trusted, native):
            (btr / "code.txt").write_text("new JavaScript implementation\n")
            release.git(btr, "add", ".")
            release.git(btr, "commit", "-m", "upstream change")
            observed = release.git(btr, "rev-parse", "HEAD").stdout.strip()
            out = root / "prepared"
            release.prepare(SimpleNamespace(repo=fork, out=out, force=False, event="schedule"))
            meta = release.load_metadata(out / "release.json", release.digest(out / "release.json"))
            self.assertEqual(meta["mode"], "report")
            self.assertNotIn("release_tag", meta)
            state = json.loads((fork / release.STATE).read_text())
            self.assertEqual(state["btr"]["observed_sha"], observed)
            self.assertEqual(state["btr"]["native_reference_sha"], native)
            clone = root / "bundle-clone"
            release.clone_source(out / "source.bundle", meta, clone)
            self.assertEqual(release.git(clone, "rev-parse", "HEAD").stdout.strip(), meta["candidate_sha"])
            self.assertEqual(release.changed_paths(clone, trusted, meta["candidate_sha"]),
                             [release.REPORT, release.STATE])

    def test_blbl_merge_cannot_add_or_replace_trusted_workflows(self):
        with self.upstream_fixture() as (root, blbl, _, fork, trusted, _):
            (blbl / "code.txt").write_text("new Android code\n")
            workflows = blbl / ".github/workflows"
            workflows.mkdir(parents=True)
            (workflows / "trusted.yml").write_text("name: hostile overwrite\n")
            (workflows / "injected.yml").write_text("name: hostile new workflow\n")
            release.git(blbl, "add", ".")
            release.git(blbl, "commit", "-m", "upstream code and workflows")
            out = root / "prepared"
            release.prepare(SimpleNamespace(repo=fork, out=out, force=False, event="schedule"))
            meta = release.load_metadata(out / "release.json", release.digest(out / "release.json"))
            self.assertEqual(meta["mode"], "release")
            self.assertEqual((fork / "code.txt").read_text(), "new Android code\n")
            self.assertEqual((fork / ".github/workflows/trusted.yml").read_text(), "name: trusted\n")
            self.assertFalse((fork / ".github/workflows/injected.yml").exists())
            self.assertEqual(release.git(fork, "diff", "--exit-code", trusted, meta["candidate_sha"], "--", *release.PROTECTED[:-1]).returncode, 0)

    def test_notice_gate_includes_added_and_renamed_readmes(self):
        for path in ("README.md", "docs/readme-zh.md", "LICENSE", "lib/COPYING", "NOTICE.txt", "LICENSING.md"):
            self.assertTrue(release.legal_path(path), path)
        self.assertFalse(release.legal_path("app/src/Example.kt"))

    def test_btr_report_distinguishes_observation_from_native_adaptation(self):
        text = release.make_report("a" * 40, "b" * 40, ["src/range-core.js"])
        self.assertIn("PENDING MANUAL NATIVE ADAPTATION", text)
        self.assertIn("they do not produce an APK", text)
        self.assertIn("src/range-core.js", text)
        self.assertIn("/compare/" + "a" * 40 + "..." + "b" * 40, text)

    def test_metadata_is_bound_to_prepare_digest_and_profile_version(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "release.json"
            release.write_json(path, metadata())
            expected = release.digest(path)
            self.assertEqual(release.load_metadata(path, expected)["mode"], "release")
            path.write_text(path.read_text() + " ", encoding="utf-8")
            with self.assertRaises(ValueError):
                release.load_metadata(path, expected)
            bad = metadata()
            bad["phone_version_name"] = bad["tv_version_name"]
            release.write_json(path, bad)
            with self.assertRaises(ValueError):
                release.load_metadata(path, release.digest(path))

    def test_epoch_version_code_rejects_boolean_and_overflow(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "release.json"
            for invalid in (True, 20261107, 2**31):
                bad = metadata()
                bad["version_code"] = invalid
                release.write_json(path, bad)
                with self.assertRaises(ValueError):
                    release.load_metadata(path, release.digest(path))

    def test_manifest_has_three_exact_profile_abi_assets_and_final_hashes(self):
        with tempfile.TemporaryDirectory() as directory:
            root, meta = Path(directory), metadata()
            for profile, abi in release.PROFILES:
                (root / release.asset_name(meta, profile, abi)).write_bytes(f"final-signed-{profile}-{abi}".encode())
            release.create_manifest(meta, root)
            manifest = json.loads((root / "update.json").read_text())
            self.assertEqual(set(manifest["variants"]), {"tv", "phone"})
            self.assertEqual(set(manifest["variants"]["phone"]["assets"]), {"arm64-v8a"})
            self.assertEqual(set(manifest["variants"]["tv"]["assets"]), {"arm64-v8a", "armeabi-v7a"})
            for profile, abi in release.PROFILES:
                asset = manifest["variants"][profile]["assets"][abi]
                name = release.asset_name(meta, profile, abi)
                self.assertEqual(asset["sha256"], release.digest(root / name))
                self.assertEqual(asset["url"], f"https://github.com/{release.REPOSITORY}/releases/download/{meta['release_tag']}/{name}")
            self.assertIn("adaptation is pending", manifest["notes"])
            self.assertEqual(len((root / "SHA256SUMS").read_text().splitlines()), 4)

    def test_apk_rejects_wrong_native_abi_and_zip_traversal(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "input.apk"
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr("lib/armeabi-v7a/libtest.so", b"native")
            with self.assertRaises(ValueError):
                release.check_apk(path, "arm64-v8a")
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr("../escape", b"unsafe")
            with self.assertRaises(ValueError):
                release.check_apk(path, "arm64-v8a")
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr("classes.dex", b"architecture-independent")
            self.assertEqual(release.check_apk(path, "arm64-v8a"), [])

    def test_failed_draft_is_retried_and_complete_release_is_not(self):
        state = {"last_release": {"tag": "v20261107.090000"}}
        with patch.object(release, "api", return_value={"draft": True, "assets": []}):
            self.assertFalse(release.release_complete(state))
        assets = [{"name": n} for n in ("one.apk", "two.apk", "three.apk", "update.json", "SHA256SUMS")]
        with patch.object(release, "api", return_value={"draft": False, "assets": assets}):
            self.assertTrue(release.release_complete(state))

    def test_git_restore_removes_upstream_workflows_and_preserves_trusted_one(self):
        with tempfile.TemporaryDirectory() as directory:
            repo = Path(directory)
            release.git(repo, "init")
            release.git(repo, "config", "user.name", "test")
            release.git(repo, "config", "user.email", "test@example.invalid")
            workflow = repo / ".github/workflows/trusted.yml"
            workflow.parent.mkdir(parents=True)
            workflow.write_text("name: trusted\n")
            release.git(repo, "add", ".")
            release.git(repo, "commit", "-m", "trusted")
            trusted = release.git(repo, "rev-parse", "HEAD").stdout.strip()
            workflow.write_text("name: upstream-unsafe\n")
            (workflow.parent / "injected.yml").write_text("name: injected\n")
            release.git(repo, "add", ".")
            release.git(repo, "restore", "--source", trusted, "--staged", "--worktree", "--", ".github/workflows")
            self.assertEqual(workflow.read_text(), "name: trusted\n")
            self.assertFalse((workflow.parent / "injected.yml").exists())


if __name__ == "__main__":
    unittest.main()
