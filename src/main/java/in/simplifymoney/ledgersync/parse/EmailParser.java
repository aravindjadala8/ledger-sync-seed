package in.simplifymoney.ledgersync.parse;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.RawMessage;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bank transaction-alert emails.
 *
 * One uniform shape across both banks in the corpus:
 *
 *   Date: Wed, 01 Jul 2026 09:02:00 +0530
 *   Subject: Transaction alert on your account
 *
 *   Dear Customer,
 *
 *   Your account ending 4821 has been credited with INR 45,000.
 *   Merchant / Remarks: SALARY CREDIT
 *   Transaction reference: 1597155421
 *
 *   This is a system generated email.
 *
 * Unlike the SMS formats, the email body carries no separate bank-reported
 * transaction time - the only timestamp available is the email's own Date
 * header. occurredAt for an email-derived ParsedTxn is therefore that header,
 * used as the best available proxy for "when the bank says it happened".
 *
 * In ingestion, when a matching SMS from the same account/direction/amount/
 * merchant exists, the SMS's occurred_at is authoritative and wins (see
 * Deduplicator) - the email's timestamp is only load-bearing for the rare
 * case (observed once in corpus-a: a NETFLIX debit) where no SMS exists at
 * all and the email is the only evidence of the transaction.
 *
 * INCIDENT FIX (INC-2026-09-11): the amount is captured as a named group
 * within the transaction sentence itself (same fix as the SMS parsers) and
 * accepts a missing fractional part ("INR 45,000." has no paise at all).
 */
public final class EmailParser implements MessageParser {

    private static final String AMT = "(?<amt>[0-9,]+(?:\\.[0-9]{1,2})?)";

    private static final Pattern TXN = Pattern.compile(
            "Your account ending (?<acct>\\d{4}) has been (?<dir>debited|credited) with "
                    + "(?:Rs\\.?|INR)\\s*" + AMT + "\\.\\s*\\n"
                    + "Merchant\\s*/\\s*Remarks:\\s*(?<merchant>.+?)\\n"
                    + "Transaction reference:\\s*(?<ref>\\d+)");

    private static final Pattern DATE_HEADER = Pattern.compile("^Date:\\s*(?<date>.+)$",
            Pattern.MULTILINE);

    private static final DateTimeFormatter RFC_1123_ISH =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH);

    @Override
    public boolean supports(RawMessage m) {
        return "email".equals(m.channel());
    }

    @Override
    public Optional<ParsedTxn> parse(RawMessage m) {
        String body = m.body();
        Matcher txn = TXN.matcher(body);
        if (!txn.find()) return Optional.empty();

        Matcher dateHeader = DATE_HEADER.matcher(body);
        if (!dateHeader.find()) return Optional.empty();

        OffsetDateTime at;
        try {
            at = OffsetDateTime.parse(dateHeader.group("date").trim(), RFC_1123_ISH);
        } catch (Exception e) {
            return Optional.empty();
        }

        Direction dir = "debited".equals(txn.group("dir")) ? Direction.DEBIT : Direction.CREDIT;
        BigDecimal amount = Amounts.parse(txn.group("amt"));

        return Optional.of(new ParsedTxn(txn.group("acct"), at, dir, amount,
                txn.group("merchant").trim(), Amounts.statedBalance(body), m.messageId()));
    }
}
