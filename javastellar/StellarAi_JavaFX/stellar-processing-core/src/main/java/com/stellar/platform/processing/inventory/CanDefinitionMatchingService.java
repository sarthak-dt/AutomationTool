package com.stellar.platform.processing.inventory;

import com.stellar.platform.dbc.model.CanIdKey;
import com.stellar.platform.dbc.model.DbcDatabase;
import com.stellar.platform.dbc.model.DbcMessage;
import com.stellar.platform.domain.inventory.CanChannelInventory;
import com.stellar.platform.domain.inventory.CanChannelMatchSummary;
import com.stellar.platform.domain.inventory.CanDefinitionMatchStatus;
import com.stellar.platform.domain.inventory.CanDefinitionMatchSummary;
import com.stellar.platform.domain.inventory.CanIdMatchDiagnostic;
import com.stellar.platform.domain.inventory.CanMessageInventory;
import com.stellar.platform.domain.inventory.CanMessageMatch;
import com.stellar.platform.domain.inventory.CanSourceInventory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Format-independent DBC Message-ID matching against an existing {@link CanSourceInventory}.
 *
 * <p>Does not reopen or reparse measurement files. Does not decode DBC signals.
 * Match key is CAN ID + standard/extended identity via {@link CanIdKey}.
 *
 * <p>Global unique counts are a UNION across channels (same ID on two channels counts once globally).
 */
public final class CanDefinitionMatchingService {

    public CanDefinitionMatchSummary match(CanSourceInventory inventory, DbcDatabase dbc) {
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(dbc, "dbc");

        Map<CanIdKey, String> dbcByKey = indexDbcMessages(dbc);
        int dbcMessageCount = dbcByKey.size();

        Set<CanIdKey> globalObserved = new HashSet<>();
        Set<CanIdKey> globalMatched = new HashSet<>();
        List<CanChannelMatchSummary> channelSummaries = new ArrayList<>();

        for (CanChannelInventory channel : inventory.channels()) {
            CanChannelMatchSummary channelSummary = matchChannel(channel, dbcByKey);
            channelSummaries.add(channelSummary);
            for (CanMessageMatch messageMatch : channelSummary.messages()) {
                CanIdKey key = CanIdKey.fromFrame(messageMatch.canId(), messageMatch.extended());
                globalObserved.add(key);
                if (messageMatch.matched()) {
                    globalMatched.add(key);
                }
            }
        }

        long globalObservedUnique = globalObserved.size();
        long globalMatchedUnique = globalMatched.size();
        long globalUnmatchedUnique = Math.max(0L, globalObservedUnique - globalMatchedUnique);

        CanDefinitionMatchStatus status = resolveStatus(
                dbcMessageCount,
                globalObservedUnique,
                globalMatchedUnique
        );

        return new CanDefinitionMatchSummary(
                status,
                dbcMessageCount,
                globalObservedUnique,
                globalMatchedUnique,
                globalUnmatchedUnique,
                percentage(globalMatchedUnique, globalObservedUnique),
                channelSummaries,
                null
        );
    }


    /**
     * Match one channel inventory against a DBC without reopening measurement files.
     */
    public CanChannelMatchSummary matchChannel(CanChannelInventory channel, DbcDatabase dbc) {
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(dbc, "dbc");
        return matchChannel(channel, indexDbcMessages(dbc));
    }

    /**
     * Unique-ID diagnostics for one channel vs one parsed DBC.
     * Detects MATCHED / UNMATCHED / TYPE_MISMATCH (same numeric ID, different standard/extended).
     */
    public List<CanIdMatchDiagnostic> diagnoseChannel(CanChannelInventory channel, DbcDatabase dbc) {
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(dbc, "dbc");
        Map<CanIdKey, String> dbcByKey = indexDbcMessages(dbc);
        Map<Long, CanIdKey> dbcByNumeric = indexDbcByNumericId(dbcByKey);
        List<CanIdMatchDiagnostic> out = new ArrayList<>(channel.messages().size());
        for (CanMessageInventory observed : channel.messages()) {
            CanIdKey key = CanIdKey.fromFrame(observed.canId(), observed.extended());
            String dbcName = dbcByKey.get(key);
            if (dbcName != null) {
                out.add(CanIdMatchDiagnostic.matched(
                        observed.canId(),
                        observed.extended(),
                        key.canId(),
                        key.extended(),
                        dbcName
                ));
                continue;
            }
            CanIdKey opposite = dbcByNumeric.get(observed.canId());
            if (opposite != null && opposite.extended() != observed.extended()) {
                out.add(CanIdMatchDiagnostic.typeMismatch(
                        observed.canId(),
                        observed.extended(),
                        opposite.canId(),
                        opposite.extended(),
                        dbcByKey.get(opposite)
                ));
            } else {
                out.add(CanIdMatchDiagnostic.unmatched(observed.canId(), observed.extended()));
            }
        }
        return List.copyOf(out);
    }

    private static CanChannelMatchSummary matchChannel(
            CanChannelInventory channel,
            Map<CanIdKey, String> dbcByKey
    ) {
        List<CanMessageMatch> messageMatches = new ArrayList<>();
        long matchedOnChannel = 0L;
        for (CanMessageInventory observed : channel.messages()) {
            CanIdKey key = CanIdKey.fromFrame(observed.canId(), observed.extended());
            String dbcName = dbcByKey.get(key);
            boolean matched = dbcName != null;
            if (matched) {
                matchedOnChannel++;
            }
            messageMatches.add(new CanMessageMatch(
                    observed.canId(),
                    observed.extended(),
                    observed.frameCount(),
                    matched,
                    dbcName
            ));
        }
        // Prefer actual identity list size; uniqueMessageCount should match but must not invent IDs.
        long observedOnChannel = Math.max(channel.uniqueMessageCount(), (long) channel.messages().size());
        long unmatchedOnChannel = Math.max(0L, observedOnChannel - matchedOnChannel);
        return new CanChannelMatchSummary(
                channel.channelId(),
                channel.displayName(),
                observedOnChannel,
                matchedOnChannel,
                unmatchedOnChannel,
                percentage(matchedOnChannel, observedOnChannel),
                messageMatches
        );
    }

    private static Map<CanIdKey, String> indexDbcMessages(DbcDatabase dbc) {
        Map<CanIdKey, String> map = new HashMap<>();
        for (DbcMessage message : dbc.messages()) {
            // Prefer first name if duplicate keys appear; DBC should be unique by ID identity.
            map.putIfAbsent(message.canId(), message.name());
        }
        return map;
    }

    private static Map<Long, CanIdKey> indexDbcByNumericId(Map<CanIdKey, String> dbcByKey) {
        Map<Long, CanIdKey> byNumeric = new HashMap<>();
        for (CanIdKey key : dbcByKey.keySet()) {
            byNumeric.putIfAbsent(key.canId(), key);
        }
        return byNumeric;
    }

    private static CanDefinitionMatchStatus resolveStatus(
            int dbcMessageCount,
            long globalObservedUnique,
            long globalMatchedUnique
    ) {
        if (dbcMessageCount <= 0) {
            return CanDefinitionMatchStatus.NO_DBC_SELECTED;
        }
        if (globalObservedUnique <= 0) {
            return CanDefinitionMatchStatus.READY;
        }
        if (globalMatchedUnique <= 0) {
            return CanDefinitionMatchStatus.NO_MATCHING_MESSAGE_IDS;
        }
        return CanDefinitionMatchStatus.READY;
    }

    /**
     * Percentage with safe zero handling. Kept as double for later UI formatting.
     */
    static double percentage(long matched, long observed) {
        if (observed <= 0L) {
            return 0.0;
        }
        return (matched * 100.0) / observed;
    }
}
