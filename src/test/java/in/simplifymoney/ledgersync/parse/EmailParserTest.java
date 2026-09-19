package in.simplifymoney.ledgersync.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.RawMessage;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class EmailParserTest {

    private final EmailParser parser = new EmailParser();

    private RawMessage email(String body) {
        return new RawMessage("m-1", "email", "alerts@hdfcbank.net",
                OffsetDateTime.parse("2026-07-01T09:47:00+05:30"), "dev-1", body);
    }

    @Test
    void parsesTransactionEmailAndUsesTheDateHeaderAsOccurredAt() {
        Optional<ParsedTxn> p = parser.parse(email(
                "Date: Wed, 01 Jul 2026 09:02:00 +0530\n"
                        + "Subject: Transaction alert on your account\n\n"
                        + "Dear Customer,\n\n"
                        + "Your account ending 4821 has been credited with INR 45,000.\n"
                        + "Merchant / Remarks: SALARY CREDIT\n"
                        + "Transaction reference: 1597155421\n\n"
                        + "This is a system generated email."));
        assertTrue(p.isPresent());
        assertEquals(new BigDecimal("45000.00"), p.get().amount());
        assertEquals(Direction.CREDIT, p.get().direction());
        assertEquals("4821", p.get().accountLast4());
        assertEquals("SALARY CREDIT", p.get().merchant());
        assertEquals(OffsetDateTime.parse("2026-07-01T09:02:00+05:30"), p.get().occurredAt());
    }

    @Test
    void parsesADebitWithPaise() {
        Optional<ParsedTxn> p = parser.parse(email(
                "Date: Wed, 22 Jul 2026 06:40:00 +0530\n"
                        + "Subject: Transaction alert on your account\n\n"
                        + "Dear Customer,\n\n"
                        + "Your account ending 4821 has been debited with INR 649.00.\n"
                        + "Merchant / Remarks: NETFLIX ENTERTAINMENT\n"
                        + "Transaction reference: 55512\n\n"
                        + "This is a system generated email."));
        assertTrue(p.isPresent());
        assertEquals(new BigDecimal("649.00"), p.get().amount());
        assertEquals(Direction.DEBIT, p.get().direction());
    }

    @Test
    void ignoresANonTransactionEmail() {
        Optional<ParsedTxn> p = parser.parse(email(
                "Date: Wed, 01 Jul 2026 09:47:00 +0530\nSubject: Your statement is ready\n\n"
                        + "Dear Customer, your monthly statement is now available."));
        assertTrue(p.isEmpty());
    }
}
