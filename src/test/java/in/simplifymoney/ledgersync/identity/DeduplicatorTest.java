package in.simplifymoney.ledgersync.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.parse.ParsedTxn;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class DeduplicatorTest {

    private final Deduplicator dedup = new Deduplicator();

    private ParsedTxn sms(String msgId, String occurredAt) {
        return new ParsedTxn("4821", OffsetDateTime.parse(occurredAt), Direction.DEBIT,
                new BigDecimal("12000.00"), "IMPS/P2A/PARAG KAPOOR",
                new BigDecimal("59564.37"), msgId);
    }

    @Test
    void collapsesAnExactReuploadReplay() {
        // corpus-a's 2026-08-15 replay block: byte-identical content,
        // different message_id/received_at - this is what that looks like
        // once parsed.
        var evidence = List.of(
                new Deduplicator.Evidence(sms("m-00146-49bd2f", "2026-07-21T13:14:00+05:30"), true),
                new Deduplicator.Evidence(sms("m-00369-386a1d", "2026-07-21T13:14:00+05:30"), true));

        List<TxnGroup> groups = dedup.dedupe(evidence);

        assertEquals(1, groups.size(), "one real transaction, not two");
        assertEquals(List.of("m-00146-49bd2f", "m-00369-386a1d"),
                groups.get(0).sourceMessageIds().stream().toList());
    }

    @Test
    void doesNotCollapseTwoDistinctTransactionsWithTheSameAmount() {
        // same amount, same account, different merchant/time - two real,
        // distinct transactions (corpus-a's 2026-08-15 stress day deliberately
        // includes same-amount collisions like this).
        ParsedTxn a = new ParsedTxn("4821", OffsetDateTime.parse("2026-07-05T11:00:00+05:30"),
                Direction.DEBIT, new BigDecimal("1111.11"), "ZOMATO", null, "m-1");
        ParsedTxn b = new ParsedTxn("4821", OffsetDateTime.parse("2026-08-01T09:00:00+05:30"),
                Direction.DEBIT, new BigDecimal("1111.11"), "DMART", null, "m-2");

        List<TxnGroup> groups = dedup.dedupe(List.of(
                new Deduplicator.Evidence(a, true), new Deduplicator.Evidence(b, true)));

        assertEquals(2, groups.size());
    }

    @Test
    void linksAnEmailToItsMatchingSmsEvenWhenHoursApart() {
        ParsedTxn theSms = sms("m-sms-1", "2026-07-16T00:00:00+05:30");
        // an email for the same account/direction/amount/merchant, but with
        // its own (email-header-derived) time many hours later - corpus
        // evidence shows this gap is not reliably small (one legitimate
        // pair in corpus-a is 9 days apart).
        ParsedTxn theEmail = new ParsedTxn("4821", OffsetDateTime.parse("2026-07-16T09:00:00+05:30"),
                Direction.DEBIT, new BigDecimal("12000.00"), "IMPS/P2A/PARAG KAPOOR",
                null, "m-email-1");

        List<TxnGroup> groups = dedup.dedupe(List.of(
                new Deduplicator.Evidence(theSms, true),
                new Deduplicator.Evidence(theEmail, false)));

        assertEquals(1, groups.size());
        assertTrue(groups.get(0).sourceMessageIds().contains("m-sms-1"));
        assertTrue(groups.get(0).sourceMessageIds().contains("m-email-1"));
        // the SMS's bank-reported time wins over the email's Date-header proxy
        assertEquals(OffsetDateTime.parse("2026-07-16T00:00:00+05:30"), groups.get(0).occurredAt());
    }

    @Test
    void anEmailWithNoMatchingSmsBecomesItsOwnTransaction() {
        // corpus-a's one real case: a NETFLIX debit with no SMS counterpart
        // at all - the email is the only evidence.
        ParsedTxn theEmail = new ParsedTxn("4821", OffsetDateTime.parse("2026-07-22T06:40:00+05:30"),
                Direction.DEBIT, new BigDecimal("649.00"), "NETFLIX ENTERTAINMENT",
                null, "m-email-only");

        List<TxnGroup> groups = dedup.dedupe(List.of(new Deduplicator.Evidence(theEmail, false)));

        assertEquals(1, groups.size());
        assertEquals(OffsetDateTime.parse("2026-07-22T06:40:00+05:30"), groups.get(0).occurredAt());
    }

    @Test
    void doesNotLinkARecurringMerchantToTheWrongOccurrence() {
        // IRCTC ~Rs.99.99 recurs many times in corpus-a on the same account;
        // an email must link to the NEAREST occurrence, not an arbitrary one.
        ParsedTxn near = new ParsedTxn("4821", OffsetDateTime.parse("2026-07-14T08:18:00+05:30"),
                Direction.DEBIT, new BigDecimal("99.99"), "IRCTC", null, "m-near");
        ParsedTxn far = new ParsedTxn("4821", OffsetDateTime.parse("2026-08-10T12:32:00+05:30"),
                Direction.DEBIT, new BigDecimal("99.99"), "IRCTC", null, "m-far");
        ParsedTxn theEmail = new ParsedTxn("4821", OffsetDateTime.parse("2026-07-14T11:00:00+05:30"),
                Direction.DEBIT, new BigDecimal("99.99"), "IRCTC", null, "m-email");

        List<TxnGroup> groups = dedup.dedupe(List.of(
                new Deduplicator.Evidence(near, true),
                new Deduplicator.Evidence(far, true),
                new Deduplicator.Evidence(theEmail, false)));

        assertEquals(2, groups.size());
        TxnGroup withEmail = groups.stream()
                .filter(g -> g.sourceMessageIds().contains("m-email")).findFirst().orElseThrow();
        assertTrue(withEmail.sourceMessageIds().contains("m-near"));
        assertTrue(!withEmail.sourceMessageIds().contains("m-far"));
    }
}
