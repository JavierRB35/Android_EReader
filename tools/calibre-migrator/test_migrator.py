import hashlib
import json
import sqlite3
import tempfile
import unittest
import zipfile
from pathlib import Path
from migrator import scan, migrate, summarize_report


class MigratorTest(unittest.TestCase):
    def test_calibre_metadata_and_dedup(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d)/"Calibre"; root.mkdir()
            for name in ("A/Uno (1)","B/Dos (2)"):
                folder=root/name; folder.mkdir(parents=True)
                (folder/"metadata.opf").write_text('<package><metadata><title>Título</title><creator>Autor</creator><subject>Historia</subject></metadata></package>',encoding="utf-8")
                # identical bytes in two folders; prefer epub over mobi
                with zipfile.ZipFile(folder/"Libro.epub","w") as z:
                    info=zipfile.ZipInfo("mimetype"); z.writestr(info,b"application/epub+zip")
                (folder/"Libro.mobi").write_bytes(b"0"*60+b"BOOKMOBI"+b"0"*60)
            original=(root/"A/Uno (1)/Libro.epub").read_bytes()
            out,n,count=migrate(root,Path(d)/"out")
            self.assertEqual((n,count),(1,1))
            with zipfile.ZipFile(out/"Migracion-001.zip") as z:
                self.assertEqual(z.namelist()[0],"biblioteca.json")
                j=json.loads(z.read("biblioteca.json"))
                book=j["books"][0]
                self.assertEqual(book["title"],"Título")
                self.assertEqual(book["authors"],"Autor")
                self.assertEqual(book["content_hash"],hashlib.sha256(original).hexdigest())
                self.assertEqual(z.read("books/"+book["id"]+".bin"),original)
                self.assertEqual(j["tags"][0]["name"],"Historia")
                self.assertEqual(j["manifest"]["counts"]["books"],1)
            self.assertIn("Duplicado exacto",(out/"Informe.csv").read_text(encoding="utf-8-sig"))
            self.assertEqual((root/"A/Uno (1)/Libro.epub").read_bytes(),original)
            with self.assertRaises(ValueError): migrate(root,root/"output")

    def test_database_authoritative_and_format_fallback(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d)/"Calibre"; root.mkdir()
            folder=root/"A/Title (1)"; folder.mkdir(parents=True)
            (folder/"Book.epub").write_bytes(b"bad")
            (folder/"Book.pdf").write_bytes(b"%PDF-1.4\n%%EOF")
            (folder/"Orphan.pdf").write_bytes(b"%PDF-1.5\n%%EOF")
            db=sqlite3.connect(root/"metadata.db")
            db.executescript("create table books(id integer,path text,title text,author_sort text); create table data(book integer,format text,name text); insert into books values(1,'A/Title (1)','Title','Author'); insert into data values(1,'EPUB','Book'),(1,'PDF','Book');")
            db.commit(); db.close()
            before=(root/"metadata.db").read_bytes()
            records,warnings=scan(root)
            self.assertEqual(len(records[0].files),2)
            out,n,count=migrate(root,Path(d)/"out")
            self.assertEqual((n,count),(1,1))
            with zipfile.ZipFile(out/"Migracion-001.zip") as z:
                self.assertEqual(json.loads(z.read("biblioteca.json"))["books"][0]["format"],"PDF")
            self.assertEqual((root/"metadata.db").read_bytes(),before)
            self.assertIn("Error:",(out/"Informe.csv").read_text(encoding="utf-8-sig"))
            self.assertEqual(summarize_report(out)["error"],1)

    def test_database_metadata_and_all_formats(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d)/"Calibre"; folder=root/"Autor/Libro (1)"
            folder.mkdir(parents=True)
            (folder/"Libro.pdf").write_bytes(b"%PDF-1.4\n%%EOF")
            (folder/"Libro.mobi").write_bytes(b"0"*60+b"BOOKMOBI"+b"0"*60)
            db=sqlite3.connect(root/"metadata.db")
            db.executescript("""
                create table books(id integer,path text,title text,author_sort text);
                create table data(book integer,format text,name text);
                create table authors(id integer,name text);
                create table books_authors_link(id integer,book integer,author integer);
                create table tags(id integer,name text);
                create table books_tags_link(id integer,book integer,tag integer);
                insert into books values(1,'Autor/Libro (1)','Libro','Apellido, Nombre');
                insert into authors values(1,'Nombre Apellido');
                insert into books_authors_link values(1,1,1);
                insert into tags values(1,'Historia');
                insert into books_tags_link values(1,1,1);
                insert into data values(1,'PDF','Libro'),(1,'MOBI','Libro'),(1,'AZW3','Libro');
            """)
            db.commit(); db.close()
            before=(root/"metadata.db").read_bytes()
            out,n,count=migrate(root,Path(d)/"out",True)
            self.assertEqual((n,count),(1,2))
            with zipfile.ZipFile(out/"Migracion-001.zip") as z:
                j=json.loads(z.read("biblioteca.json"))
                self.assertEqual({x["format"] for x in j["books"]},{"PDF","MOBI"})
                self.assertEqual({x["authors"] for x in j["books"]},{"Nombre Apellido"})
                self.assertEqual(j["tags"][0]["name"],"Historia")
                self.assertEqual(len(j["bookTags"]),2)
            self.assertEqual(summarize_report(out),dict(prepared=2,duplicate=0,error=0,unsupported=0,warning=1,pending=0))
            self.assertEqual((root/"metadata.db").read_bytes(),before)

if __name__=="__main__":
    unittest.main()
