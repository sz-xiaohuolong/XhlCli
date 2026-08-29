package com.xhlcli.tool.local.search;

import java.nio.file.Path;

public record CodeSearchRequest(
        String query,
        Path root,
        Path projectRoot,
        String glob,
        boolean regex,
        boolean caseSensitive,
        int contextLines,
        int maxResults,
        int headLimit) {}
