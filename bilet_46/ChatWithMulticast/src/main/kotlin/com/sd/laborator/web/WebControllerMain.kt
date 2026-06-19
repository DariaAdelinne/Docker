package com.sd.laborator.web

import com.sd.laborator.common.Env
import com.sd.laborator.common.Ports
import com.sd.laborator.common.SocketLine
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * WebController — Microserviciu WEB nou (SPLSD-41).
 *
 * Responsabilitate (SRP): serveste o pagina web care permite:
 *   1. Vizualizarea tuturor utilizatorilor activi
 *   2. Selectia unui subgrup de studenti (checkbox per utilizator)
 *   3. Introducerea mesajului multicast
 *   4. Trimiterea multicast-ului catre subgrupul selectat
 *
 * Principii SOLID:
 *   S - singura responsabilitate: interfata web pentru multicast
 *   O - handlerul HTTP poate fi extins fara modificarea serverului
 *   D - comunica cu MulticastProcessor prin socket (SocketLine), nu direct
 */
class WebControllerMicroservice {

    private val multicastHost = Env.str("MULTICAST_HOST", "localhost")
    private val webPort       = Env.int("WEB_PORT", 8080)

    private fun queryMulticast(command: String): String =
        SocketLine.sendAndRead(multicastHost, Ports.MULTICAST_PROCESSOR_PORT, command, timeoutMs = 3000)
            ?: "(niciun raspuns)"

    /** Preia utilizatorii activi de la MulticastProcessor. */
    private fun fetchUsers(): List<String> {
        val response = queryMulticast("LIST_USERS")
        if (response.startsWith("USERS (none)") || !response.startsWith("USERS ")) return emptyList()
        // Format: "USERS alice:user-alice:3001|bob:user-bob:3001|..."
        return response.removePrefix("USERS ")
            .split("|")
            .map { it.split(":").first() }
            .filter { it.isNotBlank() }
    }

    /** Parseaza form data URL-encoded din body POST. */
    private fun parseFormData(body: String): Map<String, List<String>> {
        val result = mutableMapOf<String, MutableList<String>>()
        body.split("&").forEach { pair ->
            val idx = pair.indexOf('=')
            if (idx < 0) return@forEach
            val key   = URLDecoder.decode(pair.substring(0, idx), "UTF-8")
            val value = URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
            result.getOrPut(key) { mutableListOf() }.add(value)
        }
        return result
    }

    /** Construieste pagina HTML principala. */
    private fun buildPage(users: List<String>, message: String = "", result: String = ""): String {
        val checkboxes = if (users.isEmpty()) {
            "<p style='color:#f38ba8'>Niciun utilizator activ. Porneste mai intai microserviciile de chat.</p>"
        } else {
            users.joinToString("\n") { user ->
                """<label style='display:block;margin:6px 0'>
                   <input type='checkbox' name='member' value='$user'> $user
                   </label>"""
            }
        }

        val resultHtml = if (result.isNotEmpty()) """
            <div style='margin-top:20px;padding:12px;background:#313244;border-radius:6px;
                        border-left:4px solid ${if (result.contains("DONE")) "#a6e3a1" else "#f38ba8"}'>
                <b>Rezultat:</b><br>$result
            </div>""" else ""

        return """<!DOCTYPE html>
<html lang='ro'>
<head>
  <meta charset='UTF-8'>
  <title>Chat Multicast — WebController</title>
  <style>
    body { font-family: monospace; background:#1e1e2e; color:#cdd6f4; margin:0; padding:30px; }
    h1   { color:#cba6f7; }
    h2   { color:#89b4fa; border-bottom:1px solid #45475a; padding-bottom:6px; }
    input[type=text], textarea {
      background:#313244; color:#cdd6f4; border:1px solid #45475a;
      border-radius:4px; padding:8px; width:100%; box-sizing:border-box; font-family:monospace;
    }
    button {
      background:#cba6f7; color:#1e1e2e; border:none; padding:10px 24px;
      border-radius:4px; cursor:pointer; font-weight:bold; margin-top:12px; font-size:14px;
    }
    button:hover { background:#b4befe; }
    .card { background:#181825; border-radius:8px; padding:20px; max-width:600px; margin:0 auto; }
    label { cursor:pointer; }
    input[type=checkbox] { margin-right:8px; accent-color:#cba6f7; }
  </style>
</head>
<body>
<div class='card'>
  <h1>&#127760; Chat Multicast</h1>
  <h2>Utilizatori activi</h2>
  $checkboxes

  <h2>Trimite mesaj multicast</h2>
  <form method='POST' action='/send'>
    <p><b>Selecteaza subgrupul (bifeaza utilizatorii):</b></p>
    $checkboxes
    <p style='margin-top:16px'><b>Mesaj:</b></p>
    <input type='text' name='message' placeholder='Scrie mesajul tau...' value='${message}' required>
    <input type='hidden' name='sender' value='web-controller'>
    <button type='submit'>&#128228; Trimite Multicast</button>
  </form>
  $resultHtml
</div>
</body>
</html>"""
    }

    private fun handleGet(exchange: HttpExchange) {
        val users = fetchUsers()
        val html  = buildPage(users)
        val bytes = html.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun handlePost(exchange: HttpExchange) {
        val body   = exchange.requestBody.bufferedReader(StandardCharsets.UTF_8).readText()
        val params = parseFormData(body)

        val members = params["member"] ?: emptyList()
        val message = params["message"]?.firstOrNull() ?: ""
        val sender  = params["sender"]?.firstOrNull()  ?: "web-controller"

        val result: String
        if (members.isEmpty() || message.isBlank()) {
            result = "Eroare: selecteaza cel putin un utilizator si scrie un mesaj."
        } else {
            // 1. Creeaza grupul temporar
            val groupName = "web-group-${System.currentTimeMillis()}"
            queryMulticast("CREATE_GROUP $groupName $sender")

            // 2. Adauga fiecare membru selectat
            members.forEach { member ->
                queryMulticast("JOIN_GROUP $groupName $member")
            }

            // 3. Trimite multicast
            val multicastResult = queryMulticast("MULTICAST $groupName $sender $message")

            // 4. Sterge grupul temporar
            queryMulticast("DELETE_GROUP $groupName")

            result = multicastResult
            println("[WebController] Multicast trimis catre $members: $multicastResult")
        }

        val users = fetchUsers()
        val html  = buildPage(users, message, result)
        val bytes = html.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    fun run() {
        val server = HttpServer.create(InetSocketAddress(webPort), 0)

        server.createContext("/") { exchange ->
            when (exchange.requestMethod.uppercase()) {
                "GET"  -> handleGet(exchange)
                "POST" -> handlePost(exchange)
                else   -> exchange.sendResponseHeaders(405, -1)
            }
        }

        server.createContext("/send") { exchange ->
            if (exchange.requestMethod.uppercase() == "POST") handlePost(exchange)
            else exchange.sendResponseHeaders(405, -1)
        }

        server.executor = null
        server.start()
        println("[WebController] Pornit pe http://0.0.0.0:$webPort")
        println("[WebController] Deschide http://localhost:$webPort in browser")
        Thread.currentThread().join()
    }
}

fun main() = WebControllerMicroservice().run()
