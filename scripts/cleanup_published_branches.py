"""Conservative cleanup of work branches already included in a verified release."""
import argparse
import json
import re
import subprocess
from urllib.parse import quote

PREFIXES = ("feature/", "feat/", "fix/", "refactor/", "chore/", "test/")

# Explicitly reviewed pending work branches of this release coordinator.
# They may have been included in a prior publication before cleanup was live.
# All normal merge/ancestry/HEAD/protection/dependency checks still apply.
REVIEWED_BACKLOG = {
    102: "fix/ai-setup-independent-providers-20261125",
    104: "fix/reliable-update-detection-20261125",
    93: "chore/repository-cleanup-20261125",
    94: "fix/multipage-selection-autoscroll-20261125",
    101: "feat/windows-calibre-migrator",
}


def in_cleanup_scope(number, branch, already_published):
    return not already_published or REVIEWED_BACKLOG.get(number) == branch


def run(*args):
    return subprocess.check_output(args, text=True).strip()


def api(path, pages=False):
    args = ["gh", "api"] + (["--paginate", "--slurp"] if pages else [])
    return json.loads(run(*args, path))


def candidate(pr, repo, active):
    head = pr.get("head") or {}
    branch = head.get("ref", "")
    sha, merge = head.get("sha", ""), pr.get("merge_commit_sha") or ""
    if (not pr.get("merged_at") or (pr.get("base") or {}).get("ref") != "main"
            or (head.get("repo") or {}).get("full_name") != repo
            or not branch.startswith(PREFIXES) or branch in active
            or not re.fullmatch(r"[a-f0-9]{40}", sha)
            or not re.fullmatch(r"[a-f0-9]{40}", merge)):
        return None
    return branch, sha, merge


def active_refs(repo):
    return {p[side]["ref"] for page in api(f"repos/{repo}/pulls?state=open&per_page=100", True)
            for p in page for side in ("head", "base") if p.get(side)}


def ancestor(older, newer):
    return subprocess.run(["git", "merge-base", "--is-ancestor", older, newer],
                          stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", required=True)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--sha", required=True)
    parser.add_argument("--previous", default="")
    a = parser.parse_args()
    r = api(f"repos/{a.repo}/releases/latest")
    name = f"BibliotecaIA-{a.tag}.apk"
    assets = {x["name"]: x for x in r["assets"]}
    if (r["tag_name"] != a.tag or r["target_commitish"] != a.sha or r["draft"] or r["prerelease"]
            or any(n not in assets or assets[n]["state"] != "uploaded"
                   or assets[n]["size"] <= 0 for n in (name, name + ".sha256"))
            or run("git", "rev-parse", "HEAD") != a.sha):
        raise ValueError("Unverified release; retain all branches")
    subprocess.run(["gh", "auth", "setup-git"], check=True)
    active = active_refs(a.repo)
    seen = set()
    for page in api(f"repos/{a.repo}/pulls?state=closed&per_page=100", True):
        for pr in page:
            value = candidate(pr, a.repo, active)
            if not value:
                continue
            branch, sha, merge = value
            if branch in seen:
                continue
            seen.add(branch)
            # New integrations plus the explicit reviewed backlog. Never sweep
            # unrelated historical/WIP branches.
            if not ancestor(merge, a.sha):
                continue
            already_published = bool(a.previous and ancestor(merge, a.previous))
            if not in_cleanup_scope(pr["number"], branch, already_published):
                continue
            try:
                meta = api(f"repos/{a.repo}/branches/{quote(branch, safe='')}")
            except subprocess.CalledProcessError:
                continue
            if meta.get("protected", True) or meta.get("commit", {}).get("sha") != sha:
                continue
            if branch in active_refs(a.repo):
                continue
            ref = "refs/heads/" + branch
            result = subprocess.run(["git", "push", f"--force-with-lease={ref}:{sha}",
                                     "origin", ":" + ref], check=False)
            if result.returncode:
                print(f"Retained {branch}: deletion denied or HEAD changed")
            else:
                print(f"Deleted {branch}: PR #{pr['number']}, published {a.tag}")


if __name__ == "__main__":
    main()
