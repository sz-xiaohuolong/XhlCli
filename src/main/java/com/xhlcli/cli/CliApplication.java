package com.xhlcli.cli;

import java.io.PrintStream;
import java.util.Objects;

public final class CliApplication {
    private final String version;
    private final PrintStream out;
    private final PrintStream err;

    public CliApplication(String version, PrintStream out, PrintStream err) {
        this.version = Objects.requireNonNull(version);
        this.out = Objects.requireNonNull(out);
        this.err = Objects.requireNonNull(err);
    }

    public int run(String[] args) {
        Objects.requireNonNull(args);
        if (args.length == 0) {
            out.println("XhlCLI — local-first terminal coding agent");
            out.println("Phase 00 foundation is ready. Run with --help for options.");
            return 0;
        }
        if (args.length == 1 && "--help".equals(args[0])) {
            printHelp();
            return 0;
        }
        if (args.length == 1 && "--version".equals(args[0])) {
            out.printf("XhlCLI %s%n", version);
            return 0;
        }
        err.printf("Unknown option: %s%n", String.join(" ", args));
        err.println("Run with --help for available options.");
        return 2;
    }

    private void printHelp() {
        out.println("Usage: xhlcli [option]");
        out.println("  --help       Show this help message");
        out.println("  --version    Show the current version");
    }
}
