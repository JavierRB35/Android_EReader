#!/usr/bin/env python3
"""Build notes from the exact first-parent range of the previous official APK.
Prefer Spanish user-facing release-note fragments newly added by each commit.
Fallback to the recorded squash subject, not an invented feature description.
"""
import argparse
import pathlib
import re
import subprocess


def git(*args):
    return subprocess.check_output(["git", *args], text=True, encoding="utf-8").strip()


def clean(line):
    return " ".join(line.strip().lstrip("-*• ").split())


def collect(base, head):
    if not re.fullmatch(r"[0-9a-f]{40}", head):
        raise ValueError("Invalid release commit")
    if not base:
        return ["Primera publicación con resumen de cambios; no hay versión anterior verificable."]
    if not re.fullmatch(r"[0-9a-f]{40}", base):
        raise ValueError("Invalid previous release commit")
    subprocess.run(["git", "merge-base", "--is-ancestor", base, head], check=True)
    result = []
    for commit in git("rev-list", "--first-parent", "--reverse", f"{base}..{head}").splitlines():
        changes = []
        for path in git("diff-tree", "--first-parent", "-m", "--no-commit-id", "--name-only", "--diff-filter=A", "-r", commit, "--", "docs/release-notes/").splitlines():
            if not path.endswith(".md"):
                continue
            for line in git("show", f"{commit}:{path}").splitlines():
                if line.strip().startswith(("- ", "* ", "• ")):
                    changes.append(clean(line))
        if not changes:
            subject = git("show", "-s", "--format=%s", commit)
            subject = re.sub(r"\\s*\\(#\\d+\\)\\s*$", "", subject)
            changes.append(clean(subject))
        for change in changes:
            if change and change not in result:
                result.append(change)
    return result or ["Sin cambios adicionales registrados desde la publicación anterior."]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="")
    parser.add_argument("--head", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    changes = collect(args.base, args.head)
    lines = ["## Cambios de esta versión (desde la última publicación)", ""]
    lines.extend("- " + change for change in changes)
    lines.extend(["", "Compilación del commit " + args.head + ".", "APK firmada y verificada."])
    pathlib.Path(args.output).write_text("\n".join(lines) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
