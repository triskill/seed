"""Fill stdout before reading a large preload, exercising duplex pipes."""
import sys

sys.stdout.write("startup:" + ("x" * (256 * 1024)) + "\n")
sys.stdout.flush()

prompt_bytes = 0
for line in sys.stdin:
    if line == "\n":
        break
    prompt_bytes += len(line.encode("utf-8"))
print(f"prompt-bytes:{prompt_bytes}", flush=True)

for line in sys.stdin:
    print(f"echo: {line.rstrip()}", flush=True)
