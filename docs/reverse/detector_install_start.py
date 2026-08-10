#!/usr/bin/env python3
"""安装新检测器 + 启动 + 检查 Root 状态与完整异常堆栈。"""
import subprocess
import time


def sh(cmd: str, timeout: int = 90) -> str:
    p = subprocess.run(cmd, shell=True, capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()


print("=== install ===")
print(sh("adb install -r D:/Files/Develop/Android/ZhangVirtualProject/VirEnvDetector/app/build/outputs/apk/debug/app-debug.apk"))
print("=== force-stop + clear + start ===")
sh("adb shell am force-stop io.github.fairyxh.VirEnvDetector")
time.sleep(1)
sh("adb logcat -c")
sh("adb shell am start -n io.github.fairyxh.VirEnvDetector/.MainActivity")
time.sleep(4)
print("=== onCreate log ===")
p = subprocess.run(["adb", "logcat", "-d", "-s", "VirEnvDetector:*"], capture_output=True, timeout=60)
print(p.stdout.decode("utf-8", errors="replace")[:1500])
