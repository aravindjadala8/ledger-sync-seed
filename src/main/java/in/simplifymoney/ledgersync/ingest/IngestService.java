package in.simplifymoney.ledgersync.ingest;

import in.simplifymoney.ledgersync.identity.Categorizer;
import in.simplifymoney.ledgersync.identity.Deduplicator;
import in.simplifymoney.ledgersync.identity.TxnGroup;
import in.simplifymoney.ledgersync.json.Json;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.model.RawMessage;
import in.simplifymoney.ledgersync.parse.ParsedTxn;
import in.simplifymoney.ledgersync.parse.Parsers;
import in.simplifymoney.ledgersync.store.LedgerStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Reads a corpus of raw messages and puts real transactions in the ledger.
 *
 * Pipeline, per file ingested:
 *   1. parse every message (a message that is not a transaction - OTP,
 *      balance enquiry, promo, phishing, future-dated e-mandate, noise -
 *      correctly parses to nothing and is skipped, not an error)
 *   2. Deduplicator groups the parsed evidence into real transactions,
 *      collapsing re-upload replays, same-channel duplicates and
 *      cross-channel (SMS/email) duplicates by content identity
 *   3. Categorizer assigns exactly one Category per transaction (TRANSFER,
 *      then MICRO, then SPEND/INCOME)
 *   4. each resulting transaction is upserted into the store
 *
 * Idempotency: step 4 uses LedgerStore.upsert, which merges into an existing
 * row of the same content identity rather than inserting a duplicate. This
 * is what makes it safe to ingest the same corpus twice, ingest overlapping
 * corpora, or retry after a partial failure - the ledger converges to the
 * same state regardless of how many times or in what order the same
 * evidence is (re-)ingested.
 */
public final class IngestService {

    private final Parsers parsers;
    private final LedgerStore store;
    private final Deduplicator deduplicator = new Deduplicator();
    private final Categorizer categorizer = new Categorizer();
    private Map<String, java.math.BigDecimal> lastBalanceEvidence = Map.of();

    public IngestService(Parsers parsers, LedgerStore store) {
        this.parsers = parsers;
        this.store = store;
    }

    public Stats ingestFile(Path corpus) throws IOException {
        List<RawMessage> messages = readCorpus(corpus);
        return ingest(messages);
    }

    public Stats ingest(List<RawMessage> messages) {
        List<Deduplicator.Evidence> evidence = new ArrayList<>();
        int skipped = 0;
        for (RawMessage m : messages) {
            Optional<ParsedTxn> p = parsers.parse(m);
            if (p.isEmpty()) {
                skipped++;
                continue;
            }
            evidence.add(new Deduplicator.Evidence(p.get(), !"email".equals(m.channel())));
        }

        List<TxnGroup> groups = deduplicator.dedupe(evidence);
        categorizer.categorize(groups);

        Map<String, java.math.BigDecimal> balanceEvidence = new java.util.LinkedHashMap<>();
        for (TxnGroup g : groups) {
            NormalizedTxn txn = g.toNormalizedTxn();
            store.upsert(txn);
            if (g.statedBalance() != null) {
                balanceEvidence.put(txn.sourceMessageIds().get(0), g.statedBalance());
            }
        }
        this.lastBalanceEvidence = balanceEvidence;

        return new Stats(messages.size(), groups.size(), skipped);
    }

    /**
     * The balance the bank quoted alongside each transaction produced by the
     * most recent call to ingest()/ingestFile(), keyed by that transaction's
     * first (lexicographically smallest) source_message_id - a stable,
     * deterministic key derivable again later from a NormalizedTxn already
     * in the store. Used only by reconciliation (see Reports.reconciliation)
     * to localize a balance-chain discrepancy to a specific transaction;
     * never persisted as part of the frozen NormalizedTxn/LedgerStore
     * contract.
     */
    public Map<String, java.math.BigDecimal> lastBalanceEvidence() {
        return lastBalanceEvidence;
    }

    public static List<RawMessage> readCorpus(Path corpus) throws IOException {
        List<RawMessage> out = new ArrayList<>();
        try (Stream<String> lines = Files.lines(corpus)) {
            for (String line : (Iterable<String>) lines.filter(s -> !s.isBlank())::iterator) {
                Map<String, Object> o = Json.parseObject(line);
                out.add(new RawMessage(
                        (String) o.get("message_id"),
                        (String) o.get("channel"),
                        (String) o.get("sender"),
                        OffsetDateTime.parse((String) o.get("received_at")),
                        (String) o.get("device_id"),
                        (String) o.get("body")));
            }
        }
        return out;
    }

    /**
     * messagesRead: every raw message seen this call.
     * transactionsWritten: the number of DISTINCT real transactions this
     *   batch resolved to (after dedup) - NOT the number of store writes,
     *   since some of those "writes" are merges into a row that already
     *   existed from a previous ingest.
     * messagesSkipped: messages that parsed to no transaction (noise).
     */
    public record Stats(int messagesRead, int transactionsWritten, int messagesSkipped) {
        @Override
        public String toString() {
            return "messages read=" + messagesRead + " transactions=" + transactionsWritten
                    + " skipped=" + messagesSkipped;
        }
    }
}
