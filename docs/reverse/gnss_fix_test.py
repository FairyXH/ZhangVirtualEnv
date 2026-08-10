#!/usr/bin/env python3
"""安装主模块 + 检测器，重启，完整验证（GNSS 屏蔽 + LTE 诊断）。"""
import subprocess
import time
import re


def sh(cmd: str, timeout: int = 90) -> str:
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


print("=== install module + detector ===")
print(sh("adb install -r D:/Files/Develop/Android/ZhangVirtualProject/ZhangVirtualEnv/app/build/outputs/apk/debug/app-debug.apk"))
print(sh("adb install -r D:/Files/Develop/Android/ZhangVirtualProject/VirEnvDetector/app/build/outputs/apk/debug/app-debug.apk"))
print("=== reboot ===")
sh("adb reboot")
subprocess.run(["adb", "wait-for-device"], capture_output=True, timeout=180)
for _ in range(120):
    if devsh("getprop sys.boot_completed") == "1":
        print("BOOT_OK")
        break
    time.sleep(2)

print("=== start detector ===")
sh("adb shell am force-stop io.github.fairyxh.VirEnvDetector")
time.sleep(1)
sh("adb logcat -c")
sh("adb shell am start -n io.github.fairyxh.VirEnvDetector/.MainActivity")
time.sleep(5)

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

print("=== wait 18s ===")
time.sleep(18)
print("=== detector logcat ===")
p = subprocess.run(["adb", "logcat", "-d", "-s", "VirEnvDetector:*"], capture_output=True, timeout=60)
print(p.stdout.decode("utf-8", errors="replace")[-5000:])
print("=== ZVirtualEnv diag ===")
p = subprocess.run(["adb", "logcat", "-d", "-s", "ZVirtualEnv:*"], capture_output=True, timeout=60)
out = p.stdout.decode("utf-8", errors="replace")
for line in out.splitlines():
    if "CellIdentityLte" in line or "gnss injector" in line or "gnss virtual" in line:
        print(line)
