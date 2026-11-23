"""Interfaz de Ensayo Pitch (Qt)."""
import os
import sys
import time

from PySide6.QtCore import QLocale, Qt, QTimer, QUrl, Signal
from PySide6.QtGui import QColor, QDesktopServices, QFont, QIcon, QKeySequence, QPainter, QPixmap, QShortcut
from PySide6.QtWidgets import (QApplication, QButtonGroup, QCheckBox, QComboBox, QFileDialog, QFrame, QGridLayout,
                               QHBoxLayout, QHeaderView, QLabel, QMainWindow, QMessageBox, QProgressBar, QPushButton,
                               QScrollArea, QSizePolicy, QSlider, QStackedWidget, QTableWidget, QTabWidget, QTableWidgetItem,
                               QTextEdit, QVBoxLayout, QWidget)

from . import audio, report, storage
from .deck import asset_dir, load_deck
from .session import SOLO, TEAM, Rehearsal, diff_label, fmt

APP_NAME = "Ensayo Pitch"
APP_VERSION = "2.1"
PC_TAG = " <span style='color:#9a9087;font-weight:400'>(lo lee el PC)</span>"
TEAM_PROFILE = "__equipo__"

PALETTE = ["#b5652b", "#3f6e8c", "#5b8a3c", "#8c4f7a", "#c29a2b", "#4f5d6b", "#a14a3b", "#2f7d73"]

QSS = """
* { font-family: 'Segoe UI', 'Noto Sans', Arial; color: #2b2722; }
QMainWindow, #page { background: #f4f1ec; }
QLabel#h1 { font-size: 26px; font-weight: 700; }
QLabel#h2 { font-size: 16px; font-weight: 700; color: #3a342e; }
QLabel#muted { color: #776d63; }
QFrame#card { background: #ffffff; border-radius: 12px; border: 1px solid #e4ddd3; }
QPushButton { background: #ffffff; border: 1px solid #d6cdc1; border-radius: 8px; padding: 8px 14px; }
QPushButton:hover { border-color: #b5652b; }
QPushButton#primary { background: #b5652b; color: white; border: none; font-size: 16px; font-weight: 700; padding: 12px 26px; }
QPushButton#primary:hover { background: #9c541f; }
QPushButton#primary:disabled { background: #d9c3b0; }
QPushButton#profile { text-align: left; padding: 10px 12px; border-radius: 10px; }
QPushButton#profile:checked { background: #fff3e4; border: 2px solid #b5652b; }
QPushButton#mode { text-align: left; padding: 14px; border-radius: 12px; font-size: 14px; }
QPushButton#mode:checked { background: #fff3e4; border: 2px solid #b5652b; }
QPushButton#mode:disabled { color: #b3aaa0; }
QComboBox { background: white; border: 1px solid #d6cdc1; border-radius: 6px; padding: 5px 8px; }
QComboBox QAbstractItemView { background: white; color: #2b2722; border: 1px solid #d6cdc1;
    selection-background-color: #fff3e4; selection-color: #2b2722; outline: 0; }
QMessageBox, QDialog { background: #f4f1ec; }
QMessageBox QLabel { color: #2b2722; }
QMenu { background: white; color: #2b2722; border: 1px solid #d6cdc1; }
QMenu::item:selected { background: #fff3e4; }
QScrollBar:vertical { background: transparent; width: 10px; }
QScrollBar::handle:vertical { background: #d6cdc1; border-radius: 5px; min-height: 30px; }
QScrollBar::add-line, QScrollBar::sub-line { height: 0; }
QProgressBar { background: #ebe5dc; border: none; border-radius: 5px; height: 10px; text-align: center; }
QProgressBar::chunk { border-radius: 5px; background: #2f7d4f; }
QTextEdit { background: #fbfaf8; border: 1px solid #e4ddd3; border-radius: 8px; padding: 6px; font-size: 13px; }
QTextEdit#script { font-size: 17px; }
QScrollArea, QScrollArea > QWidget > QWidget { background: transparent; }
QTabWidget::pane { border: none; }
QTabBar::tab { background: #ebe5dc; padding: 7px 16px; border-top-left-radius: 8px; border-top-right-radius: 8px; margin-right: 3px; }
QTabBar::tab:selected { background: #3a342e; color: white; }
QTableWidget { background: white; border: 1px solid #e4ddd3; border-radius: 8px; gridline-color: #eee; }
QHeaderView::section { background: #3a342e; color: white; padding: 6px; border: none; font-weight: 600; }
QToolTip { background: #fffaf3; color: #2b2722; border: 1px solid #d6cdc1; border-radius: 6px; padding: 5px 8px; }
QSlider::groove:horizontal { height: 6px; background: #ebe5dc; border-radius: 3px; }
QSlider::sub-page:horizontal { background: #b5652b; border-radius: 3px; }
QSlider::handle:horizontal { background: #ffffff; border: 2px solid #b5652b; width: 14px; height: 14px; margin: -6px 0; border-radius: 9px; }
QSlider::add-page:horizontal { background: #ebe5dc; border-radius: 3px; }
"""


def card():
    f = QFrame()
    f.setObjectName("card")
    return f


def label(text="", obj=None, wrap=False, size=None, bold=False, color=None):
    l = QLabel(text)
    if obj:
        l.setObjectName(obj)
    l.setWordWrap(wrap)
    st = []
    if size:
        st.append(f"font-size:{size}px")
    if bold:
        st.append("font-weight:700")
    if color:
        st.append(f"color:{color}")
    if st:
        l.setStyleSheet(";".join(st))
    return l


def turn_label(deck, name):
    """'1 turno', o '1 turno · 2 láminas' cuando pasa más de una diapositiva seguida."""
    turns = deck.turns_of(name)
    n_t, n_s = len(turns), sum(len(t) for t in turns)
    txt = "1 turno" if n_t == 1 else f"{n_t} turnos"
    if n_s != n_t:
        txt += f" · {n_s} láminas"
    return txt


def initials(name):
    parts = [p for p in name.split() if p[:1].isupper()]
    return "".join(p[0] for p in parts[:2]) or name[:2]


def avatar(name, color, size=34):
    pm = QPixmap(size, size)
    pm.fill(Qt.transparent)
    p = QPainter(pm)
    p.setRenderHint(QPainter.Antialiasing)
    p.setBrush(QColor(color))
    p.setPen(Qt.NoPen)
    p.drawEllipse(0, 0, size, size)
    p.setPen(QColor("white"))
    f = QFont()
    f.setBold(True)
    f.setPixelSize(int(size * 0.4))
    p.setFont(f)
    p.drawText(pm.rect(), Qt.AlignCenter, initials(name) if name != TEAM_PROFILE else "★")
    p.end()
    return pm


class LevelMeter(QWidget):
    def __init__(self):
        super().__init__()
        self.db = -90
        self.voiced = False
        self.setMinimumSize(140, 14)
        self.setMaximumHeight(14)

    def set_level(self, db, voiced):
        self.db, self.voiced = db, voiced
        self.update()

    def paintEvent(self, e):
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        r = self.rect()
        p.setPen(Qt.NoPen)
        p.setBrush(QColor("#ebe5dc"))
        p.drawRoundedRect(r, 6, 6)
        frac = max(0.0, min(1.0, (self.db + 70) / 60))
        p.setBrush(QColor("#2f7d4f" if self.voiced else "#b9ae9f"))
        p.drawRoundedRect(0, 0, int(r.width() * frac), r.height(), 6, 6)
        p.end()


class SlideView(QLabel):
    def __init__(self):
        super().__init__()
        self.setAlignment(Qt.AlignCenter)
        self.setMinimumSize(480, 270)
        self.setSizePolicy(QSizePolicy.Expanding, QSizePolicy.Expanding)
        self._pm = None
        self._text = ""
        self.setStyleSheet("background:#2b2722;border-radius:10px;color:#f4f1ec;font-size:24px;padding:30px")

    def show_slide(self, image, text):
        self._pm = QPixmap(image) if image and os.path.exists(image) else None
        self._text = text
        self._render()

    def resizeEvent(self, e):
        super().resizeEvent(e)
        self._render()

    def _render(self):
        if self._pm and not self._pm.isNull():
            self.setText("")
            self.setWordWrap(False)
            self.setPixmap(self._pm.scaled(self.size(), Qt.KeepAspectRatio, Qt.SmoothTransformation))
        else:
            self.setPixmap(QPixmap())
            self.setWordWrap(True)
            self.setText(self._text)


class Voices:
    """Voces del PC para leer las partes del resto (modo individual)."""

    def __init__(self):
        self.tts = None
        self.voices = []
        try:
            from PySide6.QtTextToSpeech import QTextToSpeech
            self.QTextToSpeech = QTextToSpeech
            engines = QTextToSpeech.availableEngines()
            pref = [e for e in ("winrt", "sapi") if e in engines] + [e for e in engines if e not in ("winrt", "sapi", "mock")]
            for eng in pref:
                t = QTextToSpeech(eng)
                es = [v for v in t.availableVoices() if v.locale().language() == QLocale.Spanish]
                if es or not self.tts:
                    self.tts, self.voices = t, es
                if es:
                    break
            if self.tts and not self.voices:
                for loc in self.tts.availableLocales():
                    if loc.language() == QLocale.Spanish:
                        self.tts.setLocale(loc)
                        self.voices = list(self.tts.availableVoices())
                        break
        except Exception:
            self.tts = None

    @property
    def ok(self):
        return self.tts is not None

    def names(self):
        return [f"{v.name()} ({v.locale().name()})" for v in self.voices]

    def speak(self, text, speaker_index, base_voice=0, rate=0.0):
        if not self.tts:
            return False
        if self.voices:
            v = self.voices[(base_voice + speaker_index) % len(self.voices)]
            self.tts.setVoice(v)
        # cada compañero suena un poco distinto aunque haya una sola voz instalada
        self.tts.setPitch([0.0, -0.25, 0.2, -0.1, 0.3, -0.35, 0.1][speaker_index % 7])
        self.tts.setRate(rate)
        self.tts.say(text)
        return True

    def stop(self):
        if self.tts:
            self.tts.stop()


# ------------------------------------------------------------------ inicio
class HomePage(QWidget):
    start_requested = Signal()
    pick_pptx = Signal(str)
    pick_guion = Signal(str)
    reset_team = Signal()
    device_change = Signal(object)

    def __init__(self, win):
        super().__init__()
        self.win = win
        self.setObjectName("page")
        root = QVBoxLayout(self)
        root.setContentsMargins(28, 22, 28, 22)
        root.setSpacing(14)

        top = QHBoxLayout()
        tl = QVBoxLayout()
        tl.addWidget(label(f"Ensayo del pitch <span style='font-size:13px;color:#b5652b'>v{APP_VERSION}</span>", "h1"))
        self.deck_lbl = label("", "muted")
        tl.addWidget(self.deck_lbl)
        top.addLayout(tl)
        top.addStretch()
        for txt, tip, fn in (
                ("Presentación…", "Cargar la presentación en PowerPoint (.pptx) o en PDF. Con el PDF se ven las láminas tal cual, sin necesitar PowerPoint.", self._pick_deck),
                ("Guion…", "Cargar el guion en la plantilla de Word (.docx) o de texto (.txt).", self._pick_guion),
                ("Plantilla del guion…", "Guardar la plantilla para escribir el guion de otra presentación.", self._save_template),
                ("Pitch del equipo", "Volver al pitch del BioBloque.", lambda: self.reset_team.emit())):
            b = QPushButton(txt)
            b.setToolTip(tip)
            b.clicked.connect(fn)
            top.addWidget(b)
        root.addLayout(top)

        body = QHBoxLayout()
        body.setSpacing(16)
        root.addLayout(body, 1)

        # perfiles
        left = card()
        ll = QVBoxLayout(left)
        ll.addWidget(label("¿Quién eres?", "h2"))
        ll.addWidget(label("Elige tu perfil. Tus partes quedan marcadas y el informe se enfoca en ti.", "muted", wrap=True))
        self.prof_box = QVBoxLayout()
        ll.addLayout(self.prof_box)
        ll.addStretch()
        left.setMinimumWidth(270)
        left.setMaximumWidth(330)
        body.addWidget(left)

        # centro
        mid = QVBoxLayout()
        mid.setSpacing(12)
        body.addLayout(mid, 1)

        modes = card()
        ml = QVBoxLayout(modes)
        ml.addWidget(label("¿Cómo quieres ensayar?", "h2"))
        mrow = QHBoxLayout()
        self.m_team = QPushButton("Ensayo del equipo\nTodos hablan frente al PC. El reloj corre sin parar y\nmide cuánto se demora cada uno en su parte.")
        self.m_solo = QPushButton("Ensayo individual\nTú dices tus partes y el PC lee las de tus compañeros,\nasí practicas los cambios de turno.")
        self.mode_group = QButtonGroup(self)
        for m in (self.m_team, self.m_solo):
            m.setObjectName("mode")
            m.setCheckable(True)
            self.mode_group.addButton(m)
            mrow.addWidget(m)
        self.m_team.setChecked(True)
        self.m_team.toggled.connect(self._refresh_info)
        ml.addLayout(mrow)
        # quiénes están (solo ensayo del equipo)
        self.who_box = QWidget()
        wl = QVBoxLayout(self.who_box)
        wl.setContentsMargins(0, 6, 0, 0)
        wl.addWidget(label("¿Quiénes están hoy? Desmarca a los que faltan: el PC lee sus partes.", bold=True))
        self.who_grid = QGridLayout()
        wl.addLayout(self.who_grid)
        self.who_checks = {}
        ml.addWidget(self.who_box)
        mid.addWidget(modes)

        opts = card()
        og = QGridLayout(opts)
        og.addWidget(label("Micrófono", bold=True), 0, 0)
        self.mic = QComboBox()
        self.mic.currentIndexChanged.connect(lambda _: self.device_change.emit(self.mic.currentData()))
        og.addWidget(self.mic, 0, 1)
        self.meter = LevelMeter()
        og.addWidget(self.meter, 0, 2)
        self.stt_lbl = label("", "muted", wrap=True)
        og.addWidget(self.stt_lbl, 1, 0, 1, 3)
        self.auto = QCheckBox("Pasar solo a la siguiente parte cuando se dice la frase final (o con →, Espacio, AvPág)")
        self.auto.setChecked(True)
        og.addWidget(self.auto, 2, 0, 1, 3)
        self.show_script = QCheckBox("Mostrar mi guion en pantalla mientras hablo")
        self.show_script.setChecked(True)
        og.addWidget(self.show_script, 3, 0, 1, 3)
        og.addWidget(label("Voz del PC", bold=True), 4, 0)
        self.voice = QComboBox()
        og.addWidget(self.voice, 4, 1)
        self.rate = QSlider(Qt.Horizontal)
        self.rate.setRange(-5, 5)
        self.rate.setValue(0)
        self.rate.setMinimumWidth(200)
        self.rate.setTickPosition(QSlider.TicksBelow)
        self.rate.setTickInterval(1)
        self.rate.setPageStep(1)
        self.rate_lbl = label("", size=12, bold=True)
        self.rate_lbl.setAlignment(Qt.AlignCenter)
        self.rate.valueChanged.connect(self._rate_text)
        rate_col = QVBoxLayout()
        rate_col.setSpacing(2)
        rate_col.addWidget(self.rate_lbl)
        rate_row = QHBoxLayout()
        rate_row.addWidget(label("Lenta", "muted"))
        rate_row.addWidget(self.rate, 1)
        rate_row.addWidget(label("Rápida", "muted"))
        rate_col.addLayout(rate_row)
        og.addLayout(rate_col, 4, 2)
        self._rate_text(self.rate.value())
        og.setColumnStretch(1, 1)
        mid.addWidget(opts)

        info = card()
        il = QVBoxLayout(info)
        self.info_title = label("", "h2")
        il.addWidget(self.info_title)
        self.info = label("", wrap=True)
        self.info.setTextFormat(Qt.RichText)
        self.info.setAlignment(Qt.AlignTop | Qt.AlignLeft)
        sa = QScrollArea()
        sa.setWidgetResizable(True)
        sa.setFrameShape(QFrame.NoFrame)
        sa.setWidget(self.info)
        il.addWidget(sa)
        mid.addWidget(info, 1)

        bottom = QHBoxLayout()
        bottom.addWidget(label("Atajos: → / Espacio / AvPág = siguiente · ← / RePág = repetir anterior · Esc = terminar", "muted"))
        bottom.addStretch()
        self.go = QPushButton("Empezar ensayo")
        self.go.setObjectName("primary")
        self.go.clicked.connect(self.start_requested.emit)
        bottom.addWidget(self.go)
        root.addLayout(bottom)

        self.prof_group = QButtonGroup(self)
        self.prof_group.buttonToggled.connect(lambda *_: self._refresh_info())

    RATE_TEXT = {-5: "Muy lenta", -4: "Bastante lenta", -3: "Lenta", -2: "Un poco lenta", -1: "Algo más lenta de lo normal",
                 0: "Velocidad normal", 1: "Un poco más rápida de lo normal", 2: "Algo rápida", 3: "Rápida",
                 4: "Bastante rápida", 5: "Muy rápida"}

    def _rate_text(self, v):
        self.rate_lbl.setText(self.RATE_TEXT.get(v, ""))
        self.rate_lbl.setStyleSheet("font-size:12px;font-weight:700;color:" + ("#2f7d4f" if v == 0 else "#b5652b"))

    # perfiles
    def set_deck(self, deck, last_profile=None):
        self.deck = deck
        self.build_who(deck, storage.settings().get("absent", []))
        src = "pitch del equipo" if deck.is_default else " + ".join(
            os.path.basename(x) for x in (deck.path, deck.guion) if x)
        self.deck_lbl.setText(f"{src} · {len(deck.sections)} partes · meta {fmt(deck.total_target)} "
                              f"(rango {fmt(deck.min_total)}–{fmt(deck.max_total)})")
        while self.prof_box.count():
            w = self.prof_box.takeAt(0).widget()
            if w:
                self.prof_group.removeButton(w)
                w.hide()
                w.setParent(None)
                w.deleteLater()
        names = deck.speakers + [TEAM_PROFILE]
        chosen = None
        for i, n in enumerate(names):
            color = self.win.color_for(n)
            txt = "Todo el equipo" if n == TEAM_PROFILE else n
            sub = "sin perfil, se ve todo igual" if n == TEAM_PROFILE else \
                f"{turn_label(deck, n)} · {fmt(deck.target_for(n))}"
            b = QPushButton(f"  {txt}\n  {sub}")
            b.setObjectName("profile")
            b.setCheckable(True)
            b.setIcon(avatar(n, color))
            b.setIconSize(avatar(n, color).size())
            b.setProperty("profile", n)
            self.prof_group.addButton(b)
            self.prof_box.addWidget(b)
            if n == last_profile:
                chosen = b
        (chosen or self.prof_group.buttons()[-1]).setChecked(True)
        self._refresh_info()

    def build_who(self, deck, absent=()):
        while self.who_grid.count():
            w = self.who_grid.takeAt(0).widget()
            if w:
                w.hide()
                w.setParent(None)
                w.deleteLater()
        self.who_checks = {}
        for i, sp in enumerate(deck.speakers):
            cb = QCheckBox(sp)
            cb.setChecked(sp not in absent)
            cb.toggled.connect(self._refresh_info)
            self.who_grid.addWidget(cb, i // 3, i % 3)
            self.who_checks[sp] = cb

    def present(self):
        """None si están todos; si no, el conjunto de los que están."""
        if self.mode() != TEAM:
            return None
        here = {sp for sp, cb in self.who_checks.items() if cb.isChecked()}
        return None if len(here) == len(self.who_checks) else here

    def profile(self):
        b = self.prof_group.checkedButton()
        p = b.property("profile") if b else TEAM_PROFILE
        return None if p == TEAM_PROFILE else p

    def mode(self):
        return SOLO if self.m_solo.isChecked() else TEAM

    def _refresh_info(self, *_):
        if not hasattr(self, "deck"):
            return
        p = self.profile()
        self.who_box.setVisible(self.mode() == TEAM)
        absent = set(self.who_checks) - (self.present() or set(self.who_checks))
        self.m_solo.setEnabled(p is not None)
        if p is None and self.m_solo.isChecked():
            self.m_team.setChecked(True)
        if p is None:
            self.info_title.setText("Orden del pitch")
            rows = "".join(
                f"<tr><td style='color:#776d63;padding-right:10px'>{s.slide_no}</td>"
                f"<td style='color:{self.win.color_for(s.speaker)};font-weight:600;padding-right:10px'>{s.speaker}"
                f"{PC_TAG if s.speaker in absent else ''}</td>"
                f"<td style='padding-right:10px'>{s.target_s} s</td><td>{s.text[:80] + ('…' if len(s.text) > 80 else '')}</td></tr>" for s in self.deck.sections)
            self.info.setText(f"<table cellspacing=3>{rows}</table>")
        else:
            turns = self.deck.turns_of(p)
            self.info_title.setText(f"Hola, {p.split()[0]}. Tus turnos")
            parts = []
            for n, turn in enumerate(turns, start=1):
                first, last = turn[0], turn[-1]
                prev = self.deck.sections[first.index - 1].speaker if first.index > 0 else None
                cue = f"entras después de <b>{prev}</b>" if prev else "abres el pitch"
                slides = f"Lámina {first.slide_no}" if len(turn) == 1 else \
                    "Láminas " + ", ".join(str(x.slide_no) for x in turn[:-1]) + f" y {last.slide_no}"
                head = f"<b>Turno {n}</b> · {slides} · {fmt(sum(x.target_s for x in turn))} · {cue}"
                body = ""
                for x in turn:
                    kws = ", ".join(k[0] for k in x.keywords)
                    pre = f"Lámina {x.slide_no} ({x.target_s} s): " if len(turn) > 1 else ""
                    body += f"<br><span style='color:#776d63'>{pre}no olvides {kws}</span>"
                    if x is not last:
                        body += f"<br><span style='color:#776d63'>&nbsp;&nbsp;→ al terminar, pasa a la lámina {self.deck.sections[x.index + 1].slide_no}</span>"
                parts.append(f"<p>{head}{body}<br><span style='color:#776d63'>Frase de salida: «…{' '.join(last.tail)}»</span></p>")
            hist = storage.history_for(p)[-5:]
            if hist:
                parts.append("<p><b>Tus últimos ensayos</b><br>" + "<br>".join(
                    f"{h['date']} · {'individual' if h['mode'] == SOLO else 'equipo'} · total {fmt(h['total'])} · tú "
                    f"{fmt(next((d['real'] for d in h['speakers'] if d['speaker'] == p), 0))}"
                    f" de {fmt(next((d['target'] for d in h['speakers'] if d['speaker'] == p), 0))}" for h in reversed(hist)) + "</p>")
            self.info.setText("".join(parts))

    def _pick_deck(self):
        path, _ = QFileDialog.getOpenFileName(self, "Elegir presentación", "",
                                              "Presentación (*.pptx *.pdf);;PowerPoint (*.pptx);;PDF (*.pdf)")
        if path:
            self.pick_pptx.emit(path)

    def _pick_guion(self):
        path, _ = QFileDialog.getOpenFileName(self, "Elegir guion", "", "Guion (*.docx *.txt)")
        if path:
            self.pick_guion.emit(path)

    def _save_template(self):
        import shutil
        src_dir = os.path.join(asset_dir(), "plantillas")
        docs = os.path.join(os.path.expanduser("~"), "Documents")
        path, filt = QFileDialog.getSaveFileName(
            self, "Guardar plantilla del guion", os.path.join(docs if os.path.isdir(docs) else "", "Guion_del_pitch.docx"),
            "Word (*.docx);;Texto (*.txt);;Ejemplo relleno con el pitch del equipo (*.docx)")
        if not path:
            return
        if filt.startswith("Ejemplo"):
            src = "Guion_ejemplo_FireProof.docx"
        elif filt.startswith("Texto") or path.lower().endswith(".txt"):
            src = "Plantilla_Guion.txt"
            if not path.lower().endswith(".txt"):
                path += ".txt"
        else:
            src = "Plantilla_Guion.docx"
            if not path.lower().endswith(".docx"):
                path += ".docx"
        shutil.copyfile(os.path.join(src_dir, src), path)
        QDesktopServices.openUrl(QUrl.fromLocalFile(path))


# ------------------------------------------------------------------ ensayo
class SessionPage(QWidget):
    done = Signal()

    def __init__(self, win):
        super().__init__()
        self.win = win
        self.setObjectName("page")
        self.reh = None
        root = QHBoxLayout(self)
        root.setContentsMargins(20, 16, 20, 16)
        root.setSpacing(16)

        left = QVBoxLayout()
        self.slide = SlideView()
        left.addWidget(self.slide, 3)
        self.script = QTextEdit()
        self.script.setReadOnly(True)
        self.script.setObjectName("script")
        self.script.setMaximumHeight(160)
        left.addWidget(self.script)
        root.addLayout(left, 3)

        side = card()
        side.setMinimumWidth(340)
        side.setMaximumWidth(420)
        sl = QVBoxLayout(side)
        sl.setSpacing(8)
        self.badge = label("", size=13, bold=True)
        self.badge.setAlignment(Qt.AlignCenter)
        sl.addWidget(self.badge)
        self.who = label("", size=26, bold=True)
        sl.addWidget(self.who)
        self.slide_lbl = label("", "muted")
        sl.addWidget(self.slide_lbl)
        self.clock = label("0:00", size=54, bold=True)
        self.clock.setStyleSheet("font-family:Bahnschrift,'Segoe UI';font-size:54px;font-weight:700")
        sl.addWidget(self.clock)
        self.bar = QProgressBar()
        self.bar.setTextVisible(False)
        sl.addWidget(self.bar)
        self.total = label("", "muted")
        sl.addWidget(self.total)
        self.voice_lbl = label("", "muted")
        sl.addWidget(self.voice_lbl)
        sl.addWidget(label("Puntos clave", "h2"))
        self.kw = label("", wrap=True)
        self.kw.setTextFormat(Qt.RichText)
        sl.addWidget(self.kw)
        sl.addWidget(label("Lo que escucha el PC", "h2"))
        self.heard = QTextEdit()
        self.heard.setReadOnly(True)
        self.heard.setMaximumHeight(110)
        sl.addWidget(self.heard)
        self.next_lbl = label("", wrap=True, size=14)
        sl.addWidget(self.next_lbl)
        sl.addStretch()
        btns = QHBoxLayout()
        self.b_back = QPushButton("← Repetir anterior")
        self.b_next = QPushButton("Siguiente →")
        self.b_end = QPushButton("Terminar")
        btns.addWidget(self.b_back)
        btns.addWidget(self.b_next)
        btns.addWidget(self.b_end)
        sl.addLayout(btns)
        self.b_back.clicked.connect(self.back)
        self.b_next.clicked.connect(lambda: self.next("tecla"))
        self.b_end.clicked.connect(self.finish)
        root.addWidget(side)

        for key in (Qt.Key_Right, Qt.Key_Space, Qt.Key_PageDown, Qt.Key_Down):
            QShortcut(QKeySequence(key), self, activated=lambda: self.next("tecla"))
        for key in (Qt.Key_Left, Qt.Key_PageUp, Qt.Key_Up):
            QShortcut(QKeySequence(key), self, activated=self.back)
        QShortcut(QKeySequence(Qt.Key_Escape), self, activated=self.finish)
        for b in (self.b_back, self.b_next, self.b_end):
            b.setFocusPolicy(Qt.NoFocus)

        self.timer = QTimer(self)
        self.timer.setInterval(100)
        self.timer.timeout.connect(self.tick)
        self.countdown = 0
        self._partial = ""
        self._machine_deadline = None
        self._shown_idx = None

    # ciclo
    def begin(self, reh: Rehearsal, show_script: bool):
        self.reh = reh
        self.show_script_mine = show_script
        self.countdown = 3
        self._shown_idx = None
        self.heard.clear()
        self._partial = ""
        self.win.audio_reset()
        self._show_countdown()
        QTimer.singleShot(1000, self._count)

    def _show_countdown(self):
        s0 = self.reh.deck.sections[0]
        self.slide.show_slide(s0.image, s0.title)
        self.badge.setText("")
        self.badge.setStyleSheet("")
        self.who.setText(f"Empieza en {self.countdown}…")
        self.slide_lbl.setText(f"Abre {s0.speaker}")
        self.clock.setText("0:00")
        self.bar.setValue(0)
        self.total.setText("")
        self.kw.setText("")
        self.next_lbl.setText("")
        self.voice_lbl.setText("")
        self.script.setPlainText("")

    def _count(self):
        if self.reh is None:
            return
        self.countdown -= 1
        if self.countdown > 0:
            self._show_countdown()
            QTimer.singleShot(1000, self._count)
            return
        self.reh.start(time.monotonic())
        self.timer.start()
        self._enter_section()

    def _enter_section(self):
        r = self.reh.current
        if r is None:
            return
        s = r.section
        self._shown_idx = self.reh.idx
        self._partial = ""
        self.win.audio_reset()
        self.heard.append(f"<span style='color:#9a9087'>— {s.speaker}, lámina {s.slide_no} —</span>")
        self.slide.show_slide(s.image, s.title)
        mine = self.reh.is_mine(r)
        color = self.win.color_for(s.speaker)
        self.who.setText(s.speaker)
        self.who.setStyleSheet(f"font-size:26px;font-weight:700;color:{color}")
        self.slide_lbl.setText(f"Lámina {s.slide_no} · parte {s.index + 1} de {len(self.reh.runs)} · meta {s.target_s} s")
        if r.by_machine:
            self.badge.setText(f"FALTA {s.speaker.upper()} · EL PC LEE SU PARTE" if self.reh.mode == TEAM
                               else "EL PC LEE ESTA PARTE · escucha y prepárate")
            self.badge.setStyleSheet("background:#ebe5dc;border-radius:6px;padding:5px;font-weight:700")
        elif mine:
            self.badge.setText("TE TOCA")
            self.badge.setStyleSheet("background:#b5652b;color:white;border-radius:6px;padding:5px;font-weight:700")
        else:
            self.badge.setText(f"HABLA {s.speaker.upper()}")
            self.badge.setStyleSheet(f"background:{color};color:white;border-radius:6px;padding:5px;font-weight:700")
        show = r.by_machine or not mine or self.show_script_mine or self.reh.profile is None
        self.script.setPlainText(s.text if show else "(Guion oculto: dilo de memoria. Puedes activarlo en el inicio.)")
        nxt = self.reh.next_run
        if nxt is None:
            self.next_lbl.setText("Última parte del pitch.")
        else:
            nn = nxt.section.speaker
            if nn == s.speaker:
                quien = "tú" if mine else nn
                self.next_lbl.setText(f"Sigue hablando <b>{quien}</b>: pasa a la lámina {nxt.section.slide_no}")
            else:
                extra = " · <b style='color:#b5652b'>¡te toca a ti!</b>" if self.reh.is_mine(nxt) and not mine else ""
                self.next_lbl.setText(f"Sigue: <b>{nn}</b> (lámina {nxt.section.slide_no}){extra}")
        self.next_lbl.setTextFormat(Qt.RichText)
        self._machine_deadline = None
        if r.by_machine:
            self.win.audio_mute(True)
            idx = self.reh.deck.speakers.index(s.speaker)
            if not self.win.voices.speak(s.text, idx, self.win.home.voice.currentIndex(), self.win.home.rate.value() / 10):
                # sin voz instalada: se muestra el texto y se espera el tiempo de la meta
                self._machine_deadline = time.monotonic() + s.target_s
        else:
            self.win.audio_mute(False)
        self._update_kw()

    def on_tts_done(self):
        if self.reh and self.reh.current and self.reh.current.by_machine and not self.reh.finished:
            QTimer.singleShot(250, lambda: self.next("voz-pc"))

    def next(self, how="tecla"):
        if not self.reh or not self.reh.started or self.reh.finished:
            return
        self.win.voices.stop()
        more = self.reh.advance(time.monotonic(), how)
        if more:
            self._enter_section()
        else:
            self._end()

    def back(self):
        if not self.reh or not self.reh.started or self.reh.finished:
            return
        self.win.voices.stop()
        self.reh.back(time.monotonic())
        self._enter_section()

    def finish(self):
        if not self.reh:
            return
        if not self.reh.started:
            self.reh = None
            self.win.go_home()
            return
        self.win.voices.stop()
        self.reh.finish(time.monotonic())
        self._end()

    def _end(self):
        self.timer.stop()
        self.win.audio_mute(False)
        self.done.emit()

    # señales
    def on_level(self, db, voiced):
        if self.reh and self.reh.started and not self.reh.finished:
            self.reh.on_audio(time.monotonic(), 0.1, voiced)

    def on_text(self, text, final):
        if not (self.reh and self.reh.started and not self.reh.finished):
            return
        if self.reh.current.by_machine:
            return
        self.reh.on_text(text, final)
        if final:
            self.heard.append(text)
            self._partial = ""
        else:
            self._partial = text
        self._update_kw()

    def tick(self):
        if not self.reh or self.reh.finished:
            return
        now = time.monotonic()
        r = self.reh.current
        if r.by_machine and self._machine_deadline and now >= self._machine_deadline:
            self.next("voz-pc")
            return
        if r.by_machine:
            r.end = now
        reason = self.reh.poll(now)
        if self.reh.finished:
            self._end()
            return
        if reason or self._shown_idx != self.reh.idx:
            self._enter_section()
            r = self.reh.current
        el = now - r.start
        tgt = r.section.target_s
        frac = el / tgt if tgt else 0
        col = "#2f7d4f" if frac < 0.85 else ("#c29a2b" if frac <= 1.1 else "#b23a2a")
        self.clock.setText(f"{fmt(el)} <span style='font-size:22px;color:#776d63'>/ {fmt(tgt)}</span>")
        self.clock.setTextFormat(Qt.RichText)
        self.clock.setStyleSheet(f"font-family:Bahnschrift,'Segoe UI';font-size:54px;font-weight:700;color:{col}")
        self.bar.setValue(int(min(1.0, frac) * 100))
        self.bar.setStyleSheet(f"QProgressBar::chunk{{background:{col};border-radius:5px}}")
        tot = self.reh.total_elapsed(now)
        self.total.setText(f"Total del pitch: {fmt(tot)} de {fmt(self.reh.deck.total_target)}  (rango {fmt(self.reh.deck.min_total)}–{fmt(self.reh.deck.max_total)})")
        if r.by_machine:
            self.voice_lbl.setText("Micrófono en pausa mientras habla el PC")
        else:
            sil = f" · silencio {r.silence_now:.0f} s" if r.silence_now >= 1.5 and r.spoke_once else ""
            self.voice_lbl.setText(f"Con voz {r.voiced:.0f} s{sil}")
        if self._partial:
            self.heard.setToolTip(self._partial)

    def _update_kw(self):
        r = self.reh.current if self.reh else None
        if r is None:
            return
        if r.by_machine:
            self.kw.setText("<span style='color:#776d63'>(parte leída por el PC)</span>")
            return
        if not self.win.stt_ok:
            self.kw.setText("<span style='color:#776d63'>Sin reconocimiento de voz: solo se mide el tiempo.</span>")
            return
        items = []
        for i, (lab, _) in enumerate(r.section.keywords):
            if i in r.hits:
                items.append(f"<span style='color:#2f7d4f'>✔ {lab}</span>")
            else:
                items.append(f"<span style='color:#9a9087'>○ {lab}</span>")
        tail = "<br><span style='color:#2f7d4f'>✔ frase final</span>" if r.tail_hit else ""
        self.kw.setText("<br>".join(items) + tail)


# ------------------------------------------------------------------ informe
class ReportPage(QWidget):
    again = Signal()
    home = Signal()

    def __init__(self, win):
        super().__init__()
        self.win = win
        self.setObjectName("page")
        self.path = ""
        root = QVBoxLayout(self)
        root.setContentsMargins(28, 22, 28, 22)
        self.title = label("", "h1")
        root.addWidget(self.title)
        self.sub = label("", "muted", wrap=True)
        self.sub.setTextFormat(Qt.RichText)
        root.addWidget(self.sub)
        self.big = label("", wrap=True)
        self.big.setTextFormat(Qt.RichText)
        root.addWidget(self.big)
        self.table = QTableWidget(0, 5)
        self.table.setHorizontalHeaderLabels(["Integrante", "Meta", "Real", "Diferencia", "Puntos clave"])
        self.table.horizontalHeader().setSectionResizeMode(QHeaderView.Stretch)
        self.table.verticalHeader().setVisible(False)
        self.table.setEditTriggers(QTableWidget.NoEditTriggers)
        self.tips = label("", wrap=True)
        self.tips.setTextFormat(Qt.RichText)
        root.addWidget(self.tips)
        self.slides = QTableWidget(0, 7)
        self.slides.setHorizontalHeaderLabels(["#", "Orador", "Meta", "Real", "Dif.", "Silencio máx.", "Puntos que faltaron"])
        self.slides.horizontalHeader().setSectionResizeMode(QHeaderView.ResizeToContents)
        self.slides.horizontalHeader().setStretchLastSection(True)
        self.slides.verticalHeader().setVisible(False)
        self.slides.setEditTriggers(QTableWidget.NoEditTriggers)
        tabs = QTabWidget()
        tabs.addTab(self.table, "Por integrante")
        tabs.addTab(self.slides, "Por lámina")
        root.addWidget(tabs, 1)
        row = QHBoxLayout()
        b1 = QPushButton("Abrir informe completo")
        b1.clicked.connect(lambda: QDesktopServices.openUrl(QUrl.fromLocalFile(self.path)))
        b2 = QPushButton("Abrir carpeta de informes")
        b2.clicked.connect(lambda: QDesktopServices.openUrl(QUrl.fromLocalFile(storage.reports_dir())))
        b3 = QPushButton("Volver al inicio")
        b3.clicked.connect(self.home.emit)
        b4 = QPushButton("Ensayar de nuevo")
        b4.setObjectName("primary")
        b4.clicked.connect(self.again.emit)
        for b in (b1, b2, b3):
            row.addWidget(b)
        row.addStretch()
        row.addWidget(b4)
        root.addLayout(row)

    def show_summary(self, summary, path, previous=None):
        self.path = path
        prof = summary.get("profile")
        self.title.setText(f"Resultado · {prof or 'Todo el equipo'}")
        verdict = {"ok": ("Dentro del tiempo", "#2f7d4f"), "corto": ("Quedó corto", "#c29a2b"),
                   "largo": ("Se pasaron del tiempo", "#b23a2a")}[summary["verdict"]]
        prev = ""
        if previous:
            prev = f" · la vez anterior: {fmt(previous['total'])}"
        self.big.setText(f"<span style='font-size:48px;font-weight:700;color:{verdict[1]}'>{fmt(summary['total'])}</span>"
                         f"&nbsp;&nbsp;<span style='font-size:18px'>{verdict[0]} (meta {fmt(summary['min_total'])}–{fmt(summary['max_total'])}){prev}</span>")
        mine = next((d for d in summary["speakers"] if d["speaker"] == prof), None)
        falta = (" · faltó " + ", ".join(summary["absent"]) + " (lo leyó el PC)") if summary.get("absent") else ""
        if mine:
            self.sub.setText(f"Tus partes: <b>{fmt(mine['real'])}</b> de {fmt(mine['target'])} "
                             f"({diff_label(mine['real'], mine['target'])}) · puntos clave {mine['kw_hit']}/{mine['kw_total']}"
                             f"{falta} · informe guardado en Documentos\\EnsayoPitch\\Informes")
        else:
            self.sub.setText(("Faltó " + ", ".join(summary["absent"]) + " (lo leyó el PC) · " if summary.get("absent") else "")
                             + "Informe guardado en Documentos\\EnsayoPitch\\Informes")
        sp = summary["speakers"]
        self.table.setRowCount(len(sp))
        for i, d in enumerate(sp):
            name = d["speaker"] + (" (tú)" if d["speaker"] == prof else "") + (" · leído por el PC" if d["machine"] else "")
            vals = [name, fmt(d["target"]), fmt(d["real"]), diff_label(d["real"], d["target"]),
                    "—" if d["machine"] else f"{d['kw_hit']}/{d['kw_total']}"]
            for j, v in enumerate(vals):
                it = QTableWidgetItem(v)
                if j == 2 and not d["machine"]:
                    off = d["real"] - d["target"]
                    it.setForeground(QColor("#2f7d4f" if abs(off) <= max(3, 0.12 * d["target"]) else ("#b23a2a" if off > 0 else "#c29a2b")))
                if d["speaker"] == prof:
                    it.setBackground(QColor("#fff3e4"))
                self.table.setItem(i, j, it)
        secs = summary["sections"]
        self.slides.setRowCount(len(secs))
        tips = []
        for i, s in enumerate(secs):
            miss = "—" if s["machine"] else (", ".join(s["keywords_missed"]) or "ninguno")
            vals = [str(s["slide"]), s["speaker"] + (" · PC" if s["machine"] else ""), f"{s['target']} s",
                    f"{s['real']:.0f} s", diff_label(s["real"], s["target"]),
                    "—" if s["machine"] else f"{s['longest_silence']:.0f} s", miss]
            for j, v in enumerate(vals):
                it = QTableWidgetItem(v)
                if s["speaker"] == prof:
                    it.setBackground(QColor("#fff3e4"))
                self.slides.setItem(i, j, it)
            if s["machine"] or (prof and s["speaker"] != prof):
                continue
            off = s["real"] - s["target"]
            if off > max(4, 0.2 * s["target"]):
                tips.append(f"Lámina {s['slide']} ({s['speaker']}): {off:.0f} s de más.")
            elif off < -max(4, 0.25 * s["target"]):
                tips.append(f"Lámina {s['slide']} ({s['speaker']}): {-off:.0f} s de menos.")
            if s["longest_silence"] >= 4:
                tips.append(f"Lámina {s['slide']}: silencio de {s['longest_silence']:.0f} s.")
        self.tips.setText(("<b>Para revisar:</b> " + " · ".join(tips[:6])) if tips else
                          "<b>Para revisar:</b> nada fuera de lo normal en " + ("tus partes." if prof else "el pitch."))


# ------------------------------------------------------------------ ventana
class MainWindow(QMainWindow):
    def __init__(self):
        super().__init__()
        self.setWindowTitle(f"{APP_NAME} {APP_VERSION}")
        self.resize(1280, 800)
        self.settings = storage.settings()
        self.voices = Voices()
        self.stt_ok = False
        self.worker = None
        self._colors = {}

        self.stack = QStackedWidget()
        self.setCentralWidget(self.stack)
        self.home = HomePage(self)
        self.sess = SessionPage(self)
        self.rep = ReportPage(self)
        for p in (self.home, self.sess, self.rep):
            self.stack.addWidget(p)

        self.home.start_requested.connect(self.start)
        self.home.pick_pptx.connect(self.on_pick_pptx)
        self.home.pick_guion.connect(self.on_pick_guion)
        self.home.reset_team.connect(lambda: self.load_material("", "", quiet=False))
        self.home.device_change.connect(self.restart_audio)
        self.sess.done.connect(self.show_report)
        self.rep.again.connect(self.start)
        self.rep.home.connect(self.go_home)
        if self.voices.ok:
            self.voices.tts.stateChanged.connect(self._tts_state)

        self.home.voice.addItems(self.voices.names() or (["(voz por defecto)"] if self.voices.ok else ["(no hay voces instaladas)"]))
        self.home.voice.setCurrentIndex(min(self.settings.get("voice", 0), max(0, self.home.voice.count() - 1)))
        self.home.auto.setChecked(self.settings.get("auto", True))
        self.home.rate.setValue(int(self.settings.get("rate", 0)))
        self.home.show_script.setChecked(self.settings.get("show_script", True))

        devs = audio.list_input_devices()
        self.home.mic.blockSignals(True)
        for i, name, is_def in devs:
            self.home.mic.addItem(name + ("  (predeterminado)" if is_def else ""), i)
        if not devs:
            self.home.mic.addItem("(no encontré micrófonos)", None)
        saved = self.settings.get("mic_name")
        for k in range(self.home.mic.count()):
            if saved and self.home.mic.itemText(k).startswith(saved):
                self.home.mic.setCurrentIndex(k)
        self.home.mic.blockSignals(False)

        deck_path = self.settings.get("deck") or ""
        guion_path = self.settings.get("guion") or ""
        self.load_material(deck_path if deck_path and os.path.exists(deck_path) else "",
                           guion_path if guion_path and os.path.exists(guion_path) else "", quiet=True)
        self.restart_audio(self.home.mic.currentData())

    def color_for(self, name):
        if name == TEAM_PROFILE:
            return "#3a342e"
        if name not in self._colors:
            self._colors[name] = PALETTE[len(self._colors) % len(PALETTE)]
        return self._colors[name]

    # presentación
    def on_pick_pptx(self, path):
        from .deck import is_pdf
        guion = ""
        cur = getattr(self, "deck", None)
        if is_pdf(path) and not (cur is not None and cur.guion):
            self._pending_pres = path
            QMessageBox.information(self, APP_NAME, "Listo, guardé el PDF para mostrar las láminas.\n\n"
                                    "Un PDF no trae el texto de cada persona, así que ahora carga el guion con «Guion…».")
            return
        if cur is not None and cur.guion:
            box = QMessageBox(QMessageBox.Question, APP_NAME,
                              f"¿Usar el guion que ya cargaste ({os.path.basename(cur.guion)}) con esta presentación?\n\n"
                              "Sí: el guion pone el texto y los tiempos, y la presentación las imágenes.\n"
                              "No: se leen las notas del orador de la presentación.", parent=self)
            yes = box.addButton("Sí, usar el guion", QMessageBox.YesRole)
            box.addButton("No, usar las notas", QMessageBox.NoRole)
            box.exec()
            if box.clickedButton() is yes:
                guion = cur.guion
        self.load_material(path, guion)

    def on_pick_guion(self, path):
        cur = getattr(self, "deck", None)
        pptx = cur.path if cur is not None and not cur.is_default and cur.path else ""
        pending = getattr(self, "_pending_pres", "")
        if pending and os.path.exists(pending):
            pptx = pending
        self._pending_pres = ""
        self.load_material(pptx, path)

    def load_material(self, pptx, guion, quiet=False):
        """pptx y guion vacíos = pitch del equipo."""
        try:
            QApplication.setOverrideCursor(Qt.WaitCursor)
            deck = load_deck(pptx or None, cache_dir=storage.data_dir(), guion_path=guion or None)
        except Exception as e:
            QApplication.restoreOverrideCursor()
            if not quiet:
                QMessageBox.warning(self, APP_NAME, f"No pude leer {'el guion' if guion else 'esa presentación'}.\n\n{e}")
            if not hasattr(self, "deck"):
                deck = load_deck(None, cache_dir=storage.data_dir())
                pptx = guion = ""
            else:
                return
        QApplication.restoreOverrideCursor()
        self.deck = deck
        self._colors = {}
        for sp in deck.speakers:
            self.color_for(sp)
        storage.save_settings(deck=pptx, guion=guion)
        self.home.set_deck(deck, self.settings.get("profile"))
        if quiet:
            return
        from . import deck as deckmod
        simple = pptx and any(s.image and os.sep + "simple" + os.sep in s.image for s in deck.sections)
        if pptx and (simple or not any(s.image for s in deck.sections)):
            why = f"\n\nDetalle: {deckmod.LAST_RENDER_NOTE}." if deckmod.LAST_RENDER_NOTE else ""
            QMessageBox.information(self, APP_NAME, "No pude abrir PowerPoint para sacar las láminas tal como se ven, así que "
                                    "armé una vista simple de cada una con su título, su texto y su imagen principal. "
                                    "Para ver las láminas reales, revisa que PowerPoint esté instalado y cerrado, y "
                                    "vuelve a cargar la presentación." + why)
        elif guion and not pptx:
            QMessageBox.information(self, APP_NAME, f"Guion cargado: {len(deck.sections)} partes, "
                                    f"{len(deck.speakers)} personas, meta {fmt(deck.total_target)}.\n\n"
                                    "Si quieres ver las láminas mientras ensayas, carga también la presentación.")

    # audio
    def restart_audio(self, device):
        if self.worker:
            self.worker.stop()
        self.worker = audio.AudioWorker(device)
        self.worker.level.connect(self.home.meter.set_level)
        self.worker.level.connect(self.sess.on_level)
        self.worker.partial.connect(lambda t: self.sess.on_text(t, False))
        self.worker.final.connect(lambda t: self.sess.on_text(t, True))
        self.worker.ready.connect(self._stt_ready)
        self.worker.failed.connect(lambda m: self.home.stt_lbl.setText("⚠ " + m))
        self.worker.start()
        name = self.home.mic.currentText().replace("  (predeterminado)", "")
        storage.save_settings(mic_name=name)

    def _stt_ready(self, ok):
        self.stt_ok = ok
        self.home.stt_lbl.setText("Reconocimiento de voz offline listo: el PC revisa tus puntos clave y la frase final."
                                  if ok else "No encontré el modelo de voz: se mide el tiempo y la voz, pero no las palabras. "
                                             "Usa las teclas para pasar de parte.")

    def audio_mute(self, on):
        if self.worker:
            self.worker.muted = on

    def audio_reset(self):
        if self.worker:
            self.worker.reset_recognizer()

    def _tts_state(self, st):
        Q = self.voices.QTextToSpeech
        if st == Q.State.Ready:
            self.sess.on_tts_done()
        elif st == Q.State.Error:
            self.sess.on_tts_done()

    # flujo
    def start(self):
        prof = self.home.profile()
        storage.save_settings(profile=prof or TEAM_PROFILE, auto=self.home.auto.isChecked(),
                              show_script=self.home.show_script.isChecked(), voice=self.home.voice.currentIndex(),
                              rate=self.home.rate.value())
        self.settings = storage.settings()
        present = self.home.present()
        storage.save_settings(absent=sorted(set(self.deck.speakers) - present) if present is not None else [])
        reh = Rehearsal(self.deck, mode=self.home.mode(), profile=prof, auto_advance=self.home.auto.isChecked(),
                        present=present)
        self.stack.setCurrentWidget(self.sess)
        self.sess.setFocus()
        self.sess.begin(reh, self.home.show_script.isChecked())

    def show_report(self):
        reh = self.sess.reh
        summary = reh.summary()
        prev = storage.history_for(summary["profile"])
        prev = prev[-1] if prev else None
        path = report.write_report(summary, storage.reports_dir())
        storage.add_history(summary, path)
        self.rep.show_summary(summary, path, prev)
        self.stack.setCurrentWidget(self.rep)

    def go_home(self):
        self.voices.stop()
        self.sess.timer.stop()
        self.home.set_deck(self.deck, self.home.profile() or TEAM_PROFILE)
        self.stack.setCurrentWidget(self.home)

    def closeEvent(self, e):
        if self.worker:
            self.worker.stop()
        super().closeEvent(e)


def selftest(out_path):
    """Prueba rápida del ejecutable (la usa GitHub Actions): presentación, modelo de voz y voces."""
    import json
    res = {}
    try:
        d = load_deck(None, render=False)
        res["deck"] = {"sections": len(d.sections), "total": d.total_target, "images": sum(1 for s in d.sections if s.image)}
    except Exception as e:
        res["deck"] = f"ERROR {e}"
    md = audio.model_dir()
    res["model_dir"] = md
    try:
        from vosk import KaldiRecognizer, Model, SetLogLevel
        SetLogLevel(-1)
        KaldiRecognizer(Model(md), audio.RATE)
        res["vosk"] = "ok"
    except Exception as e:
        res["vosk"] = f"ERROR {e}"
    try:
        import sounddevice as sd
        res["portaudio"] = sd.get_portaudio_version()[1]
    except Exception as e:
        res["portaudio"] = f"ERROR {e}"
    app = QApplication.instance() or QApplication(sys.argv)
    try:
        import tempfile
        from PySide6.QtGui import QPageSize, QPainter, QPdfWriter
        from .deck import render_pdf
        tmp = tempfile.mkdtemp()
        pdf = os.path.join(tmp, "prueba.pdf")
        wr = QPdfWriter(pdf)
        wr.setPageSize(QPageSize(QPageSize.A5))
        p = QPainter(wr)
        p.drawText(100, 100, "Lámina de prueba")
        wr.newPage()
        p.drawText(100, 100, "Segunda lámina")
        p.end()
        imgs = render_pdf(pdf, tmp)
        res["pdf"] = "ok" if len(imgs) == 2 and all(os.path.exists(i) for i in imgs) else f"ERROR {len(imgs)} páginas"
    except Exception as e:
        res["pdf"] = f"ERROR {e}"
    def dump():
        with open(out_path, "w", encoding="utf-8") as f:
            json.dump(res, f, ensure_ascii=False, indent=1)
    res["tts"] = "pendiente"
    dump()
    v = Voices()
    res["tts"] = {"ok": v.ok, "voices": v.names()}
    dump()
    ok = isinstance(res["deck"], dict) and res["vosk"] == "ok" and not str(res["portaudio"]).startswith("ERROR")
    return 0 if ok else 1


def force_light(app):
    """La app usa siempre colores claros, aunque Windows esté en modo oscuro."""
    from PySide6.QtGui import QPalette
    try:
        app.styleHints().setColorScheme(Qt.ColorScheme.Light)
    except Exception:
        pass
    app.setStyle("Fusion")
    pal = QPalette()
    ink, paper, white = QColor("#2b2722"), QColor("#f4f1ec"), QColor("#ffffff")
    for group in (QPalette.Active, QPalette.Inactive):
        pal.setColor(group, QPalette.Window, paper)
        pal.setColor(group, QPalette.WindowText, ink)
        pal.setColor(group, QPalette.Base, white)
        pal.setColor(group, QPalette.AlternateBase, QColor("#fbfaf8"))
        pal.setColor(group, QPalette.Text, ink)
        pal.setColor(group, QPalette.Button, white)
        pal.setColor(group, QPalette.ButtonText, ink)
        pal.setColor(group, QPalette.ToolTipBase, QColor("#fffaf3"))
        pal.setColor(group, QPalette.ToolTipText, ink)
        pal.setColor(group, QPalette.Highlight, QColor("#b5652b"))
        pal.setColor(group, QPalette.HighlightedText, white)
        pal.setColor(group, QPalette.PlaceholderText, QColor("#9a9087"))
        pal.setColor(group, QPalette.Link, QColor("#b5652b"))
    for role in (QPalette.WindowText, QPalette.Text, QPalette.ButtonText):
        pal.setColor(QPalette.Disabled, role, QColor("#b3aaa0"))
    app.setPalette(pal)


def main():
    if len(sys.argv) >= 3 and sys.argv[1] == "--selftest":
        sys.exit(selftest(sys.argv[2]))
    if sys.platform == "win32":
        try:  # para que la barra de tareas muestre el ícono de la app y no el de Python
            import ctypes
            ctypes.windll.shell32.SetCurrentProcessExplicitAppUserModelID("FireProof.EnsayoPitch")
        except Exception:
            pass
    app = QApplication(sys.argv)
    app.setApplicationName(APP_NAME)
    icon = os.path.join(asset_dir(), "icon.ico" if sys.platform == "win32" else "icon.png")
    if os.path.exists(icon):
        app.setWindowIcon(QIcon(icon))
    try:
        from PySide6.QtCore import QLibraryInfo, QTranslator
        tr = QTranslator(app)
        if tr.load("qtbase_es", QLibraryInfo.path(QLibraryInfo.TranslationsPath)):
            app.installTranslator(tr)
    except Exception:
        pass
    force_light(app)
    app.setStyleSheet(QSS)
    w = MainWindow()
    w.show()
    sys.exit(app.exec())
