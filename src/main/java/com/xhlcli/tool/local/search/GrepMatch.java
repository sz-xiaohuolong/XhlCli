package com.xhlcli.tool.local.search;

import java.util.List;

public record GrepMatch(String file, int lineNumber, List<ContextLine> context) {}
