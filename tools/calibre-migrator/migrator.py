"""Read-only Calibre migration to BibliotecaAI's verified portable ZIP v2."""
from dataclasses import dataclass
from pathlib import Path
import csv
import hashlib
import json
import os
import re
import sqlite3
import time
import uuid
import zipfile
import xml.etree.ElementTree as ET

FORMATS = ("epub", "mobi", "pdf")
MAX_BOOK = 512 * 1024**2
MAX_JSON = 16 * 1024**2
MAX_BATCH = 1024**3  # comfortably below Android's 4 GiB limit
MAX_COUNT = 1000
CHUNK = 1024**2
NS = uuid.UUID("6fefdc84-1269-4a98-b61a-154c872af11a")


@dataclass(frozen=True)
class Record:
    folder: Path
    title: str
    authors: str
    tags: tuple
    files: tuple


def inside(root, path):
    path = path.resolve()
    if not path.is_relative_to(root):
        raise ValueError("Ruta fuera de la biblioteca: " + str(path))
    return path


def metadata(folder, title, authors, fallback_tags=()):
    opf = folder / "metadata.opf"
    tags = list(fallback_tags)
    if opf.is_file():
        if opf.stat().st_size > 2 * 1024**2:
            raise ValueError("metadata.opf demasiado grande")
        raw = opf.read_bytes()
        if b"<!DOCTYPE" in raw.upper() or b"<!ENTITY" in raw.upper():
            raise ValueError("Declaración XML no permitida")
        tree = ET.fromstring(raw)
        vals = {}
        for e in tree.iter():
            name = e.tag.rsplit("}", 1)[-1]
            if name in ("title", "creator", "subject") and e.text and e.text.strip():
                vals.setdefault(name, []).append(e.text.strip())
        title = (vals.get("title") or [title])[0]
        authors = " & ".join(vals.get("creator", [])) or authors
        tags = list(dict.fromkeys(vals.get("subject", []) + tags))
    return title, authors, tuple(tags)


def scan(root):
    root = Path(root).resolve()
    if not root.is_dir():
        raise ValueError("Selecciona la carpeta raíz de Calibre")
    rows, warnings = [], []
    db = root / "metadata.db"
    if db.is_file():
        # No writes to Calibre. Read exact paths registered in metadata.db.
        conn = sqlite3.connect(db.as_uri() + "?mode=ro", uri=True)
        try:
            tables = {r[0] for r in conn.execute("select name from sqlite_master where type = 'table'")}
            for bid, folder, title, authors in conn.execute(
                "select id,path,title,author_sort from books order by id"
            ):
                try:
                    path = inside(root, root / folder)
                    tags = ()
                    if {"authors", "books_authors_link"} <= tables:
                        names = [x[0] for x in conn.execute("select a.name from authors a join books_authors_link l on a.id=l.author where l.book=? order by l.id", (bid,))]
                        authors = " & ".join(names) or authors
                    if {"tags", "books_tags_link"} <= tables:
                        tags = tuple(x[0] for x in conn.execute("select t.name from tags t join books_tags_link l on t.id=l.tag where l.book=? order by l.id", (bid,)))
                    # Use Calibre's declared format filenames, not backups in the folder.
                    files = []
                    for fmt, stem in conn.execute("select format,name from data where book=? order by format", (bid,)):
                        ext = fmt.lower()
                        if ext not in FORMATS:
                            warnings.append((str(path / (stem + "." + ext)), "Formato no compatible: " + fmt))
                            continue
                        f = inside(root, path / (stem + "." + ext))
                        if f.is_file():
                            files.append(f)
                        else:
                            warnings.append((str(f), "Formato registrado pero archivo ausente"))
                    rows.append((path, title or path.name, authors or "", tuple(files), tags))
                except (OSError, ValueError) as exc:
                    warnings.append((str(folder), str(exc)))
        finally:
            conn.close()
    else:
        # Folder-based recovery for copies without metadata.db.
        # Do not follow directory symlinks or leave the selected tree.
        for directory, dirs, names in os.walk(root, followlinks=False):
            dirs[:] = sorted(d for d in dirs if not (Path(directory) / d).is_symlink())
            folder = Path(directory)
            files = tuple(sorted((folder / n for n in names
                           if Path(n).suffix.lower().lstrip(".") in FORMATS), key=str))
            if not files and "metadata.opf" not in names:
                continue
            try:
                files = tuple(inside(root, f) for f in files)
                rows.append((folder, re.sub(r"\s+\(\d+\)$", "", folder.name),
                             folder.parent.name, files, ()))
            except (OSError, ValueError) as exc:
                warnings.append((str(folder), str(exc)))
    records = []
    for folder, title, authors, files, fallback_tags in rows:
        try:
            title, authors, tags = metadata(folder, title, authors, fallback_tags)
        except (OSError, ValueError, ET.ParseError) as exc:
            tags = fallback_tags
            warnings.append((str(folder), "Metadatos no legibles; se usa catálogo/nombre: " + str(exc)))
        records.append(Record(folder, title, authors, tags, files))
    return records, warnings


def choose_files(record, all_formats=False):
    files = sorted(record.files, key=lambda p: (FORMATS.index(p.suffix.lower()[1:]), str(p).casefold()))
    return files if all_formats else files[:1]


def identify(path, cancelled=lambda: False):
    digest = hashlib.sha256()
    count = 0
    with path.open("rb") as f:
        header = f.read(128)
        f.seek(0)
        ext = path.suffix.lower()
        if ext == ".pdf" and b"%PDF-" not in header[:1024]:
            raise ValueError("Cabecera PDF no válida")
        if ext == ".mobi" and header[60:68] != b"BOOKMOBI":
            raise ValueError("Cabecera MOBI no válida")
        if ext == ".epub":
            with zipfile.ZipFile(path) as z:
                info = z.getinfo("mimetype")
                if info.file_size > 64 or z.read(info).strip() != b"application/epub+zip":
                    raise ValueError("EPUB no válido")
        while True:
            if cancelled():
                raise InterruptedError("Cancelado")
            data = f.read(CHUNK)
            if not data:
                break
            count += len(data)
            if count > MAX_BOOK:
                raise ValueError("Supera 512 MiB por libro")
            digest.update(data)
    if not count:
        raise ValueError("Archivo vacío")
    return digest.hexdigest(), count


def safe_filename(path, digest):
    # Valid cross-platform fileName; archive entry itself is the legacy UUID.bin.
    name = re.sub(r'[\\/:*?"<>|\x00-\x1f\x7f]', "_", path.stem).strip(" .")
    name = name.encode("utf-8")[:180].decode("utf-8", "ignore").strip(" .") or digest[:16]
    if re.match(r"(?i)^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\.|$)", name):
        name = "_" + name
    return name + path.suffix.lower()


def build_snapshot(entries):
    now = int(time.time() * 1000)
    books, tags, refs = [], {}, set()
    for record, path, digest, size in entries:
        bid = str(uuid.uuid5(NS, "book:" + digest))
        books.append(dict(id=bid, title=record.title, authors=record.authors,
                          format=path.suffix[1:].upper(), file_name=safe_filename(path, digest),
                          content_hash=digest, size_bytes=size, added_at=now,
                          last_opened_at=None, reading_status="PENDING", favorite=False, progress=0.0))
        for name in record.tags:
            key = str(uuid.uuid5(NS, "tag:" + name.casefold()))
            tags.setdefault(key, dict(id=key, name=name))
            refs.add((bid, key))
    return dict(manifest=dict(app="biblioteca-ia", formatVersion=2, appVersion="CalibreMigrator-1",
                exportedAt=now, counts=dict(books=len(books), annotations=0, versions=0, positions=0, synopses=0)),
                books=books, categories=[], tags=list(tags.values()), collections=[],
                bookCategories=[], bookTags=[dict(first=b, second=t) for b,t in sorted(refs)],
                collectionBooks=[], annotations=[], annotationVersions=[], readingPositions=[],
                bookSynopses=[], deletedBooks=[])


def write_package(path, entries, cancelled=lambda: False):
    manifest = build_snapshot(entries)
    data = json.dumps(manifest, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    if len(data) > MAX_JSON:
        raise ValueError("Manifiesto supera 16 MiB")
    tmp = path.with_suffix(".partial")
    try:
        with zipfile.ZipFile(tmp, "x", compression=zipfile.ZIP_STORED, allowZip64=True) as z:
            z.writestr("biblioteca.json", data)
            for (_, source, digest, size), book in zip(entries, manifest["books"]):
                actual = hashlib.sha256()
                count = 0
                with source.open("rb") as f, z.open("books/" + book["id"] + ".bin", "w") as out:
                    while True:
                        if cancelled():
                            raise InterruptedError("Cancelado")
                        chunk = f.read(CHUNK)
                        if not chunk:
                            break
                        count += len(chunk)
                        if count > size:
                            raise ValueError("Archivo cambió durante la copia: " + str(source))
                        out.write(chunk)
                        actual.update(chunk)
                if count != size or actual.hexdigest() != digest:
                    raise ValueError("Archivo cambió durante la copia: " + str(source))
        # Independently reopen ZIP and verify every original, not only CRC.
        with zipfile.ZipFile(tmp) as z:
            assert z.namelist()[0] == "biblioteca.json"
            for book in manifest["books"]:
                actual = hashlib.sha256()
                count = 0
                with z.open("books/" + book["id"] + ".bin") as f:
                    while True:
                        if cancelled():
                            raise InterruptedError("Cancelado")
                        chunk = f.read(CHUNK)
                        if not chunk:
                            break
                        count += len(chunk)
                        actual.update(chunk)
                if actual.hexdigest() != book["content_hash"] or count != book["size_bytes"]:
                    raise ValueError("Verificación ZIP incorrecta")
        if path.exists():
            raise FileExistsError(path)
        tmp.rename(path)  # destination is a newly created private run directory
    finally:
        tmp.unlink(missing_ok=True)


def migrate(root, output, all_formats=False, progress=lambda msg: None, cancelled=lambda: False):
    root = Path(root).resolve()
    output = Path(output).resolve()
    if output == root or output.is_relative_to(root):
        raise ValueError("Elige una carpeta de destino fuera de Calibre")
    records, warnings = scan(root)
    output.mkdir(parents=True, exist_ok=True)
    run = output / ("Calibre-a-BibliotecaAI-" + time.strftime("%Y%m%d-%H%M%S") + "-" + uuid.uuid4().hex[:6])
    run.mkdir()
    pending, seen, report, packages = [], set(), [], []
    total = 0
    def flush():
        nonlocal pending, total
        if pending:
            path = run / ("Migracion-%03d.zip" % (len(packages) + 1))
            progress("Creando y verificando " + path.name)
            write_package(path, pending, cancelled)
            packages.append(path)
            for record, source, digest, size in pending:
                report.append((record.title, record.authors, str(source), digest, "Preparado", path.name))
            pending, total = [], 0
    try:
        for index, record in enumerate(records, 1):
            if cancelled():
                raise InterruptedError("Cancelado")
            progress(f"{index}/{len(records)} — {record.title}")
            files = choose_files(record, all_formats)
            if not files:
                report.append((record.title, record.authors, str(record.folder), "", "Sin formato compatible", ""))
            # Preferred mode falls back to another format when the first is invalid.
            candidates = files if all_formats else choose_files(record, True)
            for source in candidates:
                try:
                    digest, size = identify(source, cancelled)
                except InterruptedError:
                    raise
                except (OSError, ValueError, KeyError, zipfile.BadZipFile) as exc:
                    report.append((record.title, record.authors, str(source), "", "Error: " + str(exc), ""))
                    continue
                if digest in seen:
                    report.append((record.title, record.authors, str(source), digest, "Duplicado exacto", ""))
                else:
                    if pending and (total + size > MAX_BATCH or len(pending) >= MAX_COUNT):
                        flush()
                    pending.append((record, source, digest, size))
                    total += size
                    seen.add(digest)
                if not all_formats:
                    break
        flush()
    except (Exception, KeyboardInterrupt):
        for record, source, digest, size in pending:
            report.append((record.title, record.authors, str(source), digest, "Pendiente: paquete no completado", ""))
        raise
    finally:
        # Report only committed packages; a partial package never appears as ready.
        for source, issue in warnings:
            report.append(("", "", source, "", "Aviso: " + issue, ""))
        with (run / "Informe.csv").open("w", newline="", encoding="utf-8-sig") as f:
            writer = csv.writer(f, delimiter=";")
            writer.writerow(("Título", "Autor", "Origen", "SHA256", "Resultado", "Paquete"))
            # Protect spreadsheet users from formulas in Calibre-controlled text.
            for row in report:
                writer.writerow(["'" + x if x.startswith(("=", "+", "-", "@", "\t", "\r")) else x for x in row])
    return run, len(packages), sum(row[4] == "Preparado" for row in report)

def summarize_report(run):
    """Summarize committed output, not merely selected candidates."""
    counts = dict(prepared=0, duplicate=0, error=0, unsupported=0, warning=0, pending=0)
    with (Path(run) / "Informe.csv").open(encoding="utf-8-sig", newline="") as f:
        for row in csv.DictReader(f, delimiter=";"):
            status = row["Resultado"]
            if status == "Preparado": counts["prepared"] += 1
            elif status == "Duplicado exacto": counts["duplicate"] += 1
            elif status.startswith("Error:"): counts["error"] += 1
            elif status == "Sin formato compatible": counts["unsupported"] += 1
            elif status.startswith("Aviso:"): counts["warning"] += 1
            elif status.startswith("Pendiente:"): counts["pending"] += 1
    return counts
