package in.simplifymoney.ledgersync.identity;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;

/**
 * The content-derived identity of a real transaction.
 *
 * Deliberately excludes messageId and receivedAt: those identify an upload,
 * not the underlying transaction (see RawMessage's javadoc, and INC corpus
 * evidence: 2026-08-15 replays every earlier real message byte-for-byte with
 * a new messageId/receivedAt - message identity and transaction identity are
 * different things on purpose).
 *
 * occurredAt is truncated to the minute before hashing: every SMS format in
 * the corpus reports time to the minute, so this makes re-upload duplicates
 * (same content, re-parsed) collide reliably without being so coarse that
 * unrelated same-day transactions collide.
 *
 * Two identities are considered equal only when account, direction, amount
 * and (normalized) merchant all match exactly, at the same minute. This is
 * deterministic exact-match identity, not fuzzy matching - see the decision
 * log for why fuzzy matching was rejected.
 */
public record TransactionIdentity(
        String accountLast4, Direction direction, BigDecimal amount,
        String merchantKey, OffsetDateTime occurredAtMinute) {

    public static TransactionIdentity of(String accountLast4, Direction direction,
                                          BigDecimal amount, String merchant,
                                          OffsetDateTime occurredAt) {
        return new TransactionIdentity(
                accountLast4,
                direction,
                amount.stripTrailingZeros(),
                normalizeMerchant(merchant),
                occurredAt.truncatedTo(ChronoUnit.MINUTES));
    }

    /** Identity of an already-normalized ledger row - used by store upserts. */
    public static TransactionIdentity of(NormalizedTxn t) {
        return of(t.accountLast4(), t.direction(), t.amount(), t.merchant(), t.occurredAt());
    }

    /**
     * Same identity, ignoring occurred time - used to link a cross-channel
     * (typically email) message to an existing SMS-anchored transaction group
     * where the two channels' timestamps are known to diverge (see
     * Deduplicator).
     */
    public TimelessKey timeless() {
        return new TimelessKey(accountLast4, direction, amount, merchantKey);
    }

    private static String normalizeMerchant(String merchant) {
        return merchant == null ? "" : merchant.trim().toUpperCase();
    }

    public record TimelessKey(String accountLast4, Direction direction, BigDecimal amount,
                               String merchantKey) {}
}
