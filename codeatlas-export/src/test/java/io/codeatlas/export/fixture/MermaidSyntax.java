package io.codeatlas.export.fixture;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Mermaid 문법 규칙 기반 검증기(테스트용). 실제 mermaid 패키지를 쓰지 않고, mermaid의 jison 문법
 * (erDiagram, classDiagram, flowchart)에서 우리 출력이 쓰는 부분의 토큰 규칙을 줄 단위 정규식으로 옮겼다.
 * 실제 파서보다 엄격하게 잡았다(예: 속성 단어는 ASCII만). 통과하지 못한 줄을 "줄번호: 내용"으로 돌려준다.
 */
public final class MermaidSyntax {

    private MermaidSyntax() {
    }

    private static final java.util.concurrent.atomic.AtomicInteger DUMP_SEQUENCE = new java.util.concurrent.atomic.AtomicInteger();

    /**
     * 시스템 속성 {@code codeatlas.mermaid.dump}에 폴더를 주면 검증한 텍스트를 그 폴더에 파일로 남긴다.
     * 실제 mermaid 파서로 다시 확인할 때 쓴다({@code src/test/resources/mermaid-check.cjs}).
     */
    private static void dump(String kind, String text) {
        String dir = System.getProperty("codeatlas.mermaid.dump");
        if (dir == null || dir.isBlank()) {
            return;
        }
        try {
            java.nio.file.Path folder = java.nio.file.Files.createDirectories(java.nio.file.Path.of(dir));
            java.nio.file.Files.writeString(folder.resolve(String.format("%s-%03d.mmd", kind, DUMP_SEQUENCE.incrementAndGet())), text);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    // ---- erDiagram ----
    // 엔티티 이름: [A-Za-z_][A-Za-z0-9\-_]* 또는 "…"(%, \, " 제외)
    private static final String ER_ENTITY = "(?:[A-Za-z_][A-Za-z0-9\\-_]*|\"[^\"%\\\\\\r\\n]+\")";
    // 속성 단어(타입, 이름): [\*A-Za-z_][A-Za-z0-9\-_\[\]\(\)]*
    private static final String ER_WORD = "[*A-Za-z_][A-Za-z0-9\\-_\\[\\]()]*";
    private static final String ER_KEYS = "(?:PK|FK|UK)(?:\\s*,\\s*(?:PK|FK|UK))*";
    private static final Pattern ER_BLOCK_OPEN = Pattern.compile(ER_ENTITY + "\\s*\\{");
    private static final Pattern ER_ENTITY_ONLY = Pattern.compile(ER_ENTITY);
    private static final Pattern ER_ATTRIBUTE = Pattern.compile(
            ER_WORD + "\\s+" + ER_WORD + "(?:\\s+" + ER_KEYS + ")?(?:\\s+\"[^\"]*\")?");
    private static final Pattern ER_RELATION = Pattern.compile(
            ER_ENTITY + "\\s+(?:\\|o|\\|\\||\\}o|\\}\\|)(?:--|\\.\\.)(?:o\\||\\|\\||o\\{|\\|\\{)\\s+" + ER_ENTITY
                    + "\\s*:\\s*(?:[A-Za-z_][A-Za-z0-9\\-_]*|\"[^\"]*\")");

    public static List<String> checkEr(String text) {
        dump("er", text);
        List<String> errors = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        boolean header = false;
        boolean inBlock = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isEmpty() || line.startsWith("%%")) {
                continue;
            }
            if (!header) {
                if (!line.equals("erDiagram")) {
                    errors.add((i + 1) + ": erDiagram으로 시작해야 한다: " + line);
                }
                header = true;
                continue;
            }
            if (inBlock) {
                if (line.equals("}")) {
                    inBlock = false;
                } else if (!ER_ATTRIBUTE.matcher(line).matches()) {
                    errors.add((i + 1) + ": 속성 줄: " + line);
                }
            } else if (ER_BLOCK_OPEN.matcher(line).matches()) {
                inBlock = true;
            } else if (!ER_RELATION.matcher(line).matches() && !ER_ENTITY_ONLY.matcher(line).matches()) {
                errors.add((i + 1) + ": 관계 또는 엔티티 줄: " + line);
            }
        }
        if (inBlock) {
            errors.add("닫히지 않은 엔티티 블록");
        }
        if (!header) {
            errors.add("erDiagram 머리줄이 없다");
        }
        return errors;
    }

    // ---- classDiagram ----
    private static final String CLASS_ID = "[\\p{L}\\p{N}_]+";
    private static final String CLASS_DECL = "class\\s+" + CLASS_ID + "(?:~[^~\\n{}\"]+~|\\[\"[^\"\\n]*\"\\])?";
    private static final Pattern CLASS_LINE = Pattern.compile(CLASS_DECL);
    private static final Pattern CLASS_OPEN = Pattern.compile(CLASS_DECL + "\\s*\\{");
    private static final Pattern NAMESPACE_OPEN = Pattern.compile("namespace\\s+[A-Za-z0-9_]+\\s*\\{");
    private static final Pattern CLASS_RELATION = Pattern.compile(
            CLASS_ID + "\\s+(?:\"[^\"]*\"\\s+)?(?:<\\|--|<\\|\\.\\.|-->|\\.\\.>|--|\\.\\.|\\*--|o--)\\s+(?:\"[^\"]*\"\\s+)?"
                    + CLASS_ID + "(?:\\s*:\\s*[^\\n{}\"]+)?");
    private static final Pattern CLASS_ANNOTATION = Pattern.compile("<<[^<>\\n]+>>");
    private static final Pattern CLASS_MEMBER = Pattern.compile("[^{}\\n]+");

    public static List<String> checkClass(String text) {
        dump("class", text);
        List<String> errors = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        boolean header = false;
        boolean inNamespace = false;
        boolean inClass = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isEmpty() || line.startsWith("%%")) {
                continue;
            }
            if (!header) {
                if (!line.equals("classDiagram")) {
                    errors.add((i + 1) + ": classDiagram으로 시작해야 한다: " + line);
                }
                header = true;
                continue;
            }
            if (inClass) {
                if (line.equals("}")) {
                    inClass = false;
                } else if (line.startsWith("<<") ? !CLASS_ANNOTATION.matcher(line).matches() : !CLASS_MEMBER.matcher(line).matches()) {
                    errors.add((i + 1) + ": 멤버 줄: " + line);
                }
            } else if (CLASS_OPEN.matcher(line).matches()) {
                inClass = true;
            } else if (CLASS_LINE.matcher(line).matches()) {
                // 본문 없는 클래스
            } else if (inNamespace) {
                if (line.equals("}")) {
                    inNamespace = false;
                } else {
                    errors.add((i + 1) + ": namespace 안에는 클래스 정의만 올 수 있다: " + line);
                }
            } else if (NAMESPACE_OPEN.matcher(line).matches()) {
                inNamespace = true;
            } else if (!CLASS_RELATION.matcher(line).matches()) {
                errors.add((i + 1) + ": 관계 줄: " + line);
            }
        }
        if (inClass || inNamespace) {
            errors.add("닫히지 않은 블록");
        }
        if (!header) {
            errors.add("classDiagram 머리줄이 없다");
        }
        return errors;
    }

    // ---- flowchart ----
    private static final String FLOW_ID = "[A-Za-z][A-Za-z0-9_]*";
    private static final Pattern FLOW_HEADER = Pattern.compile("flowchart\\s+(?:TD|TB|BT|LR|RL)");
    private static final Pattern FLOW_NODE = Pattern.compile(FLOW_ID + "(?:\\[\"[^\"\\n]*\"\\]|\\(\\[\"[^\"\\n]*\"\\]\\)|\\[\\(\"[^\"\\n]*\"\\)\\])");
    private static final Pattern FLOW_EDGE = Pattern.compile(
            FLOW_ID + "\\s+(?:-->|-\\.->)(?:\\|\"[^\"|\\n]*\"\\|)?\\s+" + FLOW_ID);

    public static List<String> checkFlowchart(String text) {
        dump("flow", text);
        List<String> errors = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        boolean header = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isEmpty() || line.startsWith("%%")) {
                continue;
            }
            if (!header) {
                if (!FLOW_HEADER.matcher(line).matches()) {
                    errors.add((i + 1) + ": flowchart 머리줄: " + line);
                }
                header = true;
                continue;
            }
            if (!FLOW_NODE.matcher(line).matches() && !FLOW_EDGE.matcher(line).matches()) {
                errors.add((i + 1) + ": 노드 또는 엣지 줄: " + line);
            }
            if (line.matches("(?:end|subgraph|graph|class|style)\\b.*")) {
                errors.add((i + 1) + ": 예약어로 시작하는 줄: " + line);
            }
        }
        if (!header) {
            errors.add("flowchart 머리줄이 없다");
        }
        return errors;
    }
}
