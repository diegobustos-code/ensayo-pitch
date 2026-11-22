"""Historial de ensayos y ajustes, guardados en %APPDATA%\\EnsayoPitch."""
import datetime as dt
import json
import os


def data_dir() -> str:
    base = os.environ.get("APPDATA") or os.path.join(os.path.expanduser("~"), ".config")
    d = os.path.join(base, "EnsayoPitch")
    os.makedirs(d, exist_ok=True)
    return d


def reports_dir() -> str:
    docs = os.path.join(os.path.expanduser("~"), "Documents")
    if not os.path.isdir(docs):
        docs = os.path.expanduser("~")
    d = os.path.join(docs, "EnsayoPitch", "Informes")
    os.makedirs(d, exist_ok=True)
    return d


def _path(name):
    return os.path.join(data_dir(), name)


def _load(name, default):
    try:
        with open(_path(name), encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return default


def _save(name, obj):
    tmp = _path(name + ".tmp")
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(obj, f, ensure_ascii=False, indent=1)
    os.replace(tmp, _path(name))


def settings() -> dict:
    return _load("ajustes.json", {})


def save_settings(**kw):
    s = settings()
    s.update(kw)
    _save("ajustes.json", s)


def history() -> list:
    return _load("historial.json", [])


def add_history(summary: dict, report_path: str = ""):
    h = history()
    entry = dict(summary)
    entry.pop("sections", None)
    entry["date"] = dt.datetime.now().strftime("%Y-%m-%d %H:%M")
    entry["report"] = report_path
    h.append(entry)
    _save("historial.json", h[-200:])


def history_for(profile):
    return [e for e in history() if e.get("profile") == profile]
