package dev.secureshare.files

interface ObjectStorage {
    fun put(key: String, bytes: ByteArray)
    fun get(file: StoredFile): ByteArray
}
