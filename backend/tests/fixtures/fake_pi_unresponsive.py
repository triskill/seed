#!/usr/bin/env python3
"""Live RPC child that accepts stdin but never responds."""
import sys

for _ in sys.stdin:
    pass
