"""Emit one UTF-8 code point across separate pipe writes."""
import os
import sys
import time

payload = "🙂".encode("utf-8")
os.write(sys.stdout.fileno(), payload[:2])
time.sleep(0.05)
os.write(sys.stdout.fileno(), payload[2:] + b"\n")
