package com.grabx.app.grabx.core.service;

import javax.sound.sampled.*;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.prefs.Preferences;

/** Short local cues, coalesced into one non-overlapping sound per burst. No FX-thread audio I/O. */
public final class SoundFeedbackService implements AutoCloseable {
    public enum Cue {
        START("Start", 1), RESUME("Resume", 1), PAUSE("Pause", 2), CANCEL("Cancel", 3), COMPLETE("Complete", 4), ERROR("Error", 5);
        final int priority;
        private final String label;
        Cue(String label, int priority) { this.label = label; this.priority = priority; }
        @Override public String toString() { return label; }
    }
    private record Sample(AudioFormat format, byte[] pcm) { }
    private final Preferences preferences;
    private final java.util.function.BiConsumer<Cue, Double> playback;
    private final ScheduledExecutorService audio = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "grabx-sound"); t.setDaemon(true); return t;
    });
    private final Map<Cue, Sample> samples = new EnumMap<>(Cue.class);
    private volatile boolean enabled, closed;
    private volatile double volume;
    private Cue pending;
    private ScheduledFuture<?> scheduled;
    private long nextAllowed;
    private Clip playing;

    public SoundFeedbackService(Preferences preferences) {
        this(preferences, null);
    }
    SoundFeedbackService(Preferences preferences, java.util.function.BiConsumer<Cue, Double> playback) {
        this.preferences = preferences;
        this.playback = playback == null ? this::playNow : playback;
        enabled = preferences.getBoolean("grabx.sounds.enabled", true);
        volume = clamp(preferences.getDouble("grabx.sounds.volume", .30));
    }
    public boolean isEnabled() { return enabled; }
    public double volume() { return volume; }
    public void configure(boolean enabled, double volume) {
        this.enabled = enabled; this.volume = clamp(volume);
        preferences.putBoolean("grabx.sounds.enabled", enabled);
        preferences.putDouble("grabx.sounds.volume", this.volume);
        if (!enabled || this.volume == 0) silence();
    }
    public synchronized void play(Cue cue) {
        if (closed || !enabled || volume == 0) return;
        pending = preferred(pending, cue);
        if (scheduled != null) return;
        long wait = Math.max(70_000_000L, nextAllowed - System.nanoTime());
        scheduled = audio.schedule(this::flush, wait, TimeUnit.NANOSECONDS);
    }
    static Cue preferred(Cue pending, Cue incoming) {
        return pending == null || incoming.priority >= pending.priority ? incoming : pending;
    }
    private void flush() {
        Cue cue;
        double level;
        synchronized (this) {
            cue = pending; pending = null; scheduled = null;
            if (closed || !enabled || volume == 0 || cue == null) return;
            level = volume;
            nextAllowed = System.nanoTime() + 700_000_000L;
        }
        playback.accept(cue, level);
    }
    public synchronized void preview(Cue cue, double volume) {
        if (closed) return;
        double level = clamp(volume);
        if (level == 0) return;
        // Preview also shares the single audio line; never layers over a notification.
        audio.execute(() -> { if (!closed) playback.accept(cue, level); });
    }
    private void playNow(Cue cue, double level) {
        if (closed) return;
        stopPlaying();
        try {
            Sample sample = samples.get(cue);
            if (sample == null) {
                var resource = SoundFeedbackService.class.getResource("/com/grabx/app/grabx/sounds/" + cue.name().toLowerCase(java.util.Locale.ROOT) + ".wav");
                if (resource == null) return;
                try (AudioInputStream in = AudioSystem.getAudioInputStream(resource)) {
                    sample = new Sample(in.getFormat(), in.readAllBytes()); samples.put(cue, sample);
                }
            }
            byte[] pcm = sample.pcm().clone();
            for (int i = 0; i + 1 < pcm.length; i += 2) {
                short value = (short) ((pcm[i] & 255) | (pcm[i + 1] << 8));
                int scaled = (int) Math.round(value * level);
                pcm[i] = (byte) scaled; pcm[i + 1] = (byte) (scaled >> 8);
            }
            Clip clip = AudioSystem.getClip();
            playing = clip;
            clip.open(sample.format(), pcm, 0, pcm.length);
            clip.start();
            audio.schedule(() -> { if (playing == clip) stopPlaying(); },
                    Math.max(1, clip.getMicrosecondLength() / 1000) + 80, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
            // A missing/busy audio device must never affect downloads or open an error dialog.
            stopPlaying();
        }
    }
    public synchronized void silence() {
        pending = null;
        if (scheduled != null) { scheduled.cancel(false); scheduled = null; }
        if (!closed) audio.execute(this::stopPlaying);
    }
    private void stopPlaying() { if (playing != null) { playing.stop(); playing.close(); playing = null; } }
    private static double clamp(double value) { return Double.isFinite(value) ? Math.max(0, Math.min(1, value)) : .30; }
    @Override public synchronized void close() {
        if (closed) return;
        silence(); closed = true;
        audio.execute(this::stopPlaying); audio.shutdown();
    }
}
