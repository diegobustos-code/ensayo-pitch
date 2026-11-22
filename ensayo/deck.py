"""Lee la presentación: quién habla en cada lámina, cuánto tiempo le toca y qué tiene que decir.

Las notas del orador deben empezar así:  NOMBRE (25 s). texto que se dice...
Las láminas sin ese formato (por ejemplo, créditos) se saltan.
"""
import hashlib
import os
import re
import sys
import tempfile
from dataclasses import dataclass, field
from typing import List, Optional

from . import curated, textmatch

NOTE_RE = re.compile(r"^\s*([A-ZÁÉÍÓÚÑÜa-záéíóúñü][A-ZÁÉÍÓÚÑÜa-záéíóúñü .]*?)\s*\((\d+)\s*s(?:eg)?\)\.?\s*(.*)$", re.S)


def asset_dir() -> str:
    base = getattr(sys, "_MEIPASS", os.path.dirname(os.path.abspath(__file__)))
    p = os.path.join(base, "assets")
    if not os.path.isdir(p):
        p = os.path.join(base, "ensayo", "assets")
    return p


def default_deck_path() -> str:
    return os.path.join(asset_dir(), "default.pptx")


@dataclass
class Section:
    index: int
    slide_no: int
    speaker: str
    target_s: int
    text: str                 # lo que se dice, limpio
    raw_notes: str
    title: str = ""
    image: Optional[str] = None
    tail: List[str] = field(default_factory=list)
    keywords: list = field(default_factory=list)   # [(etiqueta, [alternativas])]
    has_pause: bool = False


@dataclass
class Deck:
    path: str
    sections: List[Section]
    guion: Optional[str] = None
    is_default: bool = False

    @property
    def name(self) -> str:
        if self.is_default:
            return "Pitch Fire Proof"
        if self.guion:
            return os.path.splitext(os.path.basename(self.guion))[0]
        return os.path.splitext(os.path.basename(self.path))[0]

    @property
    def min_total(self) -> int:
        """Rango aceptable: hasta 10 % menos que la meta y 5 s de más (5:00 -> 4:30-5:05)."""
        return int(round(self.total_target * 0.9))

    @property
    def max_total(self) -> int:
        return self.total_target + 5

    @property
    def speakers(self) -> List[str]:
        out = []
        for s in self.sections:
            if s.speaker not in out:
                out.append(s.speaker)
        return out

    @property
    def total_target(self) -> int:
        return sum(s.target_s for s in self.sections)

    def target_for(self, speaker: str) -> int:
        return sum(s.target_s for s in self.sections if s.speaker == speaker)


def pretty_name(raw: str) -> str:
    small = {"de", "del", "la", "y"}
    parts = raw.strip().split()
    return " ".join(p.lower() if p.lower() in small else p[:1].upper() + p[1:].lower() for p in parts)


def clean_spoken(body: str) -> str:
    t = body
    t = re.sub(r"\((?:pausa|silencio|[^)]{0,40}(?:mostrar|apunta|señala|clic)[^)]*)\)", " ", t, flags=re.I)
    t = re.sub(r"\bTodos\s*:\s*['\"‘“]?[^'\"’”]*['\"’”]?", " ", t)
    t = re.sub(r"[‘’“”'\"]", "", t)
    return re.sub(r"\s+", " ", t).strip()


def parse_notes(raw: str):
    m = NOTE_RE.match(raw or "")
    if not m:
        return None
    name, secs, body = m.group(1), int(m.group(2)), m.group(3)
    return pretty_name(name), secs, clean_spoken(body), bool(re.search(r"\(pausa\)", body, re.I))


def _slide_title(slide) -> str:
    best = ""
    try:
        if slide.shapes.title is not None and slide.shapes.title.text.strip():
            return slide.shapes.title.text.strip()
    except Exception:
        pass
    for sh in slide.shapes:
        if getattr(sh, "has_text_frame", False) and sh.text_frame.text.strip():
            txt = sh.text_frame.text.strip()
            if len(txt) > len(best):
                best = txt
    return best[:140]


def file_hash(path: str) -> str:
    h = hashlib.sha1()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()[:16]


def _bundled_images(n_slides: int):
    d = os.path.join(asset_dir(), "slides")
    return [os.path.join(d, f"s-{i:02d}.jpg") for i in range(1, n_slides + 1)]


def export_with_powerpoint(path: str, outdir: str, n_slides: int):
    """Imágenes de cada lámina usando PowerPoint (si está instalado). Devuelve lista o None."""
    if sys.platform != "win32":
        return None
    try:
        import pythoncom
        import win32com.client
        pythoncom.CoInitialize()
        app = win32com.client.DispatchEx("PowerPoint.Application")
        pres = app.Presentations.Open(os.path.abspath(path), True, False, False)
        os.makedirs(outdir, exist_ok=True)
        pres.Export(outdir, "JPG", 1280, 720)
        pres.Close()
        try:
            if app.Presentations.Count == 0:
                app.Quit()
        except Exception:
            pass
    except Exception:
        return None
    files = {}
    for fn in os.listdir(outdir):
        m = re.search(r"(\d+)\.jpe?g$", fn, re.I)
        if m:
            files[int(m.group(1))] = os.path.join(outdir, fn)
    if not files:
        return None
    return [files.get(i) for i in range(1, n_slides + 1)]


def _slide_images(path, slides, is_default, cache_dir, render):
    images = [None] * len(slides)
    if is_default:
        return [p if os.path.exists(p) else None for p in _bundled_images(len(slides))]
    if not render:
        return images
    cache_dir = cache_dir or os.path.join(tempfile.gettempdir(), "EnsayoPitch")
    outdir = os.path.join(cache_dir, "laminas", file_hash(path))
    got = None
    if os.path.isdir(outdir) and os.listdir(outdir):
        got = [os.path.join(outdir, f) for f in os.listdir(outdir) if re.search(r"(\d+)\.jpe?g$", f, re.I)]
        got = sorted(got, key=lambda p: int(re.search(r"(\d+)\.jpe?g$", os.path.basename(p), re.I).group(1)))
        if len(got) < len(slides):
            got = None
    if got is None:
        got = export_with_powerpoint(path, outdir, len(slides))
    return got or images


def load_deck(path: Optional[str] = None, cache_dir: Optional[str] = None, render: bool = True,
              guion_path: Optional[str] = None) -> Deck:
    """Presentación sola (lee las notas), guion solo, o ambas (el guion manda y la presentación pone las imágenes)."""
    from pptx import Presentation

    if guion_path:
        return _load_with_guion(path, guion_path, cache_dir, render)
    path = path or default_deck_path()
    prs = Presentation(path)
    slides = list(prs.slides)
    is_default = os.path.abspath(path) == os.path.abspath(default_deck_path()) or (
        os.path.exists(default_deck_path()) and file_hash(path) == file_hash(default_deck_path()))

    images = _slide_images(path, slides, is_default, cache_dir, render)

    sections = []
    for i, slide in enumerate(slides, start=1):
        raw = ""
        if slide.has_notes_slide:
            raw = slide.notes_slide.notes_text_frame.text
        parsed = parse_notes(raw)
        if not parsed:
            continue
        speaker, secs, text, pause = parsed
        sec = Section(index=len(sections), slide_no=i, speaker=speaker, target_s=secs, text=text,
                      raw_notes=raw, title=_slide_title(slide), image=images[i - 1], has_pause=pause)
        sec.tail = textmatch.tail_words(text, 6)
        sec.keywords = (curated.KEYWORDS.get(i) if is_default else None) or textmatch.auto_keywords(text)
        sections.append(sec)
    if not sections:
        raise ValueError("No encontré notas con el formato «NOMBRE (25 s). texto» en esta presentación. "
                         "Si tienes el guion aparte, cárgalo con «Guion…».")
    return Deck(path=path, sections=sections, is_default=is_default)


def _load_with_guion(pptx_path, guion_path, cache_dir, render) -> Deck:
    from . import guion

    entries = guion.load(guion_path)
    titles, images = {}, {}
    if pptx_path:
        from pptx import Presentation
        slides = list(Presentation(pptx_path).slides)
        for i, sl in enumerate(slides, start=1):
            titles[i] = _slide_title(sl)
        for i, img in enumerate(_slide_images(pptx_path, slides, False, cache_dir, render), start=1):
            images[i] = img
    sections = []
    for e in entries:
        speaker = pretty_name(e.speaker) if e.speaker.isupper() else " ".join(e.speaker.split())
        text = clean_spoken(e.text)
        kws = [textmatch.label_keyword(k) for k in e.keywords] or textmatch.auto_keywords(text)
        title = titles.get(e.slide) or (text[:110] + ("…" if len(text) > 110 else ""))
        sec = Section(index=len(sections), slide_no=e.slide, speaker=speaker, target_s=e.secs, text=text,
                      raw_notes=e.text, title=title, image=images.get(e.slide),
                      has_pause=bool(re.search(r"\(pausa\)", e.text, re.I)))
        sec.tail = textmatch.tail_words(text, 6)
        sec.keywords = kws
        sections.append(sec)
    return Deck(path=pptx_path or "", sections=sections, guion=guion_path)
