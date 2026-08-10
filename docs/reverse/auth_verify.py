#!/usr/bin/env python3
"""验证 API token 鉴权：无 token 应表现为连接断开，有 token 正常。"""
import subprocess
import time


def sh(cmd: str, timeout: int = 60) -> str:
    p = subprocess.run(cmd, shell=True, capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()


print("waiting boot...")
subprocess.run(["adb", "wait-for-device"], capture_output=True, timeout=180)
for _ in range(120):
    if sh("adb shell getprop sys.boot_completed") == "1":
        print("BOOT_OK")
        break
    time.sleep(2)

print("\n=== 1. no token (should hang/EOF, no bytes) ===")
p = subprocess.run(
    ["curl", "-s", "-m", "5", "-v", "http://127.0.0.1:18790/api/env/status"],
    capture_output=True, timeout=15
)
print("exit:", p.returncode)
print("stderr tail:", p.stderr.decode("utf-8", errors="replace")[-500:])
print("stdout:", repr(p.stdout.decode("utf-8", errors="replace"))[:200])

print("\n=== 2. with token ===")
token = open(r"D:\Files\Develop\Android\ZhangVirtualProject\ZhangVirtualEnv\app\src\main\assets\api_token.txt").read().strip()
print("token:", token[:12], "...")
p = subprocess.run(
    ["curl", "-s", "-m", "5", "-H", f"X-ZVE-Token: {token}", "http://127.0.0.1:18790/api/env/status"],
    capture_output=True, timeout=15
)
print("exit:", p.returncode)
print("stdout:", p.stdout.decode("utf-8", errors="replace")[:400])
