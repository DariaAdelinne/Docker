package com.sd.laborator.interfaces

data class UserEndpoint(val name: String, val host: String, val port: Int)

data class MulticastResult(
    val group: String,
    val fromUser: String,
    val message: String,
    val delivered: List<String>,   // useri la care a ajuns cu succes
    val failed: List<String>       // useri la care nu a putut ajunge
)

// ISP: interfata dedicata exclusiv gestionarii grupurilor si multicast-ului
interface IMulticastProcessorService {
    // Gestiune utilizatori
    fun registerUser(name: String, host: String, port: Int)
    fun unregisterUser(name: String)
    fun getUser(name: String): UserEndpoint?

    // Gestiune grupuri (subgrupuri de studenti)
    fun createGroup(groupName: String, creatorName: String): Boolean
    fun joinGroup(groupName: String, userName: String): Boolean
    fun leaveGroup(groupName: String, userName: String): Boolean
    fun deleteGroup(groupName: String): Boolean
    fun getGroupMembers(groupName: String): Set<String>?
    fun listGroups(): Map<String, Set<String>>

    // Multicast: trimite mesaj la toti membrii grupului
    suspend fun multicast(groupName: String, fromUser: String, message: String): MulticastResult
}
