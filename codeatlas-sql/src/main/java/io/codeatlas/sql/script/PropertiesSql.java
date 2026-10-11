package io.codeatlas.sql.script;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * {@code .properties} 파일의 값에 든 SQL을 찾는다(F-006-R43·R44). 보안 프레임워크의 권한 조회 SQL처럼 설정 파일에 적어 두고
 * 라이브러리가 실행하는 구문이 대상이다. 값이 SQL인지는 모양으로만 판정하고, 화면 문구가 모인 메시지 번들은 보지 않는다.
 */
public final class PropertiesSql {

    /** SQL 구문 꼴인 값 하나. {@code line}은 키가 있는 줄(1부터)이다. */
    public record Entry(String key, String value, int line) {
    }

    private static final String NAME = "[\\w.$\"`\\[\\]]+";

    /** 값의 첫머리가 구문 꼴인가. 공백을 한 칸으로 줄인 값에 맞춘다. */
    private static final Pattern STATEMENT = Pattern.compile("^(?:"
            + "SELECT .+ FROM " + NAME
            + "|INSERT INTO " + NAME
            + "|UPDATE " + NAME + "(?: \\w+)? SET "
            + "|DELETE FROM " + NAME
            + "|MERGE INTO " + NAME
            + "|WITH \\w+ .*?\\bAS ?\\("
            + "|\\{ ?(?:\\? ?= ?)?CALL \\w"
            + ")", Pattern.CASE_INSENSITIVE);

    /** 더미 테이블에서 리터럴 하나만 읽는 연결 확인 쿼리: 값 전체가 이 꼴이면 구문으로 만들지 않는다. */
    private static final Pattern PROBE = Pattern.compile("^SELECT (?:\\d+|'[^']*') FROM (?:DUAL|SYSIBM\\.SYSDUMMY1) ?;?$",
            Pattern.CASE_INSENSITIVE);

    /** 로케일 접미사: {@code _en}, {@code _ko_KR}, {@code _zh_Hans_CN}. */
    private static final Pattern LOCALE_SUFFIX = Pattern.compile(".*_[a-z]{2}(?:_[A-Za-z]{4})?(?:_[A-Z]{2})?");

    private PropertiesSql() {
    }

    /** 메시지 번들(화면 문구 모음)로 보는 파일인가. 이런 파일의 값은 SQL 꼴이어도 문장이다(F-006-R44). */
    public static boolean messageBundle(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String lower = base.toLowerCase(Locale.ROOT);
        return lower.contains("message") || lower.contains("i18n") || lower.contains("label")
                || base.endsWith("Resources") || LOCALE_SUFFIX.matcher(base).matches();
    }

    /** 값이 SQL 구문 꼴인가(F-006-R44). */
    public static boolean looksLikeSql(String value) {
        if (value == null) {
            return false;
        }
        String oneLine = value.replaceAll("\\s+", " ").trim();
        return STATEMENT.matcher(oneLine).find() && !PROBE.matcher(oneLine).matches();
    }

    /** 파일 본문에서 값이 SQL 구문 꼴인 항목을 나온 순서대로 돌려준다. */
    public static List<Entry> statements(String text) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries(text)) {
            if (looksLikeSql(e.value())) {
                out.add(e);
            }
        }
        return out;
    }

    /**
     * {@code java.util.Properties}와 같은 규칙으로 읽은 항목들: {@code #}·{@code !} 주석, {@code =}·{@code :}·공백 구분자,
     * 줄 끝 역슬래시 이음(이은 줄의 앞 공백은 버린다), 역슬래시 이스케이프(t, n, r, f, 유니코드 네 자리).
     */
    static List<Entry> entries(String text) {
        List<Entry> out = new ArrayList<>();
        String[] lines = text.split("\r\n|\r|\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String first = stripLeading(lines[i]);
            if (first.isEmpty() || first.charAt(0) == '#' || first.charAt(0) == '!') {
                continue;
            }
            int keyLine = i + 1;
            StringBuilder logical = new StringBuilder(first);
            while (endsWithContinuation(logical) && i + 1 < lines.length) {
                logical.setLength(logical.length() - 1);
                logical.append(stripLeading(lines[++i]));
            }
            if (endsWithContinuation(logical)) {
                logical.setLength(logical.length() - 1);
            }
            int keyEnd = keyEnd(logical);
            int valueStart = valueStart(logical, keyEnd);
            String key = unescape(logical.substring(0, keyEnd));
            if (!key.isEmpty()) {
                out.add(new Entry(key, unescape(logical.substring(valueStart)), keyLine));
            }
        }
        return out;
    }

    private static String stripLeading(String line) {
        int i = 0;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t' || line.charAt(i) == '\f')) {
            i++;
        }
        return line.substring(i);
    }

    /** 끝의 역슬래시 수가 홀수면 다음 줄로 이어진다. */
    private static boolean endsWithContinuation(CharSequence line) {
        int n = 0;
        for (int i = line.length() - 1; i >= 0 && line.charAt(i) == '\\'; i--) {
            n++;
        }
        return n % 2 == 1;
    }

    private static int keyEnd(CharSequence line) {
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == '=' || c == ':' || c == ' ' || c == '\t' || c == '\f') {
                return i;
            }
        }
        return line.length();
    }

    private static int valueStart(CharSequence line, int keyEnd) {
        int i = keyEnd;
        while (i < line.length() && isBlank(line.charAt(i))) {
            i++;
        }
        if (i < line.length() && (line.charAt(i) == '=' || line.charAt(i) == ':')) {
            i++;
            while (i < line.length() && isBlank(line.charAt(i))) {
                i++;
            }
        }
        return i;
    }

    private static boolean isBlank(char c) {
        return c == ' ' || c == '\t' || c == '\f';
    }

    private static String unescape(String s) {
        if (s.indexOf('\\') < 0) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) {
                sb.append(c);
                continue;
            }
            char next = s.charAt(++i);
            switch (next) {
                case 't' -> sb.append('\t');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 'f' -> sb.append('\f');
                case 'u' -> {
                    if (i + 4 < s.length() && s.substring(i + 1, i + 5).matches("[0-9a-fA-F]{4}")) {
                        sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                        i += 4;
                    } else {
                        sb.append(next);
                    }
                }
                default -> sb.append(next);
            }
        }
        return sb.toString();
    }
}
