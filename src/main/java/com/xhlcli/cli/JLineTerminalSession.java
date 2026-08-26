package com.xhlcli.cli;

import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.IOException;
import java.util.Objects;

public final class JLineTerminalSession implements InputReader, AutoCloseable {
    private final Terminal terminal;
    private final LineReader reader;

    public JLineTerminalSession() throws IOException {
        terminal = TerminalBuilder.builder().system(true).build();
        reader = LineReaderBuilder.builder().terminal(terminal).build();
    }

    public void bind(ChatLoop loop) {
        Objects.requireNonNull(loop, "loop");
        terminal.handle(Terminal.Signal.INT, signal -> {
            if (!loop.cancelActiveResponse()) {
                reader.callWidget(LineReader.INTERRUPT);
            }
        });
    }

    @Override
    public String readLine(String prompt) {
        try {
            return reader.readLine(prompt);
        } catch (UserInterruptException failure) {
            throw new InputInterruptedException();
        } catch (EndOfFileException failure) {
            throw new InputEndOfFileException();
        }
    }

    @Override
    public void close() throws IOException {
        terminal.close();
    }
}
