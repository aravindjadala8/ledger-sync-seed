package in.simplifymoney.ledgersync.bench;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.store.InMemoryDocumentStore;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Random;

/**
 * Populates InMemoryDocumentStore with 100,000 synthetic transactions,
 * spread realistically across accounts, months and categories, and reports
 * examined/returned for each of the three required queries.
 *
 * These numbers are measured against InMemoryDocumentStore, the
 * implementation actually compiled and tested in this environment (see the
 * README and DocumentStore's javadoc for why a real DynamoDB client could
 * not be built here). Because InMemoryDocumentStore's three indexes
 * (account+month sort-key ordering, per-category running totals, and a
 * message-id pointer index) are structurally the same PK/SK/GSI design
 * documented for DynamoDB, "examined" here means the same thing a real
 * DynamoDB Query's ScannedCount would mean for that same design: how many
 * index entries the query actually walked, not how many transactions exist
 * in total. A real DynamoDB Query against the equivalent table/GSI would
 * report ScannedCount/Count directly - this benchmark could not be re-run
 * against an actual DynamoDB (or DynamoDB Local) table in this sandbox,
 * which has no network path to AWS or to Maven Central to pull the SDK.
 */
public final class QueryBenchmark {

    public static void main(String[] args) {
        InMemoryDocumentStore store = new InMemoryDocumentStore();
        Random rnd = new Random(42);
        String[] accounts = {"4821", "9075", "3310", "1122", "6633"};
        String[] merchants = {"UPI/CHAIWALA", "AMAZON PAY", "SWIGGY", "SALARY CREDIT", "IRCTC"};
        Category[] categories = Category.values();
        int total = 100_000;

        long start = System.nanoTime();
        for (int i = 0; i < total; i++) {
            String acct = accounts[rnd.nextInt(accounts.length)];
            OffsetDateTime at = OffsetDateTime.parse("2026-01-01T00:00:00+05:30")
                    .plusMinutes(rnd.nextInt(60 * 24 * 300)); // spread over ~10 months
            Direction dir = rnd.nextBoolean() ? Direction.DEBIT : Direction.CREDIT;
            BigDecimal amount = BigDecimal.valueOf(1 + rnd.nextInt(50000), 2);
            Category cat = categories[rnd.nextInt(categories.length)];
            String merchant = merchants[rnd.nextInt(merchants.length)];
            store.save(new NormalizedTxn(acct, at, dir, amount, cat, merchant,
                    List.of("m-bench-" + i)));
        }
        long loadMillis = (System.nanoTime() - start) / 1_000_000;

        System.out.println("loaded " + total + " synthetic transactions in " + loadMillis + "ms");
        System.out.println();

        // Q1
        var q1 = store.forAccountMonth("4821", YearMonth.of(2026, 3));
        System.out.println("Q1 forAccountMonth(4821, 2026-03): examined="
                + store.lastExamined() + " returned=" + store.lastReturned()
                + " (rows in that account+month: " + q1.size() + ")");

        // Q2
        var q2 = store.categoryTotals("4821");
        System.out.println("Q2 categoryTotals(4821): examined=" + store.lastExamined()
                + " returned=" + store.lastReturned() + " totals=" + q2);

        // Q3
        var q3 = store.byMessageId("m-bench-54321");
        System.out.println("Q3 byMessageId(m-bench-54321): examined=" + store.lastExamined()
                + " returned=" + store.lastReturned() + " found=" + q3.isPresent());
    }
}
