package in.simplifymoney.ledgersync.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.RawMessage;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class HdfcSmsParserTest {

    private final HdfcSmsParser parser = new HdfcSmsParser();

    private RawMessage sms(String body) {
        return new RawMessage("m-1", "sms", HdfcSmsParser.SENDER,
                OffsetDateTime.parse("2026-07-01T09:00:00+05:30"), "dev-1", body);
    }

    @Test
    void incidentRegression_wholeRupeeAmountIsNotConfusedWithTheBalance() {
        Optional<ParsedTxn> p = parser.parse(sms(
                "Rs.5 debited from a/c **4821 on 27-06-26 at 10:00 to UPI/WATER CAN. "
                        + "Avl Bal: Rs.92,213.10. Not you? Call 18002586161"));
        assertTrue(p.isPresent());
        assertEquals(new BigDecimal("5.00"), p.get().amount());
        assertEquals(Direction.DEBIT, p.get().direction());
    }

    @Test
    void parsesV1Credit() {
        Optional<ParsedTxn> p = parser.parse(sms(
                "Rs.45,000.00 credited to a/c **4821 on 01-07-26 at 09:02 by SALARY CREDIT. "
                        + "Avl Bal: Rs.93,211.40"));
        assertTrue(p.isPresent());
        assertEquals(new BigDecimal("45000.00"), p.get().amount());
        assertEquals(Direction.CREDIT, p.get().direction());
        assertEquals("4821", p.get().accountLast4());
        assertEquals("SALARY CREDIT", p.get().merchant());
    }

    @Test
    void parsesV2MultiLineFormat() {
        Optional<ParsedTxn> p = parser.parse(sms(
                "Sent INR675.67\nTo: IRCTC\nOn: 23 Jul 26 22:16\nA/c: XX4821\n"
                        + "Available Balance: INR 45679.37\n-HDFC Bank"));
        assertTrue(p.isPresent());
        assertEquals(new BigDecimal("675.67"), p.get().amount());
        assertEquals(Direction.DEBIT, p.get().direction());
        assertEquals("IRCTC", p.get().merchant());
    }

    @Test
    void parsesV2WithWholeRupeeAmount() {
        Optional<ParsedTxn> p = parser.parse(sms(
                "Sent INR99\nTo: STATIONERY\nOn: 05 Jul 26 10:00\nA/c: XX4821\n"
                        + "Available Balance: INR 1000.00\n-HDFC Bank"));
        assertTrue(p.isPresent());
        assertEquals(new BigDecimal("99.00"), p.get().amount());
    }

    @Test
    void parsesCardSpendAndDoesNotTreatTheLimitAsABalance() {
        Optional<ParsedTxn> p = parser.parse(sms(
                "Rs.1,249.99 spent on HDFC Bank Card x3310 at BLINKIT on 03-07-26 11:51. "
                        + "Avl Limit: Rs.196,250.03. Not you? Call 18002586161"));
        assertTrue(p.isPresent());
        assertEquals(new BigDecimal("1249.99"), p.get().amount());
        assertEquals("3310", p.get().accountLast4());
        assertEquals(Direction.DEBIT, p.get().direction());
        // a card limit is not a balance - see Reports.reconciliation
        assertEquals(null, p.get().statedBalance());
    }

    @Test
    void ignoresFutureDatedEMandateNotices() {
        Optional<ParsedTxn> p = parser.parse(sms(
                "E-mandate! Rs.649.00 will be deducted from your HDFC Bank A/c XX4821 "
                        + "on 22-07-26 at 06:15 for NETFLIX ENTERTAINMENT. Avl Bal: Rs.46,868.04"));
        assertTrue(p.isEmpty());
    }

    @Test
    void ignoresBalanceEnquiries() {
        Optional<ParsedTxn> p = parser.parse(
                sms("Avl Bal in a/c **9075 is Rs.50,862.08 as on 11-07-26."));
        assertTrue(p.isEmpty());
    }

    @Test
    void ignoresOtpMessages() {
        Optional<ParsedTxn> p = parser.parse(
                sms("268880 is your OTP for txn of Rs.5160.00 on HDFC Bank Card"));
        assertTrue(p.isEmpty());
    }
}
