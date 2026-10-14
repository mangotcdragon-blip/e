package com.soundtimer;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Build;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** Decodes the bundled sound to mono float PCM at the device's output rate. */
final class SoundDecoder {

    private static float[] cached;
    private static int cachedRate;

    private SoundDecoder() {}

    static synchronized float[] load(Context ctx, int outRate) throws IOException {
        if (cached == null || cachedRate != outRate) {
            cached = trimSilence(resample(decode(ctx), outRate));
            cachedRate = outRate;
        }
        return cached;
    }

    private static int sourceRate;

    private static float[] decode(Context ctx) throws IOException {
        MediaExtractor ex = new MediaExtractor();
        try (AssetFileDescriptor afd = ctx.getResources().openRawResourceFd(R.raw.sound)) {
            ex.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
        }
        MediaFormat format = null;
        for (int i = 0; i < ex.getTrackCount(); i++) {
            MediaFormat f = ex.getTrackFormat(i);
            String mime = f.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                ex.selectTrack(i);
                format = f;
                break;
            }
        }
        if (format == null) throw new IOException("No audio track");

        MediaCodec codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
        codec.configure(format, null, null, 0);
        codec.start();

        int channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
        sourceRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        boolean floatPcm = false;

        float[] out = new float[sourceRate];
        int n = 0;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean inputDone = false;
        try {
            while (true) {
                if (!inputDone) {
                    int in = codec.dequeueInputBuffer(10_000);
                    if (in >= 0) {
                        ByteBuffer b = codec.getInputBuffer(in);
                        int size = ex.readSampleData(b, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(in, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(in, 0, size, ex.getSampleTime(), 0);
                            ex.advance();
                        }
                    }
                }
                int o = codec.dequeueOutputBuffer(info, 10_000);
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = codec.getOutputFormat();
                    channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    sourceRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    if (Build.VERSION.SDK_INT >= 24 && of.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        floatPcm = of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT;
                    }
                } else if (o >= 0) {
                    ByteBuffer b = codec.getOutputBuffer(o);
                    b.position(info.offset);
                    b.limit(info.offset + info.size);
                    b.order(ByteOrder.nativeOrder());
                    int frames = info.size / ((floatPcm ? 4 : 2) * channels);
                    if (n + frames > out.length) out = Arrays.copyOf(out, Math.max(out.length * 2, n + frames));
                    for (int f = 0; f < frames; f++) {
                        float sum = 0;
                        for (int c = 0; c < channels; c++) {
                            sum += floatPcm ? b.getFloat() : b.getShort() / 32768f;
                        }
                        out[n++] = sum / channels;
                    }
                    codec.releaseOutputBuffer(o, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                }
            }
        } finally {
            codec.stop();
            codec.release();
            ex.release();
        }
        return Arrays.copyOf(out, n);
    }

    private static float[] resample(float[] in, int outRate) {
        if (sourceRate == outRate || in.length == 0) return in;
        int outLen = (int) ((long) in.length * outRate / sourceRate);
        float[] out = new float[outLen];
        double step = (double) sourceRate / outRate;
        for (int i = 0; i < outLen; i++) {
            double pos = i * step;
            int j = (int) pos;
            float frac = (float) (pos - j);
            float a = in[Math.min(j, in.length - 1)];
            float b = in[Math.min(j + 1, in.length - 1)];
            out[i] = a + (b - a) * frac;
        }
        return out;
    }

    // MP3 files start with encoder padding (tens of milliseconds of silence).
    // Cutting it makes the tick audible right on its scheduled moment.
    private static float[] trimSilence(float[] s) {
        final float threshold = 0.001f;
        int start = 0;
        while (start < s.length && Math.abs(s[start]) < threshold) start++;
        int end = s.length;
        while (end > start && Math.abs(s[end - 1]) < threshold) end--;
        return Arrays.copyOfRange(s, start, end);
    }
}
