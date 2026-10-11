package io.codeatlas.java;

import java.io.PrintStream;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JDT가 표준 오류에 직접 쓰는 내부 로그를 가로챈다(F-002-R18).
 *
 * <p>JDT는 내부 오류를 {@code org.eclipse.jdt.internal.core.util.Util.log}로 남기는데, OSGi 없이(headless) 쓰면 플러그인 로그가 없어
 * {@code System.err.println("Status ERROR: org.eclipse.jdt.core code=4 …")}로 떨어진다. 로그를 받을 다른 훅이 없으므로
 * 분석하는 동안만 {@code System.err}를 걸러 내는 스트림으로 바꿔 그 한 줄만 받아 내고 나머지 출력은 그대로 흘려보낸다.
 *
 * <p>받아 낼 곳은 스레드마다 따로 둔다(JDT의 DOM 해석은 부른 스레드에서 돈다). 가로채기를 건 스레드가 아닌 곳의 출력은 건드리지 않는다.
 */
final class JdtLog extends PrintStream {

    /** {@code Status ERROR: org.eclipse.jdt.core code=4 <메시지> <예외>} 꼴의 줄에서 메시지 부분. */
    private static final Pattern STATUS = Pattern.compile("^Status \\w+: org\\.eclipse\\.jdt[\\w.]* code=\\d+ (.*)$", Pattern.DOTALL);
    /** 메시지 끝에 붙는 예외의 {@code toString()}: 클래스 이름, 문제 번호({@code Pb(347)}), 예외 메시지. */
    private static final Pattern EXCEPTION = Pattern.compile("^(.*?)\\s*\\b(?:[a-z_][\\w$]*\\.)+[A-Z][\\w$]*(?::\\s*(?:Pb\\(\\d+\\)\\s*)?(.*))?$", Pattern.DOTALL);
    private static final int MAX_SUMMARY = 400;

    private static final ThreadLocal<Consumer<String>> SINK = new ThreadLocal<>();
    private static JdtLog installed;
    private static int users;

    private final PrintStream original;

    private JdtLog(PrintStream original) {
        super(original, true);
        this.original = original;
    }

    /** 가로채기 하나. 닫으면 끝난다. */
    static final class Capture implements AutoCloseable {
        private boolean closed;

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                release();
            }
        }
    }

    /**
     * 이 스레드에서 JDT가 표준 오류에 쓰는 로그 줄을 sink로 받는다. 돌려준 것을 닫을 때까지다.
     * 마지막 가로채기가 닫히면 {@code System.err}를 원래 스트림으로 되돌린다.
     */
    static Capture capture(Consumer<String> sink) {
        synchronized (JdtLog.class) {
            if (users++ == 0) {
                installed = new JdtLog(System.err);
                System.setErr(installed);
            }
        }
        SINK.set(sink);
        return new Capture();
    }

    private static void release() {
        SINK.remove();
        synchronized (JdtLog.class) {
            if (--users == 0) {
                // 그 사이 다른 코드가 표준 오류를 바꿨으면 건드리지 않는다
                if (System.err == installed) {
                    System.setErr(installed.original);
                }
                installed = null;
            }
        }
    }

    @Override
    public void println(String line) {
        Consumer<String> sink = SINK.get();
        if (sink != null && isJdtStatus(line)) {
            sink.accept(line);
        } else {
            original.println(line);
        }
    }

    static boolean isJdtStatus(String line) {
        return line != null && STATUS.matcher(line).matches();
    }

    /**
     * JDT 로그 한 줄을 사람이 읽을 요약으로 줄인다: 상태 머리말과 예외 클래스 이름·문제 번호를 떼고 {@code <메시지> (<예외 메시지>)}로 만든다.
     * 꼴을 알아보지 못하면 공백만 정리한 원문이다.
     */
    static String summarize(String line) {
        String text = line == null ? "" : line.strip();
        Matcher status = STATUS.matcher(text);
        if (status.matches()) {
            text = status.group(1).strip();
            Matcher exception = EXCEPTION.matcher(text);
            if (exception.matches() && !exception.group(1).isBlank()) {
                String detail = exception.group(2) == null ? "" : exception.group(2).strip();
                text = detail.isEmpty() ? exception.group(1).strip() : exception.group(1).strip() + " (" + detail + ")";
            }
        }
        text = text.replaceAll("\\s+", " ");
        return text.length() > MAX_SUMMARY ? text.substring(0, MAX_SUMMARY) + "…" : text;
    }
}
