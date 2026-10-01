package nl.vanvrouwerff.iptv.data.settings

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom

/**
 * Tiny LAN web server so the source can be typed on a phone instead of with the remote.
 * The TV shows a QR code to `http://<tv-ip>:<port>/<token>`; the phone gets a form, and
 * a submit lands in [submission] for the settings screen to pick up. The random token
 * keeps other devices on the network from stumbling onto the form. Runs only while a
 * screen that shows the QR is open (reference counted via [start]/[stop]).
 */
object PhoneSetupServer {

    data class Submission(
        val type: String,
        val m3uUrl: String,
        val host: String,
        val username: String,
        val password: String,
    )

    private val _submission = MutableStateFlow<Submission?>(null)
    /** Latest form sent from a phone; consume with [consume] once applied. */
    val submission: StateFlow<Submission?> = _submission.asStateFlow()

    private var socket: ServerSocket? = null
    private var thread: Thread? = null
    private var users = 0
    private var token: String = ""

    /** Starts (or joins) the server; returns the URL to encode in the QR, or null offline. */
    @Synchronized
    fun start(): String? {
        users++
        val address = lanAddress() ?: return null
        if (socket == null) {
            token = newToken()
            val server = runCatching { ServerSocket(0) }.getOrElse {
                Log.w(TAG, "could not open server socket", it)
                return null
            }
            socket = server
            thread = Thread({ acceptLoop(server) }, "phone-setup").apply {
                isDaemon = true
                start()
            }
        }
        return "http://$address:${socket!!.localPort}/$token"
    }

    @Synchronized
    fun stop() {
        users = (users - 1).coerceAtLeast(0)
        if (users > 0) return
        runCatching { socket?.close() }
        socket = null
        thread = null
    }

    fun consume() {
        _submission.value = null
    }

    val isRunning: Boolean get() = socket != null

    private fun acceptLoop(server: ServerSocket) {
        while (!server.isClosed) {
            val client = runCatching { server.accept() }.getOrNull() ?: break
            runCatching { client.use { handle(it) } }
                .onFailure { Log.w(TAG, "request failed", it) }
        }
    }

    private fun handle(client: Socket) {
        client.soTimeout = 10_000
        val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
        val requestLine = reader.readLine() ?: return
        val parts = requestLine.split(" ")
        val method = parts.getOrNull(0).orEmpty()
        val path = parts.getOrNull(1).orEmpty().substringBefore('?').trim('/')
        var contentLength = 0
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            if (line.startsWith("Content-Length:", ignoreCase = true)) {
                contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
            }
        }
        val out = client.getOutputStream()
        fun respond(status: String, html: String) {
            val bytes = html.toByteArray(Charsets.UTF_8)
            out.write(
                ("HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\n" +
                    "Cache-Control: no-store\r\n" +
                    "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray(),
            )
            out.write(bytes)
            out.flush()
        }
        if (path != token) {
            respond("404 Not Found", page(errorContent("Link nicht mehr gültig", "Öffne den QR-Code auf deinem Fernseher erneut.")))
            return
        }
        when (method) {
            "GET" -> respond("200 OK", page(FORM))
            "POST" -> {
                val body = CharArray(contentLength.coerceIn(0, 16_384))
                var read = 0
                while (read < body.size) {
                    val n = reader.read(body, read, body.size - read)
                    if (n < 0) break
                    read += n
                }
                val fields = parseForm(String(body, 0, read))
                val type = fields["type"].orEmpty().ifBlank { "xtream" }
                val submission = Submission(
                    type = type,
                    m3uUrl = fields["m3u"].orEmpty().trim(),
                    host = fields["host"].orEmpty().trim(),
                    username = fields["username"].orEmpty().trim(),
                    password = fields["password"].orEmpty(),
                )
                val valid = when (type) {
                    "m3u" -> submission.m3uUrl.startsWith("http://") || submission.m3uUrl.startsWith("https://")
                    else -> submission.host.isNotBlank() &&
                        (submission.host.startsWith("http://") || submission.host.startsWith("https://")) &&
                        submission.username.isNotBlank()
                }
                if (!valid) {
                    respond(
                        "400 Bad Request",
                        page(errorContent("Angaben unvollständig", "Bitte gehe zurück und prüfe deine Zugangsdaten.")),
                    )
                    return
                }
                _submission.value = submission
                respond("200 OK", page(SUCCESS))
            }
            else -> respond("405 Method Not Allowed", page(errorContent("Nicht unterstützt", "Diese Anfrage kann nicht verarbeitet werden.")))
        }
    }

    internal fun parseForm(body: String): Map<String, String> = body.split('&')
        .mapNotNull { pair ->
            val key = pair.substringBefore('=', "")
            if (key.isEmpty()) null
            else URLDecoder.decode(key, "UTF-8") to URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
        }
        .toMap()

    private fun lanAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()

    private fun newToken(): String {
        val alphabet = "abcdefghjkmnpqrstuvwxyz23456789"
        val random = SecureRandom()
        return (1..6).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
    }

    private fun errorContent(title: String, body: String) = """
        <main class="card compact">
          <div class="brand"><span class="logo">I</span><span>I-TV</span></div>
          <div class="state-icon error">!</div>
          <h1>$title</h1>
          <p class="lead">$body</p>
        </main>
    """.trimIndent()

    private fun page(content: String) = """
        <!doctype html>
        <html lang="de">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
          <meta name="color-scheme" content="dark">
          <title>I-TV einrichten</title>
          <style>
            :root{--bg:#090a0d;--panel:#111319;--panel2:#171a22;--line:#292d38;--text:#f6f7fb;--muted:#9ca3b4;--accent:#ff3b5c;--accent2:#ff5470;--ok:#4ade80;--danger:#fb7185}
            *{box-sizing:border-box}
            html{background:var(--bg)}
            body{margin:0;min-height:100vh;font-family:Inter,ui-sans-serif,system-ui,-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;background:radial-gradient(circle at 50% -15%,#252936 0,rgba(18,20,27,.7) 28%,var(--bg) 62%);color:var(--text);padding:24px 16px 48px}
            .shell{width:min(100%,520px);margin:0 auto}
            .brand{display:flex;align-items:center;gap:10px;font-weight:800;letter-spacing:-.02em;margin-bottom:26px}
            .logo{width:34px;height:34px;display:grid;place-items:center;border-radius:10px;background:linear-gradient(145deg,var(--accent2),var(--accent));box-shadow:0 10px 30px rgba(255,59,92,.22);font-size:18px}
            .card{background:rgba(17,19,25,.92);border:1px solid var(--line);border-radius:24px;padding:26px;box-shadow:0 28px 80px rgba(0,0,0,.35);backdrop-filter:blur(18px)}
            .card.compact{text-align:center;margin-top:10vh}.compact .brand{justify-content:center}.compact h1{margin-top:8px}
            .eyebrow{display:inline-flex;align-items:center;gap:7px;color:#c9ceda;font-size:13px;font-weight:700;background:#1a1d25;border:1px solid #2c303a;padding:7px 10px;border-radius:999px;margin-bottom:14px}
            .dot{width:7px;height:7px;border-radius:50%;background:var(--ok);box-shadow:0 0 0 4px rgba(74,222,128,.09)}
            h1{font-size:30px;line-height:1.08;letter-spacing:-.04em;margin:0 0 10px}
            .lead{color:var(--muted);line-height:1.55;margin:0 0 24px;font-size:15px}
            .segmented{display:grid;grid-template-columns:1fr 1fr;gap:5px;padding:5px;background:#0c0e12;border:1px solid #232630;border-radius:14px;margin-bottom:22px}
            .segmented label{position:relative;margin:0;cursor:pointer}
            .segmented input{position:absolute;opacity:0;pointer-events:none}
            .segmented span{display:block;text-align:center;padding:11px 8px;border-radius:10px;color:var(--muted);font-size:14px;font-weight:700;transition:.16s ease}
            .segmented input:checked+span{background:var(--panel2);color:var(--text);box-shadow:0 3px 12px rgba(0,0,0,.25)}
            .fields{display:grid;gap:15px}
            .field label{display:block;margin:0 0 7px;color:#c7cbd5;font-size:13px;font-weight:650}
            .input-wrap{position:relative}
            input[type=text],input[type=password],input[type=url]{width:100%;height:50px;border:1px solid #303440;border-radius:12px;background:#0d0f14;color:var(--text);font:inherit;font-size:15px;padding:0 14px;outline:none;transition:border .15s,box-shadow .15s,background .15s}
            input:focus{border-color:#686f80;background:#0f1117;box-shadow:0 0 0 3px rgba(136,145,164,.1)}
            input::placeholder{color:#626978}
            .password-input{padding-right:70px!important}.show-pass{position:absolute;right:8px;top:7px;height:36px;border:0;background:#1b1e27;color:#bbc1cd;border-radius:8px;padding:0 10px;font-weight:700;cursor:pointer}
            .help{font-size:12px;color:#737b8c;margin-top:7px;line-height:1.4}
            .hidden{display:none!important}
            .submit{width:100%;height:52px;border:0;border-radius:13px;background:linear-gradient(135deg,var(--accent2),var(--accent));color:white;font:inherit;font-weight:800;font-size:15px;cursor:pointer;margin-top:22px;box-shadow:0 12px 28px rgba(255,59,92,.2);transition:transform .12s,filter .12s}
            .submit:active{transform:scale(.985)}.submit:hover{filter:brightness(1.06)}
            .security{display:flex;gap:10px;align-items:flex-start;margin-top:17px;color:#788091;font-size:12px;line-height:1.45}.shield{flex:0 0 auto;width:20px;height:20px;border-radius:6px;background:#1a1d25;display:grid;place-items:center;color:#aeb5c4;font-size:11px}
            .state-icon{margin:8px auto 20px;width:62px;height:62px;border-radius:19px;display:grid;place-items:center;font-size:30px;font-weight:900}.state-icon.success{background:rgba(74,222,128,.12);color:var(--ok);border:1px solid rgba(74,222,128,.24)}.state-icon.error{background:rgba(251,113,133,.11);color:var(--danger);border:1px solid rgba(251,113,133,.2)}
            .success-title{text-align:center}.success-text{text-align:center;color:var(--muted);line-height:1.55;margin:0}.success-note{margin-top:22px;padding:14px;border-radius:12px;background:#0d0f14;border:1px solid #252833;color:#a9b0be;font-size:13px;line-height:1.5;text-align:center}
            @media(max-width:430px){body{padding:16px 12px 36px}.card{padding:21px;border-radius:20px}h1{font-size:27px}.brand{margin-bottom:20px}}
          </style>
        </head>
        <body><div class="shell">$content</div></body>
        </html>
    """.trimIndent()

    private val FORM = """
        <div class="brand"><span class="logo">I</span><span>I-TV</span></div>
        <main class="card">
          <div class="eyebrow"><span class="dot"></span>Direkt mit deinem Fernseher verbunden</div>
          <h1>Quelle hinzufügen</h1>
          <p class="lead">Gib deine IPTV-Zugangsdaten bequem am Handy ein. Sie werden direkt an I-TV im selben WLAN gesendet.</p>

          <form method="post" id="sourceForm" novalidate>
            <div class="segmented" role="radiogroup" aria-label="Quellentyp">
              <label><input type="radio" name="type" value="xtream" checked><span>Xtream Codes</span></label>
              <label><input type="radio" name="type" value="m3u"><span>M3U-Playlist</span></label>
            </div>

            <div id="xtreamFields" class="fields">
              <div class="field">
                <label for="host">Server-Adresse</label>
                <input type="url" id="host" name="host" placeholder="http://provider.example:8080" inputmode="url" autocomplete="url" autocapitalize="off" spellcheck="false">
              </div>
              <div class="field">
                <label for="username">Benutzername</label>
                <input type="text" id="username" name="username" autocomplete="username" autocapitalize="off" spellcheck="false">
              </div>
              <div class="field">
                <label for="password">Passwort</label>
                <div class="input-wrap">
                  <input class="password-input" type="password" id="password" name="password" autocomplete="current-password">
                  <button class="show-pass" type="button" id="showPassword">Anzeigen</button>
                </div>
              </div>
            </div>

            <div id="m3uFields" class="fields hidden">
              <div class="field">
                <label for="m3u">M3U-URL</label>
                <input type="url" id="m3u" name="m3u" placeholder="http://provider.example/playlist.m3u" inputmode="url" autocomplete="url" autocapitalize="off" spellcheck="false">
                <div class="help">Füge die vollständige Playlist-Adresse deines Anbieters ein.</div>
              </div>
            </div>

            <button class="submit" type="submit">An I-TV senden</button>
            <div class="security"><span class="shield">✓</span><span>Lokale Übertragung: Diese Seite wird direkt von deinem Fernseher bereitgestellt. Handy und TV müssen sich im selben Netzwerk befinden.</span></div>
          </form>
        </main>
        <script>
          (() => {
            const form = document.getElementById('sourceForm');
            const radios = [...form.querySelectorAll('input[name="type"]')];
            const xtream = document.getElementById('xtreamFields');
            const m3u = document.getElementById('m3uFields');
            const host = document.getElementById('host');
            const user = document.getElementById('username');
            const playlist = document.getElementById('m3u');
            const pass = document.getElementById('password');
            const show = document.getElementById('showPassword');

            function syncType() {
              const type = radios.find(r => r.checked)?.value || 'xtream';
              const isXtream = type === 'xtream';
              xtream.classList.toggle('hidden', !isXtream);
              m3u.classList.toggle('hidden', isXtream);
              host.required = isXtream;
              user.required = isXtream;
              playlist.required = !isXtream;
            }
            radios.forEach(r => r.addEventListener('change', syncType));
            show.addEventListener('click', () => {
              const visible = pass.type === 'text';
              pass.type = visible ? 'password' : 'text';
              show.textContent = visible ? 'Anzeigen' : 'Verbergen';
            });
            form.addEventListener('submit', (event) => {
              syncType();
              const type = radios.find(r => r.checked)?.value || 'xtream';
              const value = type === 'xtream' ? host.value.trim() : playlist.value.trim();
              if (!/^https?:\/\//i.test(value)) {
                event.preventDefault();
                const input = type === 'xtream' ? host : playlist;
                input.setCustomValidity('Bitte eine vollständige Adresse mit http:// oder https:// eingeben.');
                input.reportValidity();
                input.addEventListener('input', () => input.setCustomValidity(''), { once: true });
              } else if (!form.checkValidity()) {
                event.preventDefault();
                form.reportValidity();
              }
            });
            syncType();
          })();
        </script>
    """.trimIndent()

    private val SUCCESS = """
        <main class="card compact">
          <div class="brand"><span class="logo">I</span><span>I-TV</span></div>
          <div class="state-icon success">✓</div>
          <h1 class="success-title">An I-TV gesendet</h1>
          <p class="success-text">Deine Zugangsdaten wurden direkt an den Fernseher übertragen.</p>
          <div class="success-note">Du kannst diese Seite jetzt schließen und am Fernseher fortfahren.</div>
        </main>
    """.trimIndent()

    private const val TAG = "PhoneSetupServer"
}