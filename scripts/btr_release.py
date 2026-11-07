#!/usr/bin/env python3
"""Trusted, standard-library-only helpers for the isolated BTR release workflow."""
import argparse
import base64
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request
import zipfile

REPOSITORY = "NNNNNNAAAAAA/blbl-btr"
BLBL = "cat3399/blbl"
BTR = "MrTangLuyao/Bilibili-thread-ripper"
STATE = ".github/upstream-state.json"
REPORT = ".github/btr-adaptation-report.md"
PROTECTED = (".github/workflows", "scripts/btr_release.py", "scripts/test_btr_release.py", STATE)
PROFILES = (("tv", "armeabi-v7a"), ("tv", "arm64-v8a"), ("phone", "arm64-v8a"))
SHA = re.compile(r"[0-9a-f]{40}\Z")
HASH = re.compile(r"[0-9a-f]{64}\Z")


def run(*args, cwd=None, env=None, check=True):
    return subprocess.run([str(a) for a in args], cwd=cwd, env=env, check=check, text=True,
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE)


def git(repo, *args, check=True, env=None):
    return run("git", "-c", "core.hooksPath=/dev/null", "-C", repo, *args, check=check, env=env)


def digest(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def write_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def summary(message):
    print(message)
    if os.getenv("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as stream:
            stream.write(message + "\n\n")


def output(name, value):
    if os.getenv("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as stream:
            stream.write(f"{name}={value}\n")


def api(path, missing_ok=False):
    headers = {"Accept": "application/vnd.github+json", "User-Agent": "blbl-btr-release"}
    if os.getenv("GH_TOKEN"):
        headers["Authorization"] = "Bearer " + os.environ["GH_TOKEN"]
    request = urllib.request.Request("https://api.github.com/" + path, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        if error.code == 404 and missing_ok:
            return None
        raise


def validate_sha(value):
    if not isinstance(value, str) or not SHA.fullmatch(value):
        raise ValueError("Expected a full lowercase Git commit SHA")
    return value


def release_complete(state):
    previous = state.get("last_release")
    if not previous:
        return True
    if not re.fullmatch(r"v\d{8}\.\d{6}", previous["tag"]):
        raise ValueError("Invalid previous release tag")
    release = api(f"repos/{REPOSITORY}/releases/tags/{previous['tag']}", missing_ok=True)
    return bool(release and not release["draft"] and
                {"update.json", "SHA256SUMS"}.issubset({a["name"] for a in release["assets"]}) and
                sum(a["name"].endswith(".apk") for a in release["assets"]) == 3)


def legal_path(path):
    # Deliberately includes every README edit, so disclaimer/permission changes cannot
    # silently pass merely because their heading or language changed.
    name = PurePosixPath(path).name.upper()
    return name.startswith(("README", "LICENSE", "LICENCE", "LICENSING", "COPYING", "COPYRIGHT", "NOTICE"))


def changed_paths(repo, before, after):
    return [p for p in git(repo, "diff", "--name-only", "-z", before, after).stdout.split("\0") if p]


def check_legal(repo, project, before, after):
    changed = [p for p in changed_paths(repo, before, after) if legal_path(p)]
    if changed:
        summary(f"## Stopped for manual notice/license review\n\n{project}: {before} → {after}\n\n"
                "README/license/notice files changed. Review the upstream permissions and disclaimers, "
                "preserve the author's text, then explicitly update legal_review_sha in "
                f"{STATE} before retrying. force does not bypass this gate.\n\n" +
                "\n".join("- " + p.replace('`', '') for p in changed))
        raise RuntimeError("Upstream legal/readme review is required")


def make_report(native, observed, paths):
    pending = native != observed
    listing = json.dumps(paths[:300], ensure_ascii=False, indent=2).replace("`", "\\u0060")
    return ("# BTR native adaptation report\n\n"
            f"- Native Kotlin reference: `{native}`\n- Latest observed BTR commit: `{observed}`\n"
            f"- Status: {'PENDING MANUAL NATIVE ADAPTATION' if pending else 'Native reference matches observed commit'}\n"
            f"- Compare: https://github.com/{BTR}/compare/{native}...{observed}\n\n"
            "Observing upstream JavaScript does not adapt or execute it in Android. BTR-only changes "
            "produce this report; they do not produce an APK. Port applicable changes to Kotlin, "
            "add/adjust tests, and explicitly advance native_reference_sha in the adaptation commit. "
            "An app-code push then runs the release gates. A blbl update may release independently "
            "while this report remains pending.\n\n"
            f"## Changed BTR files ({len(paths)}; first 300 shown)\n\n```json\n{listing}\n```\n")


def prepare(args):
    repo, out = Path(args.repo).resolve(), Path(args.out).resolve()
    out.mkdir(parents=True, exist_ok=True)
    trusted = validate_sha(git(repo, "rev-parse", "HEAD").stdout.strip())
    state = json.loads((repo / STATE).read_text(encoding="utf-8"))
    if state["blbl"]["repository"] != BLBL or state["btr"]["repository"] != BTR:
        raise ValueError("Unexpected upstream repository")
    if git(repo, "status", "--porcelain", "--untracked-files=no").stdout.strip():
        raise RuntimeError("Prepare requires a clean checkout")
    blbl = validate_sha(api(f"repos/{BLBL}/commits/main")["sha"])
    btr = validate_sha(api(f"repos/{BTR}/commits/main")["sha"])
    blbl_old = validate_sha(state["blbl"]["merged_sha"])
    btr_old = validate_sha(state["btr"]["observed_sha"])
    native = validate_sha(state["btr"]["native_reference_sha"])
    blbl_changed, btr_changed = blbl != blbl_old, btr != btr_old
    mode = ("release" if args.force or args.event == "push" or blbl_changed or not release_complete(state)
            else "report" if btr_changed else "none")
    output("mode", mode)
    if mode == "none":
        summary("No upstream changes and no incomplete release; no build is needed.")
        return
    git(repo, "fetch", "--no-tags", f"https://github.com/{BLBL}.git", "main:refs/remotes/blbl-upstream/main")
    if git(repo, "merge-base", "--is-ancestor", blbl_old, blbl, check=False).returncode:
        raise RuntimeError("blbl main was rewritten; manual review is required")
    check_legal(repo, BLBL, validate_sha(state["blbl"]["legal_review_sha"]), blbl)
    with tempfile.TemporaryDirectory(prefix="btr-upstream-") as directory:
        git(directory, "init")
        git(directory, "remote", "add", "upstream", f"https://github.com/{BTR}.git")
        refs = sorted({btr, btr_old, native, validate_sha(state["btr"]["legal_review_sha"])})
        git(directory, "fetch", "--filter=blob:none", "--no-tags", "--depth=1", "upstream", *refs)
        check_legal(directory, BTR, state["btr"]["legal_review_sha"], btr)
        report = make_report(native, btr, changed_paths(directory, native, btr))
    git(repo, "config", "user.name", "github-actions[bot]")
    git(repo, "config", "user.email", "41898282+github-actions[bot]@users.noreply.github.com")
    if blbl_changed:
        merged = git(repo, "merge", "--no-ff", "--no-commit", blbl, check=False)
        # Never let upstream install/modify workflows or the trusted release implementation.
        git(repo, "restore", "--source", trusted, "--staged", "--worktree", "--", *PROTECTED)
        conflicts = git(repo, "diff", "--name-only", "--diff-filter=U").stdout.strip()
        if conflicts or (merged.returncode and not (repo / ".git/MERGE_HEAD").exists()):
            summary("## Stopped: upstream merge conflict\n\n" + (conflicts or merged.stderr))
            raise RuntimeError("Upstream merge requires manual resolution")
    state["blbl"].update(merged_sha=blbl, legal_review_sha=blbl)
    state["btr"].update(observed_sha=btr, legal_review_sha=btr)
    # native_reference_sha intentionally remains exactly what the maintainer committed.
    metadata = {"schema": 1, "repository": REPOSITORY, "mode": mode, "trusted_sha": trusted,
                "blbl_sha": blbl, "btr_observed_sha": btr, "btr_native_reference_sha": native}
    if mode == "release":
        now = datetime.now(timezone.utc)
        code = int(now.timestamp())
        if not 20261107 < code < 2**31 or code <= state.get("last_release", {}).get("version_code", 0):
            raise ValueError("Release epoch versionCode must increase and fit Android Int")
        stamp = now.strftime("%Y%m%d.%H%M%S")
        metadata.update(version_code=code, release_tag="v" + stamp,
                        tv_version_name="0.1.29-btr." + stamp,
                        phone_version_name="0.1.29-btr-phone." + stamp)
        if api(f"repos/{REPOSITORY}/git/ref/tags/{metadata['release_tag']}", missing_ok=True):
            raise RuntimeError("This release tag already exists; assets will never be overwritten")
        state["last_release"] = {"tag": metadata["release_tag"], "version_code": code}
    write_json(repo / STATE, state)
    (repo / REPORT).write_text(report, encoding="utf-8")
    git(repo, "add", "--", STATE, REPORT)
    git(repo, "commit", "-m", "Track upstreams and " + ("prepare " + metadata["release_tag"] if mode == "release" else "record BTR adaptation report"))
    candidate = validate_sha(git(repo, "rev-parse", "HEAD").stdout.strip())
    git(repo, "diff", "--exit-code", trusted, candidate, "--", *PROTECTED[:-1])
    metadata["candidate_sha"] = candidate
    git(repo, "bundle", "create", out / "source.bundle", "HEAD")
    metadata["bundle_sha256"] = digest(out / "source.bundle")
    write_json(out / "release.json", metadata)
    shutil.copyfile(repo / REPORT, out / "btr-adaptation-report.md")
    output("metadata_sha256", digest(out / "release.json"))
    summary(report)
    summary(f"Prepared mode **{mode}**, candidate `{candidate}`. "
            "No main-branch changes are pushed until the applicable gates pass.")


def load_metadata(path, expected):
    if not HASH.fullmatch(expected) or digest(path) != expected:
        raise ValueError("Prepared metadata integrity mismatch")
    meta = json.loads(Path(path).read_text(encoding="utf-8"))
    if meta["schema"] != 1 or meta["repository"] != REPOSITORY or meta["mode"] not in ("release", "report"):
        raise ValueError("Invalid release metadata")
    for key in ("trusted_sha", "candidate_sha", "blbl_sha", "btr_observed_sha", "btr_native_reference_sha"):
        validate_sha(meta[key])
    if not HASH.fullmatch(meta["bundle_sha256"]):
        raise ValueError("Invalid bundle digest")
    if meta["mode"] == "release":
        if type(meta["version_code"]) is not int or not 20261107 < meta["version_code"] < 2**31:
            raise ValueError("Invalid Android versionCode")
        if not re.fullmatch(r"v\d{8}\.\d{6}", meta["release_tag"]):
            raise ValueError("Invalid release tag")
        for profile in ("tv", "phone"):
            expected_name = "0.1.29-btr" + ("-phone" if profile == "phone" else "") + "." + meta["release_tag"][1:]
            if meta[f"{profile}_version_name"] != expected_name:
                raise ValueError("Invalid profile versionName")
    return meta


def clone_source(bundle, meta, work):
    if digest(bundle) != meta["bundle_sha256"]:
        raise ValueError("Source bundle integrity mismatch")
    run("git", "clone", "--no-checkout", str(Path(bundle).resolve()), str(work))
    git(work, "checkout", "--detach", meta["candidate_sha"])


def unsigned_name(profile, abi):
    return f"unsigned-{profile}-{abi}.apk"


def asset_name(meta, profile, abi):
    return f"blbl-btr-{meta[profile + '_version_name']}-{abi}-release.apk"


def build(args):
    meta = load_metadata(args.metadata, args.metadata_sha256)
    if meta["mode"] != "release":
        raise ValueError("Report-only runs must not compile APKs")
    work, out = Path(args.work).resolve(), Path(args.out).resolve()
    out.mkdir(parents=True, exist_ok=True)
    clone_source(args.bundle, meta, work)
    for profile in ("tv", "phone"):
        command = ["bash", "./gradlew", "--no-daemon", "--stacktrace", "--console=plain",
                   "-PunsignedRelease=true", f"-PversionCode={meta['version_code']}",
                   f"-PversionName={meta[profile + '_version_name']}"]
        if profile == "phone":
            command.append("-PphonePortrait=true")
        command += [":app:testDebugUnitTest", "--tests", "blbl.cat3399.feature.player.accelerator.*",
                    "--tests", "blbl.cat3399.core.update.*", ":app:assembleRelease"]
        # Gradle is untrusted code and runs only in the job with no release secrets/write token.
        result = subprocess.run(command, cwd=work)
        result.check_returncode()
        apk_dir = work / ("app/build/phone" if profile == "phone" else "app/build") / "outputs/apk/release"
        agp = json.loads((apk_dir / "output-metadata.json").read_text(encoding="utf-8"))
        expected_id = "blbl.cat3399.btr" + (".phone" if profile == "phone" else "")
        if agp["applicationId"] != expected_id:
            raise ValueError("AGP applicationId does not match profile")
        for _, abi in (p for p in PROFILES if p[0] == profile):
            matches = [e for e in agp["elements"] if e["filters"] == [{"filterType": "ABI", "value": abi}]]
            if len(matches) != 1:
                raise ValueError("Missing/ambiguous AGP ABI output")
            element = matches[0]
            if element["versionCode"] != meta["version_code"] or element["versionName"] != meta[profile + "_version_name"]:
                raise ValueError("AGP version mismatch")
            filename = element["outputFile"]
            if PurePosixPath(filename).name != filename or not filename.endswith(".apk"):
                raise ValueError("Invalid AGP output filename")
            shutil.copyfile(apk_dir / filename, out / unsigned_name(profile, abi))


def apk_identity(aapt, apk):
    text = run(aapt, "dump", "badging", apk).stdout
    match = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", text, re.M)
    if not match:
        raise ValueError("Cannot read APK identity")
    return match[1], int(match[2]), match[3]


def check_apk(apk, abi):
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        if any(PurePosixPath(n).is_absolute() or ".." in PurePosixPath(n).parts for n in names):
            raise ValueError("Unsafe path inside APK")
        native = {PurePosixPath(n).parts[1] for n in names if n.startswith("lib/") and n.endswith(".so")}
        if native and native != {abi}:
            raise ValueError("APK native ABI does not match asset")
        # The current Media3 APK has no native .so files; it is ABI-agnostic. The
        # requested split is additionally checked against AGP metadata in the build job.
        return sorted(native)


def create_manifest(meta, directory):
    directory = Path(directory)
    variants = {}
    for profile, abi in PROFILES:
        name = asset_name(meta, profile, abi)
        variant = variants.setdefault(profile, {"version_name": meta[profile + "_version_name"], "assets": {}})
        variant["assets"][abi] = {"url": f"https://github.com/{REPOSITORY}/releases/download/{meta['release_tag']}/{name}",
                                  "sha256": digest(directory / name)}
    notes = ("blbl " + meta["blbl_sha"] + "; BTR observed " + meta["btr_observed_sha"] +
             "; native Kotlin reference " + meta["btr_native_reference_sha"] +
             (". BTR adaptation is pending; no new JavaScript changes are claimed as ported."
              if meta["btr_observed_sha"] != meta["btr_native_reference_sha"] else "."))
    write_json(directory / "update.json", {"schema": 1, "version_code": meta["version_code"],
                                          "release_tag": meta["release_tag"], "notes": notes, "variants": variants})
    files = [directory / asset_name(meta, *p) for p in PROFILES] + [directory / "update.json"]
    (directory / "SHA256SUMS").write_text("".join(f"{digest(p)}  {p.name}\n" for p in files), encoding="utf-8")
    return notes


def sign(args):
    meta = load_metadata(args.metadata, args.metadata_sha256)
    incoming, out, sdk = Path(args.unsigned).resolve(), Path(args.out).resolve(), Path(args.tools).resolve()
    required = {unsigned_name(*p) for p in PROFILES}
    if {p.name for p in incoming.iterdir()} != required or any(p.is_symlink() for p in incoming.iterdir()):
        raise ValueError("Unsigned artifact must contain exactly the three expected APKs")
    out.mkdir(parents=True, exist_ok=True)
    signer, align, aapt = sdk / "apksigner", sdk / "zipalign", sdk / "aapt2"
    for tool in (signer, align, aapt):
        if not tool.is_file():
            raise ValueError("Official Android SDK build-tools 36.0.0 are required")
    # Keystore/passwords exist only in this job. Never invoke Gradle, repo shell
    # scripts, downloaded executables, or merged source in the signing job.
    with tempfile.TemporaryDirectory(prefix="btr-sign-", dir=os.environ.get("RUNNER_TEMP")) as temporary:
        key = Path(temporary) / "release.keystore"
        encoded_key = "".join(os.environ["RELEASE_KEYSTORE_BASE64"].split())
        key.write_bytes(base64.b64decode(encoded_key, validate=True))
        key.chmod(0o600)
        for profile, abi in PROFILES:
            source = incoming / unsigned_name(profile, abi)
            if apk_identity(aapt, source) != ("blbl.cat3399.btr" + (".phone" if profile == "phone" else ""),
                                              meta["version_code"], meta[profile + "_version_name"]):
                raise ValueError("APK applicationId/version mismatch before signing")
            check_apk(source, abi)
            if run(signer, "verify", source, check=False).returncode == 0:
                raise ValueError("Build artifact was already signed; expected an unsigned APK")
            aligned = Path(temporary) / unsigned_name(profile, abi)
            run(align, "-P", "16", "-f", "4", source, aligned)
            target = out / asset_name(meta, profile, abi)
            run(signer, "sign", "--ks", key, "--ks-key-alias", os.environ["RELEASE_KEY_ALIAS"],
                "--ks-pass", "env:RELEASE_STORE_PASSWORD", "--key-pass", "env:RELEASE_KEY_PASSWORD",
                "--out", target, aligned)
            run(signer, "verify", "--verbose", target)
            run(align, "-c", "-P", "16", "4", target)
    notes = create_manifest(meta, out)
    # apksigner may create .idsig alongside APKs; those are not release payloads.
    for sidecar in out.glob("*.idsig"):
        sidecar.unlink()
    summary(f"Signed and verified three APKs for {meta['release_tag']}.\n\n{notes}")


def publish(args):
    meta = load_metadata(args.metadata, args.metadata_sha256)
    repo, work = Path(args.repo).resolve(), Path(args.work).resolve()
    if git(repo, "rev-parse", "HEAD").stdout.strip() != meta["trusted_sha"]:
        raise ValueError("Publish helpers must come from the original trusted workflow SHA")
    clone_source(args.bundle, meta, work)
    git(work, "diff", "--exit-code", meta["trusted_sha"], meta["candidate_sha"], "--", *PROTECTED[:-1])
    if api(f"repos/{REPOSITORY}/git/ref/heads/main")["object"]["sha"] != meta["trusted_sha"]:
        raise RuntimeError("main changed during the run; retry against its new head")
    if meta["mode"] == "release":
        signed = Path(args.signed).resolve()
        expected = {asset_name(meta, *p) for p in PROFILES} | {"update.json", "SHA256SUMS"}
        if {p.name for p in signed.iterdir()} != expected or any(p.is_symlink() for p in signed.iterdir()):
            raise ValueError("Release must contain exactly three signed APKs, update.json and SHA256SUMS")
        manifest = json.loads((signed / "update.json").read_text(encoding="utf-8"))
        for profile, abi in PROFILES:
            asset = manifest["variants"][profile]["assets"][abi]
            name = asset_name(meta, profile, abi)
            if asset["sha256"] != digest(signed / name) or asset["url"] != f"https://github.com/{REPOSITORY}/releases/download/{meta['release_tag']}/{name}":
                raise ValueError("Signed asset manifest mismatch")
        if api(f"repos/{REPOSITORY}/git/ref/tags/{meta['release_tag']}", missing_ok=True):
            raise RuntimeError("Release tag already exists; refusing to overwrite")
    auth = base64.b64encode(("x-access-token:" + os.environ["GH_TOKEN"]).encode()).decode()
    env = dict(os.environ, GIT_CONFIG_COUNT="1", GIT_CONFIG_KEY_0="http.https://github.com/.extraheader",
               GIT_CONFIG_VALUE_0="AUTHORIZATION: basic " + auth)
    git(work, "push", f"https://github.com/{REPOSITORY}.git", f"{meta['candidate_sha']}:refs/heads/main", env=env)
    if meta["mode"] == "report":
        summary("BTR changes recorded in .github/btr-adaptation-report.md. No APK was built or released.")
        return
    # Publish a draft only after all payloads are ready. Never --clobber existing assets.
    notes = json.loads((signed / "update.json").read_text(encoding="utf-8"))["notes"]
    notes_path = Path(args.work).parent / "release-notes.md"
    notes_path.write_text(notes + "\n\nIncludes TV ARMv7/ARM64 and phone ARM64.\n", encoding="utf-8")
    run("gh", "release", "create", meta["release_tag"], "--repo", REPOSITORY, "--draft",
        "--target", meta["candidate_sha"], "--title", meta["release_tag"], "--notes-file", notes_path)
    run("gh", "release", "upload", meta["release_tag"], "--repo", REPOSITORY, *[signed / n for n in sorted(expected)])
    run("gh", "release", "edit", meta["release_tag"], "--repo", REPOSITORY, "--draft=false", "--latest")
    summary(f"Published https://github.com/{REPOSITORY}/releases/tag/{meta['release_tag']}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    p = commands.add_parser("prepare")
    p.add_argument("--repo", default=".")
    p.add_argument("--out", required=True)
    p.add_argument("--force", action="store_true")
    p.add_argument("--event", choices=("schedule", "workflow_dispatch", "push"), required=True)
    for command in ("build", "sign", "publish"):
        p = commands.add_parser(command)
        p.add_argument("--metadata", required=True)
        p.add_argument("--metadata-sha256", default=os.getenv("PREPARED_METADATA_SHA256"), required=not bool(os.getenv("PREPARED_METADATA_SHA256")))
        if command in ("build", "publish"):
            p.add_argument("--bundle", required=True)
            p.add_argument("--work", required=True)
        if command == "build":
            p.add_argument("--out", required=True)
        if command == "sign":
            p.add_argument("--unsigned", required=True)
            p.add_argument("--out", required=True)
            p.add_argument("--tools", required=True)
        if command == "publish":
            p.add_argument("--repo", default=".")
            p.add_argument("--signed")
    args = parser.parse_args()
    try:
        globals()[args.command](args)
    except subprocess.CalledProcessError as error:
        # Do not echo a subprocess's environment or full argument list (signing alias etc.).
        details = error.stderr or f"Process exited with status {error.returncode}; see preceding build logs."
        summary("Release step failed; publication stopped.\n\n" + details[-12000:])
        raise SystemExit(1)
    except Exception as error:
        summary("Release step failed; publication stopped: " + str(error))
        raise SystemExit(1)


if __name__ == "__main__":
    main()
