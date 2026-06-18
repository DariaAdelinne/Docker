package com.sd.laborator.heartbeat

import com.sd.laborator.common.*

class HeartbeatProcessor {
    private val intervalMs = Env.long("HEARTBEAT_INTERVAL_MS", 5000)
    private val services = parseServiceList(Env.str("SERVICES_TO_CHECK", ""))
    private val replicationHost = Env.str("REPLICATION_HOST", "localhost")
    private val replicationPort = Env.int("REPLICATION_PORT", Ports.REPLICATION_PORT)
    private val fakeQuestion = Env.str("FAKE_QUESTION", "__healthcheck__")
    private val expectedAnswer = Env.str("EXPECTED_FAKE_ANSWER", "OK_HEARTBEAT")

    private fun checkPing(endpoint: ServiceEndpoint): Pair<Boolean, String> {
        return try {
            val response = SocketLine.sendAndRead(endpoint.host, endpoint.port, "PING heartbeat", timeoutMs = 2500)
            val ok = response?.startsWith("PONG") == true || response?.startsWith("ALIVE") == true
            ok to (response ?: "no-response")
        } catch (e: Exception) {
            false to (e.message ?: e.javaClass.simpleName)
        }
    }

    private fun checkFlowViaTeacher(endpoint: ServiceEndpoint): Pair<Boolean, String> {
        return try {
            val response = SocketLine.sendAndRead(endpoint.host, endpoint.port, fakeQuestion, timeoutMs = 4000)
            val ok = response == expectedAnswer
            ok to (response ?: "no-response")
        } catch (e: Exception) {
            false to (e.message ?: e.javaClass.simpleName)
        }
    }

    private fun requestReplication(endpoint: ServiceEndpoint, reason: String) {
        val request = "REPLICATE ${endpoint.name} ${endpoint.type} host=${endpoint.host} port=${endpoint.port} reason=${reason.replace(' ', '_')}"
        try {
            val response = SocketLine.sendAndRead(replicationHost, replicationPort, request, timeoutMs = 2500)
            println("[Heartbeat] Cerere replicare trimisa pentru ${endpoint.name}; raspuns=$response")
        } catch (e: Exception) {
            println("[Heartbeat] Nu pot trimite cererea de replicare: ${e.message}")
        }
    }

    fun run() {
        if (services.isEmpty()) {
            println("[Heartbeat] Nu exista SERVICES_TO_CHECK. Exemplu: teacher-flow:flow:teacher:1600:FLOW,manager:message-manager:message-manager:1501:PING")
        }
        println("[Heartbeat] Pornit. Interval=$intervalMs ms. Verific ${services.size} servicii.")
        while (true) {
            services.forEach { service ->
                val (ok, detail) = if (service.mode.uppercase() == "FLOW") checkFlowViaTeacher(service) else checkPing(service)
                if (ok) {
                    println("[Heartbeat] OK ${service.name}/${service.type} -> $detail")
                } else {
                    println("[Heartbeat] FAIL ${service.name}/${service.type} -> $detail")
                    requestReplication(service, detail)
                }
            }
            Thread.sleep(intervalMs)
        }
    }
}

fun main() = runForever { HeartbeatProcessor().run() }
