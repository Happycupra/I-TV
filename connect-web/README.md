# I-TV Connect – lokale Seite

Diese Seite ist ein lokaler Prototyp für den späteren Gerätecode-Flow von I-TV.

## Lokal starten

Im Repository:

```bash
cd connect-web
python3 -m http.server 8080
```

Danach im Browser öffnen:

```text
http://localhost:8080
```

Auf einem anderen Gerät im gleichen Netzwerk kann die Seite über die lokale IP des Rechners geöffnet werden, z. B. `http://192.168.1.20:8080`.

## Demo

Die Seite läuft standardmässig ohne Backend im Demo-Modus.

Gerätecode:

```text
482731
```

Danach kann zwischen Xtream Codes und M3U gewählt werden. Die Verbindungsprüfung und das Senden werden lokal simuliert; eingegebene Zugangsdaten werden nicht gespeichert.

## Vorgesehene Backend-Schnittstellen

Die Seite ist bereits auf folgende API-Struktur vorbereitet:

- `POST /v1/pairing/claim` – Gerätecode einlösen
- `POST /v1/sources/test` – Xtream/M3U prüfen
- `PUT /v1/devices/source` – geprüfte Quelle dem TV-Gerät zuweisen

In `index.html` muss später nur `CONFIG.apiBase` gesetzt werden, z. B.:

```js
apiBase: "https://api.itv.app"
```

Für eine produktive Version müssen Gerätecodes kurzlebig und einmalig sein. Zugangsdaten dürfen nur über HTTPS übertragen und serverseitig verschlüsselt gespeichert werden.
