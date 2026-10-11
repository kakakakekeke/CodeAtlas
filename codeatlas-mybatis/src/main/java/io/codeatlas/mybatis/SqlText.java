package io.codeatlas.mybatis;

import java.util.regex.Pattern;

/** 전개한 SQL 텍스트의 모양을 다듬는 도우미. */
final class SqlText {

    /** 노드 attrs.sql의 최대 길이. */
    static final int MAX_ATTR_LENGTH = 2000;

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern CALL = Pattern.compile("^(\\{\\s*(\\?\\s*=\\s*)?call\\b|call\\s).*",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern MERGE = Pattern.compile("merge\\s+into\\b", Pattern.CASE_INSENSITIVE);

    private SqlText() {
    }

    /** 모든 공백 묶음을 한 칸으로 줄이고 양끝을 자른다. */
    static String collapse(String sql) {
        return WHITESPACE.matcher(sql).replaceAll(" ").trim();
    }

    /** attrs.sql용: 한 줄로 줄이고 2000자로 자른다. */
    static String forAttr(String sql) {
        String collapsed = collapse(sql);
        return collapsed.length() <= MAX_ATTR_LENGTH ? collapsed : collapsed.substring(0, MAX_ATTR_LENGTH - 1) + "…";
    }

    /**
     * 내장 소스용: 줄마다 양끝 공백을 자르고 빈 줄을 뺀다. 줄바꿈은 남긴다(SQL 줄 주석이 뒤 줄을 삼키지 않게).
     */
    static String tidyLines(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        for (String line : sql.split("\\R")) {
            String stripped = line.strip();
            if (!stripped.isEmpty()) {
                if (out.length() > 0) {
                    out.append('\n');
                }
                out.append(stripped);
            }
        }
        return out.toString();
    }

    /** 프로시저 호출 구문인가. {@code {call ...}}, {@code {? = call ...}}, {@code CALL ...}. 앞의 SQL 주석은 건너뛴다. */
    static boolean isCall(String sql) {
        return CALL.matcher(skipLeadingComments(sql)).matches();
    }

    /** {@code MERGE INTO ...}로 시작하는 구문인가. 앞의 SQL 주석은 건너뛴다. */
    static boolean isMerge(String sql) {
        return MERGE.matcher(skipLeadingComments(sql)).lookingAt();
    }

    private static String skipLeadingComments(String sql) {
        String s = sql.stripLeading();
        while (true) {
            if (s.startsWith("/*")) {
                int end = s.indexOf("*/");
                if (end < 0) {
                    return s;
                }
                s = s.substring(end + 2).stripLeading();
            } else if (s.startsWith("--")) {
                int end = s.indexOf('\n');
                if (end < 0) {
                    return "";
                }
                s = s.substring(end + 1).stripLeading();
            } else {
                return s;
            }
        }
    }
}
