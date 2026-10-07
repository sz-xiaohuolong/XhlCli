package com.xhlcli.runtime.task;

import java.util.Locale;

public enum TaskStatus {
    ENQUEUED("enqueued"),
    RUNNING("running"),
    WAITING_FOR_APPROVAL("waiting_for_approval"),
    COMPLETED("completed"),
    FAILED("failed"),
    CANCELED("canceled");

    private final String value;

    TaskStatus(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static TaskStatus from(String value) {
        if (value == null) {
            return ENQUEUED;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (TaskStatus status : values()) {
            if (status.value.equalsIgnoreCase(normalized) || status.name().equalsIgnoreCase(normalized)) {
                return status;
            }
        }
        return ENQUEUED;
    }
}
