"""Informe del ensayo en HTML (para abrir en el navegador) y CSV (para Excel)."""
import csv
import datetime as dt
import html
import os

from .session import TOTAL_MAX, TOTAL_MIN, diff_label, fmt

VERDICT = {"ok": ("Dentro del tiempo", "#2f7d4f"),
           "corto": ("Quedó corto", "#b7791f"),
           "largo": ("Se pasaron del tiempo", "#b23a2a")}


def _color(real, target):
    d = real - target
    if abs(d) <= max(3, 0.12 * target):
        return "#2f7d4f"
    return "#b23a2a" if d > 0 else "#b7791f"


def write_report(summary: dict, outdir: str) -> str:
    stamp = dt.datetime.now().strftime("%Y-%m-%d_%H-%M")
    who = summary.get("profile") or "Equipo"
    base = f"Ensayo_{stamp}_{who.replace(' ', '_')}"
    html_path = os.path.join(outdir, base + ".html")
    csv_path = os.path.join(outdir, base + ".csv")

    with open(csv_path, "w", newline="", encoding="utf-8-sig") as f:
        w = csv.writer(f, delimiter=";")
        w.writerow(["Lámina", "Orador", "Meta (s)", "Real (s)", "Diferencia", "Con voz (s)",
                    "Silencio más largo (s)", "Puntos dichos", "Puntos que faltaron", "Leído por el PC", "Cómo terminó"])
        for s in summary["sections"]:
            w.writerow([s["slide"], s["speaker"], s["target"], s["real"], diff_label(s["real"], s["target"]),
                        s["voiced"], s["longest_silence"], ", ".join(s["keywords_hit"]),
                        ", ".join(s["keywords_missed"]), "sí" if s["machine"] else "no", s["ended"]])
        w.writerow([])
        w.writerow(["Total", "", summary["target_total"], summary["total"], diff_label(summary["total"], summary["target_total"])])

    vtxt, vcol = VERDICT[summary["verdict"]]
    prof = summary.get("profile")
    mode = "Ensayo individual" if summary["mode"] == "solo" else "Ensayo del equipo"
    esc = html.escape

    sp_rows = []
    for d in summary["speakers"]:
        mine = prof and d["speaker"] == prof
        kw = f'{d["kw_hit"]}/{d["kw_total"]}' if not d["machine"] else "—"
        real = fmt(d["real"])
        sp_rows.append(
            f'<tr class="{"mine" if mine else ""}"><td>{esc(d["speaker"])}{" (tú)" if mine else ""}'
            f'{" · leído por el PC" if d["machine"] else ""}</td><td>{fmt(d["target"])}</td>'
            f'<td style="color:{_color(d["real"], d["target"])};font-weight:600">{real}</td>'
            f'<td>{diff_label(d["real"], d["target"])}</td><td>{kw}</td></tr>')

    sec_rows = []
    for s in summary["sections"]:
        mine = prof and s["speaker"] == prof
        miss = ", ".join(esc(k) for k in s["keywords_missed"]) or "—"
        if s["machine"]:
            miss = "—"
        voiced = "—" if s["machine"] else "%.0f s" % s["voiced"]
        sil = "—" if s["machine"] else "%.0f s" % s["longest_silence"]
        sec_rows.append(
            f'<tr class="{"mine" if mine else ""}"><td>{s["slide"]}</td><td>{esc(s["speaker"])}</td>'
            f'<td>{s["target"]} s</td><td style="color:{_color(s["real"], s["target"])};font-weight:600">{s["real"]:.0f} s</td>'
            f'<td>{diff_label(s["real"], s["target"])}</td><td>{voiced}</td>'
            f'<td>{sil}</td><td>{miss}</td></tr>')

    tips = []
    for s in summary["sections"]:
        if s["machine"] or (prof and s["speaker"] != prof):
            continue
        d = s["real"] - s["target"]
        if d > max(4, 0.2 * s["target"]):
            tips.append(f'Lámina {s["slide"]} ({esc(s["speaker"])}): te pasaste {d:.0f} s. Recorta una frase o dila más directo.')
        elif d < -max(4, 0.25 * s["target"]):
            tips.append(f'Lámina {s["slide"]} ({esc(s["speaker"])}): te sobraron {-d:.0f} s. Puede que te hayas saltado algo o que vayas muy rápido.')
        if s["keywords_missed"] and len(s["keywords_missed"]) >= max(2, len(s["keywords_missed"]) + len(s["keywords_hit"]) // 2):
            tips.append(f'Lámina {s["slide"]}: el PC no escuchó {", ".join(esc(k) for k in s["keywords_missed"])}.')
        if s["longest_silence"] >= 4:
            tips.append(f'Lámina {s["slide"]}: hubo un silencio de {s["longest_silence"]:.0f} s.')
    tips_html = "".join(f"<li>{t}</li>" for t in tips) or "<li>Nada que marcar en tus partes. Bien ahí.</li>"

    page = f"""<!doctype html><html lang="es"><head><meta charset="utf-8">
<title>Informe de ensayo · {esc(who)}</title>
<style>
body{{font-family:Segoe UI,Arial,sans-serif;background:#f4f1ec;color:#2b2722;margin:0;padding:32px}}
.wrap{{max-width:980px;margin:auto}} h1{{margin:0 0 4px;font-size:26px}} .sub{{color:#6b625a;margin-bottom:22px}}
.big{{display:flex;gap:16px;flex-wrap:wrap;margin-bottom:22px}}
.card{{background:#fff;border-radius:10px;padding:16px 20px;box-shadow:0 1px 3px #0001;min-width:180px}}
.card b{{display:block;font-size:30px}} table{{width:100%;border-collapse:collapse;background:#fff;border-radius:10px;overflow:hidden;margin-bottom:24px}}
th,td{{padding:8px 10px;text-align:left;border-bottom:1px solid #eee;font-size:14px}} th{{background:#3a342e;color:#fff;font-weight:600}}
tr.mine td{{background:#fff6e5}} h2{{font-size:18px;margin:24px 0 10px}} li{{margin:4px 0}}
</style></head><body><div class="wrap">
<h1>Informe de ensayo · {esc(who)}</h1>
<div class="sub">{mode} · {dt.datetime.now().strftime("%d-%m-%Y %H:%M")} · meta {fmt(TOTAL_MIN)}–{fmt(TOTAL_MAX)}</div>
<div class="big">
<div class="card">Tiempo total<b style="color:{vcol}">{fmt(summary["total"])}</b>{vtxt}</div>
<div class="card">Meta del guion<b>{fmt(summary["target_total"])}</b>suma de las notas</div>
</div>
<h2>{"Para ti" if prof else "Para mejorar"}</h2><ul>{tips_html}</ul>
<h2>Por integrante</h2>
<table><tr><th>Integrante</th><th>Meta</th><th>Real</th><th>Diferencia</th><th>Puntos clave</th></tr>{"".join(sp_rows)}</table>
<h2>Por lámina</h2>
<table><tr><th>#</th><th>Orador</th><th>Meta</th><th>Real</th><th>Dif.</th><th>Con voz</th><th>Silencio máx.</th><th>Puntos que faltaron</th></tr>{"".join(sec_rows)}</table>
<p class="sub">Los puntos clave salen de lo que reconoce el micrófono, así que pueden fallar con ruido o si hablas muy rápido. Tómalos como pista, no como nota.</p>
</div></body></html>"""
    with open(html_path, "w", encoding="utf-8") as f:
        f.write(page)
    return html_path
