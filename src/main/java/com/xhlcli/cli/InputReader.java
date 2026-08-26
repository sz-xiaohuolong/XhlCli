package com.xhlcli.cli;

@FunctionalInterface
public interface InputReader {
    String readLine(String prompt) throws InputEndOfFileException, InputInterruptedException;
}
