package com.grabx.app.grabx.core.service;

import org.junit.jupiter.api.Test;
import javax.sound.sampled.AudioSystem;
import java.util.*;
import java.util.concurrent.*;
import java.util.prefs.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.grabx.app.grabx.core.service.SoundFeedbackService.Cue.*;

class SoundFeedbackServiceTest {
    static final class MemoryPreferences extends AbstractPreferences {
        final Map<String,String> values = new HashMap<>();
        MemoryPreferences(){super(null, "");}
        protected void putSpi(String k,String v){values.put(k,v);}
        protected String getSpi(String k){return values.get(k);}
        protected void removeSpi(String k){values.remove(k);}
        protected void removeNodeSpi(){values.clear();}
        protected String[] keysSpi(){return values.keySet().toArray(String[]::new);}
        protected String[] childrenNamesSpi(){return new String[0];}
        protected AbstractPreferences childSpi(String n){throw new UnsupportedOperationException();}
        protected void syncSpi(){}
        protected void flushSpi(){}
    }
    @Test void bulkActionsCoalesceAndTerminalCueWinsTheBurst() throws Exception {
        var heard = new LinkedBlockingQueue<SoundFeedbackService.Cue>();
        try(var service = new SoundFeedbackService(new MemoryPreferences(), (cue,v)->heard.add(cue))) {
            for(int n=0;n<100;n++)service.play(PAUSE);
            assertEquals(PAUSE, heard.poll(2,TimeUnit.SECONDS));
            service.play(START); service.play(COMPLETE); service.play(RESUME);
            assertEquals(COMPLETE, heard.poll(2,TimeUnit.SECONDS));
            assertNull(heard.poll(200,TimeUnit.MILLISECONDS));
        }
    }
    @Test void muteVolumeAndPreviewPersistWithoutRequiringAnAudioDevice() throws Exception {
        var preferences = new MemoryPreferences();
        var heard = new LinkedBlockingQueue<Double>();
        try(var service = new SoundFeedbackService(preferences, (cue,v)->heard.add(v))) {
            assertEquals(.30, service.volume()); assertTrue(service.isEnabled());
            service.configure(false,.12); service.play(START);
            assertNull(heard.poll(160,TimeUnit.MILLISECONDS));
            service.preview(RESUME,.21); assertEquals(.21, heard.poll(2,TimeUnit.SECONDS));
            service.configure(true,0); service.play(COMPLETE);
            assertNull(heard.poll(160,TimeUnit.MILLISECONDS));
        }
        try(var restored = new SoundFeedbackService(preferences,(cue,v)->heard.add(v))) {
            assertEquals(0,restored.volume()); assertTrue(restored.isEnabled());
            restored.play(START);
        }
        assertTrue(heard.isEmpty());
    }
    @Test void closingCancelsPendingFeedback() throws Exception {
        var heard = new LinkedBlockingQueue<SoundFeedbackService.Cue>();
        var service = new SoundFeedbackService(new MemoryPreferences(), (cue,v)->heard.add(cue));
        service.play(PAUSE); service.close(); service.play(COMPLETE);
        assertNull(heard.poll(200,TimeUnit.MILLISECONDS));
    }
    @Test void bundledCuesAreShortUnclippedPcmWithSilentEdges() throws Exception {
        for(var cue:SoundFeedbackService.Cue.values()) {
            var url = SoundFeedbackService.class.getResource("/com/grabx/app/grabx/sounds/"+cue.name().toLowerCase(Locale.ROOT)+".wav");
            assertNotNull(url);
            try(var in=AudioSystem.getAudioInputStream(url)) {
                assertEquals(1,in.getFormat().getChannels()); assertEquals(16,in.getFormat().getSampleSizeInBits());
                double duration = in.getFrameLength()/in.getFormat().getFrameRate();
                assertTrue(duration > .1 && duration < .6);
                byte[] pcm = in.readAllBytes(); int peak=0;
                for(int i=0;i<pcm.length;i+=2)peak=Math.max(peak,Math.abs((short)((pcm[i]&255)|(pcm[i+1]<<8))));
                assertTrue(peak>1000&&peak<25000);
                for(int i=pcm.length-200;i<pcm.length;i++)assertEquals(0,pcm[i]);
                assertEquals(0,pcm[0]);assertEquals(0,pcm[1]);
            }
        }
    }
    @Test void settingsRetainExistingRetentionBounds() {
        assertTrue(DownloadSettingsDialog.validDays("1"));assertTrue(DownloadSettingsDialog.validDays("365"));
        for(String value:new String[]{"0","366","2.5","","text"})assertFalse(DownloadSettingsDialog.validDays(value));
    }
}
