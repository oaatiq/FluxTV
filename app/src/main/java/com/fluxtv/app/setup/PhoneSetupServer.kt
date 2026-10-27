package com.fluxtv.app.setup

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.provider.Settings
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.NumberFormat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.concurrent.thread

/**
 * The web page a phone opens from the QR code in Settings → Playlists → Add channels using your
 * phone, so that a playlist link never has to be typed with the remote. Any phone browser will do:
 * there is nothing to install.
 *
 * It runs on the TV itself, on the home network, and only for as long as the administrator keeps that
 * screen open: Settings is behind the deliberate gesture and the optional PIN, so that screen is the
 * setup session. Every address it answers starts with a random [token] that only the QR code carries,
 * so only a phone that scanned the TV's screen can send it anything, and a new session has a new one.
 * The phone gets plain HTML (no script), which every browser shows the same way.
 *
 * [submit] is called on a server thread with the link and name the phone sent; false means the TV is
 * still busy with the last one. [status] is what the phone is shown afterwards.
 */
class PhoneSetupServer(
    private val tvName: String,
    private val status: () -> AddStatus?,
    private val submit: (link: String, name: String) -> Boolean
) {
    val token: String = randomToken()

    /** Four digits shown on the TV and on the phone page, so the administrator can tell TVs apart. */
    val tvCode: String = (SecureRandom().nextInt(9000) + 1000).toString()

    private var serverSocket: ServerSocket? = null
    private var stopped = false
    private val workers: ExecutorService = Executors.newFixedThreadPool(MAX_CONNECTIONS)

    // What the phone sent last, put back in the form when it did not work.
    @Volatile private var lastLink = ""
    @Volatile private var lastName = ""

    /**
     * Opens the server and returns its port, or null if no port could be opened or the session has
     * already ended. Blocks briefly, and a [stop] that comes meanwhile waits for it, so a session
     * ended while it was starting never leaves the server running.
     */
    @Synchronized
    fun start(): Int? {
        if (stopped) return null
        val socket = open(PREFERRED_PORT) ?: open(0) ?: return null
        serverSocket = socket
        thread(name = "FluxTV phone setup", isDaemon = true) { acceptLoop(socket) }
        return socket.localPort
    }

    /** Ends the session: the page and every address under the token stop answering. */
    @Synchronized
    fun stop() {
        stopped = true
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        workers.shutdownNow()
    }

    private fun open(port: Int): ServerSocket? = try {
        ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(port), BACKLOG)
        }
    } catch (_: Exception) {
        null
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (_: Exception) {
                break // closed by stop()
            }
            try {
                workers.execute { handle(client) }
            } catch (_: RejectedExecutionException) {
                closeQuietly(client)
            }
        }
    }

    // ---- HTTP ----

    private class Request(val method: String, val path: String, val body: String)

    private class Response(val code: Int, val html: String = "", val location: String? = null)

    private fun handle(client: Socket) {
        try {
            client.soTimeout = READ_TIMEOUT_MS
            val input = client.getInputStream().buffered()
            val response = when (val request = read(input)) {
                null -> Response(400, page(null, "<p>The TV did not understand the phone's request.</p>"))
                else -> route(request)
            }
            write(client.getOutputStream(), response)
        } catch (_: Exception) {
            // A phone that went away mid-request, or a read timeout: nothing to answer.
        } finally {
            closeQuietly(client)
        }
    }

    /** Reads one request, or null when it is malformed or larger than a playlist link needs. */
    private fun read(input: InputStream): Request? {
        val requestLine = readLine(input) ?: return null
        val parts = requestLine.split(' ')
        if (parts.size < 3) return null
        var contentLength = 0
        var headerBytes = 0
        while (true) {
            val line = readLine(input) ?: return null
            if (line.isEmpty()) break
            headerBytes += line.length
            if (headerBytes > MAX_HEADER_BYTES) return null
            val colon = line.indexOf(':')
            if (colon > 0 && line.substring(0, colon).trim().equals("Content-Length", ignoreCase = true)) {
                contentLength = line.substring(colon + 1).trim().toIntOrNull() ?: return null
            }
        }
        if (contentLength !in 0..MAX_BODY_BYTES) return null
        val body = ByteArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val n = input.read(body, read, contentLength - read)
            if (n < 0) return null
            read += n
        }
        return Request(parts[0].uppercase(), parts[1].substringBefore('?'), String(body, Charsets.UTF_8))
    }

    /** One line without its line ending, or null at the end of the stream or past [MAX_HEADER_BYTES]. */
    private fun readLine(input: InputStream): String? {
        val line = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return null
            if (b == '\n'.code) return line.toString().removeSuffix("\r")
            line.append(b.toChar())
            if (line.length > MAX_HEADER_BYTES) return null
        }
    }

    private fun route(request: Request): Response {
        val segments = request.path.trim('/').split('/')
        if (!sameToken(segments[0])) return Response(404, page(null, EXPIRED, showTv = false))
        val home = "/$token"
        return when (segments.getOrNull(1).orEmpty()) {
            "" -> Response(200, page(null, form()))
            "send" -> if (request.method == "POST") send(request.body) else Response(303, location = home)
            "status" -> statusPage()
            else -> Response(303, location = home)
        }
    }

    private fun send(body: String): Response {
        val form = parseForm(body) ?: return Response(400, page(null, form()))
        // A pasted link can bring a line break or spaces along; a link has none of its own.
        val link = form["link"].orEmpty().filterNot { it.isWhitespace() }
        val name = form["name"].orEmpty().trim().take(MAX_NAME_LENGTH)
        if (link.isEmpty()) {
            return Response(200, page(failed("Paste the playlist link first."), form(link, name)))
        }
        // Still busy with the last link, the TV takes no other: the phone is shown how that one goes.
        if (submit(link, name)) {
            lastLink = link
            lastName = name
        }
        return Response(303, location = "/$token/status")
    }

    private fun statusPage(): Response = when (val s = status()) {
        null -> Response(303, location = "/$token")
        AddStatus.Checking -> Response(
            200,
            page(
                box(
                    "Checking the playlist…",
                    "The TV is opening the link to see that it works. A large playlist can take a minute or two.",
                    style = ""
                ),
                "",
                refreshSeconds = 2
            )
        )
        is AddStatus.Added -> Response(
            200,
            if (s.inUse) {
                page(
                    box(
                        "Done",
                        "The TV now shows ${channels(s.count)} from “${esc(s.name)}”. " +
                            "You can put your phone away: the TV works without it.",
                        style = "ok"
                    ),
                    ANOTHER
                )
            } else {
                page(
                    box(
                        "Saved on the TV",
                        "“${esc(s.name)}” (${channels(s.count)}) is saved on the TV, but not in use yet. " +
                            "To watch it, choose <strong>Use this playlist</strong> on the TV.",
                        style = "ok"
                    ),
                    ANOTHER
                )
            }
        )
        is AddStatus.AlreadySaved -> Response(
            200,
            page(box("Already on the TV", "This TV already has this playlist, saved as “${esc(s.name)}”.", "ok"), ANOTHER)
        )
        is AddStatus.Failed -> Response(200, page(failed(s.message), form(lastLink, lastName)))
    }

    private fun write(out: OutputStream, r: Response) {
        val body = r.html.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 ${r.code} ${reasonPhrase(r.code)}\r\n")
            if (body.isNotEmpty()) append("Content-Type: text/html; charset=utf-8\r\n")
            r.location?.let { append("Location: $it\r\n") }
            append("Content-Length: ${body.size}\r\n")
            // The address carries the token: it is not cached, framed or passed on to other sites.
            append("Cache-Control: no-store\r\n")
            append("Referrer-Policy: no-referrer\r\n")
            append("X-Frame-Options: DENY\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.write(body)
        out.flush()
    }

    private fun sameToken(candidate: String): Boolean =
        MessageDigest.isEqual(candidate.toByteArray(), token.toByteArray())

    // ---- the page ----

    private fun page(status: String?, content: String, refreshSeconds: Int = 0, showTv: Boolean = true): String = """
        <!doctype html>
        <html lang="en"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <meta name="referrer" content="no-referrer">
        ${if (refreshSeconds > 0) "<meta http-equiv=\"refresh\" content=\"$refreshSeconds;url=/$token/status\">" else ""}
        <title>Add channels to FluxTV</title>
        <style>$CSS</style></head>
        <body><main>
        <h1>Add channels to FluxTV</h1>
        ${if (showTv) tvBlock() else ""}
        ${status.orEmpty()}
        $content
        </main></body></html>
    """

    private fun tvBlock() = "<p class=\"tv\">Sending to <strong>${esc(tvName)}</strong><br>" +
        "TV code <strong>$tvCode</strong>: the TV screen shows the same code.</p>"

    private fun form(link: String = "", name: String = ""): String = """
        <form method="post" action="/$token/send" accept-charset="utf-8">
        <label for="link">Playlist link</label>
        <textarea id="link" name="link" inputmode="url" autocapitalize="off" autocomplete="off" autocorrect="off" spellcheck="false" required placeholder="https://…">${esc(link)}</textarea>
        <p class="hint">Paste the M3U link from your TV provider.</p>
        <label for="name">Name <span class="opt">(optional)</span></label>
        <input id="name" name="name" maxlength="$MAX_NAME_LENGTH" autocomplete="off" placeholder="For example: Sports package" value="${esc(name)}">
        <p class="hint">Shown only in the TV's Settings, to tell playlists apart.</p>
        <button type="submit">Send to TV</button>
        </form>
    """.trimIndent()

    private fun failed(message: String) =
        box("Not added", "${esc(message)} Nothing on the TV was changed.", style = "bad")

    private fun box(title: String, html: String, style: String) =
        "<div class=\"status $style\"><h2>$title</h2><p>$html</p></div>"

    private fun channels(count: Int) =
        if (count == 1) "1 channel" else "${NumberFormat.getIntegerInstance().format(count)} channels"

    private companion object {
        /** Tried first so the address stays the same between sessions; any free port otherwise. */
        const val PREFERRED_PORT = 8790
        const val BACKLOG = 8
        const val MAX_CONNECTIONS = 4
        const val READ_TIMEOUT_MS = 10_000
        const val MAX_HEADER_BYTES = 8 * 1024

        /** A playlist link with its user name and password runs to a few hundred characters. */
        const val MAX_BODY_BYTES = 16 * 1024
        const val MAX_NAME_LENGTH = 60

        const val EXPIRED = "<div class=\"status bad\"><h2>This link no longer works</h2>" +
            "<p>Phone setup ends when the TV leaves its screen. On the TV, choose " +
            "<strong>Add channels using your phone</strong> again and scan the new code.</p></div>"

        const val ANOTHER = "<p><a href=\"./\">Add another playlist</a></p>"

        const val CSS =
            ":root{color-scheme:light dark;--bg:#f3f5f8;--card:#fff;--text:#14181f;--muted:#4d5866;" +
                "--accent:#0a6fae;--on-accent:#fff;--ok:#16723a;--bad:#b3261e;--border:#c6cfda}" +
                "@media (prefers-color-scheme:dark){:root{--bg:#0b0f16;--card:#151c27;--text:#f2f4f7;" +
                "--muted:#b0bac6;--accent:#2ec4f3;--on-accent:#00131c;--ok:#62d38f;--bad:#ff8a80;--border:#2e3a4b}}" +
                "*{box-sizing:border-box}" +
                "body{margin:0;background:var(--bg);color:var(--text);" +
                "font:18px/1.45 system-ui,-apple-system,'Segoe UI',Roboto,sans-serif}" +
                "main{max-width:34rem;margin:0 auto;padding:24px 16px 40px}" +
                "h1{font-size:1.6rem;line-height:1.2;margin:0 0 14px}" +
                ".tv{background:var(--card);border:1px solid var(--border);border-radius:12px;padding:12px 16px;margin:0 0 20px}" +
                "label{display:block;font-weight:600;margin:18px 0 6px}" +
                ".opt{font-weight:400;color:var(--muted)}" +
                "textarea,input{display:block;width:100%;font:inherit;padding:12px;border:2px solid var(--border);" +
                "border-radius:10px;background:var(--card);color:var(--text)}" +
                "textarea{min-height:7rem;resize:vertical;word-break:break-all}" +
                "textarea:focus,input:focus{outline:none;border-color:var(--accent)}" +
                ".hint{color:var(--muted);font-size:.95rem;margin:6px 0 0}" +
                "button{display:block;width:100%;margin:26px 0 0;padding:16px;font:inherit;font-weight:700;" +
                "font-size:1.15rem;border:0;border-radius:12px;background:var(--accent);color:var(--on-accent)}" +
                ".status{border:2px solid var(--border);border-radius:12px;background:var(--card);padding:14px 16px;margin:0 0 20px}" +
                ".status h2{font-size:1.25rem;margin:0 0 6px}.status p{margin:0}" +
                ".ok{border-color:var(--ok)}.ok h2{color:var(--ok)}" +
                ".bad{border-color:var(--bad)}.bad h2{color:var(--bad)}" +
                "a{color:var(--accent);font-weight:600}"

        private const val TOKEN_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"

        /** 12 characters of 31: about 59 bits, for a session that lasts minutes. */
        fun randomToken(): String {
            val random = SecureRandom()
            return String(CharArray(12) { TOKEN_ALPHABET[random.nextInt(TOKEN_ALPHABET.length)] })
        }

        fun parseForm(body: String): Map<String, String>? = try {
            body.split('&').filter { it.isNotEmpty() }.associate {
                URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
            }
        } catch (_: IllegalArgumentException) {
            null
        }

        fun esc(s: String): String = buildString(s.length) {
            for (c in s) {
                when (c) {
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '&' -> append("&amp;")
                    '"' -> append("&quot;")
                    '\'' -> append("&#39;")
                    else -> append(c)
                }
            }
        }

        fun reasonPhrase(code: Int) = when (code) {
            200 -> "OK"
            303 -> "See Other"
            400 -> "Bad Request"
            else -> "Not Found"
        }

        fun closeQuietly(socket: Socket) {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }
}

/**
 * The TV's address on the home network, which the phone reaches it at, or null when the TV is not
 * on a network. The active network's own address comes first: a box can have Wi-Fi and a cable.
 */
fun localNetworkAddress(context: Context): String? {
    val fromActive = try {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        cm?.getLinkProperties(cm.activeNetwork)?.linkAddresses?.map { it.address }?.firstOrNull { it.isLanIpv4() }
    } catch (_: Exception) {
        null
    }
    val address = fromActive ?: try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it.isLanIpv4() }
    } catch (_: Exception) {
        null
    }
    return address?.hostAddress
}

private fun InetAddress.isLanIpv4() = this is Inet4Address && !isLoopbackAddress && !isLinkLocalAddress

/** The name the TV goes by in its own settings ("Living room TV"), else its maker and model. */
fun tvDisplayName(context: Context): String {
    // Settings.Global.DEVICE_NAME, which Android 7.1 added; on 7.0 the read simply finds nothing.
    val named = try {
        Settings.Global.getString(context.contentResolver, "device_name")
    } catch (_: Exception) {
        null
    }
    if (!named.isNullOrBlank()) return named.trim()
    val model = Build.MODEL.orEmpty().trim()
    val maker = Build.MANUFACTURER.orEmpty().trim().replaceFirstChar { it.uppercase() }
    return when {
        model.isEmpty() -> "this TV"
        maker.isEmpty() || model.startsWith(maker, ignoreCase = true) -> model
        else -> "$maker $model"
    }
}
