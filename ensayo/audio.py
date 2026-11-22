"""Micrófono: nivel, detección de voz y reconocimiento offline con Vosk, en un hilo aparte."""
import json
import math
import os
import queue
import sys
import time

from PySide6.QtCore import QThread, Signal

RATE = 16000
BLOCK = 1600  # 0,1 s


def model_dir():
    cands = []
    base = getattr(sys, "_MEIPASS", None)
    if base:
        cands.append(os.path.join(base, "model"))
    here = os.path.dirname(os.path.abspath(__file__))
    cands += [os.path.join(os.path.dirname(here), "model"), os.path.join(here, "model")]
    if os.environ.get("ENSAYO_MODEL"):
        cands.insert(0, os.environ["ENSAYO_MODEL"])
    for c in cands:
        if os.path.isdir(c) and (os.path.exists(os.path.join(c, "am")) or os.path.exists(os.path.join(c, "conf"))):
            return c
    return None


def list_input_devices():
    try:
        import sounddevice as sd
        devs = []
        default = sd.default.device[0] if sd.default.device else None
        for i, d in enumerate(sd.query_devices()):
            if d.get("max_input_channels", 0) > 0:
                devs.append((i, d["name"], i == default))
        return devs
    except Exception:
        return []


class VAD:
    """Detector de voz por energía con piso de ruido que se adapta a la sala."""

    def __init__(self):
        self.floor = -60.0
        self.hang = 0.0
        self.calibrated = 0

    def feed(self, db: float, dt: float) -> bool:
        if self.calibrated < 10:  # primer segundo: escuchar la sala
            self.floor = db if self.calibrated == 0 else 0.8 * self.floor + 0.2 * db
            self.calibrated += 1
            return False
        loud = db > max(self.floor + 10.0, -55.0)
        if not loud:
            self.floor = 0.97 * self.floor + 0.03 * db
        else:
            self.floor = 0.999 * self.floor + 0.001 * db
        if loud:
            self.hang = 0.3
            return True
        if self.hang > 0:
            self.hang -= dt
            return True
        return False


def dbfs(samples_bytes: bytes) -> float:
    import array
    a = array.array("h", samples_bytes)
    if not a:
        return -100.0
    acc = sum(x * x for x in a) / len(a)
    rms = math.sqrt(acc) / 32768.0
    return 20 * math.log10(rms) if rms > 1e-6 else -100.0


class AudioWorker(QThread):
    level = Signal(float, bool)      # dBFS, hay voz
    partial = Signal(str)
    final = Signal(str)
    failed = Signal(str)
    ready = Signal(bool)             # True si hay reconocimiento de voz

    def __init__(self, device=None, parent=None):
        super().__init__(parent)
        self.device = device
        self._running = True
        self.muted = False           # mientras habla el PC
        self._reset_rec = False

    def stop(self):
        self._running = False
        self.wait(2000)

    def reset_recognizer(self):
        self._reset_rec = True

    def run(self):
        try:
            import sounddevice as sd
        except Exception as e:
            self.failed.emit(f"No pude abrir el audio ({e}).")
            return
        rec = None
        model = None
        md = model_dir()
        if md:
            try:
                from vosk import KaldiRecognizer, Model, SetLogLevel
                SetLogLevel(-1)
                model = Model(md)
                rec = KaldiRecognizer(model, RATE)
            except Exception:
                rec = None
        self.ready.emit(rec is not None)
        q = queue.Queue()

        def cb(indata, frames, t, status):
            q.put(bytes(indata))

        vad = VAD()
        try:
            with sd.RawInputStream(samplerate=RATE, blocksize=BLOCK, dtype="int16", channels=1,
                                   device=self.device, callback=cb):
                while self._running:
                    try:
                        data = q.get(timeout=0.5)
                    except queue.Empty:
                        continue
                    db = dbfs(data)
                    dt = len(data) / 2 / RATE
                    if self.muted:
                        self.level.emit(db, False)
                        continue
                    voiced = vad.feed(db, dt)
                    self.level.emit(db, voiced)
                    if rec is not None:
                        if self._reset_rec:
                            self._reset_rec = False
                            rec = __import__("vosk").KaldiRecognizer(model, RATE)
                        if rec.AcceptWaveform(data):
                            txt = json.loads(rec.Result()).get("text", "")
                            if txt:
                                self.final.emit(txt)
                        else:
                            txt = json.loads(rec.PartialResult()).get("partial", "")
                            if txt:
                                self.partial.emit(txt)
        except Exception as e:
            self.failed.emit(f"No pude usar el micrófono: {e}")
