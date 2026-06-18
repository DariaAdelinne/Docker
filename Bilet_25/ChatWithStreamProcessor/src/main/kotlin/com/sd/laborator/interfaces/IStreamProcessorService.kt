package com.sd.laborator.interfaces

data class FileTransferRecord(
    val fromUser: String,
    val targetUser: String,
    val filename: String,
    val sizeBytes: Long,
    val status: String   // "OK" | "TARGET_NOT_FOUND" | "ERROR"
)

// ISP: interfata dedicata exclusiv procesarii fluxurilor de fisiere
interface IStreamProcessorService {
    // Inregistreaza adresa unui utilizator ca destinatie posibila pentru fisiere
    fun registerUser(name: String, host: String, port: Int)
    fun unregisterUser(name: String)
    // Transfera fluxul de bytes catre utilizatorul tinta; returneaza rezultatul
    fun forwardStream(fromUser: String, targetUser: String, filename: String, data: ByteArray): FileTransferRecord
    // Returneaza istoricul transferurilor
    fun getHistory(): List<FileTransferRecord>
}
