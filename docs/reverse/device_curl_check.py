#!/usr/bin/env python3
"""设备端直连实验：确认 127.0.0.1:18790 在设备内带 token 是否可达。"""
import subprocess


def sh(cmd: str, timeout: int = 60) -> str:
    p = subprocess.run(cmd, shell=True, capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()


token = open(r"D:\Files\Develop\Android\ZhangVirtualProject\ZhangVirtualEnv\app\src\main\assets\api_token.txt").read().strip()

print("=== device curl WITH token ===")
print(sh(f"adb shell 'curl -s -m 5 -H \"X-ZVE-Token: {token}\" http://127.0.0.1:18790/api/env/status'"))
print("=== device curl WITHOUT token ===")
print(sh("adb shell 'curl -s -m 5 http://127.0.0.1:18790/api/env/status' 2>&1; echo EXIT=$?"))
print("=== detector logcat head (token load) ===")
print(sh("adb logcat -d -s VirEnvDetector:* 2>&1 | head -20"))
