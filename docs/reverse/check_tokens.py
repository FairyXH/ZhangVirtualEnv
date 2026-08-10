#!/usr/bin/env python3
"""检查两个 APK 内 token 与模块当前配置是否一致。"""
import zipfile

apks = {
    "module": r"D:\Files\Develop\Android\ZhangVirtualProject\ZhangVirtualEnv\app\build\outputs\apk\debug\app-debug.apk",
    "detector": r"D:\Files\Develop\Android\ZhangVirtualProject\VirEnvDetector\app\build\outputs\apk\debug\app-debug.apk",
}
for name, apk in apks.items():
    try:
        with zipfile.ZipFile(apk) as z:
            names = [n for n in z.namelist() if "token" in n.lower() or n.startswith("assets/")]
            print(f"=== {name}: {apk}")
            print("token-like entries:", [n for n in names if "token" in n.lower()])
            if any("api_token" in n for n in names):
                entry = [n for n in names if "api_token" in n][0]
                data = z.read(entry).decode("utf-8", errors="replace").strip()
                print(f"  {entry} = {data}")
            else:
                print("  NO api_token.txt in APK!")
    except Exception as e:
        print(f"{name}: error {e}")

src = open(r"D:\Files\Develop\Android\ZhangVirtualProject\ZhangVirtualEnv\app\src\main\assets\api_token.txt").read().strip()
print("\nsource module token:", src)
