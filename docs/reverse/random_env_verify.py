#!/usr/bin/env python3
"""验证 random-env API + 检测器全套随机模拟测试。"""
import subprocess
import time
import re
import json


def sh(cmd: str, timeout: int = 90) -> str:
    p = subprocess.run(cmd, shell=True, capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()


def devsh(cmd: str, timeout: int = 90) -> str:
    return sh("adb shell " + cmd, timeout)


def tap_center(xml: str, text: str):
    m = re.search(r'text="' + re.escape(text) + r'"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
    if not m:
        return False
    x = (int(m.group(1)) + int(m.group(3))) // 2
    y = (int(m.group(2)) + int(m.group(4))) // 2
    devsh(f"input tap {x} {y}")
    print(f"tap {text} at {x},{y}")
    return True


token = open(r"D:\Files\Develop\Android\ZhangVirtualProject\ZhangVirtualEnv\app\src\main\assets\api_token.txt").read().strip()

print("=== 1. random-env API ===")
p = subprocess.run(
    ["curl", "-s", "-m", "5", "-X", "POST", "-H", f"X-ZVE-Token: {token}",
     "-H", "Content-Type: application/json", "-d", "{}",
     "http://127.0.0.1:18790/api/debug/random-env"],
    capture_output=True, timeout=15
)
print(p.stdout.decode("utf-8", errors="replace")[:800])

print("\n=== 2. launch detector + random simulate ===")
devsh("am start -n io.github.fairyxh.VirEnvDetector/.MainActivity")
time.sleep(3)
for attempt in range(8):
    devsh("uiautomator dump /sdcard/det.xml")
    xml = devsh("cat /sdcard/det.xml")
    for perm_text in ["Allow", "While using the app", "仅在使用中允许", "允许", "Allow all the time", "始终允许"]:
        if tap_center(xml, perm_text):
            time.sleep(2)
            devsh("uiautomator dump /sdcard/det.xml")
            xml = devsh("cat /sdcard/det.xml")
    if tap_center(xml, "随机模拟"):
        print("random simulate tapped")
        break
    time.sleep(2)

print("\n=== 3. wait 12s ===")
time.sleep(12)

print("\n=== 4. detector logcat ===")
out = devsh("logcat -d -s VirEnvDetector:I 2>/dev/null | tail -60")
print(out)

print("\n=== 5. /api/test/report ===")
p = subprocess.run(
    ["curl", "-s", "-m", "5", "-H", f"X-ZVE-Token: {token}", "http://127.0.0.1:18790/api/test/report"],
    capture_output=True, timeout=15
)
print(p.stdout.decode("utf-8", errors="replace")[:2000])
