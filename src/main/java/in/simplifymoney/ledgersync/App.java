package in.simplifymoney.ledgersync;

import in.simplifymoney.ledgersync.ingest.IngestService;
import in.simplifymoney.ledgersync.json.Json;
import in.simplifymoney.ledgersync.parse.Parsers;
import in.simplifymoney.ledgersync.report.Reports;
import in.simplifymoney.ledgersync.store.Backfill;
import in.simplifymoney.ledgersync.store.ConsistencyChecker;
import in.simplifymoney.ledgersync.store.InMemoryDocumentStore;
import in.simplifymoney.ledgersync.store.SqlLedgerStore;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Command line entry point.
 *
 *   migrate                    apply db/migration/*.sql
 *   ingest  <corpus.jsonl>     read a corpus into the ledger
 *   report  <out-dir>          write ledger.json, summary.json, reconciliation.json
 *   backfill                   move the SQL ledger into the document store
 *   consistency-check          compare the SQL ledger against the document store
 */
public final class App {

    private static final Path DB = Path.of("data", "ledger");
    private static final Path MIGRATIONS = Path.of("db", "migration");
    private static final Path BALANCE_EVIDENCE = Path.of("data", "balance-evidence.json");

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: migrate | ingest <corpus.jsonl> | report <out-dir> "
                    + "| backfill | consistency-check");
            System.exit(2);
        }
        Files.createDirectories(DB.getParent());

        switch (args[0]) {
            case "migrate" -> {
                try (SqlLedgerStore store = new SqlLedgerStore(DB)) {
                    store.migrate(MIGRATIONS);
                    System.out.println("ledger rows: " + store.count());
                }
            }
            case "ingest" -> {
                if (args.length < 2) throw new IllegalArgumentException("ingest needs a corpus");
                try (SqlLedgerStore store = new SqlLedgerStore(DB)) {
                    store.migrate(MIGRATIONS);
                    IngestService ingest = new IngestService(new Parsers(), store);
                    var stats = ingest.ingestFile(Path.of(args[1]));
                    System.out.println(stats);
                    System.out.println("ledger rows: " + store.count());
                    saveBalanceEvidence(ingest.lastBalanceEvidence());
                }
            }
            case "report" -> {
                if (args.length < 2) throw new IllegalArgumentException("report needs a directory");
                Path out = Path.of(args[1]);
                Files.createDirectories(out);
                try (SqlLedgerStore store = new SqlLedgerStore(DB)) {
                    var ledger = store.all();
                    Map<String, BigDecimal> balanceEvidence = loadBalanceEvidence();
                    Files.writeString(out.resolve("ledger.json"),
                            Json.writePretty(Reports.ledgerDocument(ledger)));
                    Files.writeString(out.resolve("summary.json"),
                            Json.writePretty(Reports.summary(ledger)));
                    Files.writeString(out.resolve("reconciliation.json"),
                            Json.writePretty(Reports.reconciliation(ledger, balanceEvidence)));
                    System.out.println("wrote 3 files to " + out);
                }
            }
            case "backfill" -> {
                try (SqlLedgerStore sql = new SqlLedgerStore(DB)) {
                    sql.migrate(MIGRATIONS);
                    InMemoryDocumentStore documents = InMemoryDocumentStore.loadOrCreate();
                    Backfill.Result result = new Backfill(sql, documents).run();
                    System.out.println(result);
                    documents.persist();
                }
            }
            case "consistency-check" -> {
                try (SqlLedgerStore sql = new SqlLedgerStore(DB)) {
                    sql.migrate(MIGRATIONS);
                    InMemoryDocumentStore documents = InMemoryDocumentStore.loadOrCreate();
                    var divergences = new ConsistencyChecker(sql, documents).check();
                    if (divergences.isEmpty()) {
                        System.out.println("OK: the document store agrees with SQL ("
                                + sql.all().size() + " transactions checked)");
                    } else {
                        System.out.println(divergences.size() + " divergence(s) found:");
                        divergences.forEach(d -> System.out.println("  " + d));
                        System.exit(1);
                    }
                }
            }
            default -> {
                System.err.println("unknown command: " + args[0]);
                System.exit(2);
            }
        }
    }

    /**
     * The balance evidence side-file is a small, non-frozen convenience: it
     * lets `report` (which only has the SQL ledger to read) localize
     * reconciliation discrepancies precisely, without changing the frozen
     * NormalizedTxn contract or the ledger table schema to carry balance
     * data. Entries merge (not replace) across ingest runs, keyed by
     * source_message_id, so ingesting overlapping corpora is still safe.
     */
    private static void saveBalanceEvidence(Map<String, BigDecimal> fresh) throws IOException {
        Map<String, BigDecimal> merged = new LinkedHashMap<>(loadBalanceEvidence());
        merged.putAll(fresh);
        Map<String, Object> out = new LinkedHashMap<>();
        merged.forEach((k, v) -> out.put(k, v.toPlainString()));
        Files.writeString(BALANCE_EVIDENCE, Json.writePretty(out));
    }

    private static Map<String, BigDecimal> loadBalanceEvidence() throws IOException {
        if (!Files.exists(BALANCE_EVIDENCE)) return Map.of();
        Map<String, Object> raw = Json.parseObject(Files.readString(BALANCE_EVIDENCE));
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        raw.forEach((k, v) -> out.put(k, new BigDecimal((String) v)));
        return out;
    }
}
