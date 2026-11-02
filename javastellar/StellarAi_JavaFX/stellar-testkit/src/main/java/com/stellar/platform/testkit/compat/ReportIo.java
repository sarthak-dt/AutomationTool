package com.stellar.platform.testkit.compat;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.Objects;

final class ReportIo {
    private ReportIo() {
    }

    static BufferedWriter writer(Path dir, String fileName) throws IOException {
        Files.createDirectories(dir);
        return Files.newBufferedWriter(dir.resolve(fileName), StandardCharsets.UTF_8);
    }

    static void writeText(Path dir, String fileName, String content) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(fileName), content, StandardCharsets.UTF_8);
    }

    static String csv(Object... cells) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(escape(cells[i] == null ? "" : String.valueOf(cells[i])));
        }
        return sb.toString();
    }

    static String escape(String value) {
        Objects.requireNonNull(value, "value");
        if (value.indexOf(',') >= 0 || value.indexOf('"') >= 0 || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }

    static String hex(byte[] data) {
        return data == null ? "" : HexFormat.of().formatHex(data);
    }

    static void writeln(Writer w, String line) throws IOException {
        w.write(line);
        w.write('\n');
    }
}