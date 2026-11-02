package com.stellar.platform.dbc.parse;

import com.stellar.platform.dbc.model.CanIdKey;
import com.stellar.platform.dbc.model.DbcDatabase;
import com.stellar.platform.dbc.model.DbcMessage;
import com.stellar.platform.dbc.model.DbcMultiplexCondition;
import com.stellar.platform.dbc.model.DbcMultiplexRange;
import com.stellar.platform.dbc.model.DbcRawEncoding;
import com.stellar.platform.dbc.model.DbcSignal;
import com.stellar.platform.dbc.model.DbcValueTable;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Streaming / line-oriented DBC parser for the agreed production scope.
 * <p>
 * Messages and signals are collected first; {@code VAL_}, comments, and
 * {@code SG_MUL_VAL_} are applied as a resolution pass so metadata may appear
 * after {@code SG_} definitions.
 */
public final class DbcParser {

    /**
     * Prefix of the soft diagnostic recorded when {@link #parse(Path)} reads defined
     * Windows-1252 bytes in {@code 0x80..0x9F}. The file is still imported.
     */
    public static final String WINDOWS_1252_WARNING_PREFIX = "Windows-1252 encoding was used";

    /**
     * Prefix of the soft diagnostic recorded when a {@code VAL_} or {@code VAL_TABLE_}
     * statement has no terminating semicolon before the next record or the end of the file.
     * The description is not loaded. A statement that spans lines and ends with a semicolon
     * is loaded and does not use this prefix.
     */
    public static final String MULTILINE_VAL_WARNING_PREFIX = "Unterminated VAL_";

    /**
     * Prefix of the soft diagnostic recorded when {@code SIG_VALTYPE_} cannot be applied.
     * Integer decoding is kept.
     */
    public static final String SIG_VALTYPE_WARNING_PREFIX = "Unsupported SIG_VALTYPE_";

    /** Prefix of the aggregated notice for other ignored DBC records. */
    public static final String IGNORED_RECORDS_WARNING_PREFIX = "Ignored DBC records:";

    /**
     * Unicode mapping for Windows-1252 bytes {@code 0x80..0x9F}. A zero entry is an undefined
     * byte ({@code 0x81}, {@code 0x8D}, {@code 0x8F}, {@code 0x90}, {@code 0x9D}).
     */
    private static final char[] WINDOWS_1252_C1 = {
            '\u20AC', '\u0000', '\u201A', '\u0192', '\u201E', '\u2026', '\u2020', '\u2021',
            '\u02C6', '\u2030', '\u0160', '\u2039', '\u0152', '\u0000', '\u017D', '\u0000',
            '\u0000', '\u2018', '\u2019', '\u201C', '\u201D', '\u2022', '\u2013', '\u2014',
            '\u02DC', '\u2122', '\u0161', '\u203A', '\u0153', '\u0000', '\u017E', '\u0178'
    };

    private static final Pattern BO = Pattern.compile(
            "^BO_\\s+(\\d+)\\s+([^:\\s]+)\\s*:\\s*(\\d+)\\s+(\\S+)\\s*$");
    private static final Pattern CM_BO = Pattern.compile("^CM_\\s+BO_\\s+(\\d+)\\s+\"(.*)\"\\s*;\\s*$");
    private static final Pattern CM_SG = Pattern.compile("^CM_\\s+SG_\\s+(\\d+)\\s+(\\S+)\\s+\"(.*)\"\\s*;\\s*$");
    private static final Pattern VERSION = Pattern.compile("^VERSION\\s+\"(.*)\"\\s*$");
    private static final Pattern BU = Pattern.compile("^BU_\\s*:\\s*(.*)$");
    private static final Pattern SG_MUL_VAL = Pattern.compile(
            "^SG_MUL_VAL_\\s+(\\d+)\\s+(\\S+)\\s+(\\S+)\\s+(.+?)\\s*;\\s*$");
    private static final Pattern MUL_RANGE = Pattern.compile("(-?\\d+)\\s*-\\s*(-?\\d+)");
    private static final Pattern BA_DEF_SG = Pattern.compile(
            "^BA_DEF_\\s+SG_\\s+\"([^\"]+)\"\\s+(INT|HEX|FLOAT|STRING|ENUM)\\b.*",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BA_SG = Pattern.compile(
            "^BA_\\s+\"([^\"]+)\"\\s+SG_\\s+(\\d+)\\s+(\\S+)\\s+(.+?)\\s*;\\s*$");
    private static final Pattern SIG_VALTYPE = Pattern.compile(
            "^SIG_VALTYPE_\\s+(\\d+)\\s+(\\S+)\\s*:\\s*(\\d+)\\s*;\\s*$");

    public DbcDatabase parse(Path path) throws DbcParseException {
        Objects.requireNonNull(path, "path");
        byte[] raw;
        try {
            raw = Files.readAllBytes(path);
        } catch (IOException e) {
            throw new DbcParseException("Failed to read DBC file: " + path, e);
        }
        CharsetChoice choice = charsetFor(raw);
        String text = choice.windows1252()
                ? decodeWindows1252(raw)
                : new String(raw, choice.charset());
        try (BufferedReader reader = new BufferedReader(new StringReader(text))) {
            DbcDatabase database = parse(reader, fileName(path));
            if (choice.warning() == null) {
                return database;
            }
            List<String> diagnostics = new ArrayList<>();
            diagnostics.add(choice.warning());
            diagnostics.addAll(database.diagnostics());
            return new DbcDatabase(
                    database.version(), database.nodes(), database.messages(), diagnostics);
        } catch (IOException e) {
            throw new DbcParseException("Failed to read DBC file: " + path, e);
        }
    }

    /**
     * Encoding chosen for a DBC that is not valid UTF-8.
     * <p>
     * Bytes {@code 0xA0..0xFF} are identical in ISO-8859-1 and Windows-1252, so they stay
     * ISO-8859-1 and produce no warning. Defined Windows-1252 bytes in {@code 0x80..0x9F}
     * are decoded with that mapping and recorded as a warning. Undefined Windows-1252 bytes
     * and broken UTF-8 lead bytes are rejected.
     */
    static CharsetChoice charsetFor(byte[] raw) throws DbcParseException {
        if (isStrictUtf8(raw)) {
            return new CharsetChoice(StandardCharsets.UTF_8, false, null);
        }
        int undefinedAt = -1;
        int leadAt = -1;
        int definedCount = 0;
        int firstDefinedAt = -1;
        for (int i = 0; i < raw.length; i++) {
            int b = raw[i] & 0xFF;
            if (b < 0x80) {
                continue;
            }
            if (b <= 0x9F) {
                if (windows1252C1(b) == 0) {
                    if (undefinedAt < 0) {
                        undefinedAt = i;
                    }
                } else {
                    definedCount++;
                    if (firstDefinedAt < 0) {
                        firstDefinedAt = i;
                    }
                }
            } else if (b >= 0xC2 && b <= 0xF4 && leadAt < 0) {
                leadAt = i;
            }
        }
        if (undefinedAt >= 0) {
            throw encodingRejection(
                    undefinedAt,
                    raw,
                    "DBC byte 0x" + hex(raw[undefinedAt])
                            + " is not a defined Windows-1252 character. The encoding was not guessed.");
        }
        if (leadAt >= 0) {
            throw encodingRejection(
                    leadAt,
                    raw,
                    "DBC byte 0x" + hex(raw[leadAt])
                            + " starts a UTF-8 sequence that is not valid. The file was not"
                            + " reinterpreted as a single-byte encoding.");
        }
        if (definedCount > 0) {
            int firstByte = raw[firstDefinedAt] & 0xFF;
            char mapped = windows1252C1(firstByte);
            return new CharsetChoice(
                    null,
                    true,
                    WINDOWS_1252_WARNING_PREFIX + " for " + definedCount
                            + " byte(s) in 0x80-0x9F. The first is byte 0x" + hexByte(firstByte)
                            + " at line " + lineNumberAt(raw, firstDefinedAt)
                            + ", read as U+" + String.format(java.util.Locale.ROOT, "%04X", (int) mapped)
                            + ". Those bytes are not valid UTF-8."
                            + " ISO-8859-1 would assign different characters.");
        }
        return new CharsetChoice(StandardCharsets.ISO_8859_1, false, null);
    }

    /**
     * The Windows-1252 warning on a database parsed by {@link #parse(Path)}, or null when that
     * encoding was not used.
     */
    public static String windows1252Warning(DbcDatabase database) {
        if (database == null) {
            return null;
        }
        for (String diagnostic : database.diagnostics()) {
            if (diagnostic != null && diagnostic.startsWith(WINDOWS_1252_WARNING_PREFIX)) {
                return diagnostic;
            }
        }
        return null;
    }

    /**
     * Short warnings for a successful import. Detailed parser diagnostics stay on
     * {@link DbcDatabase#diagnostics()}. Repeated records are counted here so the UI
     * does not repeat every ignored line.
     */
    public static List<String> importWarnings(DbcDatabase database) {
        if (database == null) {
            return List.of();
        }
        List<String> encoding = new ArrayList<>();
        List<String> multilineVal = new ArrayList<>();
        List<String> valueTypes = new ArrayList<>();
        int ignored = 0;
        for (String diagnostic : database.diagnostics()) {
            if (diagnostic == null || diagnostic.isBlank()) {
                continue;
            }
            if (diagnostic.startsWith(WINDOWS_1252_WARNING_PREFIX)) {
                encoding.add(diagnostic);
            } else if (diagnostic.startsWith(MULTILINE_VAL_WARNING_PREFIX)) {
                multilineVal.add(diagnostic);
            } else if (diagnostic.startsWith(SIG_VALTYPE_WARNING_PREFIX)) {
                valueTypes.add(diagnostic);
            } else {
                ignored++;
            }
        }
        List<String> visible = new ArrayList<>();
        visible.addAll(encoding);
        if (!multilineVal.isEmpty()) {
            visible.add(aggregateMultilineVal(multilineVal));
        }
        if (!valueTypes.isEmpty()) {
            visible.add(aggregateSigValType(valueTypes));
        }
        if (ignored > 0) {
            visible.add(IGNORED_RECORDS_WARNING_PREFIX + " " + ignored
                    + (ignored == 1 ? " other DBC record was" : " other DBC records were")
                    + " ignored. Detailed diagnostics were kept and were not applied to numeric decoding.");
        }
        return List.copyOf(visible);
    }

    private static String aggregateMultilineVal(List<String> details) {
        List<Long> lines = diagnosticLineNumbers(details);
        StringBuilder message = new StringBuilder(MULTILINE_VAL_WARNING_PREFIX);
        message.append(": ").append(details.size())
                .append(details.size() == 1
                        ? " value description was not loaded"
                        : " value descriptions were not loaded");
        appendLineList(message, lines, 12);
        message.append(". Numeric decoding is unchanged.");
        return message.toString();
    }

    private static String aggregateSigValType(List<String> details) {
        StringBuilder message = new StringBuilder(SIG_VALTYPE_WARNING_PREFIX);
        message.append(": ").append(details.size())
                .append(details.size() == 1
                        ? " definition kept integer decoding ("
                        : " definitions kept integer decoding (");
        int shown = Math.min(details.size(), 8);
        for (int i = 0; i < shown; i++) {
            if (i > 0) {
                message.append("; ");
            }
            message.append(signalIdentity(details.get(i)));
        }
        if (details.size() > shown) {
            message.append("; and ").append(details.size() - shown).append(" more");
        }
        message.append("). The physical conversion formula was not changed.");
        return message.toString();
    }

    private static void appendLineList(StringBuilder message, List<Long> lines, int limit) {
        if (lines.isEmpty()) {
            return;
        }
        message.append(" (lines ");
        int shown = Math.min(lines.size(), limit);
        for (int i = 0; i < shown; i++) {
            if (i > 0) {
                message.append(", ");
            }
            message.append(lines.get(i));
        }
        if (lines.size() > shown) {
            message.append(", and ").append(lines.size() - shown).append(" more");
        }
        message.append(")");
    }

    private static String signalIdentity(String diagnostic) {
        Matcher name = Pattern.compile("signal '([^']+)'").matcher(diagnostic);
        String signal = name.find() ? name.group(1) : "unknown signal";
        List<Long> lines = diagnosticLineNumbers(List.of(diagnostic));
        if (lines.isEmpty()) {
            return "signal '" + signal + "'";
        }
        return "signal '" + signal + "' at line " + lines.getFirst();
    }

    private static List<Long> diagnosticLineNumbers(List<String> diagnostics) {
        List<Long> lines = new ArrayList<>();
        Matcher matcher = Pattern.compile("at line (\\d+)").matcher("");
        for (String diagnostic : diagnostics) {
            matcher.reset(diagnostic);
            if (matcher.find()) {
                lines.add(Long.parseLong(matcher.group(1)));
            }
        }
        return lines;
    }

    private static char windows1252C1(int unsignedByte) {
        int index = unsignedByte - 0x80;
        if (index < 0 || index >= WINDOWS_1252_C1.length) {
            return 0;
        }
        return WINDOWS_1252_C1[index];
    }

    private static String decodeWindows1252(byte[] raw) {
        StringBuilder text = new StringBuilder(raw.length);
        for (byte value : raw) {
            int b = value & 0xFF;
            if (b >= 0x80 && b <= 0x9F) {
                text.append(windows1252C1(b));
            } else {
                text.append((char) b);
            }
        }
        return text.toString();
    }

    private static long lineNumberAt(byte[] raw, int offset) {
        long line = 1;
        for (int i = 0; i < offset; i++) {
            if (raw[i] == '\n') {
                line++;
            }
        }
        return line;
    }

    private record CharsetChoice(Charset charset, boolean windows1252, String warning) {
    }

    private static boolean isStrictUtf8(byte[] raw) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            decoder.decode(ByteBuffer.wrap(raw));
            return true;
        } catch (CharacterCodingException ex) {
            return false;
        }
    }

    private static DbcParseException encodingRejection(int offset, byte[] raw, String message) {
        long line = 1;
        int lineStart = 0;
        for (int i = 0; i < offset; i++) {
            if (raw[i] == '\n') {
                line++;
                lineStart = i + 1;
            }
        }
        int lineEnd = offset;
        while (lineEnd < raw.length && raw[lineEnd] != '\n' && raw[lineEnd] != '\r') {
            lineEnd++;
        }
        String source = new String(raw, lineStart, lineEnd - lineStart, StandardCharsets.ISO_8859_1);
        return new DbcParseException(DbcDiagnosticCode.MALFORMED_DBC, message, line, source);
    }

    private static String hex(byte value) {
        return hexByte(value & 0xFF);
    }

    private static String hexByte(int unsignedByte) {
        return Integer.toHexString(unsignedByte & 0xFF).toUpperCase(java.util.Locale.ROOT);
    }

    public DbcDatabase parse(Path path, Charset charset) throws DbcParseException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(charset, "charset");
        try {
            byte[] raw = Files.readAllBytes(path);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    new ByteArrayInputStream(raw), charset))) {
                return parse(reader, fileName(path));
            }
        } catch (IOException e) {
            throw new DbcParseException("Failed to read DBC file: " + path, e);
        }
    }

    public DbcDatabase parse(BufferedReader reader) throws DbcParseException, IOException {
        return parse(reader, null);
    }

    private DbcDatabase parse(BufferedReader reader, String sourceName) throws DbcParseException, IOException {
        String version = "";
        List<String> nodes = new ArrayList<>();
        List<String> softDiagnostics = new ArrayList<>();
        Map<CanIdKey, MessageBuilder> messages = new LinkedHashMap<>();
        Map<String, AttributeKind> attributeKinds = new LinkedHashMap<>();
        List<PendingAttribute> attributes = new ArrayList<>();
        List<PendingValType> valTypes = new ArrayList<>();
        MessageBuilder current = null;
        long lineNo = 0;
        boolean inNs = false;
        boolean sawStructure = false;
        String carry = null;
        Map<String, DbcValueTable> namedTables = new LinkedHashMap<>();
        Map<String, String> signalTypeTables = new LinkedHashMap<>();
        List<PendingTypeRef> typeRefs = new ArrayList<>();

        String line;
        while (true) {
            if (carry != null) {
                line = carry;
                carry = null;
            } else {
                line = reader.readLine();
                if (line == null) {
                    break;
                }
                lineNo++;
            }
            String trimmed = stripComment(line).trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            if (trimmed.startsWith("NS_ ") || trimmed.equals("NS_") || trimmed.startsWith("NS_:")) {
                inNs = true;
                sawStructure = true;
                continue;
            }
            if (inNs) {
                // New-symbol list continues until BS_ / BU_: / real BO_ / VERSION.
                // Do not treat BU_SG_REL_/BO_TX_* namespace entries as section terminators.
                if (trimmed.startsWith("BS_")
                        || trimmed.matches("^BU_\\s*:.*")
                        || (trimmed.startsWith("BO_") && !trimmed.startsWith("BO_TX_"))
                        || trimmed.startsWith("VERSION")) {
                    inNs = false;
                } else {
                    continue;
                }
            }

            Matcher versionMatcher = VERSION.matcher(trimmed);
            if (versionMatcher.matches()) {
                version = versionMatcher.group(1);
                sawStructure = true;
                continue;
            }

            if (trimmed.startsWith("BS_")) {
                sawStructure = true;
                continue;
            }

            Matcher bu = BU.matcher(trimmed);
            if (bu.matches()) {
                sawStructure = true;
                String rest = bu.group(1).trim();
                if (!rest.isEmpty()) {
                    for (String n : rest.split("\\s+")) {
                        if (!n.isBlank()) {
                            nodes.add(n.trim());
                        }
                    }
                }
                continue;
            }

            if (trimmed.startsWith("BO_") && !trimmed.startsWith("BO_TX_")) {
                Matcher bo = BO.matcher(trimmed);
                if (!bo.matches()) {
                    throw new DbcParseException(
                            DbcDiagnosticCode.MALFORMED_DBC,
                            "Malformed BO_ line",
                            lineNo,
                            trimmed);
                }
                sawStructure = true;
                try {
                    long rawId = Long.parseLong(bo.group(1));
                    CanIdKey key = CanIdKey.fromDbcRawId(rawId);
                    String name = bo.group(2);
                    int size = Integer.parseInt(bo.group(3));
                    if (size < 0 || size > 64) {
                        throw new DbcParseException(
                                DbcDiagnosticCode.UNSUPPORTED_MESSAGE_SIZE,
                                unsupportedDlcMessage(sourceName, rawId, name, size),
                                lineNo,
                                trimmed);
                    }
                    String tx = bo.group(4);
                    current = new MessageBuilder(key, name, size, tx, lineNo);
                    if (messages.containsKey(key)) {
                        softDiagnostics.add("Duplicate BO_ for ID " + key + " at line " + lineNo);
                    }
                    messages.put(key, current);
                } catch (RuntimeException e) {
                    throw new DbcParseException(
                            DbcDiagnosticCode.MALFORMED_DBC,
                            "Malformed BO_ (" + e.getMessage() + ")",
                            lineNo,
                            trimmed,
                            e
                    );
                }
                continue;
            }

            if (trimmed.startsWith("SG_MUL_VAL_")) {
                sawStructure = true;
                applySgMulVal(messages, trimmed, lineNo, softDiagnostics);
                continue;
            }

            if (trimmed.startsWith("BA_DEF_DEF_")) {
                sawStructure = true;
                continue;
            }
            if (trimmed.startsWith("BA_DEF_")) {
                sawStructure = true;
                noteAttributeDefinition(trimmed, attributeKinds);
                continue;
            }
            if (trimmed.startsWith("BA_")) {
                sawStructure = true;
                noteAttributeAssignment(trimmed, lineNo, attributes, softDiagnostics);
                continue;
            }
            if (trimmed.startsWith("SIG_VALTYPE_")) {
                sawStructure = true;
                noteSigValType(trimmed, lineNo, valTypes, softDiagnostics);
                continue;
            }

            if (startsValueStatement(trimmed)) {
                sawStructure = true;
                CompletedStatement statement = completeStatement(reader, trimmed, lineNo);
                lineNo = statement.endLine();
                carry = statement.pushback();
                if (!statement.terminated()) {
                    noteUnterminatedValueStatement(statement.text(), statement.startLine(), softDiagnostics);
                    continue;
                }
                String logical = statement.text();
                if (logical.startsWith("VAL_TABLE_")) {
                    applyValTable(namedTables, logical, statement.startLine(), softDiagnostics);
                } else if (logical.startsWith("SGTYPE_")) {
                    noteSignalType(signalTypeTables, logical, statement.startLine(), softDiagnostics);
                } else if (logical.startsWith("SIG_TYPE_REF_")) {
                    noteTypeRef(typeRefs, logical, statement.startLine(), softDiagnostics);
                } else {
                    applyValStatement(messages, logical, statement.startLine(), softDiagnostics);
                }
                continue;
            }

            if (trimmed.startsWith("BO_TX_BU_")
                    || trimmed.startsWith("EV_")
                    || trimmed.startsWith("ENVVAR_DATA_")
                    || trimmed.startsWith("SIG_GROUP_")
                    || trimmed.startsWith("CAT_DEF_")
                    || trimmed.startsWith("CAT_")
                    || trimmed.startsWith("FILTER")) {
                sawStructure = true;
                softDiagnostics.add("Skipped unsupported/ignored construct at line " + lineNo + ": "
                        + preview(trimmed));
                continue;
            }

            if (trimmed.startsWith("SG_")) {
                sawStructure = true;
                if (current == null) {
                    throw new DbcParseException(
                            DbcDiagnosticCode.DBC_SG_MALFORMED,
                            "SG_ without preceding BO_",
                            lineNo,
                            trimmed
                    );
                }
                DbcSignal signal = SgSignalLineParser.parse(trimmed, lineNo);
                current.signals.add(signal);
                continue;
            }

            Matcher cmBo = CM_BO.matcher(trimmed);
            if (cmBo.matches()) {
                sawStructure = true;
                CanIdKey key = CanIdKey.fromDbcRawId(Long.parseLong(cmBo.group(1)));
                MessageBuilder mb = messages.get(key);
                if (mb != null) {
                    mb.comment = unescape(cmBo.group(2));
                }
                continue;
            }

            Matcher cmSg = CM_SG.matcher(trimmed);
            if (cmSg.matches()) {
                sawStructure = true;
                CanIdKey key = CanIdKey.fromDbcRawId(Long.parseLong(cmSg.group(1)));
                MessageBuilder mb = messages.get(key);
                if (mb != null) {
                    String sigName = cmSg.group(2);
                    String comment = unescape(cmSg.group(3));
                    mb.signalComments.put(sigName, comment);
                }
                continue;
            }

            softDiagnostics.add("Unrecognized DBC line at " + lineNo + " (ignored): " + preview(trimmed));
        }
        if (!sawStructure) {
            throw new DbcParseException(
                    DbcDiagnosticCode.MALFORMED_DBC,
                    "This file is not a DBC. It has no VERSION, node list, or message definition.");
        }

        resolveTypeRefs(messages, namedTables, signalTypeTables, typeRefs, softDiagnostics);

        List<DbcMessage> built = new ArrayList<>();
        for (MessageBuilder mb : messages.values()) {
            try {
                built.add(mb.build(softDiagnostics, attributeKinds, attributes, valTypes));
            } catch (IllegalArgumentException ex) {
                if (ex.getMessage() != null && ex.getMessage().contains("message size")) {
                    throw new DbcParseException(
                            DbcDiagnosticCode.UNSUPPORTED_MESSAGE_SIZE,
                            unsupportedDlcMessage(sourceName, mb.key.toDbcRawId(), mb.name, mb.size),
                            mb.lineNo,
                            null,
                            ex);
                }
                throw new DbcParseException(
                        DbcDiagnosticCode.MALFORMED_DBC,
                        "Malformed BO_ (" + ex.getMessage() + ")",
                        mb.lineNo,
                        null,
                        ex);
            }
        }
        return new DbcDatabase(version, nodes, built, softDiagnostics);
    }

    private static void applySgMulVal(
            Map<CanIdKey, MessageBuilder> messages,
            String trimmed,
            long lineNo,
            List<String> softDiagnostics
    ) {
        Matcher matcher = SG_MUL_VAL.matcher(trimmed);
        if (!matcher.matches()) {
            softDiagnostics.add("Malformed SG_MUL_VAL_ at line " + lineNo + ": " + preview(trimmed));
            return;
        }
        CanIdKey key = CanIdKey.fromDbcRawId(Long.parseLong(matcher.group(1)));
        MessageBuilder mb = messages.get(key);
        if (mb == null) {
            softDiagnostics.add("SG_MUL_VAL_ for unknown message at line " + lineNo);
            return;
        }
        String signalName = matcher.group(2);
        String multiplexorName = matcher.group(3);
        List<DbcMultiplexRange> ranges = parseRanges(matcher.group(4), lineNo, softDiagnostics);
        if (ranges.isEmpty()) {
            softDiagnostics.add("SG_MUL_VAL_ without value ranges at line " + lineNo);
            return;
        }
        mb.pendingMulVal.add(new PendingMulVal(signalName, multiplexorName, ranges, lineNo));
    }

    private static List<DbcMultiplexRange> parseRanges(
            String raw,
            long lineNo,
            List<String> softDiagnostics
    ) {
        List<DbcMultiplexRange> ranges = new ArrayList<>();
        Matcher entry = MUL_RANGE.matcher(raw);
        int finds = 0;
        while (entry.find()) {
            finds++;
            long min = Long.parseLong(entry.group(1));
            long max = Long.parseLong(entry.group(2));
            try {
                ranges.add(new DbcMultiplexRange(min, max));
            } catch (RuntimeException e) {
                softDiagnostics.add("Invalid SG_MUL_VAL_ range at line " + lineNo + ": " + e.getMessage());
            }
        }
        if (finds == 0) {
            softDiagnostics.add("Could not parse SG_MUL_VAL_ ranges at line " + lineNo + ": " + preview(raw));
        }
        return ranges;
    }

    private static boolean startsValueStatement(String trimmed) {
        return trimmed.startsWith("VAL_TABLE_")
                || trimmed.startsWith("VAL_")
                || trimmed.startsWith("SGTYPE_")
                || trimmed.startsWith("SIG_TYPE_REF_");
    }

    /**
     * Reads one logical DBC statement. A semicolon inside a quoted label does not end it,
     * and an escaped quote does not end the label. A later record that starts outside a
     * quote is returned for the caller to parse; it is not part of this statement.
     */
    private static CompletedStatement completeStatement(
            BufferedReader reader,
            String firstTrimmed,
            long startLine
    ) throws IOException {
        StringBuilder text = new StringBuilder(firstTrimmed);
        boolean inQuote = quoteOpen(text);
        long endLine = startLine;
        String pushback = null;
        while (inQuote || terminator(text) < 0) {
            String raw = reader.readLine();
            if (raw == null) {
                break;
            }
            endLine++;
            String trimmed = raw.trim();
            if (!inQuote && !trimmed.isEmpty() && startsDbcRecord(trimmed)) {
                pushback = raw;
                break;
            }
            if (trimmed.isEmpty() && !inQuote) {
                continue;
            }
            if (inQuote) {
                text.append('\n').append(stripEol(raw));
            } else {
                text.append(' ').append(trimmed);
            }
            inQuote = quoteOpen(text);
        }
        boolean terminated = !inQuote && terminator(text) >= 0 && pushback == null;
        return new CompletedStatement(text.toString(), startLine, endLine, terminated, pushback);
    }

    private static String stripEol(String raw) {
        int end = raw.length();
        if (end > 0 && raw.charAt(end - 1) == '\r') {
            end--;
        }
        return raw.substring(0, end);
    }

    private static boolean quoteOpen(CharSequence text) {
        boolean inQuote = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inQuote) {
                if (c == '\\' && i + 1 < text.length()) {
                    char next = text.charAt(i + 1);
                    if (next == '"' || next == '\\') {
                        i++;
                        continue;
                    }
                }
                if (c == '"') {
                    inQuote = false;
                }
            } else if (c == '"') {
                inQuote = true;
            }
        }
        return inQuote;
    }

    /** Index of the statement semicolon outside quotes, or -1. */
    private static int terminator(CharSequence text) {
        boolean inQuote = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inQuote) {
                if (c == '\\' && i + 1 < text.length()) {
                    char next = text.charAt(i + 1);
                    if (next == '"' || next == '\\') {
                        i++;
                        continue;
                    }
                }
                if (c == '"') {
                    inQuote = false;
                }
            } else if (c == '"') {
                inQuote = true;
            } else if (c == ';') {
                return i;
            }
        }
        return -1;
    }

    private static void noteUnterminatedValueStatement(String text, long lineNo, List<String> softDiagnostics) {
        Matcher identity = Pattern.compile("^VAL_(?:TABLE_)?\\s+(\\S+)(?:\\s+(\\S+))?").matcher(text);
        String record = "a value statement";
        if (text.startsWith("VAL_TABLE_") && identity.find()) {
            record = "VAL_TABLE_ " + identity.group(1);
        } else if (text.startsWith("VAL_")) {
            Matcher signal = Pattern.compile("^VAL_\\s+(\\d+)\\s+(\\S+)").matcher(text);
            record = signal.find()
                    ? "message " + signal.group(1) + " signal " + signal.group(2)
                    : "a VAL_ statement";
        } else if (text.startsWith("SGTYPE_")) {
            record = "an SGTYPE_ statement";
        } else if (text.startsWith("SIG_TYPE_REF_")) {
            record = "a SIG_TYPE_REF_ statement";
        }
        softDiagnostics.add(MULTILINE_VAL_WARNING_PREFIX + " for " + record
                + " at line " + lineNo
                + ". The statement has no terminating semicolon. The value description was not loaded."
                + " Numeric decoding is unchanged.");
    }

    private static void applyValTable(
            Map<String, DbcValueTable> namedTables,
            String logical,
            long lineNo,
            List<String> softDiagnostics
    ) {
        ValueTokens tokens = ValueTokens.parse(logical, lineNo, softDiagnostics);
        if (tokens == null || tokens.kind() != ValueKind.TABLE || tokens.name() == null) {
            return;
        }
        if (namedTables.containsKey(tokens.name())) {
            softDiagnostics.add("Duplicate VAL_TABLE_ '" + tokens.name() + "' at line " + lineNo
                    + ". The first definition was kept.");
            return;
        }
        namedTables.put(tokens.name(), new DbcValueTable(tokens.entries(), tokens.name()));
    }

    private static void applyValStatement(
            Map<CanIdKey, MessageBuilder> messages,
            String logical,
            long lineNo,
            List<String> softDiagnostics
    ) {
        ValueTokens tokens = ValueTokens.parse(logical, lineNo, softDiagnostics);
        if (tokens == null) {
            return;
        }
        if (tokens.kind() == ValueKind.ENVIRONMENT) {
            softDiagnostics.add("VAL_ for environment variable '" + tokens.name()
                    + "' at line " + lineNo
                    + " was not applied to a signal.");
            return;
        }
        if (tokens.kind() == ValueKind.BARE_TABLE) {
            softDiagnostics.add("VAL_ for message " + tokens.messageId()
                    + " signal '" + tokens.signalName() + "' names '" + tokens.name()
                    + "' without value descriptions at line " + lineNo
                    + ". A named table is applied with SGTYPE_ and SIG_TYPE_REF_. No labels were copied.");
            return;
        }
        if (tokens.kind() != ValueKind.SIGNAL) {
            return;
        }
        CanIdKey key = CanIdKey.fromDbcRawId(tokens.messageId());
        MessageBuilder mb = messages.get(key);
        if (mb == null) {
            softDiagnostics.add("VAL_ for unknown message at line " + lineNo);
            return;
        }
        mb.valueTables.put(tokens.signalName(), new DbcValueTable(tokens.entries()));
    }

    private static void noteSignalType(
            Map<String, String> signalTypeTables,
            String logical,
            long lineNo,
            List<String> softDiagnostics
    ) {
        int end = terminator(logical);
        String body = logical.substring("SGTYPE_".length(), end < 0 ? logical.length() : end).trim();
        int nameEnd = 0;
        while (nameEnd < body.length() && !Character.isWhitespace(body.charAt(nameEnd)) && body.charAt(nameEnd) != ':') {
            nameEnd++;
        }
        if (nameEnd == 0) {
            softDiagnostics.add("Malformed SGTYPE_ at line " + lineNo + ": " + preview(logical));
            return;
        }
        String typeName = body.substring(0, nameEnd);
        String table = tableNameAfterLastComma(body);
        if (table == null) {
            softDiagnostics.add("SGTYPE_ '" + typeName + "' at line " + lineNo
                    + " does not name a VAL_TABLE_. No labels were copied from it.");
            return;
        }
        if (signalTypeTables.containsKey(typeName)) {
            softDiagnostics.add("Duplicate SGTYPE_ '" + typeName + "' at line " + lineNo
                    + ". The first definition was kept.");
            return;
        }
        signalTypeTables.put(typeName, table);
    }

    private static String tableNameAfterLastComma(String body) {
        boolean inQuote = false;
        int depth = 0;
        int comma = -1;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (inQuote) {
                if (c == '\\' && i + 1 < body.length()) {
                    char next = body.charAt(i + 1);
                    if (next == '"' || next == '\\') {
                        i++;
                        continue;
                    }
                }
                if (c == '"') {
                    inQuote = false;
                }
                continue;
            }
            if (c == '"') {
                inQuote = true;
            } else if (c == '(' || c == '[') {
                depth++;
            } else if ((c == ')' || c == ']') && depth > 0) {
                depth--;
            } else if (c == ',' && depth == 0) {
                comma = i;
            }
        }
        if (comma < 0) {
            return null;
        }
        String tail = body.substring(comma + 1).trim();
        if (tail.isEmpty() || !Character.isLetter(tail.charAt(0)) && tail.charAt(0) != '_') {
            return null;
        }
        int end = 1;
        while (end < tail.length() && (Character.isLetterOrDigit(tail.charAt(end)) || tail.charAt(end) == '_')) {
            end++;
        }
        if (end != tail.length()) {
            return null;
        }
        return tail;
    }

    private static void noteTypeRef(
            List<PendingTypeRef> typeRefs,
            String logical,
            long lineNo,
            List<String> softDiagnostics
    ) {
        int end = terminator(logical);
        String body = logical.substring("SIG_TYPE_REF_".length(), end < 0 ? logical.length() : end).trim();
        Matcher matcher = Pattern.compile("^(\\d+)\\s+(\\S+)\\s*:\\s*(\\S+)\\s*$").matcher(body);
        if (!matcher.matches()) {
            softDiagnostics.add("Malformed SIG_TYPE_REF_ at line " + lineNo + ": " + preview(logical));
            return;
        }
        typeRefs.add(new PendingTypeRef(
                Long.parseLong(matcher.group(1)),
                matcher.group(2),
                matcher.group(3),
                lineNo));
    }

    private static void resolveTypeRefs(
            Map<CanIdKey, MessageBuilder> messages,
            Map<String, DbcValueTable> namedTables,
            Map<String, String> signalTypeTables,
            List<PendingTypeRef> typeRefs,
            List<String> softDiagnostics
    ) {
        for (PendingTypeRef ref : typeRefs) {
            MessageBuilder mb = messages.get(CanIdKey.fromDbcRawId(ref.messageId()));
            if (mb == null) {
                softDiagnostics.add("SIG_TYPE_REF_ for unknown message at line " + ref.lineNo());
                continue;
            }
            boolean knownSignal = false;
            for (DbcSignal signal : mb.signals) {
                if (signal.name().equals(ref.signalName())) {
                    knownSignal = true;
                    break;
                }
            }
            if (!knownSignal) {
                softDiagnostics.add("SIG_TYPE_REF_ for unknown signal '" + ref.signalName()
                        + "' at line " + ref.lineNo() + ". No labels were copied.");
                continue;
            }
            String tableName = signalTypeTables.get(ref.typeName());
            if (tableName == null) {
                softDiagnostics.add("SIG_TYPE_REF_ signal '" + ref.signalName()
                        + "' references unknown signal type '" + ref.typeName()
                        + "' at line " + ref.lineNo() + ". No labels were copied.");
                continue;
            }
            DbcValueTable table = namedTables.get(tableName);
            if (table == null) {
                softDiagnostics.add("Unresolved VAL_TABLE_ '" + tableName
                        + "' referenced by signal '" + ref.signalName()
                        + "' at line " + ref.lineNo() + ". No labels were copied.");
                continue;
            }
            DbcValueTable incoming = new DbcValueTable(new LinkedHashMap<>(table.entries()), table.tableName());
            DbcValueTable existing = mb.valueTables.get(ref.signalName());
            if (existing == null || existing.entries().isEmpty()) {
                mb.valueTables.put(ref.signalName(), incoming);
                continue;
            }
            if (existing.entries().equals(incoming.entries())) {
                if (existing.tableName() == null) {
                    mb.valueTables.put(ref.signalName(), existing.withName(table.tableName()));
                }
                continue;
            }
            softDiagnostics.add("VAL_ for signal '" + ref.signalName()
                    + "' conflicts with VAL_TABLE_ '" + table.tableName()
                    + "' at line " + ref.lineNo()
                    + ". The signal's own VAL_ labels were kept.");
        }
    }

    private static String stripComment(String line) {
        // DBC does not use // comments officially; keep line as-is except trailing spaces
        return line;
    }

    private static String unescape(String text) {
        return text.replace("\\\"", "\"");
    }

    private static String preview(String text) {
        return text.length() <= 80 ? text : text.substring(0, 80) + "...";
    }

    private static String fileName(Path path) {
        if (path == null) {
            return "";
        }
        Path name = path.getFileName();
        return name == null ? path.toString() : name.toString();
    }

    private static String unsupportedDlcMessage(String sourceName, long rawId, String messageName, int size) {
        StringBuilder message = new StringBuilder();
        message.append("Unsupported DLC ").append(size)
                .append(" for message ").append(messageName)
                .append(" (id ").append(rawId).append(")");
        if (sourceName != null && !sourceName.isBlank()) {
            message.append(" in ").append(sourceName);
        }
        message.append(". Supported sizes are 0 through 64 bytes. The DLC was not truncated.");
        return message.toString();
    }

    private static boolean startsDbcRecord(String trimmed) {
        return trimmed.startsWith("BO_ ")
                || trimmed.startsWith("SG_")
                || trimmed.startsWith("CM_")
                || trimmed.startsWith("BA_")
                || trimmed.startsWith("VAL_")
                || trimmed.startsWith("SIG_")
                || trimmed.startsWith("EV_")
                || trimmed.startsWith("ENVVAR_")
                || trimmed.startsWith("CAT_")
                || trimmed.startsWith("FILTER ")
                || trimmed.startsWith("VERSION")
                || trimmed.startsWith("BS_")
                || trimmed.startsWith("BU_")
                || trimmed.startsWith("NS_");
    }

    private record CompletedStatement(
            String text,
            long startLine,
            long endLine,
            boolean terminated,
            String pushback
    ) {
    }

    private record PendingTypeRef(long messageId, String signalName, String typeName, long lineNo) {
    }

    private enum ValueKind {
        SIGNAL,
        TABLE,
        ENVIRONMENT,
        BARE_TABLE
    }

    private record ValueTokens(
            ValueKind kind,
            long messageId,
            String signalName,
            String name,
            Map<Long, String> entries
    ) {
        private static ValueTokens parse(String logical, long lineNo, List<String> softDiagnostics) {
            int end = terminator(logical);
            if (end < 0) {
                softDiagnostics.add("Malformed value statement at line " + lineNo + ": " + preview(logical));
                return null;
            }
            String trailing = logical.substring(end + 1).trim();
            if (!trailing.isEmpty()) {
                softDiagnostics.add("Malformed value statement at line " + lineNo
                        + ": text follows the terminating semicolon. No labels were copied.");
                return null;
            }
            boolean table = logical.startsWith("VAL_TABLE_");
            String body = logical.substring(table ? "VAL_TABLE_".length() : "VAL_".length(), end).trim();
            List<Piece> pieces = scanPieces(body, lineNo, softDiagnostics);
            if (pieces == null) {
                return null;
            }
            if (table) {
                if (pieces.isEmpty() || pieces.getFirst().ident == null) {
                    softDiagnostics.add("Malformed VAL_TABLE_ at line " + lineNo + ": " + preview(logical));
                    return null;
                }
                Map<Long, String> entries = pairs(pieces.subList(1, pieces.size()), "VAL_TABLE_ " + pieces.getFirst().ident,
                        lineNo, softDiagnostics);
                if (entries == null) {
                    return null;
                }
                if (entries.isEmpty()) {
                    softDiagnostics.add("VAL_TABLE_ '" + pieces.getFirst().ident + "' at line " + lineNo
                            + " has no value descriptions. It was not stored.");
                    return null;
                }
                return new ValueTokens(ValueKind.TABLE, 0L, null, pieces.getFirst().ident, entries);
            }
            if (pieces.isEmpty()) {
                softDiagnostics.add("Malformed VAL_ at line " + lineNo + ": " + preview(logical));
                return null;
            }
            if (pieces.getFirst().number == null) {
                String env = pieces.getFirst().ident;
                if (env == null) {
                    softDiagnostics.add("Malformed VAL_ at line " + lineNo + ": " + preview(logical));
                    return null;
                }
                return new ValueTokens(ValueKind.ENVIRONMENT, 0L, null, env, Map.of());
            }
            if (pieces.size() < 2 || pieces.get(1).ident == null) {
                softDiagnostics.add("Malformed VAL_ at line " + lineNo + ": " + preview(logical));
                return null;
            }
            long messageId = pieces.getFirst().number;
            String signal = pieces.get(1).ident;
            List<Piece> rest = pieces.subList(2, pieces.size());
            if (rest.size() == 1 && rest.getFirst().ident != null) {
                return new ValueTokens(ValueKind.BARE_TABLE, messageId, signal, rest.getFirst().ident, Map.of());
            }
            Map<Long, String> entries = pairs(rest, "signal '" + signal + "'", lineNo, softDiagnostics);
            if (entries == null) {
                return null;
            }
            return new ValueTokens(ValueKind.SIGNAL, messageId, signal, null, entries);
        }

        private static Map<Long, String> pairs(
                List<Piece> pieces,
                String owner,
                long lineNo,
                List<String> softDiagnostics
        ) {
            if (pieces.size() % 2 != 0) {
                softDiagnostics.add("Malformed value descriptions for " + owner + " at line " + lineNo
                        + ". No labels were copied.");
                return null;
            }
            Map<Long, String> entries = new LinkedHashMap<>();
            for (int i = 0; i < pieces.size(); i += 2) {
                Piece code = pieces.get(i);
                Piece label = pieces.get(i + 1);
                if (code.number == null || label.text == null) {
                    softDiagnostics.add("Malformed value descriptions for " + owner + " at line " + lineNo
                            + ". No labels were copied.");
                    return null;
                }
                if (entries.containsKey(code.number)) {
                    softDiagnostics.add("Duplicate raw code " + code.number + " for " + owner
                            + " at line " + lineNo + ". The later label '" + label.text + "' was kept.");
                }
                entries.put(code.number, label.text);
            }
            return entries;
        }

        private static List<Piece> scanPieces(String body, long lineNo, List<String> softDiagnostics) {
            List<Piece> pieces = new ArrayList<>();
            int i = 0;
            while (i < body.length()) {
                char c = body.charAt(i);
                if (Character.isWhitespace(c)) {
                    i++;
                    continue;
                }
                if (c == '"') {
                    int[] end = new int[1];
                    String text = readQuoted(body, i, end);
                    if (text == null) {
                        softDiagnostics.add("Malformed quoted label at line " + lineNo
                                + ". No labels were copied.");
                        return null;
                    }
                    pieces.add(Piece.text(text));
                    i = end[0];
                    continue;
                }
                if (c == '+' || c == '-' || Character.isDigit(c)) {
                    int start = i;
                    if (c == '+' || c == '-') {
                        i++;
                    }
                    int digits = i;
                    while (i < body.length() && Character.isDigit(body.charAt(i))) {
                        i++;
                    }
                    if (i == digits || (i < body.length() && (body.charAt(i) == '.' || body.charAt(i) == 'e'
                            || body.charAt(i) == 'E'))) {
                        softDiagnostics.add("Malformed raw code at line " + lineNo
                                + ". Value-table codes are integers. No labels were copied.");
                        return null;
                    }
                    try {
                        pieces.add(Piece.number(Long.parseLong(body.substring(start, i))));
                    } catch (NumberFormatException ex) {
                        softDiagnostics.add("Malformed raw code at line " + lineNo
                                + ". No labels were copied.");
                        return null;
                    }
                    continue;
                }
                if (Character.isLetter(c) || c == '_') {
                    int start = i;
                    i++;
                    while (i < body.length() && (Character.isLetterOrDigit(body.charAt(i)) || body.charAt(i) == '_')) {
                        i++;
                    }
                    pieces.add(Piece.ident(body.substring(start, i)));
                    continue;
                }
                softDiagnostics.add("Malformed value statement at line " + lineNo
                        + ": '" + c + "'. No labels were copied.");
                return null;
            }
            return pieces;
        }

        private static String readQuoted(String body, int open, int[] end) {
            StringBuilder out = new StringBuilder();
            for (int i = open + 1; i < body.length(); i++) {
                char c = body.charAt(i);
                if (c == '\\' && i + 1 < body.length()) {
                    char next = body.charAt(i + 1);
                    if (next == '"' || next == '\\') {
                        out.append(next);
                        i++;
                        continue;
                    }
                }
                if (c == '"') {
                    end[0] = i + 1;
                    return out.toString();
                }
                out.append(c);
            }
            return null;
        }
    }

    private record Piece(Long number, String ident, String text) {
        private static Piece number(long value) {
            return new Piece(value, null, null);
        }

        private static Piece ident(String value) {
            return new Piece(null, value, null);
        }

        private static Piece text(String value) {
            return new Piece(null, null, value);
        }
    }

    private record PendingMulVal(
            String signalName,
            String multiplexorName,
            List<DbcMultiplexRange> ranges,
            long lineNo
    ) {
    }

    private static final class MessageBuilder {
        private final CanIdKey key;
        private final String name;
        private final int size;
        private final String transmitter;
        private final long lineNo;
        private final List<DbcSignal> signals = new ArrayList<>();
        private final Map<String, String> signalComments = new LinkedHashMap<>();
        private final Map<String, DbcValueTable> valueTables = new LinkedHashMap<>();
        private final List<PendingMulVal> pendingMulVal = new ArrayList<>();
        private String comment;

        private MessageBuilder(CanIdKey key, String name, int size, String transmitter, long lineNo) {
            this.key = key;
            this.name = name;
            this.size = size;
            this.transmitter = transmitter;
            this.lineNo = lineNo;
        }

        private DbcMessage build(
                List<String> softDiagnostics,
                Map<String, AttributeKind> attributeKinds,
                List<PendingAttribute> attributes,
                List<PendingValType> valTypes
        ) {
            Map<String, Integer> indexByName = new LinkedHashMap<>();
            List<DbcSignal> working = new ArrayList<>();
            for (int i = 0; i < signals.size(); i++) {
                DbcSignal signal = signals.get(i);
                String c = signalComments.get(signal.name());
                DbcValueTable table = valueTables.getOrDefault(signal.name(), DbcValueTable.empty());
                DbcSignal withMeta = signal.withCommentAndValueTable(c, table);
                indexByName.putIfAbsent(withMeta.name(), working.size());
                working.add(withMeta);
            }

            // Merge SG_MUL_VAL_ by signal + multiplexor (append ranges; AND across multiplexors).
            Map<String, Map<String, List<DbcMultiplexRange>>> bySignalThenMux = new LinkedHashMap<>();
            for (PendingMulVal pending : pendingMulVal) {
                if (!indexByName.containsKey(pending.signalName())) {
                    softDiagnostics.add("Unresolved SG_MUL_VAL_ signal '" + pending.signalName()
                            + "' at line " + pending.lineNo());
                    continue;
                }
                if (!indexByName.containsKey(pending.multiplexorName())) {
                    softDiagnostics.add("Unresolved SG_MUL_VAL_ multiplexor '" + pending.multiplexorName()
                            + "' at line " + pending.lineNo());
                    continue;
                }
                bySignalThenMux
                        .computeIfAbsent(pending.signalName(), k -> new LinkedHashMap<>())
                        .computeIfAbsent(pending.multiplexorName(), k -> new ArrayList<>())
                        .addAll(pending.ranges());
            }

            List<DbcSignal> finalized = new ArrayList<>(working.size());
            for (DbcSignal signal : working) {
                Map<String, List<DbcMultiplexRange>> muxMap = bySignalThenMux.get(signal.name());
                if (muxMap == null || muxMap.isEmpty()) {
                    finalized.add(signal);
                    continue;
                }
                List<DbcMultiplexCondition> conditions = new ArrayList<>();
                for (Map.Entry<String, List<DbcMultiplexRange>> e : muxMap.entrySet()) {
                    conditions.add(new DbcMultiplexCondition(e.getKey(), e.getValue()));
                }
                finalized.add(signal.withMultiplexConditions(conditions));
            }
            List<DbcSignal> withAttributes = new ArrayList<>(finalized.size());
            for (DbcSignal signal : finalized) {
                withAttributes.add(applySignalAttributes(
                        key, signal, attributeKinds, attributes, valTypes, softDiagnostics));
            }
            return new DbcMessage(key, name, size, transmitter, withAttributes, comment);
        }
    }

    private enum AttributeKind {
        INTEGRAL,
        TEXT
    }

    private record PendingAttribute(long rawMessageId, String signalName, String attribute, String literal, long lineNo) {
    }

    private record PendingValType(long rawMessageId, String signalName, int code, long lineNo) {
    }

    private static void noteAttributeDefinition(String trimmed, Map<String, AttributeKind> attributeKinds) {
        Matcher matcher = BA_DEF_SG.matcher(trimmed);
        if (!matcher.matches()) {
            return;
        }
        String type = matcher.group(2).toUpperCase(java.util.Locale.ROOT);
        AttributeKind kind = ("STRING".equals(type) || "ENUM".equals(type))
                ? AttributeKind.TEXT
                : AttributeKind.INTEGRAL;
        attributeKinds.put(matcher.group(1), kind);
    }

    private static void noteAttributeAssignment(
            String trimmed,
            long lineNo,
            List<PendingAttribute> attributes,
            List<String> softDiagnostics
    ) {
        Matcher matcher = BA_SG.matcher(trimmed);
        if (!matcher.matches()) {
            if (isValidityAttribute(trimmed)) {
                softDiagnostics.add("Ignored non-SG_ validity attribute at line " + lineNo + ": " + preview(trimmed));
            }
            return;
        }
        String attribute = matcher.group(1);
        if (!isValidityAttributeName(attribute)) {
            return;
        }
        attributes.add(new PendingAttribute(
                Long.parseLong(matcher.group(2)),
                matcher.group(3),
                attribute,
                matcher.group(4).trim(),
                lineNo
        ));
    }

    private static void noteSigValType(
            String trimmed,
            long lineNo,
            List<PendingValType> valTypes,
            List<String> softDiagnostics
    ) {
        Matcher matcher = SIG_VALTYPE.matcher(trimmed);
        if (!matcher.matches()) {
            softDiagnostics.add(SIG_VALTYPE_WARNING_PREFIX + " at line " + lineNo
                    + ": the record is malformed and was not applied. " + preview(trimmed));
            return;
        }
        valTypes.add(new PendingValType(
                Long.parseLong(matcher.group(1)),
                matcher.group(2),
                Integer.parseInt(matcher.group(3)),
                lineNo
        ));
    }

    private static boolean isValidityAttribute(String trimmed) {
        return trimmed.contains("GenSigInvalidValue")
                || trimmed.contains("GenSigInactiveValue")
                || trimmed.contains("GenSigStartValue");
    }

    private static boolean isValidityAttributeName(String name) {
        return "GenSigInvalidValue".equals(name)
                || "GenSigInactiveValue".equals(name)
                || "GenSigStartValue".equals(name);
    }

    private static DbcSignal applySignalAttributes(
            CanIdKey messageId,
            DbcSignal signal,
            Map<String, AttributeKind> attributeKinds,
            List<PendingAttribute> attributes,
            List<PendingValType> valTypes,
            List<String> softDiagnostics
    ) {
        DbcSignal current = signal;
        Long invalid = signal.invalidRawValue();
        Long inactive = signal.inactiveRawValue();
        Long start = signal.startRawValue();
        boolean attributesChanged = false;
        for (PendingAttribute pending : attributes) {
            if (!messageId.equals(CanIdKey.fromDbcRawId(pending.rawMessageId()))
                    || !signal.name().equals(pending.signalName())) {
                continue;
            }
            AttributeKind kind = attributeKinds.get(pending.attribute());
            if (kind == AttributeKind.TEXT) {
                softDiagnostics.add("Ignored " + pending.attribute()
                        + " on '" + signal.name() + "' at line " + pending.lineNo()
                        + ": attribute type is not an integral raw value");
                continue;
            }
            Long raw = parseIntegralRaw(pending.literal());
            if (raw == null) {
                softDiagnostics.add("Ignored " + pending.attribute()
                        + " on '" + signal.name() + "' at line " + pending.lineNo()
                        + ": '" + pending.literal() + "' is not an integral raw value");
                continue;
            }
            switch (pending.attribute()) {
                case "GenSigInvalidValue" -> invalid = raw;
                case "GenSigInactiveValue" -> inactive = raw;
                case "GenSigStartValue" -> start = raw;
                default -> {
                    continue;
                }
            }
            attributesChanged = true;
        }
        if (attributesChanged) {
            current = current.withRawAttributes(invalid, inactive, start);
        }
        for (PendingValType pending : valTypes) {
            if (!messageId.equals(CanIdKey.fromDbcRawId(pending.rawMessageId()))
                    || !signal.name().equals(pending.signalName())) {
                continue;
            }
            DbcRawEncoding encoding = switch (pending.code()) {
                case 0 -> DbcRawEncoding.INTEGER;
                case 1 -> DbcRawEncoding.IEEE_FLOAT32;
                case 2 -> DbcRawEncoding.IEEE_FLOAT64;
                default -> null;
            };
            if (encoding == null) {
                softDiagnostics.add(SIG_VALTYPE_WARNING_PREFIX + " " + pending.code()
                        + " on signal '" + signal.name() + "' (message " + pending.rawMessageId()
                        + ") at line " + pending.lineNo()
                        + ": supported types are 0, 1 and 2. Integer decoding was kept.");
                continue;
            }
            try {
                current = current.withRawEncoding(encoding);
            } catch (IllegalArgumentException e) {
                softDiagnostics.add(SIG_VALTYPE_WARNING_PREFIX + " " + pending.code()
                        + " on signal '" + signal.name() + "' (message " + pending.rawMessageId()
                        + ") at line " + pending.lineNo()
                        + ": " + e.getMessage()
                        + ". Integer decoding was kept.");
            }
        }
        return current;
    }

    /**
     * Accepts a decimal integer, a {@code 0x} hex integer, or an integral float literal.
     * Non-integral numbers are rejected so they cannot be truncated into a raw code.
     */
    static Long parseIntegralRaw(String literal) {
        if (literal == null) {
            return null;
        }
        String token = literal.trim();
        if (token.isEmpty() || token.charAt(0) == '"') {
            return null;
        }
        try {
            if (token.startsWith("0x") || token.startsWith("0X")) {
                return Long.parseUnsignedLong(token.substring(2), 16);
            }
            if (token.matches("-?\\d+")) {
                return Long.parseLong(token);
            }
            double value = Double.parseDouble(token);
            if (Double.isFinite(value) && value == Math.rint(value)
                    && value >= Long.MIN_VALUE && value <= Long.MAX_VALUE) {
                return (long) value;
            }
        } catch (NumberFormatException ignored) {
            return null;
        }
        return null;
    }
}
