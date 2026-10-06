package com.grabx.app.grabx.core.service;

import com.grabx.app.grabx.core.model.DownloadRow;
import javafx.collections.FXCollections;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DownloadSchedulerTest {
    static class Harness {
        final javafx.collections.ObservableList<DownloadRow> rows = FXCollections.observableArrayList();
        final Deque<Runnable> ui = new ArrayDeque<>();
        final Set<DownloadRow> busy = new HashSet<>();
        final List<DownloadRow> started = new ArrayList<>();
        final List<Boolean> resumes = new ArrayList<>();
        final DownloadScheduler scheduler = new DownloadScheduler(rows, (row, resume) -> {
            started.add(row); resumes.add(resume); busy.add(row); row.setState(DownloadRow.State.DOWNLOADING);
        }, (row, resume) -> !busy.contains(row), busy::contains, ui::add);
        DownloadRow add(String title) {
            var row = new DownloadRow("https://example.test/" + title, title, rows.size(), "/tmp", "Direct", "Auto");
            rows.add(0, row); return row;
        }
        void submit(DownloadRow row) { scheduler.request(row, false); }
        void pulse() { int limit = 100; while (!ui.isEmpty()) { if (--limit == 0) fail("Queue did not settle"); ui.remove().run(); } }
        void release(DownloadRow row) { busy.remove(row); scheduler.wake(); pulse(); }
        void finish(DownloadRow row) { row.setState(DownloadRow.State.COMPLETED); release(row); }
        void pause(DownloadRow row) { scheduler.withdraw(row); row.setState(DownloadRow.State.PAUSED); busy.remove(row); }
        void cancel(DownloadRow row) { scheduler.withdraw(row); row.setState(DownloadRow.State.CANCELLED); busy.remove(row); }
    }
    @Test void firstRequestsWinRegardlessOfNewestFirstDisplayAndPreparationKeepsItsSlot() {
        var h = new Harness(); h.scheduler.setLimit(2);
        var a=h.add("a");var b=h.add("b");var c=h.add("c");var d=h.add("d");
        for(var row:List.of(a,b,c,d))h.submit(row); h.pulse();
        assertEquals(List.of(a,b),h.started);assertEquals(DownloadRow.State.QUEUED,c.getState());
        a.setState(DownloadRow.State.PENDING);a.status.set("Finishing file");h.pulse();assertEquals(2,h.started.size());
        a.setState(DownloadRow.State.COMPLETED);h.pulse();assertEquals(2,h.started.size(),"Must wait for engine cleanup");
        h.release(a);assertEquals(List.of(a,b,c),h.started);
        b.setState(DownloadRow.State.FAILED);h.release(b);assertEquals(List.of(a,b,c,d),h.started);
    }
    @Test void pauseFreesSlotAndResumeWaitsItsTurnWithoutLosingIntent() {
        var h=new Harness();h.scheduler.setLimit(1);var a=h.add("a");var b=h.add("b");
        h.submit(a);h.submit(b);h.pulse();h.pause(a);h.pulse();assertEquals(List.of(a,b),h.started);
        h.scheduler.request(a,true);h.pulse();assertEquals(DownloadRow.State.QUEUED,a.getState());
        h.finish(b);assertEquals(List.of(a,b,a),h.started);assertEquals(List.of(false,false,true),h.resumes);
    }
    @Test void lowerLimitDoesNotInterruptAndHigherLimitImmediatelyFillsAvailableSlots() {
        var h=new Harness();h.scheduler.setLimit(3);var jobs=new ArrayList<DownloadRow>();
        for(int n=0;n<6;n++){var row=h.add(""+n);jobs.add(row);h.submit(row);}h.pulse();
        h.scheduler.setLimit(1);h.pulse();assertEquals(3,h.busy.size());
        h.finish(jobs.get(0));h.finish(jobs.get(1));assertEquals(3,h.started.size());
        h.finish(jobs.get(2));assertEquals(4,h.started.size());
        h.scheduler.setLimit(3);h.pulse();assertEquals(6,h.started.size());
    }
    @Test void bulkPauseAndCancelCannotAccidentallyStartWaitingRows() {
        for(boolean cancel:List.of(false,true)) {
            var h=new Harness();h.scheduler.setLimit(1);var a=h.add("a");var b=h.add("b");
            h.submit(a);h.submit(b);h.pulse();
            for(var row:List.of(a,b)) {if(cancel)h.cancel(row);else h.pause(row);}
            h.pulse();assertEquals(List.of(a),h.started);
            assertEquals(cancel?DownloadRow.State.CANCELLED:DownloadRow.State.PAUSED,b.getState());
        }
    }
    @Test void quickResumeWaitsForOldWorkerAndIgnoresItsLatePausedCallback() {
        var h=new Harness();h.scheduler.setLimit(1);var a=h.add("a");h.submit(a);h.pulse();
        h.scheduler.withdraw(a);a.setState(DownloadRow.State.PAUSED); // worker is still stopping
        h.scheduler.request(a,true);h.pulse();assertEquals(1,h.started.size());
        a.setState(DownloadRow.State.PAUSED);h.pulse();assertEquals(DownloadRow.State.QUEUED,a.getState());
        h.release(a);assertEquals(List.of(a,a),h.started);assertTrue(h.resumes.getLast());
    }
    @Test void duplicateClicksRemovalAndShutdownDoNotLaunchExtraWork() {
        var h=new Harness();h.scheduler.setLimit(1);var a=h.add("a");var b=h.add("b");
        h.submit(a);h.submit(a);h.submit(b);h.submit(b);h.pulse();h.submit(a);h.pulse();assertEquals(List.of(a),h.started);
        h.rows.remove(b);h.finish(a);assertEquals(List.of(a),h.started);
        var c=h.add("c");h.submit(c);h.scheduler.close();h.pulse();assertEquals(List.of(a),h.started);
    }
    @Test void explicitPauseWhileWaitingForCleanupWithdrawsTheResume() {
        var h=new Harness();var a=h.add("a");h.submit(a);h.pulse();
        h.scheduler.withdraw(a);a.setState(DownloadRow.State.PAUSED);h.scheduler.request(a,true);
        h.scheduler.withdraw(a);a.setState(DownloadRow.State.PAUSED);h.release(a);assertEquals(1,h.started.size());
    }
    @Test void restoredRowsWaitForAnExplicitRequest() {
        var h=new Harness();h.add("restored-queued");h.add("restored-paused").setState(DownloadRow.State.PAUSED);
        h.pulse();assertTrue(h.started.isEmpty());
    }
    @Test void failedStartDoesNotBlockTheNextRequest() {
        var rows=FXCollections.<DownloadRow>observableArrayList();var ui=new ArrayDeque<Runnable>();var started=new ArrayList<DownloadRow>();
        var first=new DownloadRow("https://example.test/a","a",0,"/tmp","Direct","Auto");
        var second=new DownloadRow("https://example.test/b","b",1,"/tmp","Direct","Auto");rows.addAll(first,second);
        var scheduler=new DownloadScheduler(rows,(row,resume)->{if(row==first)throw new IllegalStateException();started.add(row);},(r,b)->true,r->false,ui::add);
        scheduler.setLimit(1);scheduler.request(first,false);scheduler.request(second,false);while(!ui.isEmpty())ui.remove().run();
        assertEquals(DownloadRow.State.FAILED,first.getState());assertEquals(List.of(second),started);
    }
    @Test void readdingPausedDuplicateQueuesItsResumeEvenWhileTheOldWorkerIsStopping() {
        var h = new Harness(); h.scheduler.setLimit(1);
        var queue = new DownloadQueueService(h.rows, new java.util.concurrent.atomic.AtomicLong(), () -> "/tmp",
                null, null, null, null, h.scheduler::request, h.ui::add, null, "Video", "Audio", "Best", "mp3");
        var first = queue.enqueue("https://example.test/file", "/tmp", "Video", "Best"); h.pulse();
        h.scheduler.withdraw(first); first.setState(DownloadRow.State.PAUSED); // old worker still occupies a slot
        queue.enqueue("https://example.test/file", "/tmp", "Video", "Best"); h.pulse();
        assertEquals(1, h.rows.size()); assertEquals(DownloadRow.State.QUEUED, first.getState());
        assertEquals(1, h.started.size()); h.release(first);
        assertEquals(List.of(first, first), h.started); assertTrue(h.resumes.getLast());
    }
    @Test void taskLimitPersistsAndRejectsBadValues() {
        var storage=new SoundFeedbackServiceTest.MemoryPreferences();var prefs=new TaskPreferences(storage);
        assertEquals(3,prefs.maxActive());prefs.save(5);assertEquals(5,new TaskPreferences(storage).maxActive());
        assertThrows(IllegalArgumentException.class,()->prefs.save(0));assertThrows(IllegalArgumentException.class,()->prefs.save(17));
        assertEquals(5,prefs.maxActive());storage.putInt("grabx.tasks.maxActive",Integer.MAX_VALUE);assertEquals(3,prefs.maxActive());
    }
    @Test void startupResumeIsOptInAndPersistsWithoutBeingChangedByLimitOnlySave() {
        var storage = new SoundFeedbackServiceTest.MemoryPreferences();
        var prefs = new TaskPreferences(storage);
        assertFalse(prefs.autoResume());
        prefs.save(2, true);
        assertTrue(new TaskPreferences(storage).autoResume());
        prefs.save(4); assertTrue(prefs.autoResume());
        assertThrows(IllegalArgumentException.class, () -> prefs.save(0, false));
        assertTrue(prefs.autoResume());
        prefs.save(4, false); assertFalse(new TaskPreferences(storage).autoResume());
    }
}
