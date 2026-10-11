package io.codeatlas.export.common;

import java.util.regex.Pattern;

/** 출력기들이 함께 쓰는 문자열 보조 함수. */
public final class Texts {

    private Texts() {
    }

    /** null이면 빈 문자열. */
    public static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    public static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** 줄바꿈과 탭을 공백 하나로 줄인다. */
    public static String oneLine(String s) {
        return s == null ? "" : s.replaceAll("[\\r\\n\\t]+", " ").strip();
    }

    /**
     * glob을 대소문자를 가리지 않는 정규식으로 바꾼다. {@code *}는 0자 이상, {@code ?}는 한 글자다.
     * 예: {@code ORD*}, {@code *_HIST}.
     */
    public static Pattern glob(String glob) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*') {
                regex.append(".*");
            } else if (c == '?') {
                regex.append('.');
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    /** Markdown 표의 칸에 넣을 수 있게 {@code |}를 이스케이프하고 한 줄로 만든다. */
    public static String mdCell(String s) {
        return oneLine(s).replace("|", "\\|");
    }

    /** Markdown 코드 스팬. {@code <}, {@code >}, {@code *}가 든 이름을 그대로 보이게 한다. 표 안에서도 쓸 수 있다. */
    public static String mdCode(String s) {
        String text = oneLine(s);
        return text.isEmpty() ? "" : "`" + text.replace('`', '\'').replace("|", "\\|") + "`";
    }

    /** CSV 칸(RFC 4180). 쉼표, 따옴표, 줄바꿈이 있으면 큰따옴표로 감싸고 따옴표는 두 번 쓴다. */
    public static String csvCell(String s) {
        if (s == null) {
            return "";
        }
        if (s.indexOf(',') < 0 && s.indexOf('"') < 0 && s.indexOf('\n') < 0 && s.indexOf('\r') < 0) {
            return s;
        }
        return '"' + s.replace("\"", "\"\"") + '"';
    }

    /** 파일 이름으로 쓸 수 있게 문자·숫자·{@code . _ -} 외의 문자를 {@code _}로 바꾼다. 비면 {@code _}. */
    public static String fileSafe(String s) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            out.append(Character.isLetterOrDigit(c) || c == '.' || c == '_' || c == '-' ? c : '_');
        }
        return out.isEmpty() ? "_" : out.toString();
    }

    /**
     * 타입 문자열에서 패키지 한정자를 떼고 중첩 타입의 {@code $}를 {@code .}으로 바꾼다.
     * 예: {@code java.util.List<com.a.Order$Item>} → {@code List<Order.Item>}.
     */
    public static String simpleTypeText(String typeText) {
        if (typeText == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        StringBuilder word = new StringBuilder();
        for (int i = 0; i <= typeText.length(); i++) {
            char c = i < typeText.length() ? typeText.charAt(i) : '\0';
            if (i < typeText.length() && (Character.isJavaIdentifierPart(c) || c == '.')) {
                word.append(c);
            } else {
                out.append(simpleName(word.toString()));
                word.setLength(0);
                if (i < typeText.length()) {
                    out.append(c);
                }
            }
        }
        return out.toString();
    }

    /** 바이너리 이름의 단순 이름. {@code com.a.Outer$Inner} → {@code Outer.Inner}. {@code ...}(가변 인자)은 그대로 둔다. */
    public static String simpleName(String binaryName) {
        if (binaryName.isEmpty()) {
            return binaryName;
        }
        String varargs = "";
        String name = binaryName;
        if (name.endsWith("...")) {
            varargs = "...";
            name = name.substring(0, name.length() - 3);
        }
        // 첫 글자가 소문자인 조각들은 패키지로 본다. 바깥 타입 이름(Outer.Inner 표기)은 남긴다.
        String[] parts = name.split("\\.");
        if (parts.length > 1) {
            int firstType = parts.length - 1;
            for (int i = 0; i < parts.length; i++) {
                if (!parts[i].isEmpty() && Character.isUpperCase(parts[i].charAt(0))) {
                    firstType = i;
                    break;
                }
            }
            StringBuilder joined = new StringBuilder();
            for (int i = firstType; i < parts.length; i++) {
                if (joined.length() > 0) {
                    joined.append('.');
                }
                joined.append(parts[i]);
            }
            name = joined.toString();
        }
        return name.replace('$', '.') + varargs;
    }

    /** 바이너리 이름의 패키지. 패키지가 없으면 빈 문자열. */
    public static String packageOf(String binaryName) {
        int dot = binaryName.lastIndexOf('.');
        return dot < 0 ? "" : binaryName.substring(0, dot);
    }
}
