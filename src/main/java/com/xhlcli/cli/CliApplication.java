package com.xhlcli.cli;

import java.io.PrintStream;
import java.util.Objects;

public final class CliApplication {
    private final String version;
    private final PrintStream out;
    private final PrintStream err;
    private final ChatRunner chatRunner;

    public CliApplication(String version, PrintStream out, PrintStream err, ChatRunner chatRunner) {
        this.version = Objects.requireNonNull(version);
        this.out = Objects.requireNonNull(out);
        this.err = Objects.requireNonNull(err);
        this.chatRunner = Objects.requireNonNull(chatRunner);
    }

    public int run(String[] args) {
        Objects.requireNonNull(args);
        if (args.length == 1 && "--help".equals(args[0])) {
            printHelp();
            return 0;
        }
        if (args.length == 1 && "--version".equals(args[0])) {
            out.printf("XhlCLI %s%n", version);
            return 0;
        }
        for (int index = 0; index < args.length; index += 2) {
            if (!isChatOption(args[index])) {
                err.printf("Unknown option: %s%n", args[index]);
                err.println("Run with --help for available options.");
                return 2;
            }
        }
        return chatRunner.run(args);
    }

    private boolean isChatOption(String option) {
        return switch (option) {
            case "--model", "--base-url", "--connect-timeout", "--read-timeout",
                    "--request-timeout", "--max-iterations", "--agent-timeout", "--log-level" -> true;
            default -> false;
        };
    }

    private void printHelp() {
        out.println("Usage: xhlcli [options]");
        out.println("  --help       Show this help message");
        out.println("  --version    Show the current version");
        out.println("  --model <name>");
        out.println("  --base-url <url>");
        out.println("  --connect-timeout <seconds>");
        out.println("  --read-timeout <seconds>");
        out.println("  --request-timeout <seconds>");
        out.println("  --max-iterations <1-100>");
        out.println("  --agent-timeout <1-3600 seconds>");
        out.println("  --log-level <ERROR|WARN|INFO|DEBUG>");
        out.println();
        out.println("Chat commands: /help /config /clear /exit");
    }
}
