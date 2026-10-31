package dev.velocity.audiofocusprobe;

import android.app.Activity;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

/** Disposable emulator fixture: holds focus and plays a quiet looping PCM tone. */
public final class ProbeActivity extends Activity {
    private AudioManager manager;
    private AudioFocusRequest focus;
    private AudioTrack track;
    private String role;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        boolean ducker = getResources().getBoolean(R.bool.probe_ducker);
        role = ducker ? "ducker" : "player";
        TextView label = new TextView(this);
        label.setText("Audio-focus test fixture: " + role);
        setContentView(label);
        AudioAttributes attributes = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
        manager = getSystemService(AudioManager.class);
        focus = new AudioFocusRequest.Builder(ducker
                ? AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK : AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            // Receive the loss/duck callback rather than having Android silently
            // duck the player. The test observes ownership; it does not pause.
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener(new AudioManager.OnAudioFocusChangeListener() {
                @Override public void onAudioFocusChange(int change) {
                    Log.i("VelocityAudioProbe", role + " focus_change=" + change);
                }
                @Override public String toString() {
                    // AudioManager uses listener.toString() in its focus client ID.
                    // Forked fixture apps can otherwise produce identical identity
                    // hashes and overwrite each other's focus instead of ducking.
                    return getPackageName() + ":" + role + ":" + android.os.Process.myPid();
                }
            }).build();
        int result = manager.requestAudioFocus(focus);
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Log.e("VelocityAudioProbe", role + " focus_request_failed=" + result);
            return;
        }
        if (!ducker) {
            int rate = 16000;
            short[] pcm = new short[rate * 2];
            for (int i = 0; i < pcm.length; i++) {
                pcm[i] = (short) (Math.sin(2 * Math.PI * 440 * i / rate) * 1000);
            }
            track = new AudioTrack.Builder().setAudioAttributes(attributes)
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.length * 2).build();
            track.write(pcm, 0, pcm.length);
            track.setLoopPoints(0, pcm.length, -1);
            track.setVolume(0.02f);
            track.play();
        }
        Log.i("VelocityAudioProbe", role + " ready focus=granted"
            + (track == null ? "" : " play_state=" + track.getPlayState()));
    }

    // Intentionally retain the player's focus/AudioTrack while another app is
    // foreground. The fixture is used only on an ephemeral emulator.
    @Override protected void onDestroy() {
        if (track != null) { track.stop(); track.release(); }
        if (manager != null && focus != null) manager.abandonAudioFocusRequest(focus);
        super.onDestroy();
    }
}
