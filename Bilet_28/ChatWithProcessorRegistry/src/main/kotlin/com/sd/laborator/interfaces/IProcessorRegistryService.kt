package com.sd.laborator.interfaces

// Informatii despre un procesor de flux inregistrat
data class ProcessorInfo(
    val name: String,
    val host: String,
    val port: Int,
    val type: String    // ex: "log", "uppercase", "censor"
)

// Endpoint al unui observator care primeste notificari de la registru
data class ObserverEndpoint(val host: String, val port: Int)

// ===== Pattern Observer =====

// IRegistryObserver — interfata pe care o implementeaza cei care vor notificari (Observer)
// ISP: separata de IProcessorRegistryService (Subject)
interface IRegistryObserver {
    fun onProcessorRegistered(info: ProcessorInfo)
    fun onProcessorUnregistered(name: String)
}

// IProcessorRegistryService — Subject din pattern-ul Observer
// Gestioneaza lista de procesoare active si lista de observatori
interface IProcessorRegistryService {
    // Gestiune procesoare (apelate la REGISTER/UNREGISTER)
    fun registerProcessor(info: ProcessorInfo)
    fun unregisterProcessor(name: String): ProcessorInfo?
    fun getProcessor(name: String): ProcessorInfo?
    fun listProcessors(): List<ProcessorInfo>

    // Gestiune observatori (Subject din Observer)
    fun addObserver(endpoint: ObserverEndpoint)
    fun removeObserver(endpoint: ObserverEndpoint)
    fun notifyObservers(event: String, info: ProcessorInfo)
}

// IStreamProcessor — interfata locala pentru logica de procesare a mesajelor
// ISP: separata de IProcessorRegistryService
interface IStreamProcessor {
    fun process(fromUser: String, message: String): String
    val processorName: String
    val processorType: String
}
