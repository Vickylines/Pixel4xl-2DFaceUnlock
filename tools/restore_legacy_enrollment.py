"""Restore this device's backed-up v3 identity through the signed, transactional test runner.

Default: verify local recovery inputs only. --apply restores the legacy recognition backend
in the installed 0.10 APK. It does not downgrade the APK or change user preferences.
The local backup directory and maintenance APK are intentionally excluded from Git.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    backup = root / "device-backups/20261008-before-full-install"
    manifest = json.loads((backup / "manifest.json").read_text(encoding="utf-8"))
    source = (backup / "production-face2d.properties").read_bytes()
    source_hash = hashlib.sha256(source).hexdigest()
    if source_hash != manifest["production_config_sha256"] or b"template_version=3" not in source:
        raise RuntimeError("Original v3 backup verification failed")
    runner = root / "test-artifacts/enrollment-transfer.apk"
    if hashlib.sha256(runner.read_bytes()).hexdigest() != manifest["maintenance_apk_sha256"]:
        raise RuntimeError("Maintenance APK verification failed")
    serial = manifest["deviceSerial"]
    if not re.fullmatch(r"[A-Za-z0-9._:-]+", serial):
        raise RuntimeError("Invalid backup device identity")
    print("Verified original enrollment and signed maintenance APK. No biometric data displayed.")
    if not args.apply:
        print("Run with --apply to restore the legacy backend on the original device.")
        return
    adb = [str(root.parent / "adb/adb.exe"), "-s", serial]

    def call(*parts, data=None):
        return subprocess.run(adb + list(parts), input=data, stdout=subprocess.PIPE,
                              stderr=subprocess.PIPE, check=True).stdout

    def root_shell(command, data=None):
        return call("shell", "-T", "su", "-c", command, data=data)

    target = "/data/user/0/com.yusd.pixel2dface"
    stage = target + "/cache/verified-enrollment-transfer"
    current = call("exec-out", "su", "-c", "cat " + target + "/files/face2d.properties")
    # Preserve the current enrollment as a separate local recovery copy before changing it.
    current_hash = hashlib.sha256(current).hexdigest()
    (backup / ("before-legacy-restore-" + current_hash[:12] + ".properties")).write_bytes(current)
    uid = root_shell("stat -c %u " + target + "/files").decode().strip()
    if not re.fullmatch(r"[0-9]+", uid):
        raise RuntimeError("Unable to verify target owner")
    created = False
    installed = False
    try:
        root_shell("mkdir " + stage)
        created = True
        root_shell("chmod 700 " + stage)
        root_shell("cat > " + stage + "/face2d.properties", data=source)
        root_shell(f"chown {uid}:{uid} {stage} {stage}/face2d.properties")
        root_shell("chmod 600 " + stage + "/face2d.properties")
        root_shell("restorecon -RF " + stage)
        installed = b"Success" in call("install", "-r", str(runner))
        if not installed:
            raise RuntimeError("Maintenance APK installation failed")
        result = call("shell", "am", "instrument", "-w", "-e", "allow_import", "true",
                      "-e", "rollback_v3", "true", "-e", "source_sha256", source_hash,
                      "-e", "target_sha256", current_hash,
                      "com.yusd.pixel2dface.test/com.yusd.pixel2dface.EnrollmentTransferInstrumentation")
        if b"result=PASS" not in result:
            raise RuntimeError("Transactional restore did not report success; inspect current state")
        print("Original v3 identity restored. Existing preferences retained; old sessions revoked.")
    finally:
        if installed:
            call("uninstall", "com.yusd.pixel2dface.test")
        if created:
            # Exact tool-owned staging paths only; never replace or remove the store lock.
            root_shell("rm -f " + stage + "/face2d.properties")
            root_shell("rmdir " + stage + " 2>/dev/null || true")


if __name__ == "__main__":
    main()
