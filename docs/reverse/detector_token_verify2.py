#!/usr/bin/env python3
"""验证设备上实际安装的检测器 APK token + 用 Python raw socket 模拟检测器请求。"""
import subprocess
import socket
import zipfile
import io

def sh(cmd: str, timeout: int = 60) -> str:
    p = subprocess.run(cmd, shell=True, capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()

print("=== installed detector apk path ===")
apk = sh("adb shell pm path io.github.fairyxh.VirEnvDetector 2>&1")
print(apk)
path = apk.replace("package:", "").strip()
print("=== pull installed apk ===")
local = r"D:\Files\Develop\Android\ZhangVirtualProject\ZhangVirtualEnv\docs\reverse\installed_detector.apk"
print(sh(f"adb pull {path} {local}"))
try:
    with zipfile.ZipFile(local) as z:
        names = [n for n in z.namelist() if "token" in n.lower()]
        print("token entries:", names)
        for n in names:
            print(f"  {n} = {z.read(n).decode('utf-8', errors='replace')!r}")
except Exception as e:
    print("zip err:", e)

print("=== python raw socket GET with token ===")
token = open(r"D:\Files\Develop\Android\ZhangVirtualProject\ZhangVirtualEnv\app\src\main\assets\api_token.txt").read().strip()
try:
    s = socket.create_connection(("127.0.0.1", 18790), timeout=5)
    req = f"GET /api/env/status HTTP/1.1\r\nHost: 127.0.0.1\r\nX-ZVE-Token: {token}\r\nConnection: close\r\n\r\n"
    s.sendall(req.encode())
    s.settimeout(5)
    data = b""
    while True:
        chunk = s.recv(4096)
        if not chunk:
            break
        data += chunk
    print("resp:", data.decode("utf-8", errors="replace")[:300])
    s.close()
except Exception as e:
    print("raw socket err:", repr(e))
