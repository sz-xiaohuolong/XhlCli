package com.xhlcli.mcp.protocol;

/**
 * Constants defined in the Model Context Protocol (MCP) specification.
 */
public final class McpConstants {
    private McpConstants() {}

    public static final String JSONRPC_VERSION = "2.0";
    public static final String PROTOCOL_VERSION = "2024-11-05";
    public static final String CLIENT_NAME = "xhlcli";
    public static final String CLIENT_VERSION = "0.10.0";

    // Standard MCP methods
    public static final String METHOD_INITIALIZE = "initialize";
    public static final String METHOD_NOTIFICATIONS_INITIALIZED = "notifications/initialized";
    public static final String METHOD_TOOLS_LIST = "tools/list";
    public static final String METHOD_TOOLS_CALL = "tools/call";
    public static final String METHOD_RESOURCES_LIST = "resources/list";
    public static final String METHOD_RESOURCES_READ = "resources/read";

    // Notifications
    public static final String NOTIFICATION_TOOLS_LIST_CHANGED = "notifications/tools/list_changed";
    public static final String NOTIFICATION_RESOURCES_LIST_CHANGED = "notifications/resources/list_changed";
    public static final String NOTIFICATION_CANCELLED = "notifications/cancelled";
}
