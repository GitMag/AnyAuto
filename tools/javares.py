#!/usr/bin/env python3
"""Collect the Java resources from library jars that matter at runtime on Android.

Usage: javares.py OUTDIR jar...

Copies META-INF/services/* (ServiceLoader registrations, e.g. kotlinx-coroutines' Android
main dispatcher) into OUTDIR, merging same-named files line by line like AGP does, and every
non-class resource outside META-INF (e.g. the Car App Library's car-app-api.level, read with
getResourceAsStream at runtime; without it the host's first call fails). Other META-INF
content (licences, Maven/Kotlin metadata, *.version files) is left out; d8 handles the classes.
"""
import os
import sys
import zipfile


def main():
    out, jars = sys.argv[1], sys.argv[2:]
    merged = {}
    plain = {}
    for jar in jars:
        with zipfile.ZipFile(jar) as z:
            for n in z.namelist():
                if n.endswith("/") or ".." in n.split("/") or n.endswith(".class") or n == "module-info.class":
                    continue
                if not n.startswith("META-INF/"):
                    plain.setdefault(n, z.read(n))      # first jar wins, like AGP's pickFirst
                    continue
                if not n.startswith("META-INF/services/"):
                    continue
                lines = merged.setdefault(n, [])
                for line in z.read(n).decode("utf-8", "replace").splitlines():
                    line = line.strip()
                    if line and line not in lines:
                        lines.append(line)
    for n, data in sorted(plain.items()):
        path = os.path.join(out, n)
        os.makedirs(os.path.dirname(path) or out, exist_ok=True)
        with open(path, "wb") as f:
            f.write(data)
    for n, lines in sorted(merged.items()):
        path = os.path.join(out, n)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w") as f:
            f.write("\n".join(lines) + "\n")


if __name__ == "__main__":
    main()
