import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from ensayo import textmatch
from ensayo.deck import load_deck, parse_notes
from ensayo.session import SOLO, TEAM, Rehearsal


def test_parse_notes():
    sp, secs, text, pause = parse_notes("DIEGO BUSTOS (15 s). 'Hola a todos.' (pausa) 'Sigo.' Todos: 'Gracias.'")
    assert sp == "Diego Bustos" and secs == 15 and pause
    assert "Gracias" not in text and "pausa" not in text
    assert parse_notes("(No se presenta)") is None


def test_deck_default():
    d = load_deck(render=False)
    assert d.total_target == 300
    assert d.speakers[0] == "Diego Bustos"
    assert len(d.sections) == 13
    assert all(s.keywords and s.tail for s in d.sections)


def test_numbers():
    assert "mil setecientos" in textmatch.number_forms("1.700")
    assert "veintiun mil" in textmatch.number_forms("21", "mil")
    assert "cinco millones" in textmatch.number_forms("5", "millones")


def test_tail_and_keywords():
    tail = textmatch.tail_words("Son dos gastos separados, y nadie los une.")
    heard = textmatch.words("bueno y son dos gastos separados y nadie los une")
    assert textmatch.tail_hit(tail, heard)
    assert not textmatch.tail_hit(tail, textmatch.words("hoy hacen cuatro cosas"))
    assert textmatch.keyword_hit(["hervimos"], "despues la hervimos para")


def _run(reh, t, secs, text=None, voiced=True):
    """Simula 'secs' segundos de audio; devuelve el tiempo final."""
    steps = int(secs * 10)
    for k in range(steps):
        t += 0.1
        reh.on_audio(t, 0.1, voiced)
        if text and k == steps - 1:
            reh.on_text(text, True)
        reh.poll(t)
    return t


def test_team_auto_advance_by_tail():
    d = load_deck(render=False)
    reh = Rehearsal(d, mode=TEAM, profile="Gaspar")
    t = 0.0
    reh.start(t)
    for s in d.sections:
        idx = reh.idx
        t = _run(reh, t, s.target_s * 0.9, text=s.text)
        t = _run(reh, t, 1.0, voiced=False)  # deja que pase el retardo de 0,7 s
        assert reh.finished or reh.idx == idx + 1, (s.slide_no, reh.idx)
    assert reh.finished
    tot = reh.total_elapsed()
    assert 260 < tot < 290
    sp = {x["speaker"]: x for x in reh.per_speaker()}
    assert abs(sp["César"]["real"] - 27) < 1.5
    assert all(r.hits for r in reh.runs)


def test_no_early_advance():
    d = load_deck(render=False)
    reh = Rehearsal(d, mode=TEAM)
    reh.start(0.0)
    s = d.sections[0]
    _run(reh, 0.0, 2.0, text=s.text)  # dijo la frase final demasiado pronto
    assert reh.idx == 0


def test_solo_silence_and_keys():
    d = load_deck(render=False)
    reh = Rehearsal(d, mode=SOLO, profile="Diego Bustos")
    reh.start(0.0)
    assert not reh.current.by_machine
    t = _run(reh, 0.0, 10, text="levanten la mano")
    t = _run(reh, t, 8.0, voiced=False)  # la lámina 1 tiene (pausa): espera 7 s
    assert reh.idx == 1 and reh.current.by_machine
    assert abs(reh.runs[0].elapsed - 10) < 0.6
    reh.advance(t + 30, "voz-pc")
    assert reh.idx == 2
    reh.back(t + 31)
    assert reh.idx == 1
    reh.finish(t + 40)
    s = reh.summary()
    assert s["sections"][0]["keywords_hit"]


def test_report(tmp_path):
    from ensayo.report import write_report
    d = load_deck(render=False)
    reh = Rehearsal(d, mode=TEAM, profile="Daniel")
    reh.start(0.0)
    t = 0.0
    for _ in d.sections:
        t += 22
        reh.advance(t)
    p = write_report(reh.summary(), str(tmp_path))
    assert os.path.exists(p) and "Daniel" in open(p, encoding="utf-8").read()


def test_guion_formats(tmp_path):
    from ensayo import guion
    base = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "ensayo", "assets", "plantillas")
    ej = guion.load(os.path.join(base, "Guion_ejemplo_FireProof.docx"))
    assert len(ej) == 13 and ej[1].speaker == "César" and ej[1].secs == 30 and ej[0].keywords
    t = tmp_path / "g.txt"
    t.write_text("# hola\nQuién habla: Ana\nSegundos: 0:25\nLo que dice:\nPrimera línea.\nSegunda línea.\n---\n"
                 "Lámina: 4\nOrador: Bruno\nTiempo: 1 min\nPuntos clave: 30 %; cliente\nTexto: Hola a todos.\n", encoding="utf-8")
    e = guion.load(str(t))
    assert [x.slide for x in e] == [1, 4]
    assert e[0].secs == 25 and e[0].text == "Primera línea. Segunda línea."
    assert e[1].secs == 60 and e[1].keywords == ["30 %", "cliente"]


def test_deck_with_guion():
    base = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "ensayo", "assets")
    d = load_deck(os.path.join(base, "default.pptx"), render=False,
                  guion_path=os.path.join(base, "plantillas", "Guion_ejemplo_FireProof.docx"))
    assert d.total_target == 300 and (d.min_total, d.max_total) == (270, 305)
    assert d.sections[0].title  # título sacado de la lámina
    assert any("mil setecientos" in alts for _, alts in d.sections[2].keywords)
    reh = Rehearsal(d, mode=TEAM)
    t = 0.0
    reh.start(t)
    for s in d.sections:
        t = _run(reh, t, s.target_s * 0.9, text=s.text)
        t = _run(reh, t, 1.0, voiced=False)
    assert reh.finished
