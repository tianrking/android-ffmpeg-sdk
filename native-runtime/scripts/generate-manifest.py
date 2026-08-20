#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
import re
from pathlib import Path


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def normalize_argument(value: str) -> str:
    if value.startswith("--prefix="):
        return "--prefix=$PREFIX"
    marker = "/toolchains/llvm/prebuilt/"
    if marker in value:
        before, after = value.split(marker, 1)
        option = before.split("=", 1)[0] + "=" if "=" in before else ""
        relative = after.split("/", 1)
        suffix = relative[1] if len(relative) == 2 else ""
        return f"{option}$NDK_TOOLCHAIN/{suffix}"
    return re.sub(r"/[^ ]+/install-[^ /]+", "$PREFIX", value)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--lock", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--configure-log", required=True, type=Path)
    parser.add_argument("--ndk-revision", required=True)
    args = parser.parse_args()

    lock = json.loads(args.lock.read_text(encoding="utf-8"))
    configured: dict[str, list[str]] = {}
    for line in args.configure_log.read_text(encoding="utf-8").splitlines():
        fields = line.split("\t")
        abi = fields[0]
        configured[abi] = [normalize_argument(value) for value in fields[1:]]

    artifacts = []
    expected_libraries = lock["profile"]["libraries"] + lock["profile"]["sdkLibraries"]
    for abi in configured:
        for name in expected_libraries:
            path = args.output / "jniLibs" / abi / name
            artifacts.append(
                {
                    "abi": abi,
                    "path": path.relative_to(args.output).as_posix(),
                    "sizeBytes": path.stat().st_size,
                    "sha256": sha256(path),
                }
            )

    manifest = {
        "schemaVersion": 1,
        "provenance": "official-ffmpeg-signed-release",
        "profile": lock["profile"]["name"],
        "license": lock["profile"]["license"],
        "ffmpeg": {
            "tag": lock["upstream"]["tag"],
            "commit": lock["upstream"]["commit"],
            "sourceUrl": lock["upstream"]["source"]["url"],
            "sourceSha256": lock["upstream"]["source"]["sha256"],
            "sourceDateEpoch": lock["upstream"]["source"]["sourceDateEpoch"],
            "releaseKeyFingerprint": lock["upstream"]["releaseKey"]["fingerprint"],
            "signatureVerified": True,
        },
        "toolchain": {
            "ndkRevision": args.ndk_revision,
            "ndkArchiveSha256": lock["toolchain"]["androidNdk"]["sha256"],
            "androidApi": lock["toolchain"]["minSdk"],
        },
        "abis": list(configured),
        "libraries": expected_libraries,
        "systemLibraries": lock["profile"]["systemLibraries"],
        "configureArguments": configured,
        "externalLibraries": lock["profile"]["externalLibraries"],
        "patches": lock["profile"]["patches"],
        "bridgeSources": lock["profile"]["bridgeSources"],
        "artifacts": artifacts,
    }
    (args.output / "manifest.json").write_text(
        json.dumps(manifest, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
        newline="\n",
    )


if __name__ == "__main__":
    main()
