#!/usr/bin/env python3
"""验证设置页环境实时测试卡片：开始→六栏实时刷新→结束。"""
import subprocess
import time
import re


def sh(cmd: str, timeout: int = 90) -> str:
    p = subprocess.run(["adb", "shell", cmd], capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()


print("=== 1. 启动 App 进设置页 ===")
sh("am force-stop io.github.fairyxh.VirtualEnv")
time.sleep(1)
sh("am start -n io.github.fairyxh.VirtualEnv/.app.MainActivity")
time.sleep(8)
sh("uiautomator dump /sdcard/ui.xml")
xml = sh("cat /sdcard/ui.xml")
m = re.search(r'text="设置"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
if m:
    x = (int(m.group(1)) + int(m.group(3))) // 2
    y = (int(m.group(2)) + int(m.group(4))) // 2
    sh(f"input tap {x} {y}")
    time.sleep(4)

print("=== 2. 检查卡片与按钮 ===")
sh("uiautomator dump /sdcard/ui2.xml")
xml2 = sh("cat /sdcard/ui2.xml")
print("has env test card:", "环境实时测试" in xml2)
print("has 开始:", "text=\"开始\"" in xml2)
print("has 结束:", "text=\"结束\"" in xml2)
# 滚动到底部找开始按钮
m2 = re.search(r'text="开始"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml2)
if not m2:
    sh("input swipe 632 2200 632 500 600")
    time.sleep(2)
    sh("uiautomator dump /sdcard/ui3.xml")
    xml2 = sh("cat /sdcard/ui3.xml")
    m2 = re.search(r'text="开始"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml2)

print("=== 3. 点击开始 ===")
if m2:
    x = (int(m2.group(1)) + int(m2.group(3))) // 2
    y = (int(m2.group(2)) + int(m2.group(4))) // 2
    print(f"tap 开始 {x},{y}")
    sh(f"input tap {x} {y}")
    time.sleep(8)
else:
    print("开始 button not found")
    exit(1)

print("=== 4. 六栏实时数据 ===")
sh("uiautomator dump /sdcard/ui4.xml")
xml4 = sh("cat /sdcard/ui4.xml")
for section in ["位置", "基站", "蓝牙", "WiFi", "传感器", "GNSS"]:
    # 找 section 后最近的 value 文本
    msec = re.search(rf'text="{section}"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml4)
    print(f"\n--- {section} ---")
    if not msec:
        print("  (not found)")
        continue
    y_sec = (int(msec.group(2)) + int(msec.group(4))) // 2
    # value 通常紧跟 section 下方，dump 中找所有非空 text 在 y 稍下方
    vals = []
    for mm in re.finditer(r'text="([^"]{4,300})"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml4):
        t = mm.group(1)
        yy = (int(mm.group(3)) + int(mm.group(5))) // 2
        if 0 < yy - y_sec < 200 and t not in ["开始", "结束", "主页", "位置", "路线", "环境", "设置"]:
            vals.append(t)
    print("  " + "\n  ".join(vals[:4]) if vals else "  (no value)")

print("\n=== 5. 点击结束 ===")
m3 = re.search(r'text="结束"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml4)
if m3:
    x = (int(m3.group(1)) + int(m3.group(3))) // 2
    y = (int(m3.group(2)) + int(m3.group(4))) // 2
    sh(f"input tap {x} {y}")
    time.sleep(3)
    print("tapped 结束")
else:
    print("结束 button not found")

print("\n=== 6. App 日志 ===")
app = ""
for line in sh("ps -A | grep fairyxh.VirtualEnv").splitlines():
    parts = line.split()
    if len(parts) >= 2 and parts[0] != "root":
        app = parts[1]
if app:
    print(sh(f"logcat -d --pid={app} -t 200 | grep -iE 'env test' | tail -15") or "(no env test logs)")
