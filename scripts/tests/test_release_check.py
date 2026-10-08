import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
BUNDLE = 'android/app/build/outputs/bundle/release/app-release.aab'


class ReleaseCheckTests(unittest.TestCase):
    def run_gate(self, build_exit=0, inspect_exit=0, extra=()):
        with tempfile.TemporaryDirectory(prefix='release fixtures ') as directory:
            root = Path(directory)
            (root / 'Makefile').write_text((ROOT / 'Makefile').read_text())
            (root / 'android').mkdir()
            gradle = root / 'android/gradlew'
            gradle.write_text('#!/bin/sh\nprintf "build\\n" >> "$TRACE"\nexit "$BUILD_EXIT"\n')
            gradle.chmod(0o755)
            python = root / 'python3'
            python.write_text('#!/usr/bin/python3\nimport json, os, sys\nwith open(os.environ["TRACE"], "a") as f: f.write(json.dumps(sys.argv[1:])+"\\n")\nsys.exit(int(os.environ["INSPECT_EXIT"]))\n')
            python.chmod(0o755)
            trace = root / 'trace'
            env = dict(os.environ, PATH=str(root) + ':' + os.environ['PATH'], TRACE=str(trace), BUILD_EXIT=str(build_exit), INSPECT_EXIT=str(inspect_exit))
            result = subprocess.run(['make', '--no-print-directory', '-j4', 'release-check', 'PLAY_ARTIFACT=stale artifact.aab', 'PLAY_ABI=test abi', *extra], cwd=root, env=env, capture_output=True, text=True)
            return result, trace.read_text().splitlines() if trace.exists() else []

    def test_build_precedes_inspection_and_ignores_manual_artifact(self):
        result, trace = self.run_gate()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(trace[0], 'build')
        self.assertEqual(json.loads(trace[1]), ['scripts/check-play-artifact.py', BUNDLE, '--expected-abi', 'test abi'])

    def test_build_failure_prevents_inspection(self):
        result, trace = self.run_gate(build_exit=9)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(trace, ['build'])

    def test_inspector_failure_propagates(self):
        result, trace = self.run_gate(inspect_exit=7)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(len(trace), 2)

    def test_custom_bundle_rejected_without_inspecting_stale_output(self):
        result, trace = self.run_gate(extra=['PLAY_BUNDLE=custom stale.aab'])
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('PLAY_BUNDLE', result.stderr)
        self.assertEqual(trace, [])
