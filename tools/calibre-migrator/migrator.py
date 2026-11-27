"""Read-only Calibre migration to BibliotecaAI's verified portable ZIP v2.

Issue #155: folder-based recovery used the folder name as title and the parent
folder name as author for EVERY book. Metadata is now resolved per file with an
explicit hierarchy: (1) Calibre catalogue / unambiguous external OPF, (2) the
EPUB/MOBI internal metadata, (3) the file name as a last resort. Folder names
are never bibliographic data. Covers and synopses travel inside the ZIP as
derived thumbnails; originals remain untouched.
"""
from dataclasses import dataclass
from pathlib import Path
import csv
import hashlib
import json
import os
import re
import sqlite3
import struct
import time
import uuid
import zipfile
import xml.etree.ElementTree as ET

FORMATS = ("epub", "mobi")
MAX_BOOK = 512 * 1024**2
MAX_JSON = 16 * 1024**2
MAX_BATCH = 1024**3  # comfortably below Android's 4 GiB limit
MAX_COUNT = 1000
CHUNK = 1024**2
NS = uuid.UUID("6fefdc84-1269-4a98-b61a-154c872af11a")

MAX_XML = 512 * 1024              # matches Android's XML limits
MAX_COVER_BYTES = 8 * 1024**2     # raw source cover bound (Android EpubCoverSource parity)
MAX_THUMB_BYTES = 512 * 1024      # published thumbnail bound (Android SharedCoverArt parity)
THUMB_MAX_DIM = (600, 900)        # thumbnails; originals never scaled or modified
SYNOPSIS_LIMIT = 100_000          # matches BackupCodec's synopsis validation
IMPORT_PROVIDER = "calibre"       # marks catalogue-imported synopses; never replaces user text

try:  # Optional: optimizes covers. Without it, only small JPEG covers ship unchanged.
    from PIL import Image as _Image
except Exception:  # pragma: no cover - exercised in CI with and without Pillow
    _Image = None


@dataclass(frozen=True)
class CoverRef:
    """Reference to a cover image; bytes are loaded lazily to bound memory."""
    kind: str          # "epub" | "mobi" | "file"
    source: Path
    entry: str = ""    # epub: zip entry name
    offset: int = 0    # mobi: record offset
    length: int = 0    # mobi: record length


@dataclass(frozen=True)
class BookMeta:
    title: str
    authors: str
    tags: tuple
    synopsis: str = ""
    cover: CoverRef = None
    cover_name: str = ""
    source: str = "nombre"  # catálogo | opf | interno | nombre
    extended: tuple = ()    # ((field, value), …) recovered but not representable in BibliotecaAI v2


@dataclass(frozen=True)
class Record:
    folder: Path
    entries: tuple  # ((path, BookMeta), …)
    @property
    def files(self):
        return tuple(path for path, _ in self.entries)
    def meta_for(self, path):
        for candidate, meta in self.entries:
            if candidate == path:
                return meta
        return BookMeta(path.stem, "", ())


def inside(root, path):
    path = path.resolve()
    if not path.is_relative_to(root):
        raise ValueError("Ruta fuera de la biblioteca: " + str(path))
    return path


def _local(tag):
    return tag.rsplit("}", 1)[-1]


def _parse_xml(raw):
    if len(raw) > MAX_XML:
        raise ValueError("XML demasiado grande")
    if b"<!DOCTYPE" in raw.upper() or b"<!ENTITY" in raw.upper():
        raise ValueError("Declaración XML no permitida")
    return ET.fromstring(raw)


def _clean(value):
    return value.strip() if value and value.strip() else ""


def _isbn_identifier(identifier):
    scheme = (identifier.get("scheme") or identifier.get("{http://www.idpf.org/2007/opf}scheme") or "").lower()
    text = _clean(identifier.text)
    if not text:
        return ""
    if scheme in ("isbn", "") and re.sub(r"[-\s]", "", text).isdigit():
        return text
    return ""


def _opf_fields(tree):
    """Bibliographic fields from an OPF tree; None values mean absent."""
    fields = dict(title=None, creators=[], subjects=[], description=None,
                  language=None, publisher=None, date=None, isbn=None,
                  series=None, series_index=None, subtitle=None, cover_item=None)
    for element in tree.iter():
        name = _local(element.tag)
        if name == "title":
            text = _clean(element.text)
            if text and fields["title"] is None:
                fields["title"] = text
            elif text:
                refines = element.get("{http://www.idpf.org/2007/opf}refines") or element.get("refines") or ""
                if "#title" in refines and fields["subtitle"] is None:
                    fields["subtitle"] = text
        elif name == "creator" and _clean(element.text):
            fields["creators"].append(_clean(element.text))
        elif name == "subject" and _clean(element.text):
            fields["subjects"].append(_clean(element.text))
        elif name == "description" and fields["description"] is None:
            fields["description"] = _clean("".join(element.itertext()))
        elif name in ("language", "publisher", "date"):
            text = _clean(element.text)
            if text and fields[name] is None:
                fields[name] = text
        elif name == "identifier":
            text = _isbn_identifier(element)
            if text and fields["isbn"] is None:
                fields["isbn"] = text
        elif name == "meta":
            key = element.get("name") or ""
            content = _clean(element.get("content"))
            if key == "cover" and content:
                fields["cover_item"] = content
            elif key == "calibre:series" and content:
                fields["series"] = content
            elif key == "calibre:series_index" and content:
                fields["series_index"] = content
            elif key == "calibre:subtitle" and content and fields["subtitle"] is None:
                fields["subtitle"] = content
    for item in tree.iter():
        if _local(item.tag) != "item":
            continue
        if item.get("properties") and "cover-image" in item.get("properties").split():
            fields["cover_item"] = item.get("id")
            break
    return fields


def _extended(fields, skip=()):
    """Recovered fields that BibliotecaAI v2 cannot represent, for the report."""
    pairs = []
    for key, label in (("subtitle", "subtítulo"), ("language", "idioma"),
                       ("publisher", "editorial"), ("date", "fecha de publicación"),
                       ("isbn", "ISBN"), ("series", "serie"), ("series_index", "número de serie")):
        value = fields.get(key)
        if value and key not in skip and label not in skip:
            pairs.append((label, str(value)))
    return tuple(pairs)


def _opf_cover_file(folder, tree):
    """External cover for Calibre exports: manifest item or cover.jpg sibling."""
    fields = _opf_fields(tree)
    cover_id = fields["cover_item"]
    if cover_id:
        for item in tree.iter():
            if _local(item.tag) == "item" and item.get("id") == cover_id:
                href = item.get("href") or ""
                if href and not href.startswith(("/", "\\")) and ".." not in href:
                    candidate = inside(folder, folder / href)
                    if candidate.is_file() and candidate.stat().st_size <= MAX_COVER_BYTES:
                        return candidate
    plain = folder / "cover.jpg"
    if plain.is_file() and plain.stat().st_size <= MAX_COVER_BYTES:
        return plain
    return None


def epub_metadata(path):
    """Internal EPUB metadata: container.xml → OPF. Cover follows EpubCoverSource parity."""
    with zipfile.ZipFile(path) as z:
        if z.testzip() is not None:
            raise ValueError("EPUB con entradas dañadas")
        container = _parse_xml(z.read("META-INF/container.xml"))
        opf_path = None
        for element in container.iter():
            if _local(element.tag) == "rootfile":
                full = element.get("full-path") or ""
                if full and not full.startswith(("/", "\\")) and ".." not in full.split("/"):
                    opf_path = full
                    break
        if not opf_path:
            raise ValueError("EPUB sin OPF localizable")
        fields = _opf_fields(_parse_xml(z.read(opf_path)))
    title, authors = fields["title"], " & ".join(fields["creators"]) or None
    tags = tuple(dict.fromkeys(fields["subjects"]))
    synopsis = (fields["description"] or "")[:SYNOPSIS_LIMIT]
    extended = _extended(fields)
    cover = None
    if fields["cover_item"]:
        with zipfile.ZipFile(path) as z:
            for item in _parse_xml(z.read(opf_path)).iter():
                if _local(item.tag) != "item" or item.get("id") != fields["cover_item"]:
                    continue
                if item.get("media-type") not in ("image/jpeg", "image/png", "image/webp", "image/gif"):
                    break
                href = item.get("href") or ""
                if not href or href.startswith(("/", "\\")) or ".." in href:
                    break
                directory = opf_path.rsplit("/", 1)[0] if "/" in opf_path else ""
                entry = (directory + "/" + href) if directory else href
                try:
                    info = z.getinfo(entry)
                    if info.file_size <= MAX_COVER_BYTES:
                        cover = CoverRef("epub", path, entry=entry)
                except KeyError:
                    pass
                break
    return title, authors, tags, synopsis, extended, cover


def mobi_metadata(path):
    """Internal MOBI metadata: PDB name + EXTH records. Cover follows MobiCoverSource parity."""
    with path.open("rb") as f:
        size = path.stat().st_size
        f.seek(60)
        if f.read(8) != b"BOOKMOBI":
            raise ValueError("Cabecera MOBI no válida")
        f.seek(76)
        count = struct.unpack(">H", f.read(2))[0]
        table_end = 78 + count * 8
        if count < 2 or table_end > size:
            raise ValueError("MOBI sin tabla de registros válida")
        offsets = [0] * (count + 1)
        for index in range(count):
            f.seek(78 + index * 8)
            offsets[index] = struct.unpack(">I", f.read(4))[0]
            if offsets[index] < table_end or offsets[index] >= size or (index and offsets[index] <= offsets[index - 1]):
                raise ValueError("Tabla de registros MOBI no válida")
        offsets[count] = size
        def record(index):
            if not 0 <= index < count:
                return None
            length = offsets[index + 1] - offsets[index]
            if length < 1 or length > MAX_COVER_BYTES:
                return None
            f.seek(offsets[index])
            return f.read(length)
        def number(data, at):
            if at < 0 or at > len(data) - 4:
                raise ValueError("Cabecera MOBI truncada")
            return struct.unpack(">I", data[at:at + 4])[0]
        header = record(0)
        if header is None or len(header) < 132 or header[12] or header[13] or header[16:20] != b"MOBI":
            raise ValueError("Cabecera MOBI no válida")
        header_size = number(header, 20)
        if header_size < 116 or header_size > len(header) - 16:
            raise ValueError("Cabecera MOBI no válida")
        encoding = "utf-8" if number(header, 28) == 65001 else "cp1252"
        def text(value):
            try:
                return _clean(value.rstrip(b"\x00").decode(encoding, "replace"))
            except Exception:
                return _clean(value.rstrip(b"\x00").decode("cp1252", "replace"))
        name_offset, name_length = number(header, 84), number(header, 88)
        fallback_title = text(header[name_offset:name_offset + name_length]) if name_length else ""
        title, authors, subjects, description = [], [], [], ""
        extended_values = dict(language=None, publisher=None, date=None, isbn=None,
                               series=None, series_index=None, updated_title=None)
        first_image = number(header, 108)
        exth_at = 16 + header_size
        cover_index = None
        if exth_at <= len(header) - 12 and header[exth_at:exth_at + 4] == b"EXTH":
            exth_size = number(header, exth_at + 4)
            records = number(header, exth_at + 8)
            if exth_size >= 12 and exth_size <= len(header) - exth_at and records <= (exth_size - 12) // 8:
                at = exth_at + 12
                end = exth_at + exth_size
                cover_offset = None
                for _ in range(records):
                    if at > end - 8:
                        break
                    rec_type = number(header, at)
                    rec_size = number(header, at + 4)
                    if rec_size < 8 or rec_size > end - at:
                        break
                    if rec_size >= 12:
                        payload = header[at + 8:at + rec_size]
                        if rec_type == 100:
                            authors.append(text(payload))
                        elif rec_type == 103 and not description:
                            description = text(payload)
                        elif rec_type == 105:
                            value = text(payload)
                            if value:
                                subjects.append(value)
                        elif rec_type == 104 and not extended_values["isbn"]:
                            extended_values["isbn"] = text(payload)
                        elif rec_type == 101 and extended_values["publisher"] is None:
                            extended_values["publisher"] = text(payload)
                        elif rec_type == 106 and extended_values["date"] is None:
                            extended_values["date"] = text(payload)
                        elif rec_type == 524 and extended_values["language"] is None:
                            extended_values["language"] = text(payload)
                        elif rec_type == 501 and extended_values["series"] is None:
                            extended_values["series"] = text(payload)
                        elif rec_type == 502 and extended_values["series_index"] is None:
                            extended_values["series_index"] = text(payload)
                        elif rec_type == 503 and extended_values["updated_title"] is None:
                            extended_values["updated_title"] = text(payload)
                        elif rec_type in (201, 202) and cover_offset is None:
                            cover_offset = number(header, at + 8)
                    at += rec_size
                if cover_offset is not None and first_image in range(1, count):
                    index = first_image + cover_offset
                    if index in range(1, count):
                        length = offsets[index + 1] - offsets[index]
                        if 1 <= length <= MAX_COVER_BYTES:
                            cover_index = index
        title = extended_values["updated_title"] or fallback_title
        if cover_index is None:
            cover = None
        else:
            cover = CoverRef("mobi", path, offset=offsets[cover_index], length=offsets[cover_index + 1] - offsets[cover_index])
    tags = tuple(dict.fromkeys(subjects))
    synopsis = description[:SYNOPSIS_LIMIT]
    fields = dict(extended_values)
    fields["title"] = None
    extended = _extended(fields, skip=("título",))
    return title, " & ".join(dict.fromkeys(authors)) or None, tags, synopsis, extended, cover


def file_metadata(path):
    """Internal metadata of one EPUB/MOBI file; never raises for absent metadata."""
    try:
        if path.suffix.lower() == ".epub":
            title, authors, tags, synopsis, extended, cover = epub_metadata(path)
        else:
            title, authors, tags, synopsis, extended, cover = mobi_metadata(path)
        return title or "", authors or "", tags, synopsis, extended, cover
    except (OSError, ValueError, KeyError, zipfile.BadZipFile, ET.ParseError, struct.error):
        return "", "", (), "", (), None


def external_metadata(folder):
    """External OPF of a Calibre export. Only meaningful for single-book folders."""
    opf = folder / "metadata.opf"
    if not opf.is_file():
        return None
    if opf.stat().st_size > MAX_XML:
        raise ValueError("metadata.opf demasiado grande")
    tree = _parse_xml(opf.read_bytes())
    fields = _opf_fields(tree)
    cover_file = _opf_cover_file(folder, tree)
    title, authors = fields["title"], " & ".join(fields["creators"]) or None
    tags = tuple(dict.fromkeys(fields["subjects"]))
    synopsis = (fields["description"] or "")[:SYNOPSIS_LIMIT]
    extended = _extended(fields)
    cover = CoverRef("file", cover_file) if cover_file else None
    return title, authors, tags, synopsis, extended, cover


def _meta(base_title, base_authors, base_tags, base_synopsis, base_extended, base_cover,
          internal, file_name, source):
    """Hierarchy: external evidence, then internal metadata, then the file name."""
    title, authors, tags, synopsis, extended, cover = base_title, base_authors, base_tags, base_synopsis, base_extended, base_cover
    used = source
    if (not title or not authors) and internal[0]:
        if not title:
            title, used = internal[0], "interno" if used in ("", "nombre") else used
        if not authors:
            authors = authors or internal[1]
        tags = tags or internal[2]
        synopsis = synopsis or internal[3]
        extended = extended or internal[4]
        cover = cover or internal[5]
        if source in ("", "nombre") and title == internal[0]:
            used = "interno"
    if not title:
        title = re.sub(r"\.(epub|mobi)$", "", file_name, flags=re.I).strip()
        used = "nombre"
    cover_name = str(base_cover.source.name) if base_cover is not None else ""
    return BookMeta(title, authors or "", tuple(tags), synopsis, cover, cover_name, used, tuple(extended))


def _external_applies(external, internal):
    """An external OPF describes a file when the internal record agrees or is absent.

    A Calibre export folder legitimately holds several formats of the SAME book
    plus metadata.opf. A stray OPF must not baptize every file of a multi-book
    folder: disagreement on the internal title keeps the internal metadata.
    """
    ext_title, ext_authors = external[0], external[1] or ""
    if not ext_title:
        return False
    int_title, int_authors = internal[0], internal[1] or ""
    if not int_title:
        return True
    if int_title.casefold() != ext_title.casefold():
        return False
    if not int_authors or not ext_authors:
        return True
    split = re.compile(r"\s*[&,]\s*")
    int_set = {a.casefold() for a in split.split(int_authors) if a}
    ext_set = {a.casefold() for a in split.split(ext_authors) if a}
    return bool(int_set & ext_set) or int_authors.casefold() == ext_authors.casefold()


def scan(root):
    root = Path(root).resolve()
    if not root.is_dir():
        raise ValueError("Selecciona la carpeta raíz de Calibre")
    records, warnings = [], []
    db = root / "metadata.db"
    if db.is_file():
        # No writes to Calibre. Read exact paths registered in metadata.db.
        conn = sqlite3.connect(db.as_uri() + "?mode=ro", uri=True)
        try:
            tables = {r[0] for r in conn.execute("select name from sqlite_master where type = 'table'")}
            columns = {r[1] for r in conn.execute("pragma table_info(books)")}
            extra = {}
            for column, label in (("isbn", "ISBN"), ("language", "idioma"),
                                  ("publisher", "editorial"), ("pubdate", "fecha de publicación")):
                if column in columns:
                    extra[column] = label
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
                    comments = ""
                    if "comments" in columns:
                        comments = conn.execute("select comments from books where id=?", (bid,)).fetchone()[0] or ""
                    extended = []
                    if {"series", "books_series_link"} <= tables and "series_index" in columns:
                        row = conn.execute("select s.name, b.series_index from series s join books_series_link l on s.id=l.series join books b on b.id=l.book where b.id=?", (bid,)).fetchone()
                        if row:
                            extended.append(("serie", str(row[0])))
                            extended.append(("número de serie", str(row[1])))
                    for column, label in extra.items():
                        value = conn.execute(f"select {column} from books where id=?", (bid,)).fetchone()[0]
                        if value:
                            extended.append((label, str(value)))
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
                    # The catalogue is authoritative when it carries a real title;
                    # internal metadata only fills fields the catalogue lacks.
                    entries = []
                    cover_file = path / "cover.jpg"
                    external_cover = CoverRef("file", cover_file) if cover_file.is_file() and cover_file.stat().st_size <= MAX_COVER_BYTES else None
                    for f in sorted(files, key=str):
                        internal = file_metadata(f)
                        entries.append((f, _meta(
                            _clean(title), _clean(authors), tags, _clean(comments)[:SYNOPSIS_LIMIT],
                            tuple(extended), external_cover, internal, f.name, "catálogo" if _clean(title) else "")))
                    if entries:
                        records.append(Record(path, tuple(entries)))
                except (OSError, ValueError) as exc:
                    warnings.append((str(folder), str(exc)))
        finally:
            conn.close()
    else:
        # Folder-based recovery for copies without metadata.db.
        # Issue #155: the folder name is NEVER a title and the parent folder is
        # NEVER an author. Each file carries its own internal metadata; an
        # external metadata.opf only applies to a single-book folder.
        for directory, dirs, names in os.walk(root, followlinks=False):
            dirs[:] = sorted(d for d in dirs if not (Path(directory) / d).is_symlink())
            folder = Path(directory)
            for name in names:
                if Path(name).suffix.lower() == ".pdf":
                    warnings.append((str(folder / name), "Formato no compatible: PDF"))
            files = sorted((folder / n for n in names
                           if Path(n).suffix.lower().lstrip(".") in FORMATS), key=str)
            if not files and "metadata.opf" not in names:
                continue
            try:
                files = [inside(root, f) for f in files]
            except (OSError, ValueError) as exc:
                warnings.append((str(folder), str(exc)))
                continue
            external = None
            if "metadata.opf" in names:
                try:
                    external = external_metadata(folder)
                except (OSError, ValueError, ET.ParseError) as exc:
                    warnings.append((str(folder / "metadata.opf"), "Metadatos externos no legibles: " + str(exc)))
            entries = []
            disagreement = False
            for f in files:
                internal = file_metadata(f)
                if external is not None and _external_applies(external, internal):
                    ext_title, ext_authors, ext_tags, ext_synopsis, ext_extended, ext_cover = external
                    entries.append((f, _meta(ext_title, ext_authors, ext_tags, ext_synopsis,
                                             ext_extended, ext_cover, internal, f.name, "opf")))
                else:
                    if external is not None and external[0]:
                        disagreement = True
                    entries.append((f, _meta("", "", (), "", (), None, internal, f.name,
                                             "interno" if internal[0] else "")))
            if disagreement:
                warnings.append((str(folder / "metadata.opf"),
                                 "Metadatos externos no coinciden con el registro interno de algún archivo; ese archivo usa sus metadatos internos"))
            if entries:
                records.append(Record(folder, tuple(entries)))
    return records, warnings


def _bibliographic_group(record, path):
    meta = record.meta_for(path)
    return (meta.title.casefold(), meta.authors.casefold())


def choose_files(record, all_formats=False):
    """Candidates in preferred format order.

    With all_formats=False one candidate per distinct bibliographic record:
    a folder that holds several different books migrates all of them (issue
    #155), while EPUB/MOBI variants of the same record keep the EPUB
    preference. Fallback after a format error stays inside the same record.
    """
    files = sorted(record.files, key=lambda p: (FORMATS.index(p.suffix.lower()[1:]), str(p).casefold()))
    if all_formats:
        return files
    chosen, seen = [], set()
    for path in files:
        group = _bibliographic_group(record, path)
        if group not in seen:
            seen.add(group)
            chosen.append(path)
    return chosen


def format_candidates(record, path):
    """All formats of path's bibliographic record, in preference order."""
    group = _bibliographic_group(record, path)
    return [f for f in choose_files(record, True) if _bibliographic_group(record, f) == group]


def identify(path, cancelled=lambda: False):
    digest = hashlib.sha256()
    count = 0
    with path.open("rb") as f:
        header = f.read(128)
        f.seek(0)
        ext = path.suffix.lower()
        if ext.lstrip(".") not in FORMATS:
            raise ValueError("Formato no compatible: " + ext)
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


def is_jpeg(data):
    """SharedCoverArt parity: bounded SOI/EOI JPEG."""
    return bool(data) and len(data) <= MAX_THUMB_BYTES and (
        data[0] == 0xFF and data[1] == 0xD8 and data[-2] == 0xFF and data[-1] == 0xD9)


def load_cover(ref):
    """Load referenced cover bytes; bounded, read-only, never modifies originals."""
    if ref is None:
        return None, ""
    try:
        if ref.kind == "file":
            if ref.source.is_file() and ref.source.stat().st_size <= MAX_COVER_BYTES:
                data = ref.source.read_bytes()
                return data, ref.source.suffix.lower().lstrip(".")
            return None, ""
        if ref.kind == "epub":
            with zipfile.ZipFile(ref.source) as z:
                info = z.getinfo(ref.entry)
                if info.file_size > MAX_COVER_BYTES:
                    return None, ""
                data = z.read(ref.entry)
                return data, ref.entry.rsplit(".", 1)[-1].lower()
        if ref.kind == "mobi":
            with ref.source.open("rb") as f:
                f.seek(ref.offset)
                data = f.read(ref.length)
                return data, "mobi"
    except (OSError, ValueError, KeyError, zipfile.BadZipFile):
        return None, ""
    return None, ""


def _is_image(data):
    if not data or len(data) > MAX_COVER_BYTES:
        return False
    if data[:2] == b"\xff\xd8":
        return True
    if data[:8] == b"\x89PNG\r\n\x1a\n":
        return True
    return data[:3] in (b"GIF",) or data[:4] in (b"RIFF",)


def prepare_cover(ref, target, digest, progress=lambda msg: None):
    """Write an optimized JPEG thumbnail for one book hash; originals untouched.

    Returns the JPEG digest or None when no representable thumbnail exists.
    """
    raw, kind = load_cover(ref)
    if not _is_image(raw):
        return None
    data = None
    if _Image is not None:
        try:
            import io
            with _Image.open(io.BytesIO(raw)) as image:
                image.thumbnail(THUMB_MAX_DIM)
                if image.mode not in ("RGB", "L"):
                    image = image.convert("RGB")
                out = io.BytesIO()
                image.save(out, "JPEG", quality=82, optimize=True)
                data = out.getvalue()
        except Exception:
            data = None
    if data is None:
        # Without Pillow only small plain JPEGs ship as-is; Android derives the rest.
        data = raw if raw[:2] == b"\xff\xd8" and len(raw) <= MAX_THUMB_BYTES else None
    if not is_jpeg(data):
        progress("Portada recuperada pero miniatura no representable: " + digest[:12])
        return None
    tmp = target.with_suffix(".partial")
    try:
        tmp.write_bytes(data)
        if target.exists():
            target.unlink()
        tmp.rename(target)
        return hashlib.sha256(data).hexdigest()
    finally:
        tmp.unlink(missing_ok=True)


def build_snapshot(entries, extended_meta, covers):
    now = int(time.time() * 1000)
    books, tags, refs, synopses, extended_docs = [], {}, set(), [], []
    for record, path, digest, size in entries:
        meta = record.meta_for(path)
        bid = str(uuid.uuid5(NS, "book:" + digest))
        books.append(dict(id=bid, title=meta.title, authors=meta.authors,
                          format=path.suffix[1:].upper(), file_name=safe_filename(path, digest),
                          content_hash=digest, size_bytes=size, added_at=now,
                          last_opened_at=None, reading_status="PENDING", favorite=False, progress=0.0))
        for name in meta.tags:
            key = str(uuid.uuid5(NS, "tag:" + name.casefold()))
            tags.setdefault(key, dict(id=key, name=name))
            refs.add((bid, key))
        if meta.synopsis:
            synopses.append(dict(book_id=bid, text=meta.synopsis, provider=IMPORT_PROVIDER,
                                 generated_at=now, spoiler_safe=False))
        if extended_meta.get(digest):
            extended_docs.append(dict(id=bid, content_hash=digest, fields=[list(pair) for pair in extended_meta[digest]]))
    manifest = dict(app="biblioteca-ia", formatVersion=2, appVersion="CalibreMigrator-2",
                exportedAt=now, counts=dict(books=len(books), annotations=0, versions=0, positions=0,
                synopses=len(synopses)))
    snapshot = dict(manifest=manifest, books=books, categories=[], tags=list(tags.values()),
                collections=[], bookCategories=[], bookTags=[dict(first=b, second=t) for b, t in sorted(refs)],
                collectionBooks=[], annotations=[], annotationVersions=[], readingPositions=[],
                bookSynopses=synopses, deletedBooks=[])
    if extended_docs:
        snapshot["bookExtendedMetadata"] = extended_docs
    if covers:
        snapshot["covers"] = [dict(book=digest, image=image) for digest, image in sorted(covers.items())]
    return snapshot


def write_package(path, entries, extended_meta, covers, cancelled=lambda: False):
    manifest = build_snapshot(entries, extended_meta, covers)
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
            for digest, image in sorted(covers.items()):
                actual = hashlib.sha256()
                count = 0
                with (path.parent / "covers" / (digest + ".jpg")).open("rb") as f, \
                        z.open("covers/" + digest + ".jpg", "w") as out:
                    while True:
                        chunk = f.read(CHUNK)
                        if not chunk:
                            break
                        count += len(chunk)
                        out.write(chunk)
                        actual.update(chunk)
                if count <= 0 or actual.hexdigest() != image:
                    raise ValueError("Miniatura de portada no verificada: " + digest[:12])
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
            for digest, image in sorted(covers.items()):
                actual = hashlib.sha256()
                with z.open("covers/" + digest + ".jpg") as f:
                    while True:
                        chunk = f.read(CHUNK)
                        if not chunk:
                            break
                        actual.update(chunk)
                if actual.hexdigest() != image:
                    raise ValueError("Verificación de portada incorrecta: " + digest[:12])
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
    covers_dir = run / "covers"
    covers_dir.mkdir()
    pending, seen, report, packages = [], set(), [], []
    total = 0
    def flush():
        nonlocal pending, total
        if pending:
            path = run / ("Migracion-%03d.zip" % (len(packages) + 1))
            progress("Creando y verificando " + path.name)
            # Prepare optimized thumbnails only for the books actually packaged.
            extended_meta, covers = {}, {}
            for record, source, digest, size in pending:
                meta = record.meta_for(source)
                if meta.extended:
                    extended_meta[digest] = list(meta.extended)
                if meta.cover is not None and digest not in covers:
                    image = prepare_cover(meta.cover, covers_dir / (digest + ".jpg"), digest, progress)
                    if image:
                        covers[digest] = image
            write_package(path, pending, extended_meta, covers, cancelled)
            packages.append(path)
            for record, source, digest, size in pending:
                meta = record.meta_for(source)
                report.append((record.meta_for(source).title, meta.authors, str(source), digest, "Preparado", path.name,
                               meta.source, "Sí" if digest in covers else ("Recuperada" if meta.cover else "No"),
                               "Sí" if meta.synopsis else "No",
                               ", ".join(label for label, _ in meta.extended) or ""))
            pending, total = [], 0
    try:
        for index, record in enumerate(records, 1):
            if cancelled():
                raise InterruptedError("Cancelado")
            label = record.meta_for(record.files[0]).title if record.files else record.folder.name
            progress(f"{index}/{len(records)} — {label}")
            chosen = choose_files(record, all_formats)
            if not chosen:
                report.append(("", "", str(record.folder), "", "Sin formato compatible", "", "", "", "", ""))
            for source in chosen:
                # Preferred mode falls back to another format of the SAME record.
                candidates = [source] if all_formats else format_candidates(record, source)
                for candidate in candidates:
                    try:
                        digest, size = identify(candidate, cancelled)
                    except InterruptedError:
                        raise
                    except (OSError, ValueError, KeyError, zipfile.BadZipFile) as exc:
                        meta = record.meta_for(candidate)
                        report.append((meta.title, meta.authors, str(candidate), "", "Error: " + str(exc), "", meta.source, "", "", ""))
                        continue
                    meta = record.meta_for(candidate)
                    if digest in seen:
                        report.append((meta.title, meta.authors, str(candidate), digest, "Duplicado exacto", "", meta.source, "", "", ""))
                    else:
                        if pending and (total + size > MAX_BATCH or len(pending) >= MAX_COUNT):
                            flush()
                        pending.append((record, candidate, digest, size))
                        total += size
                        seen.add(digest)
                    if not all_formats:
                        break
        flush()
    except (Exception, KeyboardInterrupt):
        for record, source, digest, size in pending:
            meta = record.meta_for(source)
            report.append((meta.title, meta.authors, str(source), digest, "Pendiente: paquete no completado", "", meta.source, "", "", ""))
        raise
    finally:
        # Report only committed packages; a partial package never appears as ready.
        for source, issue in warnings:
            report.append(("", "", source, "", "Aviso: " + issue, "", "", "", "", ""))
        with (run / "Informe.csv").open("w", newline="", encoding="utf-8-sig") as f:
            writer = csv.writer(f, delimiter=";")
            writer.writerow(("Título", "Autor", "Origen", "SHA256", "Resultado", "Paquete",
                             "Origen metadatos", "Portada", "Sinopsis", "No representados"))
            # Protect spreadsheet users from formulas in Calibre-controlled text.
            for row in report:
                writer.writerow(["'" + x if x.startswith(("=", "+", "-", "@", "\t", "\r")) else x for x in row])
    return run, len(packages), sum(row[4] == "Preparado" for row in report)

def summarize_report(run):
    """Summarize committed output, not merely selected candidates."""
    counts = dict(prepared=0, duplicate=0, error=0, unsupported=0, warning=0, pending=0,
                  metadata_catalog=0, metadata_opf=0, metadata_internal=0, metadata_name=0,
                  covers=0, synopses=0, pending_covers=0)
    with (Path(run) / "Informe.csv").open(encoding="utf-8-sig", newline="") as f:
        for row in csv.DictReader(f, delimiter=";"):
            status = row["Resultado"]
            origin = row.get("Origen metadatos", "")
            if status == "Preparado":
                counts["prepared"] += 1
                counts["covers"] += 1 if row.get("Portada") == "Sí" else 0
                counts["pending_covers"] += 1 if row.get("Portada") == "Recuperada" else 0
                counts["synopses"] += 1 if row.get("Sinopsis") == "Sí" else 0
                if origin == "catálogo": counts["metadata_catalog"] += 1
                elif origin == "opf": counts["metadata_opf"] += 1
                elif origin == "interno": counts["metadata_internal"] += 1
                elif origin == "nombre": counts["metadata_name"] += 1
            elif status == "Duplicado exacto": counts["duplicate"] += 1
            elif status.startswith("Error:"): counts["error"] += 1
            elif status == "Sin formato compatible": counts["unsupported"] += 1
            elif status.startswith("Aviso:"): counts["warning"] += 1
            elif status.startswith("Pendiente:"): counts["pending"] += 1
    return counts
