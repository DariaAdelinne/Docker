package com.sd.laborator.interfaces

import java.net.Socket

data class Subscriber(val id: Int, val name: String, val socket: Socket)

// ISP: interfata dedicata exclusiv rutarii mesajelor intre abonati
interface IMessageRouterService {
    fun subscribe(id: Int, name: String, socket: Socket)
    fun unsubscribe(id: Int)
    fun broadcast(message: String, exceptId: Int)
    fun respondTo(id: Int, message: String)
}
