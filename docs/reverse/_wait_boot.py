import subprocess
import time
import sys

def sh(*args, **kw):
    return subprocess.run(args, capture_output=True, text=True, **kw)

serial = "3B6F6JE910B4WVXT"
print("waiting for boot...", flush=True)
for i in range(90):
    r = sh("adb", "-s", serial, "shell", "getprop sys.boot_completed")
    if r.returncode == 0 and r.stdout.strip() == "1":
        print("boot_completed=1 after", i, "polls", flush=True)
        break
    time.sleep(2)
else:
    print("BOOT TIMEOUT", flush=True)
    sys.exit(1)

time.sleep(5)
# backend api probe via adb forward
sh("adb", "-s", serial, "forward", "tcp:18790", "tcp:18790")
time.sleep(1)
import urllib.request
try:
    # no token -> server should close without bytes; use a quick socket check instead
    import socket
    s = socket.create_connection(("127.0.0.1", 18790), timeout=3)
    s.close()
    print("port 18790 open (forward ok)", flush=True)
except Exception as e:
    print("port check failed:", e, flush=True)

# dropbox crash check
r = sh("adb", "-s", serial, "shell", "ls -t /data/system/dropbox/ 2>/dev/null | head -5")
print("dropbox head:", r.stdout.strip()[:400] or "(empty)", flush=True)
r = sh("adb", "-s", serial, "shell", "ls /data/system/dropbox/system_server_crash@* 2>/dev/null | tail -2")
print("system_server crashes:", r.stdout.strip() or "(none)", flush=True)
