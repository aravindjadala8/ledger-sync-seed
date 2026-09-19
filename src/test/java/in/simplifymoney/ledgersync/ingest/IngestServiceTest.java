package in.simplifymoney.ledgersync.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import in.simplifymoney.ledgersync.model.RawMessage;
import in.simplifymoney.ledgersync.parse.Parsers;
import in.simplifymoney.ledgersync.store.InMemoryLedgerStore;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class IngestServiceTest {

    private List<RawMessage> sampleMessages() {
        List<RawMessage> msgs = new ArrayList<>();
        msgs.add(new RawMessage("m-1", "sms", "AD-HDFCBK-S",
                OffsetDateTime.parse("2026-07-01T09:03:00+05:30"), "dev-1",
                "Rs.45,000.00 credited to a/c **4821 on 01-07-26 at 09:02 by SALARY CREDIT. "
                        + "Avl Bal: Rs.93,211.40"));
        msgs.add(new RawMessage("m-2", "sms", "AD-HDFCBK-S",
                OffsetDateTime.parse("2026-07-01T11:52:00+05:30"), "dev-1",
                "Rs.99.99 debited from a/c **4821 on 01-07-26 at 11:52 to IRCTC. "
                        + "Avl Bal: Rs.93,111.41"));
        msgs.add(new RawMessage("noise-1", "sms", "AD-HDFCBK-S",
                OffsetDateTime.parse("2026-07-01T12:00:00+05:30"), "dev-1",
                "268880 is your OTP for txn of Rs.5160.00 on HDFC Bank Card"));
        return msgs;
    }

    @Test
    void ingestingTheSameCorpusTwiceProducesTheSameLedger() {
        InMemoryLedgerStore store = new InMemoryLedgerStore();
        IngestService ingest = new IngestService(new Parsers(), store);

        var stats1 = ingest.ingest(sampleMessages());
        assertEquals(2, stats1.transactionsWritten());
        assertEquals(1, stats1.messagesSkipped());
        long afterFirst = store.count();

        var stats2 = ingest.ingest(sampleMessages());
        assertEquals(2, stats2.transactionsWritten());
        assertEquals(afterFirst, store.count(), "re-ingesting must not add rows");
    }

    @Test
    void ingestingAnOverlappingCorpusOnlyAddsTheNewTransaction() {
        InMemoryLedgerStore store = new InMemoryLedgerStore();
        IngestService ingest = new IngestService(new Parsers(), store);

        ingest.ingest(sampleMessages()); // 2 real transactions
        assertEquals(2, store.count());

        List<RawMessage> overlapping = new ArrayList<>(sampleMessages()); // same 2, again
        overlapping.add(new RawMessage("m-3", "sms", "AD-HDFCBK-S",
                OffsetDateTime.parse("2026-07-02T09:00:00+05:30"), "dev-1",
                "Rs.200.00 debited from a/c **4821 on 02-07-26 at 09:00 to SWIGGY. "
                        + "Avl Bal: Rs.92,911.41"));

        ingest.ingest(overlapping);
        assertEquals(3, store.count(), "only the genuinely new transaction is added");
    }

    @Test
    void retryAfterASimulatedPartialFailureConvergesToTheSameLedger() {
        // Simulates a crash mid-run: ingest half, then ingest the whole
        // batch again - the end state must match ingesting it whole once.
        InMemoryLedgerStore store = new InMemoryLedgerStore();
        IngestService ingest = new IngestService(new Parsers(), store);
        List<RawMessage> all = sampleMessages();

        ingest.ingest(all.subList(0, 1)); // "crashes" after the first message
        assertEquals(1, store.count());

        ingest.ingest(all); // retried in full
        assertEquals(2, store.count());
    }
}
