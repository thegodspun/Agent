package io.github.thegodspun.sentinel.core

import java.io.File

/**
 * Writes through a temporary file and a rename, so a full disk or a killed process can't
 * leave a half-written file behind.
 */
fun writeAtomically(file: File, text: String) {
    file.parentFile?.mkdirs()
    val tmp = File(file.path + ".tmp")
    tmp.writeText(text)
    if (!tmp.renameTo(file)) {
        file.writeText(text)
        tmp.delete()
    }
}

/**
 * Reads and decodes [file]. If it is damaged, it is moved aside to `<name>.corrupt` and
 * [empty] is returned, so one bad file can't crash the app on every read.
 */
fun <T> readOrQuarantine(file: File, empty: T, decode: (String) -> T): T {
    if (!file.exists()) return empty
    return try {
        decode(file.readText())
    } catch (e: Exception) {
        file.renameTo(File(file.path + ".corrupt"))
        empty
    }
}
