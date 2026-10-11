package io.codeatlas.cli.verify;

/**
 * 판정 집계.
 *
 * @param total 항목 수
 * @param pass PASS 수
 * @param inferredPass INFERRED_PASS 수
 * @param fail FAIL 수
 */
public record Tally(int total, int pass, int inferredPass, int fail) {

    public static final Tally EMPTY = new Tally(0, 0, 0, 0);

    public Tally plus(Verdict verdict) {
        return switch (verdict) {
            case PASS -> new Tally(total + 1, pass + 1, inferredPass, fail);
            case INFERRED_PASS -> new Tally(total + 1, pass, inferredPass + 1, fail);
            case FAIL -> new Tally(total + 1, pass, inferredPass, fail + 1);
        };
    }

    /** PASS와 INFERRED_PASS를 합친 수. */
    public int passed() {
        return pass + inferredPass;
    }

    /** 통과율(%). 항목이 없으면 100. */
    public double passRate() {
        return total == 0 ? 100.0 : passed() * 100.0 / total;
    }
}
