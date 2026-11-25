"""Verify server-produced portable backups before publication. No remote access.

The producer must use ZIP_STORED, no data descriptors, legacy Android layout
books/<id>.bin, and biblioteca.json first. Only accept a private immutable
staged file; evidence is for that exact file and trusted coordinator snapshot.
"""
import hashlib
import json
import os
import re
import stat
import struct
import zipfile

MAX_JSON = 16 * 1024**2
MAX_BOOK = 512 * 1024**2
MAX_TOTAL = 4 * 1024**3
MAX_BOOKS = 10_000
HASH = re.compile(r"[a-f0-9]{64}")
ID = re.compile(r"[A-Za-z0-9_-]{1,128}")
SECTIONS = ("books", "categories", "tags", "collections", "bookCategories",
            "bookTags", "collectionBooks", "annotations", "annotationVersions",
            "readingPositions", "bookSynopses", "deletedBooks")
COUNTS = {"books": "books", "annotations": "annotations", "versions": "annotationVersions",
          "positions": "readingPositions", "synopses": "bookSynopses"}


def require(ok, message):
    if not ok:
        raise ValueError(message)


def digest(value):
    return isinstance(value, str) and HASH.fullmatch(value) is not None


def identifier(value):
    return isinstance(value, str) and ID.fullmatch(value) is not None


def unique_pairs(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, "duplicate JSON key")
        result[key] = value
    return result


def reject_constant(_):
    raise ValueError("non-finite JSON number")


def entity_ids(rows):
    result = set()
    for row in rows:
        require(isinstance(row, dict) and identifier(row.get("id")), "invalid entity id")
        require(row["id"] not in result, "duplicate entity id")
        result.add(row["id"])
    return result


def validate_catalogue(raw):
    data = json.loads(raw.decode("utf-8"), object_pairs_hook=unique_pairs,
                      parse_constant=reject_constant)
    require(isinstance(data, dict), "invalid catalogue")
    manifest = data.get("manifest")
    require(isinstance(manifest, dict) and manifest.get("app") == "biblioteca-ia"
            and type(manifest.get("formatVersion")) is int and manifest["formatVersion"] == 2
            and type(manifest.get("exportedAt")) is int
            and manifest["exportedAt"] > 0, "invalid manifest")
    require(manifest.get("originalLayout", "") == ""
            and manifest.get("missingOriginals", []) == [], "partial or unsupported layout")
    require(all(isinstance(data.get(key), list) for key in SECTIONS), "missing section")
    require(all(isinstance(row, dict) for key in SECTIONS for row in data[key]), "invalid row")
    counts = manifest.get("counts")
    require(isinstance(counts, dict) and all(type(counts.get(k)) is int
            and counts[k] == len(data[v]) for k, v in COUNTS.items()), "invalid counts")
    require(len(data["books"]) <= MAX_BOOKS, "too many books")
    books = entity_ids(data["books"])
    categories = entity_ids(data["categories"])
    tags = entity_ids(data["tags"])
    collections = entity_ids(data["collections"])
    annotations = entity_ids(data["annotations"])
    entity_ids(data["annotationVersions"])
    expected = {}
    inventory = {}
    for book in data["books"]:
        name = book.get("file_name")
        sha, size = book.get("content_hash"), book.get("size_bytes")
        require(isinstance(name, str) and 0 < len(name) <= 255 and name not in (".", "..")
                and not any(c in "/\\" or ord(c) < 32 or ord(c) == 127 for c in name),
                "unsafe original name")
        require(book.get("format") in ("EPUB", "PDF", "MOBI") and isinstance(book.get("title"), str)
                and isinstance(book.get("reading_status"), str), "invalid book metadata")
        require(digest(sha) and type(size) is int and 0 < size <= MAX_BOOK
                and sha not in inventory, "invalid or duplicate original")
        require("localPath" not in book and "local_path" not in book, "local path in catalogue")
        inventory[sha] = size
        expected["books/" + book["id"] + ".bin"] = (sha, size)
    require(sum(inventory.values()) <= MAX_TOTAL, "total original limit exceeded")
    for rows, left, right in ((data["bookCategories"], books, categories),
                              (data["bookTags"], books, tags),
                              (data["collectionBooks"], collections, books)):
        pairs = set()
        for row in rows:
            a, b = row.get("first"), row.get("second")
            require(identifier(a) and identifier(b) and a in left and b in right
                    and (a, b) not in pairs, "invalid relationship")
            pairs.add((a, b))
    for row in data["categories"]:
        parent = row.get("parent_id")
        require(parent is None or identifier(parent) and parent in categories
                and parent != row["id"], "invalid category parent")
    for row in data["annotations"]:
        book = row.get("book_id")
        require(book is None or identifier(book) and book in books, "orphan annotation")
    for row in data["annotationVersions"]:
        require(identifier(row.get("annotation_id")) and row["annotation_id"] in annotations,
                "orphan annotation version")
    for key in ("readingPositions", "bookSynopses"):
        seen = set()
        for row in data[key]:
            book = row.get("book_id")
            require(identifier(book) and book in books and book not in seen, "invalid per-book row")
            seen.add(book)
    seen = set()
    for row in data["deletedBooks"]:
        sha, deleted = row.get("hash"), row.get("deletedAt")
        require(digest(sha) and sha not in seen and type(deleted) is int and deleted > 0,
                "invalid tombstone")
        seen.add(sha)
    return expected, inventory


def hash_stream(stream, limit):
    hashed = hashlib.sha256()
    count = 0
    while chunk := stream.read(64 * 1024):
        count += len(chunk)
        require(count <= limit, "entry exceeds expected size")
        hashed.update(chunk)
    return hashed.hexdigest(), count


def check_local_layout(raw, archive, infos):
    # Both Android's streaming parser and the central-directory parser must
    # see the same ordered entries. Refuse gaps, hidden local entries and
    # ambiguous descriptors; the server controls its own ZIP producer.
    offset = 0
    for info in infos:
        require(info.header_offset == offset, "unexpected local entry offset")
        raw.seek(offset)
        header = raw.read(30)
        require(len(header) == 30, "truncated local header")
        sig, version, flags, method, _, _, crc, compressed, size, name_len, extra_len = struct.unpack(
            "<4s5H3I2H", header)
        require(sig == b"PK\x03\x04" and flags == info.flag_bits and flags in (0, 0x800)
                and method == info.compress_type == zipfile.ZIP_STORED
                and crc == info.CRC and compressed == size == info.file_size
                and compressed == info.compress_size and extra_len == 0,
                "unsupported or inconsistent local header")
        require(raw.read(name_len) == info.filename.encode("utf-8"), "inconsistent entry name")
        offset += 30 + name_len + size
    require(offset == archive.start_dir, "hidden local data or directory mismatch")


def verify_archive(path, snapshot):
    """Return byte-derived evidence for publication.mjs, or raise; never extract."""
    require(isinstance(snapshot, dict) and isinstance(snapshot.get("libraryId"), str)
            and bool(snapshot["libraryId"]) and type(snapshot.get("revision")) is int
            and 0 <= snapshot["revision"] <= 2**53 - 1
            and digest(snapshot.get("catalogueSha256")), "invalid trusted snapshot")
    require(isinstance(snapshot.get("originals"), list), "invalid trusted inventory")
    trusted = {}
    for row in snapshot["originals"]:
        require(isinstance(row, dict) and digest(row.get("sha256"))
                and type(row.get("sizeBytes")) is int and 0 < row["sizeBytes"] <= MAX_BOOK
                and row["sha256"] not in trusted, "invalid trusted inventory")
        trusted[row["sha256"]] = row["sizeBytes"]
    with open(path, "rb") as raw:
        initial = os.fstat(raw.fileno())
        require(stat.S_ISREG(initial.st_mode) and 0 < initial.st_size <= MAX_TOTAL + MAX_JSON + 16*1024**2,
                "invalid staged file size")
        with zipfile.ZipFile(raw) as archive:
            infos = archive.infolist()
            require(1 <= len(infos) <= MAX_BOOKS + 1 and infos[0].filename == "biblioteca.json",
                    "invalid archive entries")
            names = [info.filename for info in infos]
            require(len(names) == len(set(names)) and not any(info.is_dir() for info in infos),
                    "duplicate or directory entry")
            check_local_layout(raw, archive, infos)
            require(0 < infos[0].file_size <= MAX_JSON, "catalogue too large")
            metadata = archive.read(infos[0])
            require(hashlib.sha256(metadata).hexdigest() == snapshot["catalogueSha256"],
                    "catalogue digest mismatch")
            expected, inventory = validate_catalogue(metadata)
            require(inventory == trusted and set(names[1:]) == set(expected), "inventory mismatch")
            for info in infos[1:]:
                sha, size = expected[info.filename]
                require(info.file_size == size, "original declared size mismatch")
                with archive.open(info) as source:
                    require(hash_stream(source, size) == (sha, size), "original digest mismatch")
        raw.seek(0)
        sha, size = hash_stream(raw, initial.st_size)
        final = os.fstat(raw.fileno())
        require(size == initial.st_size and
                (initial.st_size, initial.st_mtime_ns, initial.st_ctime_ns) ==
                (final.st_size, final.st_mtime_ns, final.st_ctime_ns), "staged file changed")
    return {"libraryId": snapshot["libraryId"], "revision": snapshot["revision"],
            "catalogueSha256": snapshot["catalogueSha256"], "archiveSha256": sha,
            "archiveSizeBytes": size, "missingHashes": [],
            "originals": [{"sha256": h, "sizeBytes": n} for h, n in sorted(inventory.items())]}
