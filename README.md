# Ensayo Pitch · Fire Proof

App para Windows y Android para ensayar el pitch del BioBloque. Cada uno elige su perfil, habla frente al PC y al final ve cuánto se demoró en cada parte.

## Descargar

¿Primera vez? Mira el [tutorial con dibujos (PDF)](docs/Tutorial_Ensayo_Pitch.pdf). Ahí está todo, desde la descarga hasta el resultado.

1. Ve a **Releases**, a la derecha de esta página, y baja `EnsayoPitch-Windows.zip` de la versión más nueva.
2. Descomprímelo donde quieras (por ejemplo, en el Escritorio).
3. Abre `EnsayoPitch.exe`, que está dentro de la carpeta.

La primera vez Windows puede mostrar "Windows protegió su PC". Pasa porque la app no tiene firma digital (cuesta plata). Haz clic en **Más información** y después en **Ejecutar de todas formas**.

No necesita internet. El reconocimiento de voz va incluido y funciona sin conexión.

### En Android

1. Desde el teléfono, entra a **Releases** y baja `EnsayoPitch-Android.apk` (unos 85 MB).
2. Ábrelo desde las descargas. Si el teléfono dice que no puede instalar apps de origen desconocido, toca **Configuración** y activa **Permitir de esta fuente** para tu navegador o para Archivos. Después vuelve y toca **Instalar**.
3. Si aparece Play Protect avisando que la app no es conocida, toca **Más detalles** → **Instalar de todas formas**.
4. La primera vez que la abras, acepta el permiso del micrófono. El reconocimiento de voz tarda unos segundos en prepararse solo esa vez.

Las versiones nuevas se instalan encima de la anterior y no se pierde el historial.

## Cómo se usa

**1. Elige tu perfil.** A la izquierda salen los seis integrantes y la opción "Todo el equipo". Al elegirte ves tus láminas, cuánto tiempo te toca, quién habla antes que tú, los puntos que no te puedes saltar y la frase con la que cierras cada parte.

**2. Elige cómo ensayar.**

- **Ensayo del equipo:** todos hablan frente al mismo PC. El reloj no se detiene en ningún momento; cada segundo se le carga a quien está en pantalla, así que al final queda el tiempo real de cada uno.
- **Ensayo individual:** tú dices tus partes y el PC lee las de los demás con voz de Windows. Sirve para practicar solo y acostumbrarte a entrar justo cuando te toca.

**3. Habla.** La app pasa sola a la siguiente parte cuando reconoce la frase final del turno. Si improvisas o el PC no te entiende, usa el teclado:

| Tecla | Qué hace |
|---|---|
| → · Espacio · AvPág | siguiente parte |
| ← · RePág | repetir la parte anterior |
| Esc | terminar y ver el resultado |

Los punteros láser para presentaciones usan AvPág y RePág, así que también sirven.

**4. Revisa el resultado.** Sale el tiempo total comparado con la meta (entre 4:30 y 5:05), el tiempo de cada integrante, los silencios largos y los puntos clave que el PC no escuchó. Cada ensayo se guarda en `Documentos\EnsayoPitch\Informes` en HTML (se abre en el navegador) y en CSV (se abre en Excel). La app recuerda tus ensayos anteriores para que veas si vas mejorando.

## Otra presentación

Con **Cambiar presentación…** puedes cargar cualquier `.pptx`. Lo único que necesita es que las notas del orador de cada lámina empiecen así:

```
NOMBRE (25 s). Lo que se dice en esa lámina...
```

Las láminas sin ese formato, como los créditos, se saltan. Si tienes PowerPoint instalado, la app saca las imágenes de las láminas; si no, muestra el título.

## Consejos

- Usa un micrófono de audífonos si el PC está en una sala con ruido.
- Al empezar, la app escucha un segundo para medir el ruido de la sala. Quédense callados durante la cuenta regresiva.
- El reconocimiento de voz no es perfecto. Toma los puntos clave como una pista, no como una nota.

## Para quien quiera mirar el código

Está hecha en Python con PySide6 (Qt), Vosk para el reconocimiento de voz sin conexión y sounddevice para el micrófono. GitHub Actions arma el `.exe` cada vez que se sube un cambio a `main`.

```
pip install -r requirements.txt
python run.py
```

Para que reconozca palabras al correrla desde el código, descarga `vosk-model-small-es-0.42` desde alphacephei.com/vosk/models y descomprímelo en una carpeta `model` junto a `run.py`.
