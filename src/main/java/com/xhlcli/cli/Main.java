package com.xhlcli.cli;

public final class Main {
    private Main() {}

    public static void main(String[] args) {
        int exitCode = new CliApplication(version(), System.out, System.err).run(args);
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
