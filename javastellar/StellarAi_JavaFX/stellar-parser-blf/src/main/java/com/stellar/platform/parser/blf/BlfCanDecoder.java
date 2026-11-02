package com.stellar.platform.parser.blf;

import com.stellar.platform.domain.CanonicalCanFrame;
import com.stellar.platform.domain.FrameDirection;
import com.stellar.platform.domain.FrameQuality;
import com.stellar.platform.domain.SourceReference;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

final class BlfCanDecoder {

    private BlfCanDecoder() {
    }

    static CanonicalCanFrame decode(
            BlfObjectReader.BlfObject object,
            long sequenceNumber,
            BlfDiagnostics diagnostics
    ) {
        int type = object.header().objectType();
        try {
            long timestamp = object.header().timestampNanos();
            return switch (type) {
                case BlfConstants.TYPE_CAN_MESSAGE -> classic(object, sequenceNumber, timestamp, 16);
                case BlfConstants.TYPE_CAN_MESSAGE2 -> classic(object, sequenceNumber, timestamp, 24);
                case BlfConstants.TYPE_CAN_FD_MESSAGE -> canFd100(object, sequenceNumber, timestamp);
                case BlfConstants.TYPE_CAN_FD_MESSAGE_64 -> canFd64(object, sequenceNumber, timestamp);
                default -> null;
            };
        } catch (RuntimeException ex) {
            diagnostics.malformed(
                    "Cannot decode " + BlfObjectTypes.name(type) + ": " + ex.getMessage(),
                    object.header().fileOffset());
            return null;
        }
    }

    private static CanonicalCanFrame classic(
            BlfObjectReader.BlfObject object,
            long sequenceNumber,
            long timestamp,
            int minLen
    ) {
        byte[] p = object.payload();
        if (p.length < minLen && p.length < 16) {
            throw new IllegalArgumentException("classic CAN payload too short");
        }
        ByteBuffer buf = ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN);
        int channel = Short.toUnsignedInt(buf.getShort());
        int flags = Byte.toUnsignedInt(buf.get());
        int dlc = Byte.toUnsignedInt(buf.get());
        int rawId = buf.getInt();
        if ((flags & BlfConstants.CAN_FLAG_RTR) != 0) {
            return null;
        }
        if (dlc > 8) {
            throw new IllegalArgumentException("classic DLC > 8");
        }
        byte[] data = new byte[dlc];
        int available = Math.min(dlc, Math.max(0, p.length - 8));
        if (available > 0) {
            System.arraycopy(p, 8, data, 0, available);
        }
        return frame(sequenceNumber, timestamp, channel, rawId, flagsToDirection(flags), dlc, data,
                object.header().fileOffset());
    }

    private static CanonicalCanFrame canFd100(
            BlfObjectReader.BlfObject object,
            long sequenceNumber,
            long timestamp
    ) {
        byte[] p = object.payload();
        if (p.length < 20) {
            throw new IllegalArgumentException("CAN_FD_MESSAGE too short");
        }
        ByteBuffer buf = ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN);
        int channel = Short.toUnsignedInt(buf.getShort());
        int flags = Byte.toUnsignedInt(buf.get());
        int codedDlc = Byte.toUnsignedInt(buf.get());
        int rawId = buf.getInt();
        buf.getInt(); // frameLength
        buf.get(); // bitCount
        buf.get(); // fdFlags
        int valid = Byte.toUnsignedInt(buf.get());
        int actual = actualFdLength(codedDlc, valid);
        byte[] data = copyPayload(p, 20, actual);
        return frame(sequenceNumber, timestamp, channel, rawId, flagsToDirection(flags), actual, data,
                object.header().fileOffset());
    }

    private static CanonicalCanFrame canFd64(
            BlfObjectReader.BlfObject object,
            long sequenceNumber,
            long timestamp
    ) {
        byte[] p = object.payload();
        if (p.length < 40) {
            throw new IllegalArgumentException("CAN_FD_MESSAGE_64 too short");
        }
        ByteBuffer buf = ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN);
        int channel = Byte.toUnsignedInt(buf.get());
        int codedDlc = Byte.toUnsignedInt(buf.get());
        int valid = Byte.toUnsignedInt(buf.get());
        buf.get(); // txCount
        int rawId = buf.getInt();
        buf.position(34);
        int dir = Byte.toUnsignedInt(buf.get());
        int actual = actualFdLength(codedDlc, valid);
        byte[] data = copyPayload(p, 40, actual);
        FrameDirection direction = dir == 0 ? FrameDirection.RX : (dir == 1 || dir == 2 ? FrameDirection.TX : FrameDirection.UNKNOWN);
        return frame(sequenceNumber, timestamp, channel, rawId, direction, actual, data,
                object.header().fileOffset());
    }

    private static int actualFdLength(int codedDlc, int validDataBytes) {
        int fromDlc = BlfConstants.canFdDlcToLength(codedDlc);
        if (validDataBytes > 0 && validDataBytes <= CanonicalCanFrame.MAX_PAYLOAD_BYTES) {
            if (fromDlc >= 0 && validDataBytes > fromDlc) {
                throw new IllegalArgumentException("validDataBytes exceeds DLC mapping");
            }
            return validDataBytes;
        }
        if (fromDlc < 0) {
            throw new IllegalArgumentException("invalid CAN FD DLC " + codedDlc);
        }
        return fromDlc;
    }

    private static byte[] copyPayload(byte[] src, int offset, int length) {
        if (length < 0 || length > CanonicalCanFrame.MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("payload length " + length);
        }
        byte[] data = new byte[length];
        int available = Math.min(length, Math.max(0, src.length - offset));
        if (available > 0) {
            System.arraycopy(src, offset, data, 0, available);
        }
        return data;
    }

    private static FrameDirection flagsToDirection(int flags) {
        return (flags & BlfConstants.CAN_FLAG_TX) != 0 ? FrameDirection.TX : FrameDirection.RX;
    }

    private static CanonicalCanFrame frame(
            long sequenceNumber,
            long timestamp,
            int channel,
            int rawId,
            FrameDirection direction,
            int dlc,
            byte[] data,
            long offset
    ) {
        if (channel < 0) {
            throw new IllegalArgumentException("negative channel");
        }
        boolean extended = (rawId & BlfConstants.CAN_ID_EXT_FLAG) != 0;
        long canId = extended
                ? (rawId & BlfConstants.CAN_ID_EXT_MASK)
                : (rawId & BlfConstants.CAN_ID_STD_MASK);
        return new CanonicalCanFrame(
                sequenceNumber,
                timestamp,
                channel,
                canId,
                extended,
                direction,
                dlc,
                Arrays.copyOf(data, dlc),
                FrameQuality.OK,
                new SourceReference(-1L, offset)
        );
    }
}
