package in.simplifymoney.ledgersync.identity;

import in.simplifymoney.ledgersync.parse.ParsedTxn;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a batch of parsed messages into the smallest possible set of real
 * transactions.
 *
 * Two kinds of duplication are collapsed, in two passes:
 *
 *  Pass 1 (exact, same-channel-shaped evidence: every SMS-derived ParsedTxn,
 *  regardless of which bank/format produced it): grouped by full content
 *  identity (account, direction, amount, merchant, occurred-minute). This
 *  alone collapses:
 *    - exact re-upload replay (corpus-a's 2026-08-15 block: 167 messages,
 *      each byte-identical to an earlier one, uploaded again under a new
 *      message_id/received_at)
 *    - same-channel retry duplicates (e.g. the corpus's duplicated
 *      "MYNTRA 1899.00" ICICI SMS, two distinct message_ids, identical body)
 *
 *  Pass 2 (cross-channel linking): every email-derived ParsedTxn is matched,
 *  by (account, direction, amount, merchant) alone - deliberately ignoring
 *  time - against the groups pass 1 already built. Corpus evidence showed
 *  cross-channel timing is usually ~3 hours apart but is NOT reliably
 *  bounded (one legitimate pair was 9 days apart), so time is used only to
 *  disambiguate when a merchant/amount recurs (e.g. a recurring IRCTC
 *  subscription charge), never as a hard filter. An email with no SMS match
 *  at all becomes its own transaction, using the email's own Date header as
 *  occurredAt (see EmailParser) - this is the one real case in corpus-a
 *  (a NETFLIX debit with no SMS counterpart).
 *
 * This is deterministic content-equality, not fuzzy matching: two
 * transactions are only merged when account+direction+amount+merchant match
 * exactly. No probabilistic/similarity scoring is used, so this never
 * silently merges two distinct transactions that merely look alike.
 *
 * Complexity: pass 1 is a single O(n) pass through a hash map. Pass 2 is
 * O(m) lookups into that map (m = number of emails), each O(1) plus a
 * linear scan only across the (typically tiny) list of same-key candidates -
 * never an O(n^2) scan of the whole batch.
 */
public final class Deduplicator {

    public record Evidence(ParsedTxn txn, boolean isSmsLike) {}

    public List<TxnGroup> dedupe(List<Evidence> evidences) {
        Map<TransactionIdentity, TxnGroup> byIdentity = new LinkedHashMap<>();
        Map<TransactionIdentity.TimelessKey, List<TxnGroup>> byTimeless = new LinkedHashMap<>();
        List<Evidence> crossChannel = new ArrayList<>();

        // Pass 1: exact-identity grouping of every SMS-shaped message.
        for (Evidence e : evidences) {
            if (!e.isSmsLike()) {
                crossChannel.add(e);
                continue;
            }
            ParsedTxn t = e.txn();
            TransactionIdentity id = TransactionIdentity.of(
                    t.accountLast4(), t.direction(), t.amount(), t.merchant(), t.occurredAt());
            TxnGroup existing = byIdentity.get(id);
            if (existing == null) {
                TxnGroup g = new TxnGroup(t.accountLast4(), t.direction(), t.amount(),
                        t.merchant(), t.occurredAt(), true, t.sourceMessageId());
                g.noteStatedBalance(t.statedBalance(), true);
                byIdentity.put(id, g);
                byTimeless.computeIfAbsent(id.timeless(), k -> new ArrayList<>()).add(g);
            } else {
                existing.addEvidence(t.sourceMessageId(), t.occurredAt(), true);
                existing.noteStatedBalance(t.statedBalance(), true);
            }
        }

        // Pass 2: link (or create) cross-channel evidence, e.g. emails.
        Map<TransactionIdentity, TxnGroup> crossChannelOnly = new LinkedHashMap<>();
        for (Evidence e : crossChannel) {
            ParsedTxn t = e.txn();
            TransactionIdentity id = TransactionIdentity.of(
                    t.accountLast4(), t.direction(), t.amount(), t.merchant(), t.occurredAt());
            TransactionIdentity.TimelessKey key = id.timeless();

            List<TxnGroup> candidates = byTimeless.get(key);
            if (candidates != null && !candidates.isEmpty()) {
                TxnGroup nearest = nearestByTime(candidates, t.occurredAt());
                nearest.addEvidence(t.sourceMessageId(), t.occurredAt(), false);
                nearest.noteStatedBalance(t.statedBalance(), false);
                continue;
            }

            // No SMS evidence exists for this transaction at all. Collapse
            // duplicate cross-channel-only evidence (e.g. two identical
            // emails) by the same exact-identity rule as pass 1.
            TxnGroup existing = crossChannelOnly.get(id);
            if (existing == null) {
                TxnGroup g = new TxnGroup(t.accountLast4(), t.direction(), t.amount(),
                        t.merchant(), t.occurredAt(), false, t.sourceMessageId());
                g.noteStatedBalance(t.statedBalance(), false);
                crossChannelOnly.put(id, g);
            } else {
                existing.addEvidence(t.sourceMessageId(), t.occurredAt(), false);
                existing.noteStatedBalance(t.statedBalance(), false);
            }
        }

        List<TxnGroup> out = new ArrayList<>(byIdentity.values());
        out.addAll(crossChannelOnly.values());
        return out;
    }

    private static TxnGroup nearestByTime(List<TxnGroup> candidates,
                                           java.time.OffsetDateTime target) {
        TxnGroup best = candidates.get(0);
        Duration bestDelta = Duration.between(best.occurredAt(), target).abs();
        for (TxnGroup g : candidates.subList(1, candidates.size())) {
            Duration d = Duration.between(g.occurredAt(), target).abs();
            if (d.compareTo(bestDelta) < 0) {
                best = g;
                bestDelta = d;
            }
        }
        return best;
    }
}
