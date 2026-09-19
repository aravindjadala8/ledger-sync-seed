package in.simplifymoney.ledgersync.parse;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.RawMessage;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ICICI Bank SMS.
 *
 * Two real transaction shapes are handled:
 *  - V1: "Dear Customer, Acct XX.. is debited/credited with Rs.X on
 *        dd/mm/yyyy hh:mm. Info: <merchant>. Avl Bal Rs.Y -ICICI Bank"
 *  - V2: "ICICI Bank Acct XX.. Dr/Cr Rs.X on dd-Mon-yyyy hh:mm; <merchant>
 *        ref no <digits>. BalAvl Rs Y" - the second format that corpus
 *        analysis showed accounts for the majority of ICICI SMS traffic
 *        (V1 only matched 57 of 161 ICICI SMS in corpus-a; V2 covers the
 *        other 100, on top of a handful of promotional/phishing messages
 *        which correctly match neither pattern).
 *
 * INCIDENT FIX (INC-2026-09-11): both patterns capture the amount as a named
 * group inside the transaction clause itself, not via a whole-body scan - see
 * Amounts.java and HdfcSmsParser.java for the full note. This matters
 * doubly here, since V2's "Dr INR 5 on ...; ... . BalAvl Rs 52,841.30" is
 * exactly the same incident shape as the original water-can example.
 */
public final class IciciSmsParser implements MessageParser {

    public static final String SENDER = "VM-ICICIB-T";

    private static final String AMT = "(?<amt>[0-9,]+(?:\\.[0-9]{1,2})?)";

    private static final Pattern V1 = Pattern.compile(
            "Acct XX(?<acct>\\d{4}) is (?<dir>debited|credited) with (?:Rs\\.?|INR)\\s*" + AMT
                    + " on (?<when>\\d{2}/\\d{2}/\\d{4} \\d{2}:\\d{2})\\. "
                    + "Info: (?<merchant>[^.]+)\\.");

    private static final Pattern V2 = Pattern.compile(
            "ICICI Bank Acct XX(?<acct>\\d{4}) (?<dir>Dr|Cr) (?:Rs\\.?|INR)\\s*" + AMT
                    + " on (?<when>\\d{2}-\\w{3}-\\d{4} \\d{2}:\\d{2}); "
                    + "(?<merchant>[^;]+?) ref no (?<ref>\\d+)\\.");

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
            return build(m, v1.group("acct"), v1.group("when"), d, v1.group("merchant"),
                    v1.group("amt"));
        }

        Matcher v2 = V2.matcher(body);
        if (v2.find()) {
            Direction d = "Dr".equals(v2.group("dir")) ? Direction.DEBIT : Direction.CREDIT;
            return build(m, v2.group("acct"), v2.group("when"), d, v2.group("merchant"),
                    v2.group("amt"));
        }

        return Optional.empty();
    }

    private Optional<ParsedTxn> build(RawMessage m, String acct, String when, Direction dir,
                                       String merchant, String rawAmount) {
        BigDecimal amount = Amounts.parse(rawAmount);
        OffsetDateTime at = Dates.ist(when);
        if (at == null) return Optional.empty();
        return Optional.of(new ParsedTxn(acct, at, dir, amount, merchant.trim(),
                Amounts.statedBalance(m.body()), m.messageId()));
    }
}
