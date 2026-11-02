package com.stellar.platform.parser.mf4.dynamicschema;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Runtime schema view for a native (non-bus-frame) MF4 measurement Channel Group.
 * Built dynamically from {@link MdfRecordSchema} — no hard-coded offsets or names.
 */
public record MdfNativeMeasurementSchema(
        long dataGroupAddress,
        long channelGroupAddress,
        long recordId,
        MdfChannelFieldSchema masterTimeChannel,
        List<MdfChannelFieldSchema> measurementChannels,
        MdfDataSourceSchema dataSource
) {
    public MdfNativeMeasurementSchema {
        Objects.requireNonNull(masterTimeChannel, "masterTimeChannel");
        measurementChannels = List.copyOf(Objects.requireNonNull(measurementChannels, "measurementChannels"));
        Objects.requireNonNull(dataSource, "dataSource");
        if (measurementChannels.isEmpty()) {
            throw new IllegalArgumentException("measurementChannels must not be empty");
        }
    }

    public static Optional<MdfNativeMeasurementSchema> tryFrom(MdfRecordSchema schema) {
        if (schema == null || !schema.usable() || schema.vlsdChannelGroup()) {
            return Optional.empty();
        }
        if (schema.emitsCanonicalCanFrames()) {
            return Optional.empty();
        }
        MdfBusKind kind = schema.busKind();
        if (kind == MdfBusKind.LIN || kind == MdfBusKind.ERROR_STATUS || kind == MdfBusKind.UNSUPPORTED) {
            return Optional.empty();
        }
        MdfChannelFieldSchema time = null;
        double best = -1;
        List<MdfChannelFieldSchema> measurements = new java.util.ArrayList<>();
        for (MdfChannelFieldSchema f : schema.channelFields()) {
            if (f.compositionContainer() || !f.supported()) {
                continue;
            }
            double score = timeScore(f);
            if (score > best) {
                best = score;
                time = f;
            }
        }
        if (time == null || best < 1.0) {
            return Optional.empty();
        }
        for (MdfChannelFieldSchema f : schema.channelFields()) {
            if (f.channelAddress() == time.channelAddress()) {
                continue;
            }
            if (!isMeasurementField(f, time.channelAddress())) {
                continue;
            }
            measurements.add(f);
        }
        if (measurements.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new MdfNativeMeasurementSchema(
                schema.dataGroupAddress(),
                schema.channelGroupAddress(),
                schema.recordId(),
                time,
                measurements,
                schema.dataSource()
        ));
    }

    static double timeScore(MdfChannelFieldSchema f) {
        if (f.syncType() == 1 || f.channelType() == 2 || f.channelType() == 3) {
            return 3.0;
        }
        if (f.canRoleCandidate() != null && f.canRoleCandidate().role() == MdfCanRole.TIMESTAMP) {
            return 2.5;
        }
        String n = f.leafName().toLowerCase(java.util.Locale.ROOT);
        if (n.equals("timestamp") || n.equals("time") || n.equals("t_stamp") || n.equals("tstamp")) {
            return 2.0;
        }
        return 0.0;
    }

    static boolean isMeasurementField(MdfChannelFieldSchema f, long timeChannelAddress) {
        if (f == null || f.compositionContainer() || !f.supported()) {
            return false;
        }
        if (f.channelAddress() == timeChannelAddress || timeScore(f) >= 1.0) {
            return false;
        }
        if (f.channelType() != 0 && f.channelType() != 1) {
            return false;
        }
        return switch (f.dataTypeKind()) {
            case UNSIGNED_INTEGER_LE, UNSIGNED_INTEGER_BE, SIGNED_INTEGER_LE, SIGNED_INTEGER_BE,
                 FLOAT_LE, FLOAT_BE -> f.bitLength() > 0 && f.bitLength() <= 64;
            default -> false;
        };
    }
}
