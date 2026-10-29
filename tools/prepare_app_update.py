"""Prepare update-next.json for an already signed, universal release APK. Does not publish."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
from urllib.parse import quote
import zipfile

CERTIFICATES = {
    "com.traveler.next": ("My-Travel-Diary", "d10b598715b7d0922189b047820266316b07ac2518c4705fc2f89622af5bbe2a"),
    "com.traveler.threed.next": ("My-Travel-Diary-3D", "111eee72aad8e65b12b1e439153526dafd7e325d1607973db4e1b5f344ad7ae7"),
}


def prepare(apk, tag, notes, sdk, output):
    build_tools = sorted((sdk / "build-tools").glob("*"), key=lambda p: tuple(int(n) for n in re.findall(r"\d+", p.name)))[-1]
    suffix = ".exe" if os.name == "nt" else ""
    badging = subprocess.check_output([str(build_tools / ("aapt2" + suffix)), "dump", "badging", str(apk)], text=True, encoding="utf-8")
    package = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
    if not package or package[1] not in CERTIFICATES:
        raise ValueError("Not a supported Traveler APK")
    app_id, code, name = package.groups()
    repo, expected = CERTIFICATES[app_id]
    if tag != "v" + name or re.fullmatch(r"[A-Za-z0-9._-]+", tag) is None:
        raise ValueError("Release tag must be v followed by the APK version name")
    if "application-debuggable" in badging:
        raise ValueError("Do not publish a debuggable APK")
    signer = build_tools / ("apksigner.bat" if os.name == "nt" else "apksigner")
    verification = subprocess.check_output([str(signer), "verify", "--print-certs", str(apk)], text=True, encoding="utf-8")
    digests = re.findall(r"certificate SHA-256 digest: ([0-9a-f]+)", verification)
    if digests != [expected]:
        raise ValueError("Signing certificate does not match this New app lineage; use its permanent production key")
    with zipfile.ZipFile(apk) as z:
        abis = {n.split('/')[1] for n in z.namelist() if n.startswith('lib/') and n.endswith('.so')}
    if not {"arm64-v8a", "armeabi-v7a", "x86_64"}.issubset(abis):
        raise ValueError("Use the universal APK for in-app updates")
    if not 0 < apk.stat().st_size <= 512 * 1024 * 1024:
        raise ValueError("APK exceeds updater size limit")
    if len(notes) > 20_000:
        raise ValueError("Release notes exceed 20,000 characters")
    with apk.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    manifest = dict(schemaVersion=1, applicationId=app_id, versionCode=int(code), versionName=name,
                    minSdk=int(re.search(r"(?:sdkVersion|minSdkVersion):\s*'?(\d+)", badging)[1]),
                    apkUrl=f"https://github.com/HyeokjaeKwon26/{repo}/releases/download/{quote(tag)}/{quote(apk.name)}",
                    apkBytes=apk.stat().st_size, sha256=digest, notes=notes)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Prepared {output}. Upload it as update-next.json alongside {apk.name} in release {tag}.")
    print(f"Verified signing certificate: {expected}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--notes", type=Path, required=True, help="UTF-8 release notes")
    parser.add_argument("--sdk", type=Path, default=Path(os.environ.get("ANDROID_HOME", os.environ.get("ANDROID_SDK_ROOT", "."))))
    parser.add_argument("--output", type=Path, default=Path("artifacts/update/update-next.json"))
    args = parser.parse_args()
    prepare(args.apk, args.tag, args.notes.read_text(encoding="utf-8"), args.sdk, args.output)
