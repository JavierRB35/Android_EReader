import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
import warnings
import zipfile

from verify_archive import verify_archive, SECTIONS


def sha(data):
    return hashlib.sha256(data).hexdigest()


class VerifyArchiveTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.path = Path(self.tmp.name) / "candidate.zip"
        self.original = b"synthetic ebook"
        self.catalogue = {key: [] for key in SECTIONS}
        self.catalogue["manifest"] = {
            "app": "biblioteca-ia", "formatVersion": 2, "exportedAt": 100,
            "counts": {"books": 1, "annotations": 1, "versions": 1, "positions": 1, "synopses": 1}}
        self.catalogue["books"] = [{
            "id": "book-1", "title": "Test", "file_name": "test.epub", "format": "EPUB",
            "reading_status": "PENDING", "content_hash": sha(self.original), "size_bytes": len(self.original)}]
        self.catalogue["annotations"] = [{"id": "note-1", "book_id": "book-1", "body": "example"}]
        self.catalogue["annotationVersions"] = [{"id": "v-1", "annotation_id": "note-1", "body": "old"}]
        self.catalogue["readingPositions"] = [{"book_id": "book-1", "locator": "page:3"}]
        self.catalogue["bookSynopses"] = [{"book_id": "book-1", "text": "synopsis"}]

    def make(self, catalogue=None, entries=None, raw=None, compression=zipfile.ZIP_STORED):
        if raw is None:
            raw = json.dumps(self.catalogue if catalogue is None else catalogue).encode()
        if entries is None:
            entries = [("books/book-1.bin", self.original)]
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with zipfile.ZipFile(self.path, "w", compression=compression) as archive:
                archive.writestr("biblioteca.json", raw)
                for name, data in entries:
                    archive.writestr(name, data)
        return {"libraryId": "lib-1", "revision": 3, "catalogueSha256": sha(raw),
                "originals": [{"sha256": sha(self.original), "sizeBytes": len(self.original)}]}

    def test_verified_actual_zip(self):
        snapshot = self.make()
        evidence = verify_archive(self.path, snapshot)
        self.assertEqual(evidence["archiveSha256"], sha(self.path.read_bytes()))
        self.assertEqual(evidence["archiveSizeBytes"], self.path.stat().st_size)
        self.assertEqual(evidence["originals"], snapshot["originals"])
        self.assertEqual(evidence["revision"], 3)
        self.assertEqual(evidence["missingHashes"], [])

    def test_wrong_metadata_digest_and_inventory(self):
        snapshot = self.make()
        for patch in ({"catalogueSha256": "0" * 64}, {"originals": []}):
            with self.subTest(patch=patch), self.assertRaises(ValueError):
                verify_archive(self.path, {**snapshot, **patch})

    def test_partial_duplicate_unexpected_and_corrupt_original(self):
        for entries in ([], [("books/book-1.bin", self.original)] * 2,
                        [("books/book-1.bin", self.original), ("../escape", b"x")],
                        [("books/book-1.bin", b"x" * len(self.original))],
                        [("books/book-1.bin", b"x")]):
            with self.subTest(entries=entries), self.assertRaises(ValueError):
                verify_archive(self.path, self.make(entries=entries))

    def test_missing_section_wrong_counts_or_dangling_reference(self):
        for mutate in (lambda c: c.pop("deletedBooks"),
                       lambda c: c["manifest"]["counts"].update(annotations=0),
                       lambda c: c["readingPositions"][0].update(book_id="missing"),
                       lambda c: c["manifest"].update(missingOriginals=[sha(self.original)]),
                       lambda c: c["manifest"].update(originalLayout="named-books-v1")):
            candidate = copy.deepcopy(self.catalogue)
            mutate(candidate)
            with self.assertRaises(ValueError):
                verify_archive(self.path, self.make(catalogue=candidate))

    def test_duplicate_json_key(self):
        raw = json.dumps(self.catalogue).encode()
        raw = raw[:-1] + b', "books": []}'
        with self.assertRaises(ValueError):
            verify_archive(self.path, self.make(raw=raw))

    def test_local_header_mutation_and_compression_rejected(self):
        snapshot = self.make()
        data = bytearray(self.path.read_bytes())
        data[30] = ord("x")
        self.path.write_bytes(data)
        with self.assertRaises(ValueError):
            verify_archive(self.path, snapshot)
        with self.assertRaises(ValueError):
            verify_archive(self.path, self.make(compression=zipfile.ZIP_DEFLATED))

    def test_truncated_archive_rejected(self):
        snapshot = self.make()
        self.path.write_bytes(self.path.read_bytes()[:-12])
        with self.assertRaises((ValueError, zipfile.BadZipFile)):
            verify_archive(self.path, snapshot)


if __name__ == "__main__":
    unittest.main()
