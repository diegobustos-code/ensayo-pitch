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

    def turns_of(self, speaker: str):
        """Turnos de una persona: láminas seguidas de la misma persona cuentan como un solo turno."""
        out, cur = [], None
        for s in self.sections:
            if s.speaker == speaker:
                if cur is not None and cur[-1].index == s.index - 1:
                    cur.append(s)
                else:
                    cur = [s]
                    out.append(cur)
            else:
                cur = None
        return out

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


LAST_RENDER_NOTE = ""   # explica por qué no se pudo usar PowerPoint (se muestra en la app)


def export_with_powerpoint(path: str, outdir: str, n_slides: int):
    """Imágenes de cada lámina usando PowerPoint (si está instalado). Devuelve lista o None."""
    global LAST_RENDER_NOTE
    if sys.platform != "win32":
        LAST_RENDER_NOTE = "solo funciona en Windows"
        return None
    os.makedirs(outdir, exist_ok=True)
    app = pres = None
    try:
        import pythoncom
        import win32com.client
        try:
            pythoncom.CoInitialize()
        except Exception:
            pass
        try:
            app = win32com.client.DispatchEx("PowerPoint.Application")
        except Exception:
            app = win32com.client.Dispatch("PowerPoint.Application")
        # ReadOnly, Untitled, WithWindow
        pres = app.Presentations.Open(os.path.abspath(path), True, False, False)
        count = int(pres.Slides.Count)
        for i in range(1, count + 1):
            pres.Slides(i).Export(os.path.join(os.path.abspath(outdir), f"s-{i:02d}.jpg"), "JPG", 1280, 720)
    except Exception as e:
        LAST_RENDER_NOTE = f"PowerPoint no respondió ({e.__class__.__name__}: {str(e)[:160]})"
    finally:
        try:
            if pres is not None:
                pres.Close()
            if app is not None and app.Presentations.Count == 0:
                app.Quit()
        except Exception:
            pass
    files = {}
    for fn in os.listdir(outdir):
        m = re.match(r"s-(\d+)\.jpe?g$", fn, re.I)
        if m:
            files[int(m.group(1))] = os.path.join(outdir, fn)
    if not files:
        if not LAST_RENDER_NOTE:
            LAST_RENDER_NOTE = "PowerPoint no generó imágenes"
        return None
    return [files.get(i) for i in range(1, n_slides + 1)]


def _shape_iter(shapes):
    for sh in shapes:
        yield sh
        if getattr(sh, "shape_type", None) == 6:  # grupo
            try:
                yield from _shape_iter(sh.shapes)
            except Exception:
                pass


def compose_slide_images(slides, outdir):
    """Plan B sin PowerPoint: arma una imagen por lámina con su título, su texto y su foto principal."""
    try:
        from PySide6.QtCore import QRectF, Qt
        from PySide6.QtGui import QColor, QFont, QGuiApplication, QImage, QPainter
    except Exception:
        return None
    if QGuiApplication.instance() is None:
        return None
    os.makedirs(outdir, exist_ok=True)
    out = []
    W, H = 1280, 720
    for i, slide in enumerate(slides, start=1):
        title = _slide_title(slide)
        lines, pic, best = [], None, 0
        for sh in _shape_iter(slide.shapes):
            try:
                if getattr(sh, "shape_type", None) == 13:  # imagen
                    area = int(sh.width or 0) * int(sh.height or 0)
                    if area > best:
                        best, pic = area, sh.image.blob
                elif getattr(sh, "has_text_frame", False):
                    for para in sh.text_frame.paragraphs:
                        t = "".join(r.text for r in para.runs).strip()
                        if t and t != title and t not in lines:
                            lines.append(t)
            except Exception:
                continue
        img = QImage(W, H, QImage.Format_RGB32)
        img.fill(QColor("#ffffff"))
        p = QPainter(img)
        p.setRenderHint(QPainter.Antialiasing)
        p.setRenderHint(QPainter.SmoothPixmapTransform)
        p.fillRect(0, 0, W, 10, QColor("#b5652b"))
        text_w = W - 120
        if pic:
            q = QImage()
            if q.loadFromData(pic):
                box = QRectF(W * 0.52, 140, W * 0.44, H - 190)
                sc = q.scaled(int(box.width()), int(box.height()), Qt.KeepAspectRatio, Qt.SmoothTransformation)
                p.drawImage(int(box.x() + (box.width() - sc.width()) / 2), int(box.y() + (box.height() - sc.height()) / 2), sc)
                text_w = int(W * 0.52) - 90
        f = QFont("Segoe UI")
        f.setPixelSize(46)
        f.setBold(True)
        p.setFont(f)
        p.setPen(QColor("#2b2722"))
        p.drawText(QRectF(60, 40, W - 120, 90), Qt.AlignLeft | Qt.AlignVCenter | Qt.TextWordWrap, title or f"Lámina {i}")
        f.setPixelSize(26)
        f.setBold(False)
        p.setFont(f)
        p.setPen(QColor("#4a433c"))
        p.drawText(QRectF(60, 150, text_w, H - 200), Qt.AlignLeft | Qt.AlignTop | Qt.TextWordWrap,
                   "\n".join("• " + l for l in lines[:9]))
        f.setPixelSize(18)
        p.setFont(f)
        p.setPen(QColor("#9a9087"))
        p.drawText(QRectF(60, H - 44, W - 120, 30), Qt.AlignRight, f"Lámina {i} · vista simple")
        p.end()
        fp = os.path.join(outdir, f"c-{i:02d}.png")
        img.save(fp)
        out.append(fp)
    return out


def is_pdf(path: str) -> bool:
    try:
        with open(path, "rb") as f:
            return f.read(5) == b"%PDF-"
    except OSError:
        return False


def render_pdf(path: str, cache_dir: Optional[str] = None):
    """Una imagen por página del PDF (las láminas tal cual), con QtPdf. No necesita PowerPoint."""
    global LAST_RENDER_NOTE
    cache_dir = cache_dir or os.path.join(tempfile.gettempdir(), "EnsayoPitch")
    outdir = os.path.join(cache_dir, "laminas", "pdf-" + file_hash(path))
    if os.path.isdir(outdir):
        found = sorted(f for f in os.listdir(outdir) if re.match(r"p-\d+\.jpg$", f))
        if found:
            return [os.path.join(outdir, f) for f in found]
    try:
        from PySide6.QtCore import QSize
        from PySide6.QtGui import QColor, QImage, QPainter
        from PySide6.QtPdf import QPdfDocument
    except Exception as e:
        LAST_RENDER_NOTE = f"no pude abrir el PDF ({e})"
        return []
    doc = QPdfDocument()
    doc.load(path)
    n = doc.pageCount()
    if n <= 0:
        LAST_RENDER_NOTE = "el PDF no tiene páginas o está protegido"
        return []
    os.makedirs(outdir, exist_ok=True)
    out = []
    for i in range(n):
        pt = doc.pagePointSize(i)
        w = 1280
        h = max(1, int(round(w * pt.height() / pt.width()))) if pt.width() > 0 else 720
        img = doc.render(i, QSize(w, h))
        bg = QImage(img.size(), QImage.Format_RGB32)
        bg.fill(QColor("#ffffff"))
        p = QPainter(bg)
        p.drawImage(0, 0, img)
        p.end()
        fp = os.path.join(outdir, f"p-{i + 1:03d}.jpg")
        bg.save(fp, "JPG", 90)
        out.append(fp)
    doc.close()
    return out


def _slide_images(path, slides, is_default, cache_dir, render):
    global LAST_RENDER_NOTE
    LAST_RENDER_NOTE = ""
    images = [None] * len(slides)
    if is_default:
        return [p if os.path.exists(p) else None for p in _bundled_images(len(slides))]
    if not render:
        return images
    cache_dir = cache_dir or os.path.join(tempfile.gettempdir(), "EnsayoPitch")
    outdir = os.path.join(cache_dir, "laminas", file_hash(path))
    got = None
    if os.path.isdir(outdir):
        found = {}
        for f in os.listdir(outdir):
            m = re.match(r"s-(\d+)\.jpe?g$", f, re.I)
            if m:
                found[int(m.group(1))] = os.path.join(outdir, f)
        if len(found) >= len(slides):
            got = [found.get(i) for i in range(1, len(slides) + 1)]
    if got is None:
        got = export_with_powerpoint(path, outdir, len(slides))
    if got is None:
        got = compose_slide_images(slides, os.path.join(outdir, "simple"))
    return got or images


def load_deck(path: Optional[str] = None, cache_dir: Optional[str] = None, render: bool = True,
              guion_path: Optional[str] = None) -> Deck:
    """Presentación sola (lee las notas), guion solo, o ambas (el guion manda y la presentación pone las imágenes)."""
    from pptx import Presentation

    if guion_path:
        return _load_with_guion(path, guion_path, cache_dir, render)
    if path and is_pdf(path):
        raise ValueError("Un PDF no trae las notas del orador. Carga también el guion (botón «Guion…») "
                         "y el PDF se usará para mostrar las láminas.")
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
    if pptx_path and is_pdf(pptx_path):
        for i, img in enumerate(render_pdf(pptx_path, cache_dir) if render else [], start=1):
            images[i] = img
    elif pptx_path:
        from pptx import Presentation
        slides = list(Presentation(pptx_path).slides)
        for i, sl in enumerate(slides, start=1):
            titles[i] = _slide_title(sl)
        same_as_team = os.path.exists(default_deck_path()) and file_hash(pptx_path) == file_hash(default_deck_path())
        for i, img in enumerate(_slide_images(pptx_path, slides, same_as_team, cache_dir, render), start=1):
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
