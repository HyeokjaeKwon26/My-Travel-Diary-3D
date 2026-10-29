"""Create tiny emulator-only APKs for updater verification; never signs with release keys."""
import argparse
from pathlib import Path
import os
import re
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", type=Path, default=Path(os.environ.get("ANDROID_HOME", ".")))
    parser.add_argument("--serial", required=True)
    parser.add_argument("--application-id", choices=["com.traveler.next", "com.traveler.threed.next"], required=True)
    parser.add_argument("--debug-keystore", type=Path, default=Path.home() / ".android/debug.keystore")
    args = parser.parse_args()
    if not args.serial.startswith("emulator-"):
        raise ValueError("These fixtures are restricted to an emulator")
    exe = ".exe" if os.name == "nt" else ""
    adb = [str(args.sdk / "platform-tools" / ("adb" + exe)), "-s", args.serial]
    package = subprocess.check_output(adb + ["shell", "dumpsys", "package", args.application_id], text=True)
    code = int(re.search(r"versionCode=(\d+)", package)[1]) + 1
    build_tools = sorted((args.sdk / "build-tools").glob("*"), key=lambda p: tuple(int(n) for n in re.findall(r"\d+", p.name)))[-1]
    platform = sorted((args.sdk / "platforms").glob("android-*"), key=lambda p: int(p.name.split('-')[1]))[-1]
    keytool = Path(os.environ["JAVA_HOME"]) / "bin" / ("keytool" + exe)
    with tempfile.TemporaryDirectory(prefix="traveler-update-test-") as temporary:
        root = Path(temporary)
        wrong = root / "wrong-test-key.jks"
        subprocess.run([str(keytool), "-genkeypair", "-keystore", str(wrong), "-storepass", "android", "-keypass", "android",
                        "-alias", "androiddebugkey", "-dname", "CN=Updater Test Only", "-keyalg", "RSA", "-validity", "30"], check=True, capture_output=True)
        remote = f"/sdcard/Android/data/{args.application_id}/files/updater-fixtures"
        subprocess.run(adb + ["shell", "mkdir", "-p", remote], check=True)
        # adb-created test directory is shell-owned; allow the emulator app to read fixtures.
        subprocess.run(adb + ["shell", "chmod", "2777", remote], check=True)
        for name, app_id, key in [("valid", args.application_id, args.debug_keystore),
                                  ("wrong-signer", args.application_id, wrong),
                                  ("wrong-package", "com.traveler.unrelated", args.debug_keystore)]:
            manifest = root / "AndroidManifest.xml"
            manifest.write_text(f'''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{app_id}"
android:versionCode="{code}" android:versionName="update-fixture">
<uses-sdk android:minSdkVersion="26" android:targetSdkVersion="36" />
<application android:label="Updater Test Only" android:hasCode="false" android:debuggable="true" />
</manifest>''', encoding="utf-8")
            unsigned = root / (name + "-unsigned.apk")
            apk = root / (name + ".apk")
            subprocess.run([str(build_tools / ("aapt2" + exe)), "link", "--manifest", str(manifest), "-I", str(platform / "android.jar"), "-o", str(unsigned)], check=True)
            subprocess.run([str(build_tools / ("apksigner.bat" if os.name == "nt" else "apksigner")), "sign", "--ks", str(key),
                            "--ks-pass", "pass:android", "--key-pass", "pass:android", "--out", str(apk), str(unsigned)], check=True)
            subprocess.run(adb + ["push", str(apk), remote + "/" + apk.name], check=True)
        print(f"Emulator fixtures for {args.application_id}, versionCode {code}. Not distributable app builds.")


if __name__ == "__main__":
    main()
