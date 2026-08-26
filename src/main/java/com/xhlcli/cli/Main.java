package com.xhlcli.cli;

import java.nio.file.Path;

public final class Main {
    private Main() {}

    public static void main(String[] args) {
        ChatRunner runner = new ChatBootstrap(
                System.getenv(),
                Path.of("").toAbsolutePath(),
                Path.of(System.getProperty("user.home")),
                System.out,
                System.err);
        int exitCode = new CliApplication(version(), System.out, System.err, runner).run(args);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static String version() {
        String implementationVersion = Main.class.getPackage().getImplementationVersion();
        return implementationVersion == null || implementationVersion.isBlank()
                ? "dev"
                : implementationVersion;
    }
}
