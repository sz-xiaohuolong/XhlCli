package com.xhlcli.desktop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class DesktopStoreTest {
    @TempDir Path home;
    @Test void marksUnfinishedTurnsInterruptedAndRejectsSecondProcess() throws Exception {
        try(var store=new DesktopStore(home)) {
            store.state.withArray("sessions").addObject().put("id","s").putArray("turns").addObject().put("status","approval");store.save();
            assertThrows(Exception.class,()->new DesktopStore(home));
        }
        try(var store=new DesktopStore(home)) {
            assertEquals("interrupted",store.state.path("sessions").get(0).path("turns").get(0).path("status").asText());
        }
    }
    @Test void pendingPlanSurvivesRestartAsHistoryWithoutExecution() throws Exception {
        try(var store=new DesktopStore(home)) {
            var turn=store.state.withArray("sessions").addObject().put("id","s").putArray("turns")
                    .addObject().put("status","plan_review");
            turn.putArray("events").addObject().put("type","plan.ready").putObject("data").put("id","plan-v1").put("summary","待确认方案");
            store.save();
        }
        try(var store=new DesktopStore(home)) {
            var turn=store.state.path("sessions").get(0).path("turns").get(0);
            assertEquals("interrupted",turn.path("status").asText());
            assertEquals("待确认方案",turn.path("events").get(0).path("data").path("summary").asText());
        }
    }
    @Test void preservesCorruptedState() throws Exception {
        Path dir=Files.createDirectories(home.resolve(".xhlcli/desktop"));Path file=dir.resolve("state.json");Files.writeString(file,"{invalid");
        assertThrows(Exception.class,()->new DesktopStore(home));assertEquals("{invalid",Files.readString(file));
    }
}
