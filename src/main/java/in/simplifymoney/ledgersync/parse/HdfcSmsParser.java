package in.simplifymoney.ledgersync.parse;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.RawMessage;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HDFC Bank SMS.
 *
 * Three real transaction shapes are handled:
 *  - V1: the older single-sentence format
 *  - V2: the newer multi-line "Sent/Received" format, rolled out partway
 *        through the window we have data for (both shapes coexist afterwards)
 *  - CARD: "spent on HDFC Bank Card x3310 ..." - quotes an available LIMIT,
 *        not a balance
 *
 * Everything else HDFC sends (balance enquiries, OTPs, future-dated e-mandate
 * notices) intentionally does not match any pattern below and falls through
 * to Optional.empty() - these are not transactions.
 *
 * INCIDENT FIX (INC-2026-09-11): each pattern below captures the amount
 * itself, from within its own transaction clause, as a named group. There is
 * no separate "scan the whole body for the first rupee figure" step any more,
 * so a balance/limit mentioned later in the same message can no longer be
 * mistaken for the amount - regardless of whether the real amount happens to
 * carry paise or not. See Amounts.java for the full incident note.
 */
public final class HdfcSmsParser implements MessageParser {

    public static final String SENDER = "AD-HDFCBK-S";

    private static final String AMT = "(?<amt>[0-9,]+(?:\\.[0-9]{1,2})?)";

    private static final Pattern V1 = Pattern.compile(
            "Rs\\.?\\s*" + AMT + " (?<dir>debited|credited) (?:from|to) "
                    + "a/c \\*\\*(?<acct>\\d{4}) "
                    + "on (?<when>\\d{2}-\\d{2}-\\d{2} at \\d{2}:\\d{2}) "
                    + "(?:to|by) (?<merchant>[^.]+)\\.");

    private static final Pattern V2 = Pattern.compile(
            "^(?<dir>Sent|Received) (?:Rs\\.?|INR)\\s*" + AMT + "\\n"
                    + "(?:To|From): (?<merchant>.+?)\\n"
                    + "On: (?<when>\\d{2} \\w{3} \\d{2} \\d{2}:\\d{2})\\n"
                    + "A/c: XX(?<acct>\\d{4})",
            Pattern.DOTALL);

    private static final Pattern CARD = Pattern.compile(
            "Rs\\.?\\s*" + AMT + " spent on HDFC Bank Card x(?<acct>\\d{4}) "
                    + "at (?<merchant>.+?) "
                    + "on (?<when>\\d{2}-\\d{2}-\\d{2} \\d{2}:\\d{2})\\.");

    @Override
    public boolean supports(RawMessage m) {
        return "sms".equals(m.channel()) && SENDER.equals(m.sender());
    }

    @Override
    public Optional<ParsedTxn> parse(RawMessage m) {
        String body = m.body();

        Matcher v1 = V1.matcher(body);
        if (v1.find()) {
            Direction d = "debited".equals(v1.group("dir")) ? Direction.DEBIT : Direction.CREDIT;
            return build(m, v1.group("acct"), v1.group("when").replace(" at ", " "),
                    d, v1.group("merchant"), v1.group("amt"));
        }

        Matcher v2 = V2.matcher(body);
        if (v2.find()) {
            Direction d = "Sent".equals(v2.group("dir")) ? Direction.DEBIT : Direction.CREDIT;
            return build(m, v2.group("acct"), v2.group("when"), d, v2.group("merchant"),
                    v2.group("amt"));
        }

        Matcher card = CARD.matcher(body);
        if (card.find()) {
            // A card message quotes an available CREDIT LIMIT, not an
            // account balance - the two are not reconcilable the same way
            // (see Reports.reconciliation and the decision log), so this is
            // deliberately not passed through as statedBalance.
            BigDecimal amount = Amounts.parse(card.group("amt"));
            OffsetDateTime at = Dates.ist(card.group("when"));
            if (at == null) return Optional.empty();
            return Optional.of(new ParsedTxn(card.group("acct"), at, Direction.DEBIT, amount,
                    card.group("merchant").trim(), null, m.messageId()));
        }

        return Optional.empty();
    }

    private Optional<ParsedTxn> build(RawMessage m, String acct, String when,
                                       Direction dir, String merchant, String rawAmount) {
        BigDecimal amount = Amounts.parse(rawAmount);
        OffsetDateTime at = Dates.ist(when);
        if (at == null) return Optional.empty();
        return Optional.of(new ParsedTxn(acct, at, dir, amount, merchant.trim(),
                Amounts.statedBalance(m.body()), m.messageId()));
    }
}
