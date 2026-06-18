package com.sd.laborator.interfaces

// ISP: interfata dedicata exclusiv trimiterii/primirii de mesaje P2P
interface IChatService {
    // Trimite un mesaj direct la un utilizator (dupa ce i-a aflat adresa din registry)
    suspend fun sendMessage(targetUser: String, message: String)
    // Primeste un mesaj direct de la alt utilizator
    fun onMessageReceived(from: String, text: String)
}
