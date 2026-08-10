#!/usr/bin/env python3
"""重新启动检测器，立即抓安装日志（清空 logcat 后）。"""
import subprocess
import time
import re


def sh(cmd: str, timeout: int = 60) -> str:
    p = subprocess.run(cmd, shell=True, capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()


def devsh(cmd: str, timeout: int = 60) -> str:
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


print("=== restart detector, clear logcat ===")
sh("adb shell am force-stop io.github.fairyxh.VirEnvDetector")
time.sleep(1)
sh("adb logcat -c")
sh("adb shell am start -n io.github.fairyxh.VirEnvDetector/.MainActivity")
time.sleep(4)

for attempt in range(8):
    devsh("uiautomator dump /sdcard/d.xml")
    xml = devsh("cat /sdcard/d.xml")
    for perm_text in ["Allow", "While using the app", "仅在使用中允许", "允许", "Allow all the time", "始终允许"]:
        if tap_center(xml, perm_text):
            time.sleep(2)
            devsh("uiautomator dump /sdcard/d.xml")
            xml = devsh("cat /sdcard/d.xml")
    if tap_center(xml, "随机模拟"):
        print("random simulate tapped")
        break
    time.sleep(2)
time.sleep(6)

print("=== install + gnss logs ===")
p = subprocess.run(["adb", "logcat", "-d"], capture_output=True, timeout=120)
out = p.stdout.decode("utf-8", errors="replace")
for line in out.splitlines():
    if any(k in line for k in [
        "ZVirtualEnv", "VirEnvDetector started", "gnss injector", "GnssStatus",
        "hooked LocationManager", "candidates not found", "framework env hooks",
    ]):
        if "StepHook" not in line and "CellIdentityLte diag" not in line:
            print(line)
print("---done---")
