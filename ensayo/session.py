"""Modelo del ensayo, sin interfaz: tiempos reales por turno, voz, silencios y puntos clave.

El reloj no se detiene entre turnos: el ensayo corre de principio a fin y cada segundo
se le carga al turno que está en pantalla.
"""
from dataclasses import dataclass, field
from typing import List, Optional

from . import textmatch

TEAM = "team"      # todo el equipo habla frente al PC
SOLO = "solo"      # una persona ensaya; el PC lee las partes del resto

TOTAL_MIN = 270    # 4:30
TOTAL_MAX = 305    # 5:05


@dataclass
class SectionRun:
    section: object
    start: Optional[float] = None
    end: Optional[float] = None
    voiced: float = 0.0
    longest_silence: float = 0.0
    silence_now: float = 0.0
    spoke_once: bool = False
    words: List[str] = field(default_factory=list)
    transcript: str = ""
    hits: set = field(default_factory=set)
    tail_hit: bool = False
    by_machine: bool = False     # lo leyó el PC (modo individual)
    how_ended: str = ""          # frase | silencio | tecla | voz-pc | fin

    @property
    def elapsed(self) -> float:
        if self.start is None:
            return 0.0
        return (self.end if self.end is not None else self.start) - self.start

    def to_dict(self):
        s = self.section
        return {
            "slide": s.slide_no, "speaker": s.speaker, "target": s.target_s,
            "real": round(self.elapsed, 1), "voiced": round(self.voiced, 1),
            "longest_silence": round(self.longest_silence, 1),
            "keywords_hit": [k[0] for i, k in enumerate(s.keywords) if i in self.hits],
            "keywords_missed": [k[0] for i, k in enumerate(s.keywords) if i not in self.hits],
            "machine": self.by_machine, "ended": self.how_ended, "transcript": self.transcript.strip(),
        }


class Rehearsal:
    def __init__(self, deck, mode: str = TEAM, profile: Optional[str] = None, auto_advance: bool = True):
        self.deck = deck
        self.mode = mode
        self.profile = profile            # None = todo el equipo
        self.auto_advance = auto_advance
        self.runs = [SectionRun(s) for s in deck.sections]
        self.idx = -1
        self.t0 = None
        self.t_end = None
        self._now = 0.0
        self._pending_advance_at = None
        self._pending_reason = ""

    # ---------- estado ----------
    @property
    def started(self):
        return self.t0 is not None

    @property
    def finished(self):
        return self.t_end is not None

    @property
    def current(self) -> Optional[SectionRun]:
        return self.runs[self.idx] if 0 <= self.idx < len(self.runs) else None

    @property
    def next_run(self) -> Optional[SectionRun]:
        return self.runs[self.idx + 1] if self.idx + 1 < len(self.runs) else None

    def is_mine(self, run) -> bool:
        return self.profile is not None and run.section.speaker == self.profile

    def machine_reads(self, run) -> bool:
        return self.mode == SOLO and self.profile is not None and run.section.speaker != self.profile

    def total_elapsed(self, now=None) -> float:
        if self.t0 is None:
            return 0.0
        end = self.t_end if self.t_end is not None else (now if now is not None else self._now)
        return end - self.t0

    # ---------- control ----------
    def start(self, now: float):
        self.t0 = now
        self._now = now
        self._enter(0, now)

    def _enter(self, i, now):
        self.idx = i
        r = self.runs[i]
        r.start, r.end = now, None
        r.voiced = r.longest_silence = r.silence_now = 0.0
        r.spoke_once = False
        r.words, r.transcript, r.hits, r.tail_hit = [], "", set(), False
        r.by_machine = self.machine_reads(r)
        r.how_ended = ""
        self._pending_advance_at = None

    def advance(self, now: float, how: str = "tecla") -> bool:
        """Pasa al turno siguiente. Devuelve False si se terminó el ensayo."""
        if self.finished or self.current is None:
            return False
        cur = self.current
        cur.end = now
        cur.how_ended = how
        if self.idx + 1 >= len(self.runs):
            self.t_end = now
            return False
        self._enter(self.idx + 1, now)
        return True

    def back(self, now: float):
        """Vuelve al turno anterior y lo repite (el tiempo de ese turno se reinicia)."""
        if self.finished or self.idx <= 0:
            return
        self.current.start = self.current.end = None
        self._enter(self.idx - 1, now)

    def finish(self, now: float):
        if self.finished:
            return
        if self.current is not None:
            self.current.end = now
            self.current.how_ended = self.current.how_ended or "fin"
        self.t_end = now

    # ---------- señales del micrófono ----------
    def on_audio(self, now: float, dt: float, voiced: bool):
        self._now = now
        r = self.current
        if r is None or self.finished:
            return
        r.end = now
        if r.by_machine:
            return
        if voiced:
            r.voiced += dt
            r.spoke_once = True
            r.silence_now = 0.0
        else:
            r.silence_now += dt
            if r.spoke_once:
                r.longest_silence = max(r.longest_silence, r.silence_now)

    def on_text(self, text: str, final: bool):
        r = self.current
        if r is None or self.finished or r.by_machine:
            return
        norm = textmatch.normalize(text)
        if final and norm:
            r.transcript += " " + norm
            r.words.extend(norm.split())
        probe = (r.transcript + " " + ("" if final else norm)).strip()
        for i, (_, alts) in enumerate(r.section.keywords):
            if i not in r.hits and textmatch.keyword_hit(alts, probe):
                r.hits.add(i)
        recent = r.words + ([] if final else norm.split())
        if not r.tail_hit and textmatch.tail_hit(r.section.tail, recent):
            r.tail_hit = True

    # ---------- avance automático ----------
    def silence_limit(self, run) -> float:
        return 7.0 if run.section.has_pause else 3.5

    def poll(self, now: float) -> Optional[str]:
        """Revisa si corresponde avanzar solo. Devuelve el motivo si avanzó."""
        self._now = now
        r = self.current
        if r is None or self.finished or not self.auto_advance or r.by_machine:
            return None
        el = now - r.start
        enough = el >= 0.4 * r.section.target_s
        if self._pending_advance_at is None and enough:
            if r.tail_hit:
                self._pending_advance_at, self._pending_reason = now + 0.7, "frase"
            elif self.mode == SOLO and r.spoke_once and r.silence_now >= self.silence_limit(r):
                self._pending_advance_at, self._pending_reason = now, "silencio"
            elif self.mode == TEAM and r.spoke_once and r.silence_now >= 6.0 and el >= 0.7 * r.section.target_s:
                self._pending_advance_at, self._pending_reason = now, "silencio"
        if self._pending_advance_at is not None and now >= self._pending_advance_at:
            reason = self._pending_reason
            # el tiempo del turno termina cuando se dijo la frase, no cuando reaccionó el PC
            end_at = now - (r.silence_now if reason == "silencio" else 0.7)
            end_at = max(end_at, r.start)
            self.advance(end_at, reason)
            if not self.finished and self.current is not None:
                self.current.start = end_at
            return reason
        return None

    # ---------- resultados ----------
    def per_speaker(self):
        out = {}
        for r in self.runs:
            sp = r.section.speaker
            d = out.setdefault(sp, {"speaker": sp, "target": 0, "real": 0.0, "voiced": 0.0,
                                    "kw_hit": 0, "kw_total": 0, "machine": False, "turns": 0})
            d["target"] += r.section.target_s
            d["real"] += r.elapsed
            d["voiced"] += r.voiced
            d["kw_hit"] += len(r.hits)
            d["kw_total"] += len(r.section.keywords)
            d["machine"] = d["machine"] or r.by_machine
            d["turns"] += 1
        return list(out.values())

    def verdict(self):
        t = self.total_elapsed()
        if t < self.deck.min_total:
            return "corto", t
        if t > self.deck.max_total:
            return "largo", t
        return "ok", t

    def summary(self):
        v, t = self.verdict()
        return {
            "mode": self.mode, "profile": self.profile, "deck": self.deck.path,
            "total": round(t, 1), "target_total": self.deck.total_target, "verdict": v,
            "min_total": self.deck.min_total, "max_total": self.deck.max_total, "deck_name": self.deck.name,
            "speakers": [{**d, "real": round(d["real"], 1), "voiced": round(d["voiced"], 1)} for d in self.per_speaker()],
            "sections": [r.to_dict() for r in self.runs if r.start is not None],
        }


def fmt(sec: float) -> str:
    sec = max(0, int(round(sec)))
    return f"{sec // 60}:{sec % 60:02d}"


def diff_label(real: float, target: float) -> str:
    d = real - target
    if abs(d) < 1.5:
        return "justo"
    return f"+{int(round(d))} s" if d > 0 else f"−{int(round(-d))} s"
