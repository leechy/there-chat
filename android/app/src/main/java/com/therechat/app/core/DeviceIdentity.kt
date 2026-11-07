package com.therechat.app.core

import android.content.Context
import android.util.Base64
import com.google.android.gms.auth.blockstore.Blockstore
import com.google.android.gms.auth.blockstore.DeleteBytesRequest
import com.google.android.gms.auth.blockstore.RetrieveBytesRequest
import com.google.android.gms.auth.blockstore.StoreBytesData
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import java.security.SecureRandom

/**
 * The device's anonymous identity: 32 random bytes.
 *
 * Kept in Block Store, which survives reinstalls and is restored to a new
 * phone from the Google backup, plus a local copy so we don't hit Play
 * Services on every launch. The server only ever stores a hash of it.
 */
class DeviceIdentity(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("identity", Context.MODE_PRIVATE)
    private val blockstore = Blockstore.getClient(app)
    private val mutex = Mutex()

    /** The base64url-encoded secret, created on first use. */
    suspend fun secret(): String = mutex.withLock {
        prefs.getString(KEY, null)?.let { return it }
        val bytes = restore() ?: ByteArray(32).also { SecureRandom().nextBytes(it) }.also { backup(it) }
        val encoded = encode(bytes)
        prefs.edit().putString(KEY, encoded).apply()
        encoded
    }

    /** Forgets the secret here and in Block Store; a new one is made on next use. */
    suspend fun reset() = mutex.withLock {
        prefs.edit().remove(KEY).commit()
        runCatching {
            blockstore.deleteBytes(DeleteBytesRequest.Builder().setKeys(listOf(KEY)).build()).await()
        }
        Unit
    }

    private suspend fun restore(): ByteArray? = runCatching {
        val request = RetrieveBytesRequest.Builder().setKeys(listOf(KEY)).build()
        blockstore.retrieveBytes(request).await().blockstoreDataMap[KEY]?.bytes
    }.getOrNull()

    private suspend fun backup(bytes: ByteArray) {
        runCatching {
            val data = StoreBytesData.Builder()
                .setKey(KEY)
                .setBytes(bytes)
                .setShouldBackupToCloud(true)
                .build()
            blockstore.storeBytes(data).await()
        }
    }

    companion object {
        private const val KEY = "com.therechat.device.secret"

        fun encode(bytes: ByteArray): String =
            Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }
}
