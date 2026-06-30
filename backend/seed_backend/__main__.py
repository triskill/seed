"""Entry point for `python -m seed_backend`.

Task 0.1: just prints a banner so the package is runnable.
Real entry point (uvicorn-loaded service) lands in Task 0.2.
"""
import sys


def main() -> int:
    print("seed backend")
    return 0


if __name__ == "__main__":
    sys.exit(main())
