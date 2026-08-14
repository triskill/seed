"""Spawn a TERM-ignoring helper in the runner's process group."""
import signal
import subprocess
import sys
import time

signal.signal(signal.SIGTERM, signal.SIG_IGN)
helper = subprocess.Popen([
    sys.executable,
    "-c",
    "import signal,time; signal.signal(signal.SIGTERM, signal.SIG_IGN); time.sleep(60)",
])
print(f"helper-pid:{helper.pid}", flush=True)
while True:
    time.sleep(1)
