# DefaultRenderersFactory instantiates the FFmpeg extension renderer by its
# fully qualified name via reflection, so neither the class nor its
# constructor may be renamed or removed.
-keep class androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer {
    <init>(android.os.Handler, androidx.media3.exoplayer.audio.AudioRendererEventListener, androidx.media3.exoplayer.audio.AudioSink);
}
