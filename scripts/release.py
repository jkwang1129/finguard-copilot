#!/usr/bin/env python3
"""Build a Git-addressed image bundle or deploy/roll it back on Linux."""

import argparse
from contextlib import contextmanager
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tarfile
import tempfile

ROOT = Path(__file__).resolve().parents[1]
OPS = ROOT / "ops/integration"
APPS = ("core", "copilot", "frontend")
REVISION_LABEL = "org.opencontainers.image.revision"
CORE_LABEL = "io.finguard.core.revision"
SHA = re.compile(r"[0-9a-f]{40}")
DIGEST = re.compile(r"sha256:[0-9a-f]{64}")


def run(*cmd, capture=False, env=None):
    result = subprocess.run(cmd, cwd=ROOT, check=True, env=env, encoding="utf-8",
                            stdout=subprocess.PIPE if capture else None)
    return result.stdout.strip() if capture else None


def revision(value):
    if not SHA.fullmatch(value):
        raise ValueError("Expected a full, lowercase Git SHA")
    return value


def sha_file(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def image_info(image):
    return json.loads(run("docker", "image", "inspect", image, capture=True))[0]


def inspect_image(image, expected_sha, core_sha):
    info = image_info(image)
    config = info["Config"]
    user = config["User"].split(":")[0]
    if not user.isdecimal() or int(user) == 0:
        raise ValueError(f"{image} must declare a numeric non-root user")
    labels = config["Labels"]
    if labels.get(REVISION_LABEL) != expected_sha or labels.get(CORE_LABEL) != core_sha:
        raise ValueError(f"{image} source labels do not match the release")
    return info["Id"]


def git_archive(repo, commit, path):
    archive = path.with_suffix(".tar")
    run("git", "-C", str(repo), "archive", "-o", str(archive), commit)
    with tarfile.open(archive) as stream:
        stream.extractall(path, filter="data")
    archive.unlink()


def schema_fingerprint(*sources):
    digest = hashlib.sha256()
    for source in sources:
        migrations = source / "src/main/resources/db/migration"
        for file in sorted(migrations.rglob("*")):
            if file.is_file():
                digest.update(file.relative_to(migrations).as_posix().encode() + b"\0")
                digest.update(bytes.fromhex(sha_file(file)))
        digest.update(b"\0")
    return digest.hexdigest()


def build(args):
    source = args.source.resolve(strict=True)
    commit = revision(run("git", "-C", str(source), "rev-parse", "HEAD", capture=True))
    if args.expect_sha and revision(args.expect_sha) != commit:
        raise ValueError("Checkout does not match expected Git SHA")
    core_repo = args.core_root.resolve(strict=True)
    core_commit = revision(run("git", "-C", str(core_repo), "rev-parse", "--verify", "--end-of-options",
                               args.core_ref + "^{commit}", capture=True))
    destination = args.output.resolve() / commit
    if destination.exists():
        raise ValueError(f"Release bundle already exists at {destination}")
    with tempfile.TemporaryDirectory(prefix="finguard-release-") as tmp:
        tmp = Path(tmp)
        copilot, core = tmp / "copilot", tmp / "core"
        git_archive(source, commit, copilot)
        git_archive(core_repo, core_commit, core)
        labels = ("--label", f"{REVISION_LABEL}={commit}", "--label", f"{CORE_LABEL}={core_commit}")
        builds = {
            "core": ("--target", "core-runtime", "--build-context", f"core-source={core}", str(copilot)),
            "copilot": ("--target", "runtime", str(copilot)),
            "frontend": (str(copilot / "frontend"),),
        }
        tags = {service: f"finguard-{service}:{commit}" for service in APPS}
        for tag in tags.values():
            existing = subprocess.run(["docker", "image", "inspect", tag], stdout=subprocess.DEVNULL,
                                      stderr=subprocess.DEVNULL, check=False)
            if existing.returncode == 0:
                raise ValueError(f"Refusing to overwrite existing release tag {tag}")
        images = {}
        built = []
        destination.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix=".release-", dir=destination.parent) as stage:
            stage = Path(stage)
            try:
                for service, options in builds.items():
                    tag = tags[service]
                    run("docker", "build", "--progress", "plain", *labels, "--tag", tag, *options)
                    built.append(tag)
                    images[service] = inspect_image(tag, commit, core_commit)
                archive = stage / "images.tar"
                run("docker", "image", "save", "--output", str(archive), *tags.values())
                manifest = {
                    "revision": commit, "coreRevision": core_commit, "schemaSha256": schema_fingerprint(core, copilot),
                    "archiveSha256": sha_file(archive), "images": images,
                }
                (stage / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
                stage.rename(destination)
            except BaseException:
                if built:
                    subprocess.run(["docker", "image", "rm", *built], check=False)
                raise
    print(destination, flush=True)


def load_manifest(releases, commit):
    directory = releases / revision(commit)
    manifest = json.loads((directory / "manifest.json").read_text(encoding="utf-8"))
    if (manifest["revision"] != commit or set(manifest["images"]) != set(APPS)
            or not all(DIGEST.fullmatch(value) for value in manifest["images"].values())
            or not all(re.fullmatch(r"[0-9a-f]{64}", manifest[key]) for key in ("schemaSha256", "archiveSha256"))):
        raise ValueError("Invalid release manifest")
    revision(manifest["coreRevision"])
    return directory, manifest


def ensure_images(directory, manifest):
    commit = manifest["revision"]
    missing = False
    for app, wanted in manifest["images"].items():
        tag = f"finguard-{app}:{commit}"
        result = subprocess.run(["docker", "image", "inspect", tag], capture_output=True, check=False)
        if result.returncode:
            missing = True
        elif json.loads(result.stdout)[0]["Id"] != wanted:
            raise ValueError(f"Image tag collision at {tag}")
    if missing:
        archive = directory / "images.tar"
        if sha_file(archive) != manifest["archiveSha256"]:
            raise ValueError("Release archive checksum mismatch")
        run("docker", "image", "load", "--input", str(archive))
    for app, wanted in manifest["images"].items():
        if inspect_image(f"finguard-{app}:{commit}", commit, manifest["coreRevision"]) != wanted:
            raise ValueError(f"Loaded image identity mismatch for {app}")


@contextmanager
def locked_state(directory):
    import fcntl

    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    with (directory / "release.lock").open("a", encoding="utf-8") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        yield


def save_state(path, state):
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps(state, indent=2) + "\n", encoding="utf-8")
    tmp.replace(path)


def deploy(args):
    if not re.fullmatch(r"finguard-p6-[a-z0-9][a-z0-9-]{0,39}", args.project):
        raise ValueError("Use a dedicated finguard-p6-* Compose project")
    if args.wait_timeout < 1:
        raise ValueError("Wait timeout must be positive")
    env_file = args.env_file.resolve(strict=True)
    releases = args.releases.resolve(strict=True)
    state_dir = args.state_dir.resolve() / args.project
    with locked_state(state_dir):
        state_file = state_dir / "release.json"
        state = json.loads(state_file.read_text(encoding="utf-8")) if state_file.exists() else {}
        current = state.get("current")
        pending = state.get("pending")
        if state and state["environment"] != str(env_file):
            raise ValueError("Project is already associated with another environment file")
        if pending and args.action == "deploy" and args.sha != pending:
            raise ValueError("Retry the interrupted release or roll back to the last healthy release")
        wanted = args.sha if args.action == "deploy" else current if pending else state.get("previous")
        if not wanted:
            raise ValueError("No previously healthy release is recorded")
        directory, manifest = load_manifest(releases, wanted)
        old = load_manifest(releases, current) if current else None
        if old and old[1]["schemaSha256"] != manifest["schemaSha256"]:
            raise ValueError("Migration sets differ. Use a separately verified migration and restore procedure.")

        def activate(location, release):
            ensure_images(location, release)
            env = {key: value for key, value in os.environ.items() if not key.startswith(("FG_", "COMPOSE_"))}
            env["FG_RELEASE_SHA"] = release["revision"]
            command = ("docker", "compose", "-p", args.project, "--env-file", str(env_file),
                       "-f", str(OPS / "compose.yml"), "-f", str(OPS / "release.yml"))
            config = json.loads(run(*command, "config", "--format", "json", env=env, capture=True))
            if str(config["services"]["core"]["ports"][0]["published"]) != "18080":
                raise ValueError("The release frontend expects Core on localhost port 18080")
            if not state:
                label = f"label=com.docker.compose.project={args.project}"
                for kind, flags in (("container", ("-a",)), ("volume", ()), ("network", ())):
                    if run("docker", kind, "ls", *flags, "-q", "--filter", label, capture=True):
                        raise ValueError("Existing Compose resources have no release state")
            save_state(state_file, {**state, "environment": str(env_file), "pending": release["revision"]})
            run(*command, "up", "--no-build", "--wait", "--wait-timeout", str(args.wait_timeout), env=env)
            for app, image_id in release["images"].items():
                container = run(*command, "ps", "-q", app, capture=True, env=env)
                actual = run("docker", "inspect", "--format", "{{.Image}}", container, capture=True)
                if actual != image_id:
                    raise ValueError(f"{app} did not start the release image")

        try:
            activate(directory, manifest)
        except (subprocess.CalledProcessError, ValueError):
            if old and state_file.exists() and json.loads(state_file.read_text(encoding="utf-8")).get("pending") == wanted:
                activate(*old)
                save_state(state_file, {"current": current, "previous": state.get("previous"),
                                        "environment": str(env_file)})
            raise
        previous = current if current != wanted else state.get("previous")
        save_state(state_file, {"current": wanted, "previous": previous, "environment": str(env_file)})
        print(f"Healthy release {wanted}", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="action", required=True)
    builder = subparsers.add_parser("build")
    builder.add_argument("--source", type=Path, default=ROOT)
    builder.add_argument("--core-root", type=Path, required=True)
    builder.add_argument("--core-ref", required=True)
    builder.add_argument("--expect-sha")
    builder.add_argument("--output", type=Path, required=True)
    builder.set_defaults(handler=build)
    for name in ("deploy", "rollback"):
        command = subparsers.add_parser(name)
        command.add_argument("--project", required=True)
        command.add_argument("--env-file", type=Path, required=True)
        command.add_argument("--releases", type=Path, required=True)
        command.add_argument("--state-dir", type=Path, required=True)
        command.add_argument("--wait-timeout", type=int, default=180)
        if name == "deploy":
            command.add_argument("--sha", required=True)
        command.set_defaults(handler=deploy)
    args = parser.parse_args()
    args.handler(args)


if __name__ == "__main__":
    main()
