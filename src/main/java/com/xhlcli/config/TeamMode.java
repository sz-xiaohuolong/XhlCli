package com.xhlcli.config;

/** 普通对话是否允许模型选择团队协作。 */
public enum TeamMode {
    MANUAL, AUTO;

    public static TeamMode parse(String value) throws ConfigurationException {
        if (value != null) {
            if (value.equalsIgnoreCase("manual")) return MANUAL;
            if (value.equalsIgnoreCase("auto")) return AUTO;
        }
        throw new ConfigurationException("team-mode 仅支持 manual 或 auto");
    }
}
