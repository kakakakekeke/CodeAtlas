package io.codeatlas.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 루트 {@code CHANGELOG.md}가 AGENTS.md의 "변경 기록" 형식을 지키는지 본다. 항목을 쓰는 사람과 에이전트가 달라도 같은 모양이 나오게
 * 형식을 테스트로 고정한다. 형식을 바꾸려면 AGENTS.md의 규칙과 이 검사를 함께 고친다.
 */
class ChangelogFormatTest {

    private static final List<String> SECTIONS = List.of("추가", "변경", "수정", "제거");
    private static final List<String> LABELS = List.of("세부", "영향", "조치");
    /** 요약 줄과 딸린 줄의 길이 한도(글자 수). 넘으면 딸린 줄로 나눈다. */
    private static final int SUMMARY_MAX = 120;
    private static final int DETAIL_MAX = 300;

    private static final Pattern RELEASE = Pattern.compile("## \\[(Unreleased|\\d+\\.\\d+\\.\\d+)](?: - (\\d{4}-\\d{2}-\\d{2}))?");
    private static final Pattern SECTION = Pattern.compile("### (\\S+)");
    private static final Pattern ENTRY = Pattern.compile("- (F-\\d{3}): (.+) \\((\\d{4}-\\d{2}-\\d{2})\\)");
    private static final Pattern DETAIL = Pattern.compile("  - ([^:\\s]+): (.+)");
    private static final Pattern FEATURE_ROW = Pattern.compile("(?m)^\\| (F-\\d{3}) \\|");

    /** 형식에 어긋난 곳을 "줄 번호: 까닭"으로 돌려준다. 어긋난 곳이 없으면 빈 목록이다. */
    static List<String> violations(String markdown, Set<String> featureIds) {
        List<String> found = new ArrayList<>();
        String[] lines = markdown.split("\\R", -1);
        String release = null;
        boolean releaseHasSection = false;
        int sectionRank = -1;
        boolean sectionHasEntry = true;
        LocalDate previousDate = null;
        boolean inEntry = false;
        int labelRank = -1;
        boolean unreleasedSeen = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String at = (i + 1) + "행: ";
            if (line.startsWith("## ")) {
                if (!sectionHasEntry) {
                    found.add(at + "앞 분류에 항목이 없다(빈 분류는 제목을 두지 않는다)");
                }
                Matcher m = RELEASE.matcher(line);
                if (!m.matches()) {
                    found.add(at + "릴리스 제목은 `## [Unreleased]`나 `## [x.y.z] - YYYY-MM-DD` 꼴이다");
                } else if (m.group(1).equals("Unreleased")) {
                    if (release != null) {
                        found.add(at + "`## [Unreleased]`는 첫 릴리스 제목이다");
                    }
                    unreleasedSeen = true;
                } else if (m.group(2) == null || parseDate(m.group(2)) == null) {
                    found.add(at + "릴리스 제목에 날짜(YYYY-MM-DD)가 없다");
                }
                release = line;
                releaseHasSection = false;
                sectionRank = -1;
                sectionHasEntry = true;
                inEntry = false;
                continue;
            }
            if (release == null) {
                continue; // 첫 릴리스 제목 앞의 머리말은 자유롭게 쓴다.
            }
            if (line.startsWith("### ")) {
                if (!sectionHasEntry) {
                    found.add(at + "앞 분류에 항목이 없다(빈 분류는 제목을 두지 않는다)");
                }
                Matcher m = SECTION.matcher(line);
                int rank = m.matches() ? SECTIONS.indexOf(m.group(1)) : -1;
                if (rank < 0) {
                    found.add(at + "분류는 " + String.join(", ", SECTIONS) + " 가운데 하나다");
                } else if (rank == sectionRank) {
                    found.add(at + "분류 `" + SECTIONS.get(rank) + "`가 한 릴리스에 두 번 나온다");
                } else if (rank < sectionRank) {
                    found.add(at + "분류의 순서는 " + String.join(" → ", SECTIONS) + "다");
                }
                sectionRank = Math.max(sectionRank, rank);
                releaseHasSection = true;
                sectionHasEntry = false;
                previousDate = null;
                inEntry = false;
                continue;
            }
            if (line.isEmpty()) {
                inEntry = false;
                continue;
            }
            if (!releaseHasSection) {
                found.add(at + "릴리스 제목 아래에는 분류 제목(`### 추가` 등)이 먼저 온다");
                continue;
            }
            if (line.startsWith("- ")) {
                Matcher m = ENTRY.matcher(line);
                if (!m.matches()) {
                    found.add(at + "항목의 첫 줄은 `- F-xxx: 요약. (YYYY-MM-DD)` 꼴이다");
                    inEntry = true;
                    labelRank = -1;
                    sectionHasEntry = true;
                    continue;
                }
                if (i > 0 && lines[i - 1].isEmpty() && sectionHasEntry) {
                    found.add(at + "같은 분류의 항목 사이에는 빈 줄을 두지 않는다");
                }
                if (!featureIds.contains(m.group(1))) {
                    found.add(at + "docs/features.md에 없는 기능 ID다: " + m.group(1));
                }
                checkSentence(m.group(2), SUMMARY_MAX, "요약", at, found);
                if (withoutCode(m.group(2)).matches(".*[.?!] .*")) {
                    found.add(at + "요약은 한 문장이다(나머지는 딸린 줄로 내린다)");
                }
                LocalDate date = parseDate(m.group(3));
                if (date == null) {
                    found.add(at + "없는 날짜다: " + m.group(3));
                } else {
                    if (previousDate != null && date.isAfter(previousDate)) {
                        found.add(at + "새 항목은 분류의 맨 위에 넣는다(날짜가 위 항목보다 늦다)");
                    }
                    previousDate = date;
                }
                inEntry = true;
                labelRank = -1;
                sectionHasEntry = true;
                continue;
            }
            Matcher m = DETAIL.matcher(line);
            if (!m.matches() || !inEntry) {
                found.add(at + "항목에 딸린 줄은 항목 바로 아래에 `  - 세부|영향|조치: 내용.` 꼴로 쓴다");
                continue;
            }
            int rank = LABELS.indexOf(m.group(1));
            if (rank < 0) {
                found.add(at + "딸린 줄의 머리말은 " + String.join(", ", LABELS) + " 가운데 하나다");
                continue;
            }
            if (rank < labelRank) {
                found.add(at + "딸린 줄의 순서는 " + String.join(" → ", LABELS) + "다");
            } else if (rank == labelRank && rank > 0) {
                found.add(at + "`" + LABELS.get(rank) + "`는 한 항목에 한 줄이다");
            }
            labelRank = Math.max(labelRank, rank);
            checkSentence(m.group(2), DETAIL_MAX, "딸린 줄", at, found);
        }
        if (!sectionHasEntry) {
            found.add((lines.length) + "행: 마지막 분류에 항목이 없다(빈 분류는 제목을 두지 않는다)");
        }
        if (!unreleasedSeen) {
            found.add("`## [Unreleased]`가 없다");
        }
        return found;
    }

    private static void checkSentence(String text, int max, String what, String at, List<String> found) {
        if (!text.endsWith(".")) {
            found.add(at + what + "은 마침표로 끝난다");
        }
        if (text.length() > max) {
            found.add(at + what + "이 " + max + "자를 넘는다(" + text.length() + "자). 딸린 줄로 나눈다");
        }
        if (!text.equals(text.strip()) || text.contains("  ")) {
            found.add(at + what + "에 겹친 공백이나 끝 공백이 있다");
        }
    }

    /** 백틱으로 감싼 코드와 괄호 안을 뺀 글자. 그 안의 마침표는 문장의 끝이 아니다. */
    private static String withoutCode(String text) {
        return text.replaceAll("`[^`]*`", "`").replaceAll("\\([^()]*\\)", "()");
    }

    private static LocalDate parseDate(String text) {
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static Set<String> featureIds() throws Exception {
        Set<String> ids = new TreeSet<>();
        Matcher m = FEATURE_ROW.matcher(Files.readString(Path.of("..", "docs", "features.md"), StandardCharsets.UTF_8));
        while (m.find()) {
            ids.add(m.group(1));
        }
        return ids;
    }

    @Test
    @DisplayName("CHANGELOG.md의 모든 항목이 AGENTS.md의 변경 기록 형식을 지킨다")
    void changelogFollowsFormat() throws Exception {
        Set<String> ids = featureIds();
        assertThat(ids).as("docs/features.md에서 읽은 기능 ID").contains("F-001", "F-016");
        String changelog = Files.readString(Path.of("..", "CHANGELOG.md"), StandardCharsets.UTF_8);

        assertThat(violations(changelog, ids)).as("CHANGELOG.md가 형식에 어긋난 곳(형식은 AGENTS.md의 \"변경 기록\")").isEmpty();
        assertThat(changelog).as("항목이 하나도 없는 파일을 통과시키지 않는다").containsPattern("(?m)^- F-\\d{3}: ");
    }

    @Test
    @DisplayName("AGENTS.md의 변경 기록 절에 적힌 예시가 형식 검사를 통과한다")
    void documentedExampleFollowsFormat() throws Exception {
        String agents = Files.readString(Path.of("..", "AGENTS.md"), StandardCharsets.UTF_8);
        int start = agents.indexOf("## 변경 기록");
        assertThat(start).as("AGENTS.md에 \"## 변경 기록\" 절이 있다").isGreaterThanOrEqualTo(0);
        Matcher block = Pattern.compile("(?s)```markdown\\R(.*?)```").matcher(agents.substring(start));
        assertThat(block.find()).as("변경 기록 절에 ```markdown 예시가 있다").isTrue();

        assertThat(violations(block.group(1), featureIds())).isEmpty();
        assertThat(block.group(1)).contains("- 세부: ", "- 영향: ", "- 조치: ");
    }

    @Test
    @DisplayName("형식 검사는 어긋난 항목을 잡는다: 분류, 첫 줄, 딸린 줄, 날짜, 기능 ID")
    void detectsViolations() {
        Set<String> ids = Set.of("F-001", "F-016");
        String ok = """
                # 변경 기록

                머리말은 자유롭게 쓴다.

                ## [Unreleased]

                ### 추가
                - F-016: 뷰어에 뒤로 가기 단추를 더했다. (2026-10-12)
                  - 세부: 상세 안의 링크로 옮겨 간 노드에서 돌아온다.
                  - 세부: 브라우저의 뒤로 가기(`history.back()`)로도 돌아온다.
                  - 영향: 주소의 `#` 뒤가 달라진다.
                  - 조치: `viewer.html`을 다시 만든다.
                - F-001: 옵션 `--out`을 받는다. (2026-10-11)

                ### 수정
                - F-001: 경로 구분자를 `/`로 저장한다. (2026-10-12)

                ## [0.1.0] - 2026-10-01

                ### 변경
                - F-001: 출력 폴더의 기본값을 바꿨다. (2026-09-30)
                """;
        assertThat(violations(ok, ids)).isEmpty();

        assertThat(violations(ok.replace("### 수정", "### 추가"), ids)).anyMatch(v -> v.contains("두 번"));
        assertThat(violations(ok.replace("### 추가", "### 수정").replaceFirst("### 수정\\n- F-001: 경로", "### 추가\n- F-001: 경로"), ids))
                .anyMatch(v -> v.contains("분류의 순서"));
        assertThat(violations(ok.replace("### 수정", "### 고침"), ids)).anyMatch(v -> v.contains("분류는"));
        assertThat(violations(ok.replace("- F-001: 경로 구분자를 `/`로 저장한다. (2026-10-12)\n", ""), ids)).anyMatch(v -> v.contains("항목이 없다"));
        assertThat(violations(ok.replace("- F-001: 옵션", "- 옵션"), ids)).anyMatch(v -> v.contains("첫 줄"));
        assertThat(violations(ok.replace("더했다. (2026-10-12)", "더했다. (10월 12일)"), ids)).anyMatch(v -> v.contains("첫 줄"));
        assertThat(violations(ok.replace("더했다. (2026-10-12)", "더했다. (2026-13-40)"), ids)).anyMatch(v -> v.contains("없는 날짜"));
        assertThat(violations(ok.replace("더했다. (2026-10-12)", "더했다 (2026-10-12)"), ids)).anyMatch(v -> v.contains("마침표"));
        assertThat(violations(ok.replace("더했다. (2026-10-12)", "더했다. 서랍에도 있다. (2026-10-12)"), ids)).anyMatch(v -> v.contains("한 문장"));
        assertThat(violations(ok.replace("더했다. (2026-10-12)", "더했" + "다".repeat(130) + ". (2026-10-12)"), ids)).anyMatch(v -> v.contains("넘는다"));
        assertThat(violations(ok.replace("(2026-10-11)", "(2026-10-13)"), ids)).anyMatch(v -> v.contains("맨 위"));
        assertThat(violations(ok.replace("- F-001: 옵션", "- F-999: 옵션"), ids)).anyMatch(v -> v.contains("F-999"));
        assertThat(violations(ok.replace("  - 영향:", "  - 참고:"), ids)).anyMatch(v -> v.contains("머리말"));
        assertThat(violations(ok.replace("  - 영향: 주소의 `#` 뒤가 달라진다.\n  - 조치: `viewer.html`을 다시 만든다.",
                "  - 조치: `viewer.html`을 다시 만든다.\n  - 영향: 주소의 `#` 뒤가 달라진다."), ids)).anyMatch(v -> v.contains("딸린 줄의 순서"));
        assertThat(violations(ok.replace("  - 조치: `viewer.html`을 다시 만든다.", "  - 조치: 다시 만든다.\n  - 조치: 또 만든다."), ids))
                .anyMatch(v -> v.contains("한 줄"));
        assertThat(violations(ok.replace("  - 조치: `viewer.html`을 다시 만든다.", "  이미 만든 파일은 다시 만든다."), ids)).anyMatch(v -> v.contains("딸린 줄은"));
        assertThat(violations(ok.replace("- F-001: 옵션", "\n- F-001: 옵션"), ids)).anyMatch(v -> v.contains("빈 줄"));
        assertThat(violations(ok.replace("## [0.1.0] - 2026-10-01", "## 0.1.0"), ids)).anyMatch(v -> v.contains("릴리스 제목"));
        assertThat(violations(ok.replace("## [Unreleased]", "## [0.2.0] - 2026-10-12"), ids)).anyMatch(v -> v.contains("[Unreleased]"));
        assertThat(violations(ok.replace("### 추가\n", ""), ids)).anyMatch(v -> v.contains("분류 제목"));
    }
}
