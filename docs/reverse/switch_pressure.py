#!/usr/bin/env python3
"""压力测试：连续 3 次随机模拟，观察配置切换期间是否有真实数据泄漏。"""
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


sh("adb logcat -c")
for round_no in range(3):
    print(f"===== round {round_no + 1} =====")
    for attempt in range(8):
        devsh("uiautomator dump /sdcard/d.xml")
        xml = devsh("cat /sdcard/d.xml")
        if tap_center(xml, "随机模拟"):
            break
        time.sleep(2)
    # 观察切换窗口：2s 后取一轮，6s 后再取一轮
    time.sleep(2)
    p = subprocess.run(["adb", "logcat", "-d"], capture_output=True, timeout=120)
    out = p.stdout.decode("utf-8", errors="replace")
    verdicts = [l for l in out.splitlines() if "VirEnvDetector" in l and any(k in l for k in ["location:", "cell:", "ble:", "wifi:", "sensor:", "gnss:"])]
    print("--- 切换后 2s ---")
    for line in verdicts[-6:]:
        print(line[:150])
    time.sleep(5)
    p = subprocess.run(["adb", "logcat", "-d"], capture_output=True, timeout=120)
    out = p.stdout.decode("utf-8", errors="replace")
    verdicts = [l for l in out.splitlines() if "VirEnvDetector" in l and any(k in l for k in ["location:", "cell:", "ble:", "wifi:", "sensor:", "gnss:"])]
    print("--- 切换后 7s ---")
    for line in verdicts[-6:]:
        print(line[:150])
