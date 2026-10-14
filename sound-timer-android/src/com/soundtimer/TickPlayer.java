package com.soundtimer;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTimestamp;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Process;

import java.math.BigDecimal;
import java.util.Arrays;

/**
 * Streams audio continuously and mixes the tick in at exact sample positions,
 * so every tick lands precisely on its slot instead of whenever the system
 * gets around to starting a new sound.
 *
 * The first tick plays at {@code firstNanos} (a System.nanoTime() value, or
 * "now" if it is already past); the rest follow every {@code interval}
 * seconds after it, counted in samples so there is no drift.
 */
final class TickPlayer extends Thread {

    interface Listener {
        void onTick(long played);
        void onDone(long played);
    }

    private static final int CHUNK = 192;
    private static final int MAX_VOICES = 64;

    private final float[] sound;
    private final int rate;
    private final long firstNanos;
    private final double intervalFrames;
    private final long count;
    private final Listener listener;
    private volatile boolean cancelled;

    TickPlayer(float[] sound, int rate, long firstNanos, BigDecimal intervalSeconds, long count,
               Listener listener) {
        super("tick-player");
        this.sound = sound;
        this.rate = rate;
        this.firstNanos = firstNanos;
        this.intervalFrames = intervalSeconds.multiply(BigDecimal.valueOf(rate)).doubleValue();
        this.count = count;
        this.listener = listener;
    }

    static int outputRate(AudioManager am) {
        try {
            String r = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE);
            if (r != null) return Integer.parseInt(r);
        } catch (NumberFormatException ignored) {
        }
        return 48000;
    }

    void cancel() {
        cancelled = true;
    }

    @Override
    public void run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);

        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        AudioFormat format = new AudioFormat.Builder()
                .setSampleRate(rate)
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build();
        int minBytes = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_FLOAT);
        int bytes = Math.max(minBytes, CHUNK * 4 * 2);

        AudioTrack track;
        if (Build.VERSION.SDK_INT >= 26) {
            track = new AudioTrack.Builder()
                    .setAudioAttributes(attrs)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bytes)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .build();
        } else {
            track = new AudioTrack(attrs, format, bytes, AudioTrack.MODE_STREAM,
                    AudioManager.AUDIO_SESSION_ID_GENERATE);
        }
        int bufferFrames = Build.VERSION.SDK_INT >= 23 ? track.getBufferSizeInFrames() : bytes / 4;

        float[] buf = new float[CHUNK];
        long[] voices = new long[MAX_VOICES];
        int voiceCount = 0;
        long written = 0;
        long lastWriteNanos = System.nanoTime();
        AudioTimestamp ts = new AudioTimestamp();
        boolean haveTs = false;
        long lastTsCheck = 0;
        boolean baseKnown = false;
        long base = 0;
        long played = 0;
        long reported = 0;

        try {
            track.play();
            while (!cancelled) {
                long c0 = written;
                long c1 = written + CHUNK;

                // Map the first tick's wall-clock time onto a sample position.
                // Uses the hardware timestamp when the device reports one.
                if (!baseKnown) {
                    long now = System.nanoTime();
                    if (now - lastTsCheck > 50_000_000L) {
                        lastTsCheck = now;
                        if (track.getTimestamp(ts) && ts.framePosition > 0) haveTs = true;
                    }
                    double f = haveTs
                            ? ts.framePosition + (firstNanos - ts.nanoTime) * (double) rate / 1e9
                            : written - bufferFrames + (firstNanos - lastWriteNanos) * (double) rate / 1e9;
                    if (f < c1) {
                        base = Math.max(c0, (long) Math.floor(f));
                        baseKnown = true;
                    }
                }

                if (baseKnown) {
                    while (count == 0 || played < count) {
                        long tickFrame = base + Math.round(played * intervalFrames);
                        if (tickFrame >= c1) break;
                        if (voiceCount == MAX_VOICES) {
                            System.arraycopy(voices, 1, voices, 0, voiceCount - 1);
                            voiceCount--;
                        }
                        voices[voiceCount++] = Math.max(tickFrame, c0);
                        played++;
                    }
                    if (played != reported) {
                        reported = played;
                        listener.onTick(played);
                    }
                }

                Arrays.fill(buf, 0f);
                int keep = 0;
                for (int v = 0; v < voiceCount; v++) {
                    long s = voices[v];
                    long end = s + sound.length;
                    for (long f = Math.max(c0, s), to = Math.min(c1, end); f < to; f++) {
                        buf[(int) (f - c0)] += sound[(int) (f - s)];
                    }
                    if (end > c1) voices[keep++] = s;
                }
                voiceCount = keep;
                for (int i = 0; i < CHUNK; i++) {
                    if (buf[i] > 1f) buf[i] = 1f;
                    else if (buf[i] < -1f) buf[i] = -1f;
                }

                if (!writeFully(track, buf)) break;
                written = c1;
                lastWriteNanos = System.nanoTime();

                if (count != 0 && played >= count && voiceCount == 0) break;
            }

            if (!cancelled) {
                // Push silence through so the tail of the last tick is fully heard.
                Arrays.fill(buf, 0f);
                for (int i = 0; i <= bufferFrames / CHUNK + 1 && !cancelled; i++) {
                    if (!writeFully(track, buf)) break;
                }
            }
        } finally {
            if (cancelled) {
                track.pause();
                track.flush();
            } else {
                track.stop();
            }
            track.release();
        }
        if (!cancelled) listener.onDone(played);
    }

    private boolean writeFully(AudioTrack track, float[] buf) {
        int off = 0;
        while (off < buf.length) {
            if (cancelled) return false;
            int n = track.write(buf, off, buf.length - off, AudioTrack.WRITE_BLOCKING);
            if (n < 0) return false;
            off += n;
        }
        return true;
    }
}
