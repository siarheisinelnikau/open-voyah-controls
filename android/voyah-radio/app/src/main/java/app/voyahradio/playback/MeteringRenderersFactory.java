package app.voyahradio.playback;

import android.content.Context;

import androidx.annotation.OptIn;
import androidx.media3.common.Format;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.ForwardingAudioSink;

import java.nio.ByteBuffer;

@OptIn(markerClass = UnstableApi.class)
final class MeteringRenderersFactory extends DefaultRenderersFactory {
    private final AudioMeter meter;

    MeteringRenderersFactory(Context context, AudioMeter meter) {
        super(context);
        this.meter = meter;
    }

    @Override protected AudioSink buildAudioSink(Context context, boolean enableFloatOutput,
                                                 boolean enableAudioTrackPlaybackParams) {
        AudioSink delegate = super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams);
        return new ForwardingAudioSink(delegate) {
            private Format format;

            @Override public void configure(Format format, int bufferSize, int[] outputChannels)
                    throws ConfigurationException {
                super.configure(format, bufferSize, outputChannels);
                this.format = format;
            }

            @Override public boolean handleBuffer(ByteBuffer buffer, long timeUs, int units)
                    throws InitializationException, WriteException {
                if (format != null) meter.sample(buffer, format.sampleRate, format.channelCount, format.pcmEncoding);
                return super.handleBuffer(buffer, timeUs, units);
            }
        };
    }
}
