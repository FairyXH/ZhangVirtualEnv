#!/usr/bin/env python3
"""VirEnvDetector 检测器验证：启动→权限→开始→读取 logcat 六项数据。"""
import subprocess
import time
import re


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


print("=== ensure detector foreground ===")
devsh("am start -n io.github.fairyxh.VirEnvDetector/.MainActivity")
time.sleep(3)

for attempt in range(6):
    devsh("uiautomator dump /sdcard/det.xml")
    xml = devsh("cat /sdcard/det.xml")
    # 权限弹窗：点击 Allow / 允许
    for perm_text in ["Allow", "While using the app", "仅在使用中允许", "允许", "Allow all the time", "始终允许"]:
        if tap_center(xml, perm_text):
            time.sleep(2)
            devsh("uiautomator dump /sdcard/det.xml")
            xml = devsh("cat /sdcard/det.xml")
    if tap_center(xml, "开始检测"):
        print("started")
        break
    time.sleep(2)

print("=== wait 10s for data ===")
time.sleep(10)
print("=== logcat VirEnvDetector ===")
out = devsh("logcat -d -s VirEnvDetector:I 2>/dev/null | tail -80")
print(out)
