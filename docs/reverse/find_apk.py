#!/usr/bin/env python3
"""查找设备上 APK 路径并对比哈希。"""
import subprocess
import hashlib

LOCAL = r"D:\Files\Develop\Android\ZhangVirtualProject\ZhangVirtualEnv\app\build\outputs\apk\debug\app-debug.apk"
with open(LOCAL, "rb") as f:
    local_hash = hashlib.sha256(f.read()).hexdigest()
print("local:", local_hash, __import__("os").path.getsize(LOCAL))

p = subprocess.run(["adb", "shell", "pm path io.github.fairyxh.VirtualEnv"], capture_output=True, timeout=60)
print("pm path:", p.stdout.decode("utf-8", errors="replace").strip())
