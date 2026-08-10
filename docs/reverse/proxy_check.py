#!/usr/bin/env python3
"""检查设备代理设置（HttpURLConnection 走代理而 raw socket 不走）。"""
import subprocess


def sh(cmd: str, timeout: int = 60) -> str:
    p = subprocess.run(cmd, shell=True, capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()


print("=== global http proxy ===")
print(sh("adb shell settings get global http_proxy 2>&1"))
print(sh("adb shell settings get global global_http_proxy_host 2>&1"))
print(sh("adb shell settings get global global_http_proxy_port 2>&1"))
print("=== secure proxy ===")
print(sh("adb shell settings get secure http_proxy 2>&1"))
print("=== system properties proxy ===")
print(sh("adb shell getprop | grep -i proxy 2>&1"))
print("=== iptables proxy redirect? ===")
print(sh("adb shell su -c 'iptables -t nat -L -n 2>/dev/null | head -20' 2>&1"))
