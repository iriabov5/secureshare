package dev.secureshare.files

interface ObjectStorage {
    fun put(file: StoredFile, bytes: ByteArray)
    fun get(file: StoredFile): ByteArray
}
