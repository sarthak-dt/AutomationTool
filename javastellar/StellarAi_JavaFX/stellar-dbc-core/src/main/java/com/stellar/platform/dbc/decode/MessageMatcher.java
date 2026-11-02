package com.stellar.platform.dbc.decode;

import com.stellar.platform.dbc.model.CanIdKey;
import com.stellar.platform.dbc.model.DbcDatabase;
import com.stellar.platform.dbc.model.DbcMessage;
import com.stellar.platform.domain.CanonicalCanFrame;

import java.util.Optional;

/**
 * Matches a {@link CanonicalCanFrame} to a {@link DbcMessage} by normalized CAN ID
 * and payload-length compatibility.
 */
public final class MessageMatcher {

    private MessageMatcher() {
    }

    public static Optional<DbcMessage> match(CanonicalCanFrame frame, DbcDatabase database) {
        if (frame == null || database == null) {
            return Optional.empty();
        }
        CanIdKey key = CanIdKey.fromFrame(frame.canId(), frame.extended());
        Optional<DbcMessage> message = database.findMessage(key);
        if (message.isEmpty()) {
            return Optional.empty();
        }
        return message;
    }
}
