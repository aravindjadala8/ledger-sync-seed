package in.simplifymoney.ledgersync.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.RawMessage;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class IciciSmsParserTest {

    private final IciciSmsParser parser = new IciciSmsParser();

    private RawMessage sms(String body) {
        return new RawMessage("m-1", "sms", IciciSmsParser.SENDER,
                OffsetDateTime.parse("2026-07-01T09:00:00+05:30"), "dev-1", body);
    }

    @Test
    void parsesV1() {
        Optional<ParsedTxn> p = parser.parse(sms(
                "Dear Customer, Acct XX9075 is debited with INR 333.33 on 04/07/2026 07:54. "
                        + "Info: SWIGGY. Avl Bal Rs.49,857.25 -ICICI Bank"));
        assertTrue(p.isPresent());
        assertEquals(new BigDecimal("333.33"), p.get().amount());
        assertEquals(Direction.DEBIT, p.get().direction());
        assertEquals("9075", p.get().accountLast4());
        assertEquals("SWIGGY", p.get().merchant());
    }

    @Test
    void incidentRegression_secondFormatWholeRupeeAmount() {
        // Same incident shape as the HDFC water-can example, in the second
        // ICICI format: a whole-rupee amount followed by a much larger
        // quoted balance must not be misread as the amount.
        Optional<ParsedTxn> p = parser.parse(sms(
                "ICICI Bank Acct XX9075 Dr INR 5 on 21-Jul-2026 13:16; UPI/BARBER "
                        + "ref no 123456. BalAvl Rs 52,841.30"));
        assertTrue(p.isPresent());
        assertEquals(new BigDecimal("5.00"), p.get().amount());
        assertEquals(Direction.DEBIT, p.get().direction());
    }

    @Test
    void parsesSecondFormatCredit() {
        Optional<ParsedTxn> p = parser.parse(sms(
                "ICICI Bank Acct XX9075 Cr INR 12000.00 on 21-Jul-2026 13:16; "
                        + "IMPS/P2A/PARAG KAPOOR ref no 998877. BalAvl Rs 51,838.14"));
        assertTrue(p.isPresent());
        assertEquals(new BigDecimal("12000.00"), p.get().amount());
        assertEquals(Direction.CREDIT, p.get().direction());
        assertEquals("IMPS/P2A/PARAG KAPOOR", p.get().merchant());
    }

    @Test
    void ignoresPhishingMessages() {
        Optional<ParsedTxn> p = parser.parse(sms(
                "Your ICICI netbanking will be suspended. Verify PAN immediately at "
                        + "icicibank-secure.co to avoid debit of Rs.45,000.00"));
        assertTrue(p.isEmpty());
    }
}
