package com.sd.laborator.interfaces

data class UserInfo(val name: String, val host: String, val port: Int)

// ISP: interfata dedicata exclusiv operatiilor de registru utilizatori
interface IUserRegistryService {
    fun register(name: String, host: String, port: Int)
    fun lookup(name: String): UserInfo?
    fun unregister(name: String)
    fun listAll(): List<UserInfo>
}
