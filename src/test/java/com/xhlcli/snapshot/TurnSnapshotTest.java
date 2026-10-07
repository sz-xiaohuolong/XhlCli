package com.xhlcli.snapshot;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TurnSnapshotTest {

    @Test
    void shortCommitIdTruncatesProperly() {
        TurnSnapshot snapshot = new TurnSnapshot(
                "abcdef0123456789abcdef0123456789",
                SnapshotPhase.PRE_TURN,
                "turn-1",
                Instant.now(),
                "pre-turn turn-1"
        );
        assertEquals("abcdef0123", snapshot.shortCommitId());
        assertEquals("turn-1", snapshot.turnId());
        assertEquals(SnapshotPhase.PRE_TURN, snapshot.phase());
    }

    @Test
    void shortCommitIdHandlesShortOrNull() {
        TurnSnapshot shortSnapshot = new TurnSnapshot(
                "abc",
                SnapshotPhase.POST_TURN,
                "turn-2",
                Instant.now(),
                "post-turn"
        );
        assertEquals("abc", shortSnapshot.shortCommitId());

        TurnSnapshot nullSnapshot = new TurnSnapshot(
                null,
                SnapshotPhase.PRE_RESTORE,
                "turn-3",
                Instant.now(),
                "pre-restore"
        );
        assertNull(nullSnapshot.shortCommitId());
    }
}
