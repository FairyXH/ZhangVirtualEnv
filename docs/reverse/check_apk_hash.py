#!/usr/bin/env python3
"""对比本地与设备 APK 哈希。"""
import subprocess
import hashlib

LOCAL = r"D:\Files\Develop\Android\ZhangVirtualProject\ZhangVirtualEnv\app\build\outputs\apk\debug\app-debug.apk"

with open(LOCAL, "rb") as f:
    local_hash = hashlib.sha256(f.read()).hexdigest()
print("local sha256:", local_hash)
print("local size:", __import__("os").path.getsize(LOCAL))

p = subprocess.run(
    ["adb", "shell", "sha256sum", "/data/app/io.github.fairyxh.VirtualEnv-*/base.apk"],
    capture_output=True, timeout=60
)
print("device sha256:", p.stdout.decode("utf-8", errors="replace").strip())
print("device stderr:", p.stderr.decode("utf-8", errors="replace").strip())
