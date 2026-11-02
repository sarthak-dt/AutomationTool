package com.stellar.platform.parser.mf4.recovery;

import com.stellar.platform.parser.mf4.decode.MdfCanFieldResolver;
import com.stellar.platform.parser.mf4.decode.MdfDecodedRecord;

/**
 * Result of recovery structural validation. CAN role resolution is performed at most once.
 */
public record RecoveryValidatedRecord(
        MdfDecodedRecord decoded,
        MdfCanFieldResolver.ResolvedCanFields canFields,
        boolean sound
) {
}
