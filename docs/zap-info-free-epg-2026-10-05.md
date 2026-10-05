# Umschaltinfo und kostenlose EPG-Quellen

Vorschau `0.1.2-preview`, VersionCode 3; Stand 5. Oktober 2026.

## Kurze Info beim Senderwechsel

Beim Umschalten zeigt die App für vier Sekunden einen kompakten Infoblock mit Sendernummer und Sendername. Wenn passende EPG-Daten vorhanden sind, erscheinen die aktuelle Sendung mit Beginn/Ende und die nächste Sendung. Eine schmale Zeile zeigt benachbarte Sender; Tippen oder OK öffnet die vollständige Senderliste.

Die bisherige Info wurde von der automatisch geöffneten Senderliste verdeckt. Die automatische Vorschau fasst jetzt beide Informationen zusammen. Sie übernimmt keinen Fernbedienungsfokus. Eine manuell geöffnete Liste oder ein anderes manuelles Player-Menü behält Vorrang.

Schnelle Wechsel starten die Anzeigedauer erneut. Die Vier-Sekunden-Frist beginnt sofort beim Wechsel und wartet nicht auf die EPG-Datenbank. Alte oder verspätete Abfragen dürfen weder eine abgelaufene Info öffnen noch Informationen eines früheren Senders einsetzen; auch A → B → A ist abgesichert.

## Externes EPG verwenden

Unter **Einstellungen → Programmübersicht (EPG)** kann eine **Externe EPG-Adresse** eingetragen werden. **EPG synchronisieren** übernimmt die Adresse und lädt die Daten. Unterstützt werden HTTP(S), XMLTV und komprimierte XMLTV.gz-Dateien, sowohl für M3U- als auch Xtream-Senderlisten. Bei einer Adresse ohne Protokoll verwendet die Eingabe HTTPS.

Ein leeres Feld verwendet wieder das Anbieter-EPG beziehungsweise die EPG-Adresse aus der M3U-Liste. Vorher geänderte Senderquellen oder Filter zuerst mit **Speichern** übernehmen. Eine externe Adresse gilt für die aktuelle Anbieterquelle; beim Wechsel des Anbieters wird sie zurückgesetzt. Eine reine Filteränderung behält die Adresse.

Die Senderkennungen im Feed müssen genau zu den EPG-Kennungen der Senderliste passen. Es gibt keine automatische unscharfe Zuordnung nach ähnlich klingenden Namen. Wenn keine Kennungen passen, bleibt der bisherige Programmplan erhalten und die App zeigt einen erklärenden Fehler. Auch Download-, XML- und Abbruchfehler erhalten den vorhandenen Stand. Automatische Aktualisierung, manuelle Synchronisierung und vollständiger Senderimport verwenden dieselbe ausgewählte EPG-Quelle.

## Öffentlich verfügbare kostenlose Quellen

Am 5. Oktober 2026 wurden die folgenden öffentlichen Feeds erfolgreich über HTTP geladen, entpackt und als XML geprüft. EPGShare01 nennt sein Angebot kostenlos; seine nationale Feed-Aufteilung hält Downloads kleiner als der weltweite Gesamtfeed.

| Land | XMLTV.gz-Adresse | Beispiele für Senderkennungen |
| --- | --- | --- |
| Deutschland | https://epgshare01.online/epgshare01/epg_ripper_DE1.xml.gz | `Das.Erste.de`, `ZDF.de` |
| Österreich | https://epgshare01.online/epgshare01/epg_ripper_AT1.xml.gz | `ORF.1.at` |
| Schweiz | https://epgshare01.online/epgshare01/epg_ripper_CH1.xml.gz | `SRF.1.ch` |

Die geprüften Dateien waren etwa 3,6–6,2 MB komprimiert und enthielten Programme bis zum 8./9. Oktober. Abdeckung und Aktualität hängen vom jeweiligen Feed ab. Die App wählt keine externe Quelle automatisch aus.

[EPGShare01 / Informationen](https://epgshare01.online/) · [Feed-Verzeichnis](https://epgshare01.online/epgshare01/) · [Senderkennungen und Hinweise](https://epgshare01.online/epgshare01/0_READ_ME_FIRST_AS_I_CONTAIN_VERY_HELPFUL_INFO.html)

Eine weitere kostenlose Quelle ist [epg.pw](https://epg.pw/xmltv.html), beispielsweise https://epg.pw/xmltv/epg_DE.xml.gz. Dieser Feed verwendet eigene numerische Senderkennungen, etwa `76674` für Das Erste. Er benötigt daher ebenfalls eine dazu passende Senderliste. [iptv-org/epg](https://github.com/iptv-org/epg) bietet Werkzeuge zum eigenen Erzeugen von EPG-Dateien; es wurde hier kein öffentlicher Standard-DACH-Feed daraus angenommen.

## Validierung

Abschlusslauf erfolgreich: `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleRelease --no-daemon --console=plain --max-workers=2`.

- 183 Tests in 31 Testsuiten bestanden; keine Fehler und keine übersprungenen Tests. 20 neue Tests gegenüber der vorherigen EPG-/Smartphone-Vorschau.
- Fünf neue Tests prüfen die Vier-Sekunden-Anzeige, verspätete Metadaten, schnelle Senderwechsel einschließlich A → B → A und den Vorrang manueller Menüs.
- 15 neue Tests prüfen externe XMLTV-Quellen, gzip/HTTP, exakte Senderkennungen, manuelle/Katalog-/304-Aktualisierung, Änderungen der Quelle und Abbruch. Echte Room-/DataStore-Integration sichert Datenbankrollback und den nur für die aktuelle Quelle gesetzten Erfolgszeitstempel ab.
- Lint: 0 Fehler, 92 bereits geprüfte Wartungs-/Stilwarnungen und 8 Hinweise; keine neue Baseline oder Unterdrückung.
- Optimierter Release-Build mit R8 und Ressourcenverkleinerung erfolgreich. `git diff --check` sowie unabhängiges Integrationsreview ohne weiteren konkreten Blocker.

Die APK bleibt eine mit dem vorhandenen Debug-Schlüssel signierte Vorschau. Die Tests ersetzen keinen praktischen Test auf dem tatsächlichen Smartphone/TV und mit den Senderkennungen des eigenen Anbieters.
