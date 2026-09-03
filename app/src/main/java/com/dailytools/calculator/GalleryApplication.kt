package com.dailytools.calculator

import android.app.Application
import android.os.Build
import coil.Coil
import coil.ImageLoader
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import com.dailytools.calculator.util.ExternalCache
import java.io.File

class GalleryApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val imageLoader = ImageLoader.Builder(this)
            .components {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            .build()
        Coil.setImageLoader(imageLoader)

        // Files decrypted from the external cache are only meant to live for one session.
        File(cacheDir, ExternalCache.DECRYPTED_DIR_NAME).deleteRecursively()
    }
}
