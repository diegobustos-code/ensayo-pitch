"""Lee el guion del pitch desde la plantilla de Word (.docx) o desde texto (.txt).

Word: la primera tabla cuyo encabezado tenga columnas de "quién habla" y "segundos".
Columnas reconocidas (da igual el orden y las mayúsculas):
  Lámina · Quién habla · Segundos · Lo que dice · Puntos clave (separados por punto y coma)

Texto: bloques separados por una línea con ---, por ejemplo:
  Lámina: 1
  Quién habla: Ana Pérez
  Segundos: 20
  Puntos clave: problema; 30 %; cliente
  Lo que dice:
  Hola, somos...
"""
import re
import zipfile
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from typing import List, Optional

from . import textmatch

W = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"


@dataclass
class Entry:
    slide: Optional[int]
    speaker: str
    secs: int
    text: str
    keywords: List[str] = field(default_factory=list)


def parse_secs(v: str) -> Optional[int]:
    v = (v or "").strip().lower()
    if not v:
        return None
    m = re.match(r"^(\d+)\s*:\s*(\d{1,2})", v)
    if m:
        return int(m.group(1)) * 60 + int(m.group(2))
    m = re.search(r"(\d+(?:[.,]\d+)?)", v)
    if not m:
        return None
    n = float(m.group(1).replace(",", "."))
    if re.search(r"\bmin", v):
        n *= 60
    return int(round(n))


def split_keywords(v: str) -> List[str]:
    parts = re.split(r"[;\n•]+", v or "")
    return [p.strip(" .-–\t") for p in parts if p.strip(" .-–\t")]


def _col_kind(h: str) -> Optional[str]:
    n = textmatch.normalize(h)
    if not n:
        return None
    if "clave" in n or "puntos" in n or "no olvidar" in n:
        return "kw"
    if "quien" in n or "orador" in n or "nombre" in n or "integrante" in n or "habla" in n:
        return "speaker"
    if "segundo" in n or "tiempo" in n or "duracion" in n or n in ("s", "seg"):
        return "secs"
    if "dice" in n or "texto" in n or "guion" in n or "decir" in n:
        return "text"
    if "lamina" in n or "diapositiva" in n or "slide" in n or n in ("n", "no", "nro", "numero"):
        return "slide"
    return None


def _cell_text(tc) -> str:
    paras = []
    for p in tc.iter(W + "p"):
        paras.append("".join(t.text or "" for t in p.iter(W + "t")))
    return "\n".join(paras).strip()


def rows_to_entries(rows: List[List[str]]) -> List[Entry]:
    """rows[0] es el encabezado."""
    kinds = [_col_kind(h) for h in rows[0]]
    if "speaker" not in kinds or "secs" not in kinds:
        return []
    out = []
    last_slide = 0
    for r in rows[1:]:
        d = {}
        for k, v in zip(kinds, r):
            if k and k not in d:
                d[k] = v.strip()
        sp, text = d.get("speaker", ""), d.get("text", "")
        if not sp and not text:
            continue
        secs = parse_secs(d.get("secs", ""))
        slide = parse_secs(d.get("slide", "")) if d.get("slide") else None
        if slide is None:
            slide = last_slide + 1
        last_slide = slide
        out.append(Entry(slide, sp or "Sin nombre", secs or 20, " ".join(text.split()), split_keywords(d.get("kw", ""))))
    return out


def parse_docx(path: str) -> List[Entry]:
    with zipfile.ZipFile(path) as z:
        root = ET.fromstring(z.read("word/document.xml"))
    for tbl in root.iter(W + "tbl"):
        rows = []
        for tr in tbl.findall(W + "tr"):
            rows.append([_cell_text(tc) for tc in tr.findall(W + "tc")])
        if rows:
            entries = rows_to_entries(rows)
            if entries:
                return entries
    return []


def parse_txt(text: str) -> List[Entry]:
    text = text.replace("\r\n", "\n").lstrip("﻿")
    blocks = re.split(r"\n\s*-{3,}\s*\n", "\n" + text + "\n")
    out = []
    last_slide = 0
    for b in blocks:
        lines = [l for l in b.split("\n") if not l.strip().startswith("#")]
        d = {}
        cur = None
        for line in lines:
            m = re.match(r"^\s*([A-Za-zÁÉÍÓÚÑáéíóúñ ]{2,30}):\s*(.*)$", line)
            kind = _col_kind(m.group(1)) if m else None
            if kind:
                cur = kind
                d[cur] = m.group(2).strip()
            elif cur in ("text", "kw"):
                d[cur] = (d.get(cur, "") + "\n" + line).strip()
        sp, body = d.get("speaker", ""), d.get("text", "")
        if not sp and not body:
            continue
        slide = parse_secs(d.get("slide", "")) if d.get("slide") else None
        if slide is None:
            slide = last_slide + 1
        last_slide = slide
        out.append(Entry(slide, sp or "Sin nombre", parse_secs(d.get("secs", "")) or 20,
                         " ".join(body.split()), split_keywords(d.get("kw", ""))))
    return out


def load(path: str) -> List[Entry]:
    low = path.lower()
    if low.endswith(".docx"):
        entries = parse_docx(path)
    else:
        with open(path, "rb") as f:
            raw = f.read()
        try:
            txt = raw.decode("utf-8")
        except UnicodeDecodeError:
            txt = raw.decode("latin-1")
        entries = parse_txt(txt)
    if not entries:
        raise ValueError("No encontré el guion. Revisa que uses la plantilla: columnas «Quién habla» y «Segundos», "
                         "o bloques «Quién habla: …» separados por ---.")
    return entries
