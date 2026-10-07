package com.xhlcli.runtime.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TaskStatusTest {

    @Test
    void parsesStatusCorrectly() {
        assertEquals(TaskStatus.ENQUEUED, TaskStatus.from("enqueued"));
        assertEquals(TaskStatus.RUNNING, TaskStatus.from("running"));
        assertEquals(TaskStatus.WAITING_FOR_APPROVAL, TaskStatus.from("waiting_for_approval"));
        assertEquals(TaskStatus.COMPLETED, TaskStatus.from("completed"));
        assertEquals(TaskStatus.FAILED, TaskStatus.from("failed"));
        assertEquals(TaskStatus.CANCELED, TaskStatus.from("canceled"));

        // case insensitivity and fallback
        assertEquals(TaskStatus.ENQUEUED, TaskStatus.from("RUNNING_UNKNOWN"));
        assertEquals(TaskStatus.RUNNING, TaskStatus.from("RUNNING"));
        assertEquals(TaskStatus.ENQUEUED, TaskStatus.from(null));
        assertEquals(TaskStatus.ENQUEUED, TaskStatus.from(""));
    }

    @Test
    void returnsCorrectValue() {
        assertEquals("enqueued", TaskStatus.ENQUEUED.value());
        assertEquals("running", TaskStatus.RUNNING.value());
        assertEquals("waiting_for_approval", TaskStatus.WAITING_FOR_APPROVAL.value());
        assertEquals("completed", TaskStatus.COMPLETED.value());
        assertEquals("failed", TaskStatus.FAILED.value());
        assertEquals("canceled", TaskStatus.CANCELED.value());
    }
}
