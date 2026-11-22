"""Normalización de texto en español y comparación con lo que reconoce el micrófono."""
import re
import unicodedata

try:
    from num2words import num2words
except Exception:  # pragma: no cover
    num2words = None

STOP = set("""
a al algo ante antes aqui asi aun bajo bien cada como con contra cual cuando de del desde donde dos e el ella ellas ellos
en entre era es esa ese eso esta este esto estos estas fue ha hace han hasta hay la las le les lo los mas me mi mis muy
nada ni no nos nuestra nuestro nuestros o otra otro para pero poco por porque que quien se sea ser si sin sobre son su
sus tambien te tiene tienen todo todos tu tus un una uno unos unas y ya yo eso esas esos les nosotros ustedes hoy
primero segundo tercero despues ademas entonces luego tambien siempre nunca ahora asimismo finalmente
""".split())


def strip_accents(s: str) -> str:
    return "".join(c for c in unicodedata.normalize("NFD", s) if unicodedata.category(c) != "Mn")


def normalize(s: str) -> str:
    s = strip_accents(s.lower())
    s = s.replace("%", " por ciento ")
    s = re.sub(r"[^a-z0-9ñ ]+", " ", s)
    return re.sub(r"\s+", " ", s).strip()


def words(s: str):
    return normalize(s).split()


def number_forms(token: str, following: str = ""):
    """'1.700' -> {'1700', 'mil setecientos'}; '21' + 'mil' -> también 'veintiun mil'."""
    raw = token.replace(".", "").replace(",", ".")
    forms = set()
    try:
        val = float(raw)
    except ValueError:
        return forms
    n = int(val) if val == int(val) else None
    forms.add(raw.rstrip("0").rstrip(".") if "." in raw else raw)
    if n is not None and num2words:
        try:
            forms.add(normalize(num2words(n, lang="es")))
        except Exception:
            pass
        mult = {"mil": 1000, "millon": 10**6, "millones": 10**6}.get(normalize(following))
        if mult and num2words:
            try:
                big = normalize(num2words(n * mult, lang="es"))
                forms.add(big)
                forms.add(re.sub(r"uno (mil|millones)", r"un \1", big))
                forms.add(re.sub(r"uno$", "un", normalize(num2words(n, lang="es"))) + " " + normalize(following))
            except Exception:
                pass
    return {f for f in forms if f}


def tail_words(text: str, n: int = 6):
    """Últimas palabras con contenido del texto hablado: el 'pie de salida' de cada turno."""
    w = [x for x in words(text) if len(x) > 2 and x not in STOP]
    return w[-n:]


def tail_hit(tail, recent_words, threshold: float = 0.7) -> bool:
    """¿Se dijeron (en orden, con holgura) la mayoría de las palabras finales del turno?"""
    if not tail:
        return False
    window = recent_words[-(len(tail) * 4 + 6):]
    i = 0
    hits = 0
    for t in tail:
        for j in range(i, len(window)):
            if similar(window[j], t):
                hits += 1
                i = j + 1
                break
    return hits / len(tail) >= threshold


def similar(a: str, b: str) -> bool:
    if a == b:
        return True
    if len(a) >= 5 and len(b) >= 5 and a[:5] == b[:5]:
        return True
    return False


def keyword_hit(alts, text_norm: str) -> bool:
    padded = " " + text_norm + " "
    for alt in alts:
        a = normalize(alt)
        if not a:
            continue
        if " " in a:
            if a in text_norm:
                return True
        elif len(a) >= 6:
            # raíz: "hervimos" cuenta para "hervir"
            if re.search(r"\b" + re.escape(a[:5]), text_norm):
                return True
        elif " " + a + " " in padded:
            return True
    return False


def auto_keywords(text: str, max_words: int = 4):
    """Puntos clave automáticos: cifras del guion y palabras con más contenido."""
    kws = []
    toks = re.findall(r"\d[\d.,]*\s*%?|\S+", text)
    for i, tok in enumerate(toks):
        m = re.match(r"(\d[\d.,]*)\s*(%?)", tok)
        if m:
            nxt = toks[i + 1].lower() if i + 1 < len(toks) else ""
            forms = number_forms(m.group(1).rstrip(".,"), nxt)
            if forms:
                nn = normalize(nxt)
                label = m.group(1).rstrip(".,") + (" %" if m.group(2) else (" " + nn if nn in ("mil", "millones", "millon") else ""))
                kws.append((label, sorted(forms)))
    seen = set()
    cands = []
    for m in re.finditer(r"[A-Za-zÁÉÍÓÚÑáéíóúñü]+", text):
        w = m.group(0)
        n = normalize(w)
        if n in STOP or len(n) < 6 or n in seen:
            continue
        before = text[:m.start()].rstrip()
        sentence_start = (not before) or before[-1] in ".:;¿?¡!"
        if sentence_start and len(n) < 9:
            continue
        seen.add(n)
        cands.append((w[0].isupper() and not sentence_start, len(n), w, n))
    cands.sort(key=lambda c: (not c[0], -c[1]))
    for _, _, w, n in cands[:max_words]:
        kws.append((w, [n]))
    # sin duplicados por etiqueta
    out, labels = [], set()
    for lab, alts in kws:
        if lab.lower() in labels:
            continue
        labels.add(lab.lower())
        out.append((lab, alts))
    return out[:8]


def label_keyword(label: str):
    """Punto clave escrito por la persona en el guion: se acepta la frase completa,
    sus cifras en palabras o cualquiera de sus palabras con más contenido."""
    alts = []
    n = normalize(label)
    if n:
        alts.append(n)
    toks = re.findall(r"\d[\d.,]*|[^\s\d]+", label)
    for i, tok in enumerate(toks):
        if re.match(r"^\d", tok):
            nxt = toks[i + 1] if i + 1 < len(toks) else ""
            alts.extend(sorted(number_forms(tok.rstrip(".,"), nxt)))
    for w in n.split():
        if len(w) >= 5 and w not in STOP and w not in alts:
            alts.append(w)
    return (label.strip(), alts or [label])
