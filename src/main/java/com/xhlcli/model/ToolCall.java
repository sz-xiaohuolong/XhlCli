package com.xhlcli.model;

/** A provider-neutral function invocation supplied by the model. */
public record ToolCall(String id, String name, String argumentsJson) {}
