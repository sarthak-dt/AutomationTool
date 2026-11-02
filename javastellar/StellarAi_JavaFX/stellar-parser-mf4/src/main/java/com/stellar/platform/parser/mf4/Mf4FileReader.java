package com.stellar.platform.parser.mf4;

import com.stellar.platform.domain.FileMetadata;
import com.stellar.platform.parser.mf4.decode.MdfDynamicRecordDecoder;
import com.stellar.platform.parser.spi.CanChannelDiscoveryCapable;
import com.stellar.platform.parser.spi.CanChannelDiscoveryResult;
import com.stellar.platform.parser.spi.CanFrameConsumer;
import com.stellar.platform.parser.spi.CancellationToken;
import com.stellar.platform.parser.spi.NativeSignalCapableReader;
import com.stellar.platform.parser.spi.ParserException;
import com.stellar.platform.parser.spi.ReaderContext;
import com.stellar.platform.parser.spi.SignalBatchConsumer;
import com.stellar.platform.parser.spi.WarningConsumer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Streaming MF4 {@link NativeSignalCapableReader}: Metadata Discovery → Schema Builder → Dynamic Record Decoder.
 */
public final class Mf4FileReader implements NativeSignalCapableReader, CanChannelDiscoveryCapable {

    private final MdfDynamicRecordDecoder decoder = new MdfDynamicRecordDecoder();

    @Override
    public String formatKey() {
        return Mf4MeasurementFileParser.FORMAT_KEY;
    }

    @Override
    public boolean supports(Path path) {
        return MdfContentProbe.parserCanRead(path);
    }

    @Override
    public FileMetadata readMetadata(Path path) throws ParserException {
        Path normalized = requireSupported(path);
        return new FileMetadata(formatKey(), normalized.getFileName().toString(), normalized.toAbsolutePath().toString());
    }

    @Override
    public CanChannelDiscoveryResult discoverCanChannels(Path path, CancellationToken cancellation)
            throws ParserException {
        Path normalized = requireSupported(path);
        return decoder.discoverCanChannels(normalized, cancellation);
    }

    @Override
    public void readFrames(
            Path path,
            ReaderContext context,
            CanFrameConsumer frameConsumer,
            WarningConsumer warningConsumer
    ) throws ParserException {
        readFramesAndSignals(path, context, frameConsumer, SignalBatchConsumer.NOOP, warningConsumer);
    }

    @Override
    public void readFramesAndSignals(
            Path path,
            ReaderContext context,
            CanFrameConsumer frameConsumer,
            SignalBatchConsumer nativeSignalConsumer,
            WarningConsumer warningConsumer
    ) throws ParserException {
        Path normalized = requireSupported(path);
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(frameConsumer, "frameConsumer");
        var match = MdfContentProbe.probe(normalized);
        if (match.isPresent() && majorOf(match.get().version()) == 3) {
            new Mdf3SignalReader().read(normalized, context, nativeSignalConsumer, warningConsumer);
            return;
        }
        if (match.isPresent()) {
            int major = majorOf(match.get().version());
            if (major > 0 && major != 4) {
                throw new ParserException(match.get().unsupportedVersionMessage());
            }
        }
        decoder.decode(normalized, context, frameConsumer, nativeSignalConsumer, warningConsumer);
    }

    private Path requireSupported(Path path) throws ParserException {
        if (path == null) {
            throw new ParserException("MF4 path must not be null");
        }
        if (!supports(path) && !hasMdfExtension(path)) {
            throw new ParserException("Not an MF4 path: " + path);
        }
        if (!Files.isRegularFile(path)) {
            throw new ParserException("MF4 path is not a readable file: " + path);
        }
        return path;
    }

    private static int majorOf(String version) {
        if (version == null) {
            return -1;
        }
        int dot = version.indexOf('.');
        String major = dot < 0 ? version : version.substring(0, dot);
        try {
            return Integer.parseInt(major.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static boolean hasMdfExtension(Path path) {
        Path name = path.getFileName();
        if (name == null) {
            return false;
        }
        String lower = name.toString().toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".mf4") || lower.endsWith(".mdf");
    }
}
