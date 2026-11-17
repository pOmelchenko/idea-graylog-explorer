package dev.graylog.jetbrains;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.IOException;
import java.io.PushbackReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

/** Incremental RFC 4180 reader; handles multiline fields and never buffers the whole export. */
final class CsvRecords implements AutoCloseable {
    private static final int MAX_RECORD_CHARS = 16 * 1024 * 1024;
    private final PushbackReader reader;
    CsvRecords(Reader reader) { this.reader = new PushbackReader(new BufferedReader(reader), 1); }

    List<String> next() throws IOException, InterruptedException {
        List<String> result = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false, closedQuote = false, started = false;
        int length = 0;
        while (true) {
            if ((length & 4095) == 0 && Thread.currentThread().isInterrupted()) throw new InterruptedException();
            int c = reader.read();
            if (c == -1) {
                if (quoted) throw new EOFException("Incomplete CSV export: unterminated quoted field.");
                if (!started && result.isEmpty() && field.isEmpty()) return null;
                result.add(field.toString()); return result;
            }
            started = true;
            if (++length > MAX_RECORD_CHARS) throw new IOException("A Graylog export record exceeds 16 MiB; the period index was not completed.");
            if (quoted) {
                if (c == '"') {
                    int next = reader.read();
                    if (next == '"') field.append('"');
                    else { quoted = false; closedQuote = true; if (next != -1) reader.unread(next); }
                } else field.append((char) c);
            } else if (c == ',') {
                if (result.size() >= 255) throw new IOException("Too many columns in Graylog CSV export.");
                result.add(field.toString()); field.setLength(0); closedQuote = false;
            } else if (c == '\n' || c == '\r') {
                if (c == '\r') { int next = reader.read(); if (next != '\n' && next != -1) reader.unread(next); }
                result.add(field.toString()); return result;
            } else if (c == '"' && field.isEmpty() && !closedQuote) quoted = true;
            else {
                if (closedQuote || c == '"') throw new IOException("Malformed CSV export; the period index was not completed.");
                field.append((char) c);
            }
        }
    }
    @Override public void close() throws IOException { reader.close(); }
}
