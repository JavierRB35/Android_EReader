import unittest
from release_guard import official, require_newer
from cleanup_published_branches import candidate, in_cleanup_scope


def fixture(tag="v0.1.128"):
    return {"tag_name": tag, "draft": False, "prerelease": False,
            "assets": [{"name": f"BibliotecaIA-{tag}.apk{suffix}", "id": i,
                        "size": 100, "updated_at": "2026-11-25", "state": "uploaded"}
                       for i, suffix in enumerate(("", ".sha256"), 1)]}


class ReleasePolicyTest(unittest.TestCase):
    def test_strict_both_identifiers(self):
        records = official([[fixture()]])
        audit = {records[0][1]: {"tag": "v0.1.128", "code": 100128}}
        require_newer(records, "v0.1.129", 100129, audit)
        for tag, code in (("v0.1.128", 100129), ("v0.1.129", 100128),
                          ("v0.1.127", 100130), ("v0.1.129", 100127)):
            with self.subTest(tag=tag, code=code), self.assertRaises(ValueError):
                require_newer(records, tag, code, audit)
        with self.assertRaises(ValueError):
            require_newer(records, "v0.1.129", 100129, {})

    def test_all_pages_and_incomplete_asset(self):
        records = official([[fixture("v0.1.130")], [fixture()]])
        with self.assertRaises(ValueError):
            require_newer(records, "v0.1.129", 100129)
        partial = fixture()
        partial["assets"].pop()
        with self.assertRaises(ValueError):
            official([[partial]])


class CleanupPolicyTest(unittest.TestCase):
    def test_conservative_candidates(self):
        repo = "owner/project"
        pr = {"merged_at": "2026-11-25", "base": {"ref": "main"},
              "head": {"ref": "fix/one", "sha": "a"*40, "repo": {"full_name": repo}},
              "merge_commit_sha": "b"*40}
        self.assertEqual(candidate(pr, repo, set()), ("fix/one", "a"*40, "b"*40))
        self.assertIsNone(candidate(pr, repo, {"fix/one"}))
        self.assertIsNone(candidate(pr, "other/project", set()))
        self.assertIsNone(candidate(dict(pr, merged_at=None), repo, set()))
        for branch in ("main", "wip/other", "release/one"):
            self.assertIsNone(candidate(dict(pr, head=dict(pr["head"], ref=branch)), repo, set()))

    def test_only_reviewed_backlog_can_be_cleaned_after_previous_release(self):
        self.assertTrue(in_cleanup_scope(104, "fix/reliable-update-detection-20261125", True))
        self.assertFalse(in_cleanup_scope(104, "fix/different", True))
        self.assertFalse(in_cleanup_scope(99, "wip/transactional-coordinator-v1", True))
        self.assertTrue(in_cleanup_scope(105, "fix/strict-release-and-cleanup-20261125", False))
