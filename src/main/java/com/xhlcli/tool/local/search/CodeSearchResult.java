package com.xhlcli.tool.local.search;

import java.util.List;

public record CodeSearchResult(
        String engine,
        List<GrepMatch> matches,
        boolean partial,
        String partialReason) {}
