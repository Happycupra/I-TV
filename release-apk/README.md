# I-TV APK

Dieser Ordner enthält die automatisch gebaute, installierbare APK von I-TV.

Die Datei `I-TV-release.apk` wird von GitHub Actions nach einem erfolgreichen Test- und Release-Build aktualisiert. `SHA256SUMS.txt` enthält die zugehörige SHA-256-Prüfsumme.

Hinweis: Solange kein eigener Release-Keystore in `local.properties` bzw. als CI-Secret hinterlegt ist, verwendet das Projekt laut bestehender Build-Konfiguration die Debug-Signatur als Fallback. Die APK ist damit zum Testen/Sideloading geeignet, aber noch nicht für eine finale Store-Veröffentlichung signiert.
