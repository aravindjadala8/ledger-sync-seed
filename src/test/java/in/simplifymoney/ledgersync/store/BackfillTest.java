package in.simplifymoney.ledgersync.store;

import static org.junit.jupiter.api.Assertions.assertEquals;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class BackfillTest {

    private NormalizedTxn row(String amount, Category cat, String merchant, String at,
                               String... msgIds) {
        return new NormalizedTxn("4821", OffsetDateTime.parse(at), Direction.DEBIT,
                new BigDecimal(amount), cat, merchant, List.of(msgIds));
    }

    /** Mirrors db/migration/V2__seed.sql's known-duplicate SWIGGY row. */
    @Test
    void collapsesDuplicateSqlRowsIntoOneDocument() {
        InMemoryLedgerStore sql = new InMemoryLedgerStore();
        sql.save(row("449.00", Category.SPEND, "SWIGGY", "2026-06-01T20:00:00+05:30",
                "m-legacy-0001"));
        sql.save(row("449.00", Category.SPEND, "SWIGGY", "2026-06-01T20:00:00+05:30",
                "m-legacy-0001")); // literal duplicate row
        sql.save(row("449.00", Category.SPEND, "SWIGGY", "2026-06-01T20:00:00+05:30",
                "m-legacy-0002")); // a different message, same real transaction

        InMemoryDocumentStore documents = new InMemoryDocumentStore();
        Backfill.Result result = new Backfill(sql, documents).run();

        assertEquals(1, documents.size(), "three SQL rows, one real transaction");
        assertEquals(List.of("m-legacy-0001", "m-legacy-0002"),
                documents.all().get(0).sourceMessageIds());
        assertEquals(3, result.read());
        assertEquals(1, result.written());
        assertEquals(2, result.duplicatesCollapsed());
    }

    @Test
    void rerunningBackfillDoesNotDuplicateAnything() {
        InMemoryLedgerStore sql = new InMemoryLedgerStore();
        sql.save(row("100.00", Category.SPEND, "A", "2026-07-01T10:00:00+05:30", "m1"));
        sql.save(row("200.00", Category.SPEND, "B", "2026-07-02T10:00:00+05:30", "m2"));

        InMemoryDocumentStore documents = new InMemoryDocumentStore();
        new Backfill(sql, documents).run();
        assertEquals(2, documents.size());

        new Backfill(sql, documents).run(); // rerun, nothing changed in SQL
        assertEquals(2, documents.size(), "a rerun must not duplicate documents");
    }

    @Test
    void resumingAfterAPartialRunFinishesCleanly() {
        // Simulates: first run only sees half the SQL data (a "partial
        // failure" upstream); a later run sees the rest and completes it.
        InMemoryLedgerStore sql = new InMemoryLedgerStore();
        sql.save(row("100.00", Category.SPEND, "A", "2026-07-01T10:00:00+05:30", "m1"));

        InMemoryDocumentStore documents = new InMemoryDocumentStore();
        new Backfill(sql, documents).run();
        assertEquals(1, documents.size());

        sql.save(row("200.00", Category.SPEND, "B", "2026-07-02T10:00:00+05:30", "m2"));
        new Backfill(sql, documents).run();

        assertEquals(2, documents.size());
    }
}
