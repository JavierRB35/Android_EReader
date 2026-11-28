import hashlib
import csv
import json
import sqlite3
import struct
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
            (folder/"Book.mobi").write_bytes(b"0"*60+b"BOOKMOBI"+b"0"*60)
            (folder/"Orphan.pdf").write_bytes(b"%PDF-1.5\n%%EOF")
            db=sqlite3.connect(root/"metadata.db")
            db.executescript("create table books(id integer,path text,title text,author_sort text); create table data(book integer,format text,name text); insert into books values(1,'A/Title (1)','Title','Author'); insert into data values(1,'EPUB','Book'),(1,'MOBI','Book');")
            db.commit(); db.close()
            before=(root/"metadata.db").read_bytes()
            records,warnings=scan(root)
            self.assertEqual(len(records[0].files),2)
            out,n,count=migrate(root,Path(d)/"out")
            self.assertEqual((n,count),(1,1))
            with zipfile.ZipFile(out/"Migracion-001.zip") as z:
                self.assertEqual(json.loads(z.read("biblioteca.json"))["books"][0]["format"],"MOBI")
            self.assertEqual((root/"metadata.db").read_bytes(),before)
            self.assertIn("Error:",(out/"Informe.csv").read_text(encoding="utf-8-sig"))
            self.assertEqual(summarize_report(out)["error"],1)

    def test_database_metadata_and_all_formats(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d)/"Calibre"; folder=root/"Autor/Libro (1)"
            folder.mkdir(parents=True)
            (folder/"Libro.pdf").write_bytes(b"%PDF-1.4\n%%EOF")
            with zipfile.ZipFile(folder/"Libro.epub","w") as z:
                z.writestr("mimetype",b"application/epub+zip")
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
                insert into data values(1,'EPUB','Libro'),(1,'PDF','Libro'),(1,'MOBI','Libro'),(1,'AZW3','Libro');
            """)
            db.commit(); db.close()
            before=(root/"metadata.db").read_bytes()
            out,n,count=migrate(root,Path(d)/"out",True)
            self.assertEqual((n,count),(1,2))
            with zipfile.ZipFile(out/"Migracion-001.zip") as z:
                j=json.loads(z.read("biblioteca.json"))
                self.assertEqual({x["format"] for x in j["books"]},{"EPUB","MOBI"})
                self.assertEqual({x["authors"] for x in j["books"]},{"Nombre Apellido"})
                self.assertEqual(j["tags"][0]["name"],"Historia")
                self.assertEqual(len(j["bookTags"]),2)
            self.assertEqual(summarize_report(out),dict(prepared=2,duplicate=0,error=0,unsupported=0,warning=2,pending=0,
                metadata_catalog=2,metadata_opf=0,metadata_internal=0,metadata_name=0,covers=0,synopses=0,pending_covers=0))
            self.assertEqual((root/"metadata.db").read_bytes(),before)

    def test_interrupted_batch_is_not_reported_as_prepared(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d)/"Calibre"; folder=root/"Autor/Libro (1)"
            folder.mkdir(parents=True)
            original=folder/"Libro.mobi"
            data=b"0"*60+b"BOOKMOBI"+b"0"*60
            original.write_bytes(data)
            output=Path(d)/"out"
            cancel=[False]
            def on_progress(message):
                if message.startswith("Creando"):
                    cancel[0]=True
            with self.assertRaises(InterruptedError):
                migrate(root,output,True,on_progress,lambda:cancel[0])
            run=next(output.iterdir())
            self.assertEqual(list(run.glob("*.zip")),[])
            self.assertEqual(list(run.glob("*.partial")),[])
            self.assertEqual(summarize_report(run)["pending"],1)
            self.assertEqual(summarize_report(run)["prepared"],0)
            self.assertEqual(original.read_bytes(),data)
            retry,n,count=migrate(root,output,True)
            self.assertEqual((n,count),(1,1))
            self.assertEqual(summarize_report(retry)["prepared"],1)

    def test_pdf_only_is_reported_without_a_package(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d)/"Calibre"; folder=root/"Autor/Libro (1)"
            folder.mkdir(parents=True)
            original=folder/"Libro.pdf"
            original.write_bytes(b"%PDF-1.4\n%%EOF")
            out,n,count=migrate(root,Path(d)/"out",True)
            self.assertEqual((n,count),(0,0))
            self.assertIn("Formato no compatible: PDF",(out/"Informe.csv").read_text(encoding="utf-8-sig"))
            self.assertEqual(original.read_bytes(),b"%PDF-1.4\n%%EOF")

    # ---------- Issue #155: per-file metadata, covers and synopses ----------

JPEG = b"\xff\xd8\xff\xd9"  # minimal bounded JPEG thumbnail shipped without Pillow


def build_epub(path, title, authors, subject="", description="", language="",
               publisher="", isbn="", extra_meta="", cover=JPEG, cover_href="images/cover.png",
               cover_type="image/jpeg"):
    container=('<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">'
               '<rootfiles><rootfile full-path="OEBPS/content.opf" '
               'media-type="application/oebps-package+xml"/></rootfiles></container>')
    dc=""
    for tag,value in (("title",title),):
        if value: dc+=f"<dc:{tag}>{value}</dc:{tag}>"
    for author in authors:
        dc+=f'<dc:creator opf:role="aut">{author}</dc:creator>'
    if subject: dc+=f"<dc:subject>{subject}</dc:subject>"
    if description: dc+=f"<dc:description>{description}</dc:description>"
    if language: dc+=f"<dc:language>{language}</dc:language>"
    if publisher: dc+=f"<dc:publisher>{publisher}</dc:publisher>"
    if isbn: dc+=f'<dc:identifier opf:scheme="ISBN">{isbn}</dc:identifier>'
    if cover is not None:
        dc+='<meta name="cover" content="coverid"/>'
    if extra_meta: dc+=extra_meta
    opf=('<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="id">'
         f'<metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">{dc}</metadata>'
         '<manifest><item id="coverid" href="' + cover_href + '" media-type="' + cover_type + '"/></manifest></package>')
    with zipfile.ZipFile(path,"w") as z:
        z.writestr("mimetype",b"application/epub+zip")
        z.writestr("META-INF/container.xml",container)
        z.writestr("OEBPS/content.opf",opf)
        if cover is not None:
            z.writestr("OEBPS/"+cover_href,cover)


def build_mobi(path, title=None, authors=(), subjects=(), description=None,
               cover=JPEG, encoding=65001):
    """Minimal MOBI: record0 (PalmDOC+MOBI+EXTH), one text record, optional cover record."""
    text = b"Capitulo uno"
    records = [None, text]
    if cover is not None:
        records.append(cover)
    first_image = 2 if cover is not None else len(records)
    enc_b = struct.pack(">I", encoding)
    name = (title or "Sin titulo").encode("utf-8" if encoding==65001 else "cp1252")
    exth = b""
    entries = []
    for author in authors:
        entries.append((100, author.encode("utf-8" if encoding==65001 else "cp1252")))
    if description:
        entries.append((103, description.encode("utf-8" if encoding==65001 else "cp1252")))
    for subject in subjects:
        entries.append((105, subject.encode("utf-8" if encoding==65001 else "cp1252")))
    if title:
        entries.append((503, name))
    if cover is not None:
        entries.append((201, struct.pack(">I", 0)))
    for kind,payload in entries:
        body=payload+b"\x00"*(-len(payload)%4)  # EXTH record lengths are 4-byte aligned
        exth+=struct.pack(">II",kind,8+len(body))+body
    exth=b"EXTH"+struct.pack(">II",12+len(exth),len(entries))+exth
    header_size=232
    name_offset=16+header_size+len(exth)
    mobi=bytearray(16+header_size)
    struct.pack_into(">HHIHH",mobi,0,2,0,len(text),1,4096)
    mobi[16:20]=b"MOBI"
    struct.pack_into(">I",mobi,20,header_size)
    struct.pack_into(">I",mobi,24,2)
    mobi[28:32]=enc_b
    struct.pack_into(">I",mobi,84,name_offset)
    struct.pack_into(">I",mobi,88,len(name))
    struct.pack_into(">I",mobi,108,first_image)
    struct.pack_into(">I",mobi,128,0x40)
    record0=bytes(mobi)+exth+name
    records[0]=record0
    body=b"BOOKMOBI"
    body+=b"\x00"*2+b"\x00"*4+b"\x00"*8+b"\x00"*8  # attrs..sortinfo padding to offset 60? keep explicit:
    # Build full PDB header: 32-byte name, 28 bytes metadata, BOOKMOBI, 8 bytes, count.
    pdb=bytearray(78)
    pdb[0:8]=b"SyncBook"
    pdb[60:68]=b"BOOKMOBI"
    struct.pack_into(">H",pdb,76,len(records))
    offsets=[]
    position=78+len(records)*8
    for data in records:
        offsets.append(position)
        position+=len(data)
    for offset in offsets:
        pdb+=struct.pack(">IBBH",offset,0,0,0)
    with path.open("wb") as f:
        f.write(pdb)
        for data in records:
            f.write(data)


class MetadataHierarchyTest(unittest.TestCase):
    """Issue #155: folder names are never bibliographic data."""

    def test_flat_folder_uses_internal_metadata_not_folder_names(self):
        with tempfile.TemporaryDirectory() as d:
            # Exactly the reported layout: Desktop/epub with several different EPUBs.
            root=Path(d)/"Desktop"/"epub"; root.mkdir(parents=True)
            specs=[("a.epub","La Sombra",("Ana Ruiz",),"Aventura","Resumen de La Sombra"),
                   ("b.epub","El Faro",("Bo Carre",),"Misterio","Resumen de El Faro"),
                   ("c.epub","Nudo",("Celia Paz","Duo Sur"),"Poesía","")]
            for name,title,authors,subject,description in specs:
                build_epub(root/name,title,authors,subject,description)
            records,warnings=scan(root)
            self.assertEqual(len(records),1)
            entries=dict(records[0].entries)
            self.assertEqual(len(entries),3)
            titles={meta.title for meta in entries.values()}
            authors={meta.authors for meta in entries.values()}
            self.assertNotIn("epub",titles)
            self.assertNotIn("Desktop",authors)
            self.assertEqual(titles,{"La Sombra","El Faro","Nudo"})
            self.assertEqual(len(authors),3)
            for meta in entries.values():
                self.assertEqual(meta.source,"interno")
                self.assertTrue(meta.cover)
            out,n,count=migrate(root,Path(d)/"out")
            self.assertEqual((n,count),(1,3))
            with zipfile.ZipFile(out/"Migracion-001.zip") as z:
                self.assertEqual(z.namelist()[0],"biblioteca.json")
                j=json.loads(z.read("biblioteca.json"))
                self.assertEqual({b["title"] for b in j["books"]},{"La Sombra","El Faro","Nudo"})
                self.assertFalse(any(b["authors"]=="Desktop" for b in j["books"]))
                self.assertEqual(j["manifest"]["counts"]["synopses"],2)
                self.assertEqual(len(j["bookSynopses"]),2)
                self.assertEqual(len(j["tags"]),3)
                covers=[name for name in z.namelist() if name.startswith("covers/")]
                for cover in covers:
                    data=z.read(cover)
                    self.assertLessEqual(len(data),512*1024)
                    self.assertEqual(data[:2],b"\xff\xd8")
                self.assertEqual(len(covers),3)
                self.assertEqual({c["book"] for c in j["covers"]},
                                 {b["content_hash"] for b in j["books"]})
            rows=list(csv.DictReader((out/"Informe.csv").open(encoding="utf-8-sig",newline=""),delimiter=";"))
            prepared=[r for r in rows if r["Resultado"]=="Preparado"]
            self.assertEqual(len(prepared),3)
            self.assertTrue(all(r["Origen metadatos"]=="interno" for r in prepared))
            self.assertTrue(all(r["Portada"]=="Sí" for r in prepared))
            summary=summarize_report(out)
            self.assertEqual(summary["metadata_internal"],3)
            self.assertEqual(summary["metadata_name"],0)
            self.assertEqual(summary["covers"],3)

    def test_mobi_exth_metadata_and_cover(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d)/"Desktop"/"libros"; root.mkdir(parents=True)
            build_mobi(root/"Novela.mobi",title="Marea Alta",authors=("Luis Mar",),
                       subjects=("Negra",),description="Sinopsis de Marea Alta")
            records,warnings=scan(root)
            meta=dict(records[0].entries)[records[0].files[0]]
            self.assertEqual(meta.title,"Marea Alta")
            self.assertEqual(meta.authors,"Luis Mar")
            self.assertEqual(meta.tags,("Negra",))
            self.assertEqual(meta.synopsis,"Sinopsis de Marea Alta")
            self.assertTrue(meta.cover)
            out,n,count=migrate(root,Path(d)/"out")
            self.assertEqual((n,count),(1,1))
            with zipfile.ZipFile(out/"Migracion-001.zip") as z:
                j=json.loads(z.read("biblioteca.json"))
                self.assertEqual(j["books"][0]["title"],"Marea Alta")
                self.assertEqual(j["books"][0]["authors"],"Luis Mar")
                self.assertEqual(j["bookSynopses"][0]["provider"],"calibre")
                self.assertTrue(any(name.startswith("covers/") for name in z.namelist()))

    def test_mobi_without_exth_uses_record_name_only(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d)/"Desktop"/"libros"; root.mkdir(parents=True)
            build_mobi(root/"Viejo.mobi",title=None,cover=None)
            records,_=scan(root)
            meta=dict(records[0].entries)[records[0].files[0]]
            self.assertEqual(meta.title,"Sin titulo")
            self.assertEqual(meta.authors,"")
            self.assertNotEqual(meta.title,"libros")
            self.assertNotEqual(meta.authors,"Desktop")
            self.assertFalse(meta.cover)

    def test_calibre_export_folder_applies_external_opf_to_same_book_formats(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d)/"CalibreExport"; folder=root/"Dune"; folder.mkdir(parents=True)
            (folder/"metadata.opf").write_text(
                '<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" xmlns:opf="http://www.idpf.org/2007/opf" version="2.0">'
                '<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">'
                '<dc:title>Dune</dc:title><dc:creator opf:role="aut">Frank Herbert</dc:creator>'
                '<dc:subject>Ciencia ficción</dc:subject>'
                '<dc:description>Crónica de Arrakis completa.</dc:description>'
                '<dc:language>es</dc:language><dc:publisher>Debolsillo</dc:publisher>'
                '<meta name="cover" content="coverid"/></metadata>'
                '<manifest><item id="coverid" href="cover.jpg" media-type="image/jpeg"/></manifest></package>',
                encoding="utf-8")
            (folder/"cover.jpg").write_bytes(JPEG)
            build_epub(folder/"Dune.epub","Dune",("Frank Herbert",))
            build_mobi(folder/"Dune.mobi",title="Dune",authors=("Frank Herbert",))
            records,warnings=scan(root)
            entries=dict(records[0].entries)
            self.assertTrue(all(meta.source=="opf" for meta in entries.values()))
            self.assertTrue(all(meta.synopsis=="Crónica de Arrakis completa." for meta in entries.values()))
            self.assertTrue(all(meta.cover and meta.cover.kind=="file" for meta in entries.values()))
            out,n,count=migrate(root,Path(d)/"out",True)
            self.assertEqual((n,count),(1,2))
            with zipfile.ZipFile(out/"Migracion-001.zip") as z:
                j=json.loads(z.read("biblioteca.json"))
                self.assertEqual({b["title"] for b in j["books"]},{"Dune"})
                self.assertEqual({b["authors"] for b in j["books"]},{"Frank Herbert"})
                self.assertEqual(j["tags"][0]["name"],"Ciencia ficción")
                self.assertEqual(j["manifest"]["counts"]["synopses"],2)
                self.assertEqual(len([n for n in z.namelist() if n.startswith("covers/")]),2)
            summary=summarize_report(out)
            self.assertEqual(summary["metadata_opf"],2)
            self.assertEqual(summary["covers"],2)

    def test_external_opf_does_not_override_different_internal_records(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d)/"Mezcla"; root.mkdir(parents=True)
            (root/"metadata.opf").write_text(
                '<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" xmlns:opf="http://www.idpf.org/2007/opf" version="2.0">'
                '<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">'
                '<dc:title>Ficha Ajena</dc:title><dc:creator opf:role="aut">Otro Autor</dc:creator>'
                '</metadata><manifest/></package>', encoding="utf-8")
            build_epub(root/"a.epub","Alfa",("Autor Alfa",))
            build_epub(root/"b.epub","Beta",("Autor Beta",))
            records,warnings=scan(root)
            entries=dict(records[0].entries)
            self.assertEqual({meta.title for meta in entries.values()},{"Alfa","Beta"})
            self.assertTrue(all(meta.source=="interno" for meta in entries.values()))
            self.assertTrue(any("no coinciden" in issue for _,issue in warnings))

    def test_extended_metadata_is_exported_and_reported(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d)/"Desktop"/"epub"; root.mkdir(parents=True)
            build_epub(root/"x.epub","Obra",("Alguien",),language="es",publisher="Editorial Sur",
                       isbn="9780123456789",
                       extra_meta='<meta name="calibre:series" content="Colección"/><meta name="calibre:series_index" content="3"/>')
            records,_=scan(root)
            meta=dict(records[0].entries)[records[0].files[0]]
            labels=dict(meta.extended)
            self.assertEqual(labels.get("idioma"),"es")
            self.assertEqual(labels.get("editorial"),"Editorial Sur")
            self.assertEqual(labels.get("ISBN"),"9780123456789")
            self.assertEqual(labels.get("serie"),"Colección")
            out,_,_=migrate(root,Path(d)/"out")
            with zipfile.ZipFile(out/"Migracion-001.zip") as z:
                j=json.loads(z.read("biblioteca.json"))
                extended=j["bookExtendedMetadata"][0]
                self.assertEqual(extended["content_hash"],j["books"][0]["content_hash"])
                self.assertEqual(dict(extended["fields"]).get("serie"),"Colección")
                self.assertIn("No representados",(out/"Informe.csv").read_text(encoding="utf-8-sig"))

    def test_legacy_minimal_epubs_keep_working_and_report_name_fallback(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d)/"Desktop"/"epub"; root.mkdir(parents=True)
            for name,junk in (("Sin Datos.epub",b"a"),("sin-datos2.epub",b"b")):
                with zipfile.ZipFile(root/name,"w") as z:
                    z.writestr("mimetype",b"application/epub+zip")
                    z.writestr("junk.txt",junk)  # distinct bytes: no accidental duplicate
            records,warnings=scan(root)
            entries=dict(records[0].entries)
            self.assertEqual({meta.title for meta in entries.values()},{"Sin Datos","sin-datos2"})
            self.assertTrue(all(meta.source=="nombre" for meta in entries.values()))
            self.assertFalse(any(meta.authors for meta in entries.values()))
            out,_,_=migrate(root,Path(d)/"out")
            summary=summarize_report(out)
            self.assertEqual(summary["metadata_name"],2)
            self.assertEqual(summary["covers"],0)
            with zipfile.ZipFile(out/"Migracion-001.zip") as z:
                j=json.loads(z.read("biblioteca.json"))
                self.assertFalse(any(b["authors"]=="Desktop" for b in j["books"]))
                self.assertNotIn("covers",j)

    def test_png_cover_without_pillow_is_recovered_not_lost(self):
        """PNG covers ship as optimized JPEG when Pillow exists; otherwise the
        report marks them recovered and Android derives the thumbnail. The
        original EPUB must stay untouched either way."""
        def make_png():
            import zlib
            def chunk(tag,data):
                c=struct.pack(">I",len(data))+tag+data
                return c+struct.pack(">I",zlib.crc32(tag+data)&0xffffffff)
            ihdr=struct.pack(">IIBBBBB",8,12,8,2,0,0,0)
            raw=b"".join(b"\x00"+b"\x40\x80\xc0"*8 for _ in range(12))
            return b"\x89PNG\r\n\x1a\n"+chunk(b"IHDR",ihdr)+chunk(b"IDAT",zlib.compress(raw))+chunk(b"IEND",b"")
        with tempfile.TemporaryDirectory() as d:
            root=Path(d)/"Desktop"/"epub"; root.mkdir(parents=True)
            build_epub(root/"p.epub","Con PNG",("Alguien",),cover=make_png(),
                       cover_href="images/cover.png",cover_type="image/png")
            records,_=scan(root)
            meta=dict(records[0].entries)[records[0].files[0]]
            self.assertTrue(meta.cover)
            out,_,_=migrate(root,Path(d)/"out")
            try:
                import PIL  # noqa: F401
                with_pillow=True
            except ImportError:
                with_pillow=False
            with zipfile.ZipFile(out/"Migracion-001.zip") as z:
                covers=[n for n in z.namelist() if n.startswith("covers/")]
                if with_pillow:
                    self.assertEqual(len(covers),1)
                    data=z.read(covers[0])
                    self.assertEqual(data[:2],b"\xff\xd8")
                    self.assertLessEqual(len(data),512*1024)
                else:
                    self.assertEqual(covers,[])
            rows=list(csv.DictReader((out/"Informe.csv").open(encoding="utf-8-sig",newline=""),delimiter=";"))
            prepared=[r for r in rows if r["Resultado"]=="Preparado"]
            self.assertEqual(prepared[0]["Portada"],"Sí" if with_pillow else "Recuperada")
            summary=summarize_report(out)
            self.assertEqual(summary["covers"],1 if with_pillow else 0)
            self.assertEqual(summary["pending_covers"],0 if with_pillow else 1)

    def test_catalogue_database_still_wins_and_internal_fills_gaps(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d)/"Calibre"; folder=root/"Autor/Título (1)"
            folder.mkdir(parents=True)
            build_epub(folder/"Libro.epub","Título",("Nombre Autor",),"Género DB")
            (folder/"cover.jpg").write_bytes(JPEG)
            db=sqlite3.connect(root/"metadata.db")
            db.executescript("""
                create table books(id integer,path text,title text,author_sort text,comments text,isbn text);
                create table data(book integer,format text,name text);
                create table authors(id integer,name text);
                create table books_authors_link(id integer,book integer,author integer);
                create table tags(id integer,name text);
                create table books_tags_link(id integer,book integer,tag integer);
                insert into books values(1,'Autor/Título (1)','Título','Nombre Autor','Argumento del catálogo','9780000000001');
                insert into authors values(1,'Nombre Autor');
                insert into books_authors_link values(1,1,1);
                insert into tags values(1,'Catálogo');
                insert into books_tags_link values(1,1,1);
                insert into data values(1,'EPUB','Libro');
            """)
            db.commit(); db.close()
            records,_=scan(root)
            meta=dict(records[0].entries)[records[0].files[0]]
            self.assertEqual(meta.source,"catálogo")
            self.assertEqual(meta.synopsis,"Argumento del catálogo")
            self.assertEqual(meta.tags,("Catálogo",))
            self.assertEqual(meta.cover.kind,"file")
            self.assertEqual(dict(meta.extended).get("ISBN"),"9780000000001")
            out,_,_=migrate(root,Path(d)/"out")
            summary=summarize_report(out)
            self.assertEqual(summary["metadata_catalog"],1)
            self.assertEqual(summary["covers"],1)
            self.assertEqual(summary["synopses"],1)

if __name__=="__main__":
    unittest.main()
