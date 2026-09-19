package in.simplifymoney.ledgersync.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.Test;

class InMemoryDocumentStoreTest {

    private NormalizedTxn txn(String acct, String amount, Category cat, String merchant,
                               String at, String... msgIds) {
        return new NormalizedTxn(acct, OffsetDateTime.parse(at), Direction.DEBIT,
                new BigDecimal(amount), cat, merchant, List.of(msgIds));
    }

    @Test
    void query1_returnsOneAccountsMonthNewestFirstWithoutScanning() {
        InMemoryDocumentStore store = new InMemoryDocumentStore();
        store.save(txn("4821", "100.00", Category.SPEND, "A", "2026-07-01T10:00:00+05:30", "m1"));
        store.save(txn("4821", "200.00", Category.SPEND, "B", "2026-07-15T10:00:00+05:30", "m2"));
        store.save(txn("4821", "300.00", Category.SPEND, "C", "2026-08-01T10:00:00+05:30", "m3"));
        store.save(txn("9075", "400.00", Category.SPEND, "D", "2026-07-10T10:00:00+05:30", "m4"));

        List<NormalizedTxn> julyFor4821 = store.forAccountMonth("4821", YearMonth.of(2026, 7));

        assertEquals(2, julyFor4821.size());
        assertEquals("B", julyFor4821.get(0).merchant(), "newest first");
        assertEquals("A", julyFor4821.get(1).merchant());
        assertEquals(store.lastReturned(), store.lastExamined(),
                "a well-indexed query examines exactly what it returns, no more");
    }

    @Test
    void query2_categoryTotalsAreMaintainedIncrementallyNotByScanning() {
        InMemoryDocumentStore store = new InMemoryDocumentStore();
        store.save(txn("4821", "100.00", Category.SPEND, "A", "2026-07-01T10:00:00+05:30", "m1"));
        store.save(txn("4821", "50.00", Category.MICRO, "B", "2026-07-02T10:00:00+05:30", "m2"));
        store.save(txn("4821", "1000.00", Category.INCOME, "C", "2026-07-03T10:00:00+05:30", "m3"));

        var totals = store.categoryTotals("4821");

        assertEquals(new BigDecimal("100.00"), totals.get(Category.SPEND));
        assertEquals(new BigDecimal("50.00"), totals.get(Category.MICRO));
        assertEquals(new BigDecimal("1000.00"), totals.get(Category.INCOME));
        assertEquals(BigDecimal.ZERO.setScale(2), totals.get(Category.TRANSFER));
        assertTrue(store.lastExamined() <= Category.values().length);
    }

    @Test
    void query3_findsTheTransactionForEachOfItsSourceMessages() {
        InMemoryDocumentStore store = new InMemoryDocumentStore();
        store.save(txn("4821", "100.00", Category.SPEND, "A", "2026-07-01T10:00:00+05:30",
                "m1", "m2"));

        assertTrue(store.byMessageId("m1").isPresent());
        assertTrue(store.byMessageId("m2").isPresent());
        assertEquals(store.byMessageId("m1").get(), store.byMessageId("m2").get());
        assertTrue(store.byMessageId("does-not-exist").isEmpty());
    }

    @Test
    void savingTheSameTransactionTwiceIsIdempotent() {
        InMemoryDocumentStore store = new InMemoryDocumentStore();
        NormalizedTxn t = txn("4821", "100.00", Category.SPEND, "A",
                "2026-07-01T10:00:00+05:30", "m1");

        store.save(t);
        store.save(t);

        assertEquals(1, store.size());
        assertEquals(new BigDecimal("100.00"), store.categoryTotals("4821").get(Category.SPEND),
                "re-saving must not double the category total");
    }

    @Test
    void savingAnOverlappingTransactionMergesSourceMessageIds() {
        InMemoryDocumentStore store = new InMemoryDocumentStore();
        store.save(txn("4821", "100.00", Category.SPEND, "A", "2026-07-01T10:00:00+05:30", "m1"));
        store.save(txn("4821", "100.00", Category.SPEND, "A", "2026-07-01T10:00:00+05:30", "m2"));

        assertEquals(1, store.size());
        assertEquals(List.of("m1", "m2"), store.all().get(0).sourceMessageIds());
    }
}
