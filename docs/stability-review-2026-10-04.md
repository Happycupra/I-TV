# Stabilitätsprüfung, EPG und Smartphone-Bedienung

Stand: 4. Oktober 2026. Vorschauversion `0.1.1-preview` (VersionCode 2).

Diese Prüfung umfasst Quellcode-Review, automatisierte Regressionstests und einen optimierten APK-Build. Stabilität hat Vorrang: Anbieterfehler sollen vorhandene Daten erhalten, abgebrochene Aufgaben dürfen keine neuere Auswahl überschreiben und Zusatzdaten dürfen die Wiedergabe nicht beenden.

## Befunde und Korrekturen

| Bereich | Befund | Korrektur |
| --- | --- | --- |
| Smartphone-Touch | TV-Komponenten reagierten auf Fernbedienungstasten, aber nicht zuverlässig auf Fingertipps. Kleine Ziele erschwerten die Bedienung. | Gemeinsame Touch-Komponenten unterstützen Tippen und langes Drücken. Tasten und anklickbare Flächen erhalten auf Smartphones mindestens 56 × 56 dp. Scrollen bricht einen begonnenen Tipp ab; deaktivierte Tasten bleiben deaktiviert. |
| Kleine und gedrehte Bildschirme | Ein breites Smartphone im Querformat konnte das geräumige TV-Layout erhalten. Aktionen, PIN-Eingabe und Fehlermeldungen konnten aus dem sichtbaren Bereich rutschen. | Kompaktes Layout berücksichtigt auch geringe Bildschirmhöhe. Aktionen umbrechen oder scrollen; PIN-Eingabe, Einstellungen, Profile und Player-Fehlerdialoge bleiben erreichbar. Eingabeansichten berücksichtigen die Bildschirmtastatur. |
| Programmliste beim Umschalten | Die Liste musste manuell geöffnet werden. | Ein Live-TV-Senderwechsel zeigt vier Sekunden lang eine Vorschau mit dem aktuellen Sender. Weitere Umschaltbefehle funktionieren direkt; eine manuell geöffnete Liste bleibt offen. |
| EPG-Quellen | M3U-EPG-Adressen und komprimierte XMLTV-Dateien wurden nicht vollständig unterstützt. | Auswertung von `url-tvg`, `x-tvg-url` und `tvg-url`, relativen Adressen und mehreren Feeds. Gzip wird auch ohne passenden HTTP-Header erkannt. Xtream-EPG bleibt unterstützt. |
| EPG-Aktualisierung | EPG-Erfolg und fehlende oder fehlerhafte Programmdaten waren schwer unterscheidbar. Offene Ansichten konnten veraltet bleiben. | Manueller Synchronisieren-Knopf in Einstellungen und Guide, Anzeige des letzten Erfolgs und eines verständlichen Fehlers. Guide und Player reagieren auf erfolgreiche Aktualisierungen. Eine eigene Hintergrundaufgabe prüft im Abstand von sechs Stunden; beim Öffnen des Guides werden veraltete Daten ebenfalls aktualisiert. |
| Fehlerhafte EPG-Antworten | Leere, unpassende oder abgebrochene Antworten dürfen den bisherigen Programmplan nicht löschen. | Erst vollständige erfolgreiche Antworten mit passenden Senderkennungen ersetzen die EPG-Daten. Bei Fehlern bleiben Programme und der letzte erfolgreiche Zeitstempel erhalten. HTTP-Abbruch ist auch während blockierter Body-Lesevorgänge wirksam. XMLTV-Zeitstempel werden streng geprüft. |
| Gleichzeitige Aktualisierung und Quellenwechsel | Alte Downloads oder parallele Aufrufe konnten mit einer neuen Konfiguration kollidieren. | Gemeinsame Schreibsperre für Katalog und EPG; parallele Aufrufer erhalten das tatsächliche Ergebnis. Quellen- und Filteränderungen werden geprüft. Quelle und Filter werden gemeinsam gespeichert. Absichtlich geänderte Filter dürfen einen kleineren Katalog liefern; der Schutz vor unerwartet kleinen Antworten bleibt beim App-Upgrade erhalten. |
| Fließender Senderwechsel | Jede Settings-Schreiboperation konnte eine unnötige EPG-Neuabfrage und Cache-Löschung auslösen. | Der Player beobachtet nur tatsächlich geänderte EPG-Zeitstempel. Gruppen-EPG wird kurz zwischengespeichert und außerhalb des UI-Threads verarbeitet. |
| Wiedergabefehler und Lebenszyklus | Veraltete Player-Rückmeldungen, offene Aufgaben und endlose Wiederholungen konnten neue Wiedergabe stören. | Alte Rückmeldungen werden verworfen, Aufgaben beim Stoppen beendet und Fortschritt dem richtigen Medium zugeordnet. Vorübergehende Fehler einschließlich eines verlorenen Live-Zeitfensters verwenden ein begrenztes Wiederholungsbudget. Der Pausenzustand bleibt bei Rückkehr in die App erhalten. |
| Große Serienlisten | Große Episodenlisten konnten die Android-Grenze für Intent-Daten überschreiten. | Kleine Listen bleiben direkt im Intent; große Listen werden über eine begrenzte Datei im privaten App-Cache übergeben. Referenzen, Dateigröße, beschädigte Dateien und Ablaufzeit werden geprüft. Die Liste bleibt für Activity-Neuerstellung lesbar. |
| Serien- und Film-Metadaten | Abgebrochene alte Ladevorgänge und veraltete Suchindizes konnten Ergebnisse einer neuen Auswahl überschreiben. Cache-Schlüssel konnten zwischen Anbieterzugängen kollidieren. | Coroutine-Abbruch wird weitergereicht; Anbieterzugänge erhalten getrennte Serien-Cache-Schlüssel. Invalidierte Suchindizes können durch einen alten Aufbau nicht erneut veröffentlicht werden. Titeländerungen bei gleichbleibender Kataloggröße werden berücksichtigt. |
| Optionale Daten und Sprache | Fehler in Verlauf, Fortschritt, Erinnerungen oder Guide konnten unnötig durchschlagen. Einzelne UI-Texte und Datumsformate reagierten nicht auf Sprachwechsel. | Optionale Datenzugriffe sind abgefangen, Coroutine-Abbruch bleibt erhalten. Compose-Ressourcen und beobachtbare Locale werden verwendet. Player-Fehlertexte werden vor der Anzeige bereinigt. |

## EPG verwenden

`Einstellungen → Programmübersicht (EPG) → EPG synchronisieren`, alternativ der gleichnamige Knopf im Guide. Der letzte erfolgreiche Stand erscheint neben der Aktion. Eine manuelle Synchronisierung erzwingt die Abfrage; automatische Aufrufe vermeiden unnötige Downloads bei Daten, die jünger als sechs Stunden sind. Android kann Hintergrundaufgaben verzögern.

EPG benötigt Programmdaten des Anbieters und passende Senderkennungen. Bei M3U muss die Liste eine unterstützte EPG-Adresse enthalten; bei Xtream wird der XMLTV-Endpunkt verwendet. Die App kann keine fehlenden Sendungsinformationen erzeugen. Fehlerhafte neue Daten ersetzen den vorhandenen Stand nicht.

## Automatisierte Validierung

Abschlusslauf erfolgreich: `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleRelease --no-daemon --console=plain --max-workers=2`.

- 163 Tests in 29 Testsuiten bestanden; keine Fehler und keine übersprungenen Tests. Gegenüber der vorherigen Touch-Vorschau kamen 48 Tests hinzu.
- Lint: 0 Fehler, 92 Warnungen und 8 Hinweise. Die Warnungen betreffen überwiegend ungenutzte Ressourcen, verfügbare Werkzeug-/Bibliotheksupdates und Stilfragen; sie wurden auf konkrete Stabilitätsrisiken geprüft. Es wurde keine Lint-Baseline oder neue Unterdrückung eingeführt.
- Optimierter Release-Build mit R8 und Ressourcenverkleinerung erfolgreich; APK-Signaturprüfung mit `apksigner verify --verbose` bestanden.
- APK-Metadaten geprüft: `nl.vanvrouwerff.iptv`, Version `0.1.1-preview`, VersionCode 2, Android 9/API 28 oder neuer; ARM und x86 jeweils mit 32-/64-Bit-Bibliotheken.
- `git diff --check` bestanden; unabhängiges abschließendes Review ohne weiteren konkreten Integrationsblocker.

Die Tests umfassen echte Room-/DataStore-Zustandswechsel unter Robolectric, lokale HTTP-Antworten über MockWebServer und Compose-Touch-Ereignisse. Besonders abgesichert sind:

- Erhalt vorhandener EPG-Daten und Erfolgszeitstempel bei leeren oder fehlgeschlagenen Antworten.
- Parallele Aktualisierungen, Quellenwechsel, atomisches Speichern und Katalogschutz beim Upgrade.
- Relative M3U-EPG-Adressen, Gzip, strikte XMLTV-Zeitstempel und Abbruch während verzögerter HTTP-Body-Übertragung.
- 56-dp-Touch-Ziele einschließlich Randtreffern, Smartphone-Querformat, Scrollabbruch, deaktivierte Aktionen und weiterhin genau eine Aktion pro D-Pad-Enter.
- Scrollbare PIN-Eingabe und erreichbare Beenden-Taste im Player bei 320 × 240 dp und langer Fehlermeldung.
- Große Episodenlisten, erneutes Lesen nach Activity-Neuerstellung, ungültige Referenzen, beschädigte und zu große Dateien sowie abgelaufene Cache-Dateien.
- Coroutine-Abbruch, getrennte Anbieter-Caches, Invalidierung von Suchindizes und begrenzte Wiedergabe-Wiederholungen.

## Noch am Gerät zu prüfen

Die Tests simulieren Android 34. Hier stand kein echtes Smartphone, keine TV-Box und kein persönlicher Anbieterzugang zur Verfügung. Bediengeschwindigkeit, Bildwiedergabe und mehrstündige Stabilität sind daher noch nicht am Gerät gemessen.

Für den Praxistest: Smartphone hochkant und quer mit geöffneter Tastatur bedienen; wiederholt schnell Sender wechseln; Wiedergabe pausieren und App verlassen/zurückkehren; Netzwerk unterbrechen und wiederherstellen; EPG-Ergebnis mit dem Anbieter vergleichen; auf der TV-Box D-Pad-Fokus prüfen und Live-TV länger laufen lassen.

Die APK ist eine installierbare Vorschau, mit dem vorhandenen Debug-Schlüssel signiert. Ein Update über eine anders signierte Installation kann Android ablehnen.
