package com.dailytools.calculator.util

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.documentfile.provider.DocumentFile
import com.dailytools.calculator.data.model.Post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val cacheHttpClient = OkHttpClient()

/**
 * A "check the drive first, download and copy a copy there otherwise" cache for the user's
 * chosen external (typically OTG USB) folder. Every cached file is AES-256-GCM encrypted with a
 * key held in the Android Keystore (hardware-backed on most devices, never extractable even by
 * this app's own process) and named with a SHA-256 hash of its post id - so anyone who browses
 * the drive directly on another device just sees a folder of same-looking random files with no
 * extension and no readable content, not a recognizable photo/video library.
 *
 * Deliberately doesn't try to make Coil/ExoPlayer's own disk caches point at this folder - both
 * are built around direct, unencrypted File access for performance. Instead this sits as its own
 * layer: on a cache hit it decrypts into a short-lived file in the app's private cache directory
 * and hands that back, which both Coil and ExoPlayer read like any other local file (including
 * proper video seeking, since by then it's just a normal decrypted file on disk).
 */
object ExternalCache {

    private const val KEY_ALIAS = "calc_gallery_cache_key"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_LENGTH_BITS = 128

    /** Where decrypted temp copies live for the current session only - see [GalleryApplication]. */
    const val DECRYPTED_DIR_NAME = "decrypted_media"

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    /** A stable, unrecognizable name for this post - no id, no extension, just a hash. */
    private fun obfuscatedFileName(post: Post): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest("calc_gallery_cache_v1:${post.id}".toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }

    private fun rootOrNull(context: Context, treeUriString: String?): DocumentFile? {
        val treeUri = treeUriString?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return null
        return runCatching { DocumentFile.fromTreeUri(context, treeUri) }.getOrNull()?.takeIf { it.canRead() }
    }

    /**
     * Some SAF providers silently append an extension to the name you ask for based on the mime
     * type given at creation, so look up by prefix rather than assuming an exact match.
     */
    private fun findCached(root: DocumentFile, fileName: String): DocumentFile? =
        runCatching {
            root.listFiles().firstOrNull { it.isFile && it.name?.startsWith(fileName) == true && it.length() > GCM_IV_LENGTH }
        }.getOrNull()

    /**
     * Decrypts an already-cached post into a private temp file and returns it, or null if it
     * isn't cached (yet) or anything about the drive/key goes wrong.
     */
    suspend fun cachedDecryptedFile(context: Context, treeUriString: String?, post: Post): File? =
        withContext(Dispatchers.IO) {
            runCatching {
                val root = rootOrNull(context, treeUriString) ?: return@runCatching null
                val fileName = obfuscatedFileName(post)
                val doc = findCached(root, fileName) ?: return@runCatching null

                val decryptedDir = File(context.cacheDir, DECRYPTED_DIR_NAME).apply { mkdirs() }
                val outFile = File(decryptedDir, fileName)

                val decrypted = context.contentResolver.openInputStream(doc.uri)?.use { rawIn ->
                    val iv = ByteArray(GCM_IV_LENGTH)
                    var offset = 0
                    while (offset < GCM_IV_LENGTH) {
                        val read = rawIn.read(iv, offset, GCM_IV_LENGTH - offset)
                        if (read == -1) return@use false
                        offset += read
                    }
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
                    CipherInputStream(rawIn, cipher).use { plaintext ->
                        FileOutputStream(outFile).use { out -> plaintext.copyTo(out) }
                    }
                    true
                } ?: false

                if (decrypted) outFile else null
            }.getOrNull()
        }

    /**
     * Downloads [sourceUrl] and saves it AES-encrypted into the external folder if it isn't
     * already there. Safe to call every time a post is viewed - it's a no-op once cached, and
     * fails silently (falling back to plain network loading) if the drive is unplugged, out of
     * space, or permission was lost.
     */
    suspend fun cacheInBackground(context: Context, treeUriString: String?, post: Post, sourceUrl: String) {
        withContext(Dispatchers.IO) {
            runCatching {
                val root = rootOrNull(context, treeUriString) ?: return@runCatching
                if (!root.canWrite()) return@runCatching
                val fileName = obfuscatedFileName(post)
                if (findCached(root, fileName) != null) return@runCatching

                val request = Request.Builder().url(sourceUrl).header("User-Agent", "CalcGallery/1.0").build()
                cacheHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val body = response.body ?: return@use
                    // No real mime type on purpose - a bare, unrecognizable file.
                    val doc = root.createFile("application/octet-stream", fileName) ?: return@use
                    context.contentResolver.openOutputStream(doc.uri)?.use { rawOut ->
                        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
                        rawOut.write(cipher.iv)
                        CipherOutputStream(rawOut, cipher).use { encrypting ->
                            body.byteStream().copyTo(encrypting)
                        }
                    }
                }
            }
        }
    }
}
