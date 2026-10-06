package com.xhlcli.cli;

import org.jline.reader.Completer;
import org.jline.reader.EndOfFileException;
import org.jline.reader.Highlighter;
import org.jline.reader.History;
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
        this(null, null, null);
    }

    public JLineTerminalSession(Completer completer, Highlighter highlighter, History history) throws IOException {
        terminal = TerminalBuilder.builder().system(true).build();
        LineReaderBuilder builder = LineReaderBuilder.builder().terminal(terminal);
        if (completer != null) {
            builder.completer(completer);
        }
        if (highlighter != null) {
            builder.highlighter(highlighter);
        }
        if (history != null) {
            builder.history(history);
        }
        reader = builder.build();
    }

    public Terminal terminal() {
        return terminal;
    }

    public LineReader reader() {
        return reader;
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
