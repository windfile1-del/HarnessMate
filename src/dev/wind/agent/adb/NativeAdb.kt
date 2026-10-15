package dev.wind.agent.adb

import java.io.IOException

/** Own compiled native ADB transport. Identity is PEM key followed by PEM certificate. */
object NativeAdb {
    private val loaded: Boolean by lazy {
        try {
            System.loadLibrary("vegaadb")
            true
        } catch (_: LinkageError) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    fun available(): Boolean = loaded

    private fun requireAvailable() {
        if (!available()) throw IOException("ADB_NATIVE_UNAVAILABLE")
    }

    fun generateIdentity(): ByteArray { requireAvailable(); return nativeGenerateIdentity() }
    fun open(port: Int, identity: ByteArray, timeoutMs: Int, networkHandle: Long = 0): Long {
        requireAvailable(); return nativeOpen(port, identity, timeoutMs, networkHandle)
    }
    fun upgradeTls(handle: Long, timeoutMs: Int) { requireAvailable(); nativeUpgradeTls(handle, timeoutMs) }
    fun signToken(handle: Long, token: ByteArray): ByteArray { requireAvailable(); return nativeSignToken(handle, token) }
    fun publicKey(handle: Long): ByteArray { requireAvailable(); return nativePublicKey(handle) }
    /** Returns -1 on EOF, 0 only for a zero-length request; may return a short read. */
    fun read(handle: Long, data: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
        requireAvailable(); return nativeRead(handle, data, offset, length, timeoutMs)
    }
    fun write(handle: Long, data: ByteArray, offset: Int, length: Int, timeoutMs: Int) {
        requireAvailable(); nativeWrite(handle, data, offset, length, timeoutMs)
    }
    /** Idempotent. Cancels pending I/O; the descriptor remains owned until I/O exits. */
    fun close(handle: Long) { if (available()) nativeClose(handle) }
    fun pair(port: Int, code: String, identity: ByteArray, timeoutMs: Int, networkHandle: Long = 0): String {
        requireAvailable(); return nativePair(port, code, identity, timeoutMs, networkHandle)
    }

    private external fun nativeGenerateIdentity(): ByteArray
    private external fun nativeSignToken(handle: Long, token: ByteArray): ByteArray
    private external fun nativePublicKey(handle: Long): ByteArray
    private external fun nativeOpen(port: Int, identity: ByteArray, timeoutMs: Int, networkHandle: Long): Long
    private external fun nativeUpgradeTls(handle: Long, timeoutMs: Int)
    private external fun nativeRead(handle: Long, data: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int
    private external fun nativeWrite(handle: Long, data: ByteArray, offset: Int, length: Int, timeoutMs: Int)
    private external fun nativeClose(handle: Long)
    private external fun nativePair(port: Int, code: String, identity: ByteArray, timeoutMs: Int, networkHandle: Long): String
}
