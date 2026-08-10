#!/usr/bin/env python3
"""重启检测器并立刻抓 GnssStatus.Builder 枚举日志（清空缓冲）。"""
import subprocess
import time
import re


def sh(cmd: str, timeout: int = 60) -> str:
    p = subprocess.run(cmd, shell=True, capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()


def devsh(cmd: str, timeout: int = 60) -> str:
    return sh("adb shell " + cmd, timeout)


print("=== clear + restart ===")
sh("adb shell am force-stop io.github.fairyxh.VirEnvDetector")
time.sleep(1)
sh("adb logcat -c")
sh("adb shell am start -n io.github.fairyxh.VirEnvDetector/.MainActivity")
time.sleep(4)

# tap 随机模拟
for attempt in range(8):
    devsh("uiautomator dump /sdcard/d.xml")
    xml = devsh("cat /sdcard/d.xml")
    m = re.search(r'text="随机模拟"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
    if m:
        x = (int(m.group(1)) + int(m.group(3))) // 2
        y = (int(m.group(2)) + int(m.group(4))) // 2
        devsh(f"input tap {x} {y}")
        print(f"tap random at {x},{y}")
        break
    time.sleep(2)
time.sleep(6)

p = subprocess.run(["adb", "logcat", "-d"], capture_output=True, timeout=120)
out = p.stdout.decode("utf-8", errors="replace")
for line in out.splitlines():
    if "GnssStatus.Builder" in line or "build virtual gnss" in line or "NoSuchMethodException" in line:
        print(line[:260])
print("---done---")
