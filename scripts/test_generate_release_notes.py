import os
import pathlib
import subprocess
import tempfile
import unittest

from generate_release_notes import collect


class ReleaseNotesTest(unittest.TestCase):
    def git(self, *args):
        return subprocess.check_output(["git", *args], text=True).strip()

    def test_only_changes_since_previous_official_release(self):
        cwd = os.getcwd()
        with tempfile.TemporaryDirectory() as tmp:
            try:
                os.chdir(tmp)
                self.git("init", "-q")
                self.git("config", "user.name", "Test")
                self.git("config", "user.email", "test@example.invalid")
                notes = pathlib.Path("docs/release-notes")
                notes.mkdir(parents=True)
                (notes / "old.md").write_text("- Antiguo", encoding="utf-8")
                self.git("add", ".")
                self.git("commit", "-qm", "Previous release")
                base = self.git("rev-parse", "HEAD")
                (notes / "one.md").write_text("- Acceso a Drive\n- Importación múltiple\n", encoding="utf-8")
                self.git("add", ".")
                self.git("commit", "-qm", "Implementation (#1)")
                pathlib.Path("fix.txt").write_text("fix", encoding="utf-8")
                self.git("add", ".")
                self.git("commit", "-qm", "Corrige notas (#2)")
                head = self.git("rev-parse", "HEAD")
                self.assertEqual(["Acceso a Drive", "Importación múltiple", "Corrige notas"], collect(base, head))
                self.assertIn("Sin cambios", collect(head, head)[0])
                self.assertIn("Primera publicación", collect("", head)[0])
            finally:
                os.chdir(cwd)


if __name__ == "__main__":
    unittest.main()
