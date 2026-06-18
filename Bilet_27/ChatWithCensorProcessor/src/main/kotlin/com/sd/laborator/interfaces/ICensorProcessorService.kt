package com.sd.laborator.interfaces

data class CensorResult(
    val original: String,
    val censored: String,
    val replacedWords: List<String>   // cuvintele care au fost inlocuite
)

// ISP: interfata dedicata exclusiv cenzurii textului pe baza unui dictionar
interface ICensorProcessorService {
    // Incarca dictionarul de cuvinte interzise dintr-un fisier (cate un cuvant pe linie)
    fun loadDictionary(path: String)
    // Returneaza dictionarul curent
    fun getDictionary(): Set<String>
    // Adauga un cuvant in dictionar la runtime
    fun addWord(word: String)
    // Scoate un cuvant din dictionar la runtime
    fun removeWord(word: String): Boolean
    // Cenzureaza textul: inlocuieste fiecare cuvant din dictionar cu "x" * lungime
    fun censor(text: String): CensorResult
}
