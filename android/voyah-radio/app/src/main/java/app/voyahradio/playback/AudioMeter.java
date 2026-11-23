package app.voyahradio.playback;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

@OptIn(markerClass = UnstableApi.class)
public final class AudioMeter {
    private static final int WINDOW = 256;
    private static final int WAVE_POINTS = 64;
    private static final long SAMPLE_INTERVAL_NS = 32_000_000;
    private static final int[] FREQUENCIES = {90, 140, 220, 350, 550, 850, 1300, 2000, 3000, 4500, 6500, 9500};

    public static final class Frame {
        public final float[] spectrum;
        public final float[] wave;

        Frame(float[] spectrum, float[] wave) {
            this.spectrum = spectrum;
            this.wave = wave;
        }
    }

    private volatile Frame frame;
    private volatile boolean enabled;
    private final float[] input = new float[WINDOW];
    private final float[] window = new float[WINDOW];
    private final float[] coefficients = new float[FREQUENCIES.length];
    private int sampleRate;
    private long nextSample;

    AudioMeter() {
        for (int i = 0; i < WINDOW; i++) {
            window[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (WINDOW - 1)));
        }
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) frame = null;
    }

    public Frame frame() {
        return frame;
    }

    void sample(ByteBuffer pcm, int rate, int channels, int encoding) {
        if (!enabled || channels <= 0 || rate <= 0) return;
        int bytesPerChannel = switch (encoding) {
            case C.ENCODING_PCM_16BIT -> 2;
            case C.ENCODING_PCM_FLOAT -> 4;
            default -> 0;
        };
        int stride = channels * bytesPerChannel;
        if (stride == 0 || pcm.remaining() < WINDOW * stride) return;
        long now = System.nanoTime();
        if (now < nextSample) return;
        nextSample = now + SAMPLE_INTERVAL_NS;

        if (sampleRate != rate) initializeCoefficients(rate);
        ByteBuffer samples = pcm.duplicate().order(ByteOrder.nativeOrder());
        int start = samples.position();
        float[] wave = new float[WAVE_POINTS];
        for (int i = 0; i < WINDOW; i++) {
            int offset = start + i * stride;
            float value = read(samples, offset, bytesPerChannel);
            if (channels > 1) value = (value + read(samples, offset + bytesPerChannel, bytesPerChannel)) * 0.5f;
            input[i] = value * window[i];
            if (i % (WINDOW / WAVE_POINTS) == 0) wave[i / (WINDOW / WAVE_POINTS)] = value;
        }

        float[] spectrum = new float[FREQUENCIES.length];
        for (int band = 0; band < spectrum.length; band++) {
            float previous = 0;
            float before = 0;
            float coefficient = coefficients[band];
            for (float sample : input) {
                float current = sample + coefficient * previous - before;
                before = previous;
                previous = current;
            }
            float power = previous * previous + before * before - coefficient * previous * before;
            double amplitude = Math.sqrt(Math.max(0, power)) / WINDOW;
            spectrum[band] = (float) Math.min(1, Math.log1p(amplitude * 60) / 2.5);
        }
        if (enabled) frame = new Frame(spectrum, wave);
    }

    private void initializeCoefficients(int rate) {
        sampleRate = rate;
        for (int band = 0; band < FREQUENCIES.length; band++) {
            double frequency = Math.min(FREQUENCIES[band], rate * 0.45);
            coefficients[band] = (float) (2 * Math.cos(2 * Math.PI * frequency / rate));
        }
    }

    private float read(ByteBuffer buffer, int offset, int bytes) {
        return bytes == 2 ? buffer.getShort(offset) / 32768f : buffer.getFloat(offset);
    }
}
