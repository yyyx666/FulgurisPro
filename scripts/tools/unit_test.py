#!/usr/bin/env python3
"""Run the JVM unit tests (the test/ source set, Robolectric for anything Android).

    python scripts/tools/unit_test.py                          # everything
    python scripts/tools/unit_test.py --test HistoryGroupingTest
    python scripts/tools/unit_test.py --test 'fulguris.*.Test'

--test takes a Gradle `--tests` pattern (class name or fully qualified name,
wildcards allowed), it can be repeated.
"""
from __future__ import annotations

import argparse
import os
import subprocess

import adb


def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--test", action="append", default=[],
                        help="Gradle --tests pattern, repeatable (default: all tests)")
    parser.add_argument("--build-type", choices=["agentDebug", "agentRelease"],
                        default="agentDebug",
                        help="Which variant's unit tests to run (default: agentDebug)")
    args = parser.parse_args()

    root = adb.repo_root()
    gradlew = os.path.join(root, "gradlew.bat" if os.name == "nt" else "gradlew")
    variant = "SlionsFullAgentDebug" if args.build_type == "agentDebug" else "SlionsFullAgentRelease"
    command = [gradlew, f":app:test{variant}UnitTest"]
    for pattern in args.test:
        command += ["--tests", pattern]

    print("Running " + " ".join(command) + " ...")
    result = subprocess.run(command, cwd=root)
    if result.returncode != 0:
        print("Unit tests FAILED.")
        # Gradle's own summary is terse, point at the human readable reports
        print(f"Reports: {os.path.join(root, 'app', 'build', 'reports', 'tests')}")
        return result.returncode
    print("Unit tests OK.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
