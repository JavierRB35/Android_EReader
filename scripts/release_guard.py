"""Fail closed if either Android release version identifier is not increasing."""
import argparse
import concurrent.futures
import hashlib
import json
import pathlib
import re
import subprocess
import tempfile

TAG = re.compile(r"v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)\Z")
MAX_CODE = 2100000000
PACKAGE = "com.javierrb35.bibliotecaia"


def version(tag):
    match = TAG.fullmatch(tag)
    if not match:
        raise ValueError(f"Invalid stable tag: {tag}")
    return tuple(map(int, match.groups()))


def official(pages):
    records = []
    for page in pages:
        for release in page:
            tag = release["tag_name"]
            if release.get("draft") or release.get("prerelease") or not TAG.fullmatch(tag):
                continue
            assets = {a["name"]: a for a in release["assets"]}
            name = f"BibliotecaIA-{tag}.apk"
            if name not in assets:
                continue
            pair = [assets[name], assets.get(name + ".sha256")]
            if any(not a or a.get("state") != "uploaded" or a.get("size", 0) <= 0 for a in pair):
                raise ValueError(f"Incomplete official release {tag}")
            identity = json.dumps([[a["id"], a["size"], a.get("digest"), a["updated_at"]]
                                   for a in pair], separators=(",", ":"))
            records.append((tag, identity))
    return records


def require_newer(records, tag, code, audit=None):
    current = version(tag)
    if type(code) is not int or not 0 < code <= MAX_CODE:
        raise ValueError("Invalid candidate versionCode")
    for prior, identity in records:
        if current <= version(prior):
            raise ValueError(f"{tag} is not newer than {prior}")
        if audit is not None:
            entry = audit.get(identity)
            old = entry.get("code") if entry and entry.get("tag") == prior else None
            if type(old) is not int or not 0 < old <= MAX_CODE or code <= old:
                raise ValueError(f"Missing/non-increasing versionCode against {prior}: {old}")


def inspect(repo, tools, cert, record):
    tag, identity = record
    name = f"BibliotecaIA-{tag}.apk"
    with tempfile.TemporaryDirectory() as directory:
        subprocess.run(["gh", "release", "download", tag, "--repo", repo,
                        "--pattern", name, "--pattern", name + ".sha256",
                        "--dir", directory], check=True)
        apk = pathlib.Path(directory) / name
        manifest = (apk.parent / (name + ".sha256")).read_text().strip()
        checksum = re.fullmatch(r"([0-9a-fA-F]{64})[ \t]+\*?" + re.escape(name), manifest)
        with apk.open("rb") as stream:
            actual = hashlib.file_digest(stream, "sha256").hexdigest()
        if not checksum or actual != checksum.group(1).lower():
            raise ValueError(f"Historical checksum mismatch: {tag}")
        signed = subprocess.check_output([str(tools / "apksigner"), "verify",
                                         "--print-certs", str(apk)], text=True)
        signers = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]+)$",
                             signed, flags=re.M)
        if [s.lower() for s in signers] != [cert]:
            raise ValueError(f"Historical certificate mismatch: {tag}")
        metadata = subprocess.check_output([str(tools / "aapt"), "dump", "badging", str(apk)],
                                           text=True).splitlines()[0]
        fields = dict(re.findall(r"(\w+)='([^']*)'", metadata))
        if fields.get("name") != PACKAGE or fields.get("versionName") != tag[1:]:
            raise ValueError(f"Historical APK identity mismatch: {tag}")
        code = int(fields["versionCode"])
        if not 0 < code <= MAX_CODE:
            raise ValueError(f"Invalid historical code: {tag}")
        return identity, {"tag": tag, "code": code, "cert": cert, "sha256": actual}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=["semantic", "audit", "check"])
    parser.add_argument("--releases", type=pathlib.Path, required=True)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--code", type=int, required=True)
    parser.add_argument("--ledger", type=pathlib.Path)
    parser.add_argument("--repo")
    parser.add_argument("--tools", type=pathlib.Path)
    parser.add_argument("--certificate", default="")
    a = parser.parse_args()
    records = official(json.loads(a.releases.read_text()))
    require_newer(records, a.tag, a.code)
    if a.mode == "semantic":
        return
    if not a.ledger:
        parser.error("--ledger required")
    ledger = json.loads(a.ledger.read_text()) if a.ledger.exists() else {}
    if a.mode == "audit":
        cert = re.sub(r"[\s:-]", "", a.certificate).lower()
        if not re.fullmatch(r"[0-9a-f]{64}", cert) or not a.repo or not a.tools:
            parser.error("Pinned certificate, repo and Android tools required")
        ledger = {k: v for k, v in ledger.items()
                  if isinstance(v, dict) and v.get("cert") == cert}
        missing = [r for r in records if r[1] not in ledger or ledger[r[1]].get("tag") != r[0]]
        with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
            for key, entry in pool.map(lambda r: inspect(a.repo, a.tools, cert, r), missing):
                ledger[key] = entry
        a.ledger.parent.mkdir(parents=True, exist_ok=True)
        a.ledger.write_text(json.dumps(ledger, sort_keys=True))
    require_newer(records, a.tag, a.code, ledger)
    print(f"Checked {len(records)} prior official APKs: {a.tag} / {a.code}")


if __name__ == "__main__":
    main()
