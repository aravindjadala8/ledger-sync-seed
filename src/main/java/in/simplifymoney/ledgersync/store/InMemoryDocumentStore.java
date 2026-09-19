package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.identity.TransactionIdentity;
import in.simplifymoney.ledgersync.json.Json;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory stand-in for the DynamoDB design documented on DocumentStore.
 *
 * Every index described there is modeled with its own data structure, kept
 * in sync on every save() - this is deliberate, so that each of the three
 * required queries touches only as much data as the equivalent real
 * DynamoDB Query/GetItem would (see examined()/returned() below), never a
 * full scan of every transaction:
 *
 *   primary   Map<txnId, NormalizedTxn>                (the "table")
 *   byAcctSK  Map<account, TreeMap<SK, txnId>>          (the table's own
 *                                                        sort-key ordering -
 *                                                        serves Q1)
 *   gsi1      Map<account, Map<Category, BigDecimal>>   (running totals,
 *                                                        maintained by
 *                                                        incremental ADD,
 *                                                        never recomputed -
 *                                                        serves Q2)
 *   gsi2      Map<messageId, txnId>                     (serves Q3)
 *
 * txnId is the transaction's first (lexicographically smallest)
 * source_message_id - stable, deterministic, and derivable again from a
 * NormalizedTxn alone, same convention used for the balance-evidence
 * side-file (see IngestService).
 */
public final class InMemoryDocumentStore implements DocumentStore {

    private final Map<TransactionIdentity, NormalizedTxn> primary = new LinkedHashMap<>();
    private final Map<String, TreeMap<String, TransactionIdentity>> byAcctSK = new LinkedHashMap<>();
    private final Map<String, Map<Category, BigDecimal>> gsi1 = new LinkedHashMap<>();
    private final Map<String, TransactionIdentity> gsi2 = new LinkedHashMap<>();

    // Instrumentation only - lets BenchmarkTest report the same "examined vs
    // returned" pair a real DynamoDB/Mongo client would give via
    // ScannedCount/Count or totalDocsExamined/nReturned.
    private final AtomicLong lastExamined = new AtomicLong();
    private final AtomicLong lastReturned = new AtomicLong();

    @Override
    public synchronized void save(NormalizedTxn txn) {
        TransactionIdentity id = TransactionIdentity.of(txn);
        NormalizedTxn existing = primary.get(id);
        NormalizedTxn toStore = txn;
        if (existing != null) {
            var merged = new java.util.TreeSet<>(existing.sourceMessageIds());
            merged.addAll(txn.sourceMessageIds());
            toStore = new NormalizedTxn(existing.accountLast4(), existing.occurredAt(),
                    existing.direction(), existing.amount(), existing.category(),
                    existing.merchant(), merged.stream().toList());
            // undo the old item's contribution to GSI1 before re-adding, so
            // re-saving the same transaction never double-counts its total
            adjustCategoryTotal(existing, -1);
            for (String mid : existing.sourceMessageIds()) gsi2.remove(mid);
            byAcctSK.getOrDefault(existing.accountLast4(), new TreeMap<>())
                    .remove(sortKey(existing, id));
        }

        primary.put(id, toStore);
        byAcctSK.computeIfAbsent(toStore.accountLast4(), k -> new TreeMap<>())
                .put(sortKey(toStore, id), id);
        adjustCategoryTotal(toStore, +1);
        for (String mid : toStore.sourceMessageIds()) gsi2.put(mid, id);
    }

    @Override
    public synchronized List<NormalizedTxn> forAccountMonth(String accountLast4, YearMonth month) {
        TreeMap<String, TransactionIdentity> sk = byAcctSK.get(accountLast4);
        if (sk == null) { lastExamined.set(0); lastReturned.set(0); return List.of(); }

        String prefix = "TXN#" + month + "#";
        String from = prefix;
        String to = prefix + Character.MAX_VALUE;
        var slice = sk.subMap(from, true, to, true);

        List<NormalizedTxn> out = new ArrayList<>();
        for (TransactionIdentity id : slice.values()) out.add(primary.get(id));
        out.sort(Comparator.comparing(NormalizedTxn::occurredAt).reversed());

        lastExamined.set(slice.size());   // a real Query examines exactly the
        lastReturned.set(out.size());     // matching SK range - no more
        return out;
    }

    @Override
    public synchronized Map<Category, BigDecimal> categoryTotals(String accountLast4) {
        Map<Category, BigDecimal> totals = gsi1.getOrDefault(accountLast4, Map.of());
        Map<Category, BigDecimal> out = new LinkedHashMap<>();
        for (Category c : Category.values()) {
            out.put(c, totals.getOrDefault(c, BigDecimal.ZERO.setScale(2)));
        }
        lastExamined.set(Category.values().length); // one GetItem per category
        lastReturned.set(Category.values().length);
        return out;
    }

    @Override
    public synchronized Optional<NormalizedTxn> byMessageId(String messageId) {
        TransactionIdentity id = gsi2.get(messageId);
        lastExamined.set(1); // a single GSI2 Query on an exact-match PK
        lastReturned.set(id == null ? 0 : 1);
        return id == null ? Optional.empty() : Optional.ofNullable(primary.get(id));
    }

    public long lastExamined() { return lastExamined.get(); }
    public long lastReturned() { return lastReturned.get(); }

    public synchronized int size() { return primary.size(); }

    public synchronized List<NormalizedTxn> all() {
        return List.copyOf(primary.values());
    }

    private void adjustCategoryTotal(NormalizedTxn t, int sign) {
        Map<Category, BigDecimal> totals = gsi1.computeIfAbsent(t.accountLast4(),
                k -> new LinkedHashMap<>());
        BigDecimal delta = t.amount().multiply(BigDecimal.valueOf(sign));
        totals.merge(t.category(), delta, BigDecimal::add);
    }

    private static String sortKey(NormalizedTxn t, TransactionIdentity id) {
        return "TXN#" + YearMonth.from(t.occurredAt()) + "#"
                + t.occurredAt().toString() + "#" + id.hashCode();
    }

    // -------------------------------------------------------- persistence
    // Backfill and consistency-check are separate CLI invocations (separate
    // JVMs), so this store's contents are persisted to a plain JSON file
    // between calls - a pragmatic stand-in for DynamoDB's own durability in
    // this offline, in-memory implementation.

    private static final Path FILE = Path.of("data", "document-store.json");

    public static InMemoryDocumentStore loadOrCreate() throws IOException {
        InMemoryDocumentStore store = new InMemoryDocumentStore();
        if (!Files.exists(FILE)) return store;
        Object parsed = Json.parse(Files.readString(FILE));
        for (Object row : (List<?>) parsed) {
            @SuppressWarnings("unchecked")
            Map<String, Object> r = (Map<String, Object>) row;
            @SuppressWarnings("unchecked")
            List<Object> ids = (List<Object>) r.get("source_message_ids");
            store.save(new NormalizedTxn(
                    (String) r.get("account_last4"),
                    OffsetDateTime.parse((String) r.get("occurred_at")),
                    Direction.valueOf((String) r.get("direction")),
                    new BigDecimal((String) r.get("amount")).setScale(2),
                    Category.valueOf((String) r.get("category")),
                    (String) r.get("merchant"),
                    ids.stream().map(String.class::cast).toList()));
        }
        return store;
    }

    public void persist() throws IOException {
        List<Object> rows = new ArrayList<>();
        for (NormalizedTxn t : all()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("account_last4", t.accountLast4());
            r.put("occurred_at", t.occurredAt().toString());
            r.put("direction", t.direction().name());
            r.put("amount", t.amount().toPlainString());
            r.put("category", t.category().name());
            r.put("merchant", t.merchant());
            r.put("source_message_ids", t.sourceMessageIds());
            rows.add(r);
        }
        Files.createDirectories(FILE.getParent());
        Files.writeString(FILE, Json.writePretty(rows));
    }
}
