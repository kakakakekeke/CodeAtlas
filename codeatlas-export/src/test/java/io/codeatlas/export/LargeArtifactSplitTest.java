package io.codeatlas.export;

import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.SourceLocation;
import io.codeatlas.export.fixture.GraphFixture;
import io.codeatlas.export.fixture.MermaidSyntax;
import io.codeatlas.export.fixture.ShopGraph;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 큰 프로젝트에서 표준 산출물이 쓸 수 있는 크기로 나뉘는지 본다(Mermaid 글자 수 한도, 엔드포인트 콜트리 파일). */
class LargeArtifactSplitTest {

    @TempDir
    Path dir;

    private Map<String, String> export(GraphModel graph, String name, Map<String, String> options) throws IOException {
        Path out = Files.createDirectories(dir.resolve(name));
        new StandardExporter().export(graph, out, options);
        Map<String, String> result = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(out)) {
            for (Path p : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                result.put(out.relativize(p).toString().replace('\\', '/'), Files.readString(p, StandardCharsets.UTF_8));
            }
        }
        return result;
    }

    /** 영역 넷(SYS_ 12개, CMS_ 12개, ACT_RU_ 6개·ACT_HI_ 6개)과 접두사가 제각각인 테이블 둘. 영역 사이에 FK가 몇 개 있다. */
    private static GraphFixture schema() {
        GraphFixture f = GraphFixture.create();
        for (String prefix : List.of("SYS", "CMS", "ACT_RU", "ACT_HI")) {
            int tables = prefix.startsWith("ACT") ? 6 : 12;
            for (int t = 0; t < tables; t++) {
                String table = prefix + "_T" + t;
                f.table(table, prefix + " 영역의 테이블 " + t);
                f.column(table, "ID", "NUMBER(19)", 1, "pk", "notnull");
                f.column(table, "PARENT_ID", "NUMBER(19)", 2, "nullable");
                for (int c = 0; c < 12; c++) {
                    f.column(table, "ATTRIBUTE_COLUMN_" + c, "VARCHAR2(200)", 3 + c, "nullable", "comment=" + prefix + " 속성 " + c);
                }
                if (t > 0) {
                    f.fk(table, prefix + "_T0", "FK_" + table, List.of("PARENT_ID"), List.of("ID"));
                }
            }
        }
        f.table("LONELY");
        f.column("LONELY", "ID", "NUMBER", 1, "pk");
        f.table("ODD_ONE");
        f.column("ODD_ONE", "ID", "NUMBER", 1, "pk");
        f.column("ODD_ONE", "USER_ID", "NUMBER", 2);
        // 영역 사이의 관계
        f.fk("CMS_T1", "SYS_T0", "FK_CMS_SYS", List.of("PARENT_ID"), List.of("ID"));
        f.fk("CMS_T2", "SYS_T3", "FK_CMS_SYS2", List.of("PARENT_ID"), List.of("ID"));
        f.fk("ACT_RU_T1", "ACT_HI_T0", "FK_RU_HI", List.of("PARENT_ID"), List.of("ID"));
        f.fk("ODD_ONE", "SYS_T0", "FK_ODD_SYS", List.of("USER_ID"), List.of("ID"));
        return f;
    }

    @Test
    @DisplayName("F-013-AC18 erd.mmd가 Mermaid 글자 수 한도를 넘으면 주제 영역(테이블 이름 접두사)별 erd-<영역>.mmd, 키만 남긴 erd-overview.mmd, erd/index.md로 나눈다")
    void splitsErdBySubjectArea() throws Exception {
        GraphModel g = schema().graph();

        Map<String, String> files = export(g, "erd", Map.of("export.mermaid.maxChars", "12000"));

        String full = files.get("erd/erd.mmd");
        assertThat(full.length()).isGreaterThan(12000);
        assertThat(full).startsWith("erDiagram\n    %% 주의: 이 다이어그램은 ").contains("erd/index.md");
        assertThat(files.keySet().stream().filter(k -> k.startsWith("erd/"))).containsExactly(
                "erd/erd-ACT.mmd", "erd/erd-CMS.mmd", "erd/erd-SYS.mmd", "erd/erd-etc.mmd", "erd/erd-overview.mmd",
                "erd/erd.dbml", "erd/erd.mmd", "erd/erd.puml", "erd/index.md", "erd/tables.md");
        for (Map.Entry<String, String> e : files.entrySet()) {
            if (e.getKey().startsWith("erd/erd-")) {
                assertThat(e.getValue().length()).as(e.getKey()).isLessThanOrEqualTo(12000);
                assertThat(MermaidSyntax.checkEr(e.getValue())).as(e.getKey()).isEmpty();
            }
        }
        // 영역 다이어그램: 영역 안 테이블은 컬럼까지, 관계로 이어진 다른 영역의 테이블은 이름만
        String cms = files.get("erd/erd-CMS.mmd");
        assertThat(cms).contains("    CMS_T1 {\n").contains("VARCHAR2(200) ATTRIBUTE_COLUMN_0").contains("\n    SYS_T0\n").contains("\n    SYS_T3\n")
                .contains("SYS_T0 |o--o{ CMS_T1 : \"FK_CMS_SYS\"").doesNotContain("SYS_T0 {").doesNotContain("SYS_T1").doesNotContain("ACT_");
        // 작은 묶음(접두사가 같은 테이블이 둘 이하)은 기타로 합친다
        assertThat(files.get("erd/erd-etc.mmd")).contains("    LONELY {\n").contains("    ODD_ONE {\n").contains("\n    SYS_T0\n");
        // ACT_RU, ACT_HI는 첫 토큰이 같아 한 영역이다(한도 안에 들어간다)
        assertThat(files.get("erd/erd-ACT.mmd")).contains("ACT_RU_T1 {").contains("ACT_HI_T0 {").contains("\"FK_RU_HI\"");
        // 개요: 전체 테이블, 컬럼은 키만
        String overview = files.get("erd/erd-overview.mmd");
        assertThat(overview).contains("SYS_T0 {").contains("NUMBER(19) ID PK").contains("CMS_T11").contains("LONELY").doesNotContain("ATTRIBUTE_COLUMN_");
        // 색인: 영역 목록과 영역 간 관계 수
        String index = files.get("erd/index.md");
        assertThat(index).startsWith("# ERD\n").contains("export.mermaid.maxChars")
                .contains("| ACT | 12 | 11 | 0 | [erd-ACT.mmd](erd-ACT.mmd) |")
                .contains("| CMS | 12 | 11 | 2 | [erd-CMS.mmd](erd-CMS.mmd) |")
                .contains("| SYS | 12 | 11 | 3 | [erd-SYS.mmd](erd-SYS.mmd) |")
                .contains("| 기타 | 2 | 0 | 1 | [erd-etc.mmd](erd-etc.mmd) |")
                .contains("[erd-overview.mmd](erd-overview.mmd)")
                .contains("| CMS | SYS | 2 |").contains("| SYS | 기타 | 1 |").doesNotContain("| ACT | SYS |");
        // PlantUML과 DBML은 나누지 않는다
        assertThat(files.get("erd/erd.puml")).contains("CMS_T11").contains("ACT_HI_T5");

        // 영역 하나가 한도를 넘으면 둘째 토큰까지로 나누고(ACT_HI, ACT_RU), 그래도 넘으면 이름순으로 자른다(SYS-1, SYS-2 …)
        Map<String, String> tight = export(g, "erd-tight", Map.of("export.mermaid.maxChars", "5400"));
        assertThat(tight).containsKeys("erd/erd-ACT_HI.mmd", "erd/erd-ACT_RU.mmd", "erd/erd-SYS-1.mmd", "erd/erd-SYS-2.mmd")
                .doesNotContainKey("erd/erd-ACT.mmd").doesNotContainKey("erd/erd-SYS.mmd");
        tight.forEach((name, text) -> {
            if (name.startsWith("erd/erd-") && !name.equals("erd/erd-overview.mmd")) {
                assertThat(text.length()).as(name).isLessThanOrEqualTo(5400);
            }
        });
        // 개요가 키만으로도 넘으면 컬럼 없이 그린다
        assertThat(tight.get("erd/erd-overview.mmd")).doesNotContain("NUMBER(19) ID PK").contains("\n    SYS_T0\n");

        // 한도 안이면 나누지 않는다(기본 45000). 0이면 한도를 보지 않는다
        assertThat(export(g, "erd-default", Map.of()).keySet().stream().filter(k -> k.startsWith("erd/")))
                .containsExactly("erd/erd.dbml", "erd/erd.mmd", "erd/erd.puml", "erd/tables.md");
        assertThat(export(g, "erd-nolimit", Map.of("export.mermaid.maxChars", "0")).get("erd/erd.mmd")).doesNotContain("주의");
        assertThat(export(new ShopGraph().f.graph(), "erd-small", Map.of())).doesNotContainKey("erd/index.md");
        // 입력 순서가 달라도 같다
        assertThat(export(schema().reversedCopy(), "erd-reversed", Map.of("export.mermaid.maxChars", "12000")))
                .isEqualTo(files);
    }

    /**
     * 밑줄 없는 이름의 테이블 40개(전자정부 공통컴포넌트의 COMTN… 꼴): COMTNBBS와 COMTNUSER를 중심으로 한 별 모양 둘(잎 5개씩,
     * COMTNBBS → COMTNUSER로 이어짐), 잎 9개가 참조하는 공통코드 COMTCCODE, 관계가 없는 테이블 27개.
     */
    private static GraphFixture plainNames() {
        GraphFixture f = GraphFixture.create();
        Map<String, List<String>> stars = new TreeMap<>();
        stars.put("COMTNBBS", List.of("COMTNBBSUSE", "COMTNCOMMENT", "COMTNSCRAP", "COMTNSTSFDG", "COMTNTMPLAT"));
        stars.put("COMTNUSER", List.of("COMTNAUTHOR", "COMTNGROUP", "COMTNLOGIN", "COMTNPASSWD", "COMTNROLE"));
        f.table("COMTCCODE", "공통코드");
        f.column("COMTCCODE", "CODE", "VARCHAR2(10)", 1, "pk", "notnull");
        stars.forEach((center, leaves) -> {
            List<String> all = new java.util.ArrayList<>(leaves);
            all.add(center);
            for (String table : all) {
                f.table(table);
                f.column(table, "ID", "NUMBER(19)", 1, "pk", "notnull");
                f.column(table, "PARENT_ID", "NUMBER(19)", 2, "nullable");
                f.column(table, "CODE", "VARCHAR2(10)", 3, "nullable");
                for (int c = 0; c < 8; c++) {
                    f.column(table, "ATTRIBUTE_COLUMN_" + c, "VARCHAR2(200)", 4 + c, "nullable");
                }
            }
            leaves.forEach(leaf -> f.fk(leaf, center, "FK_" + leaf, List.of("PARENT_ID"), List.of("ID")));
        });
        f.fk("COMTNBBS", "COMTNUSER", "FK_BBS_USER", List.of("PARENT_ID"), List.of("ID"));
        for (String leaf : List.of("COMTNBBSUSE", "COMTNCOMMENT", "COMTNSCRAP", "COMTNSTSFDG",
                "COMTNAUTHOR", "COMTNGROUP", "COMTNLOGIN", "COMTNPASSWD", "COMTNROLE")) {
            f.fk(leaf, "COMTCCODE", "FK_CODE_" + leaf, List.of("CODE"), List.of("CODE"));
        }
        for (int i = 0; i < 27; i++) {
            String table = "COMTHLOG" + (char) ('A' + i % 26) + (i / 26);
            f.table(table);
            f.column(table, "ID", "NUMBER(19)", 1, "pk");
        }
        return f;
    }

    @Test
    @DisplayName("F-013-AC20 접두사로 묶이지 않는 테이블(밑줄 없는 이름)은 관계로 이어진 테이블끼리 영역으로 묶고 영역 이름은 그 안에서 가장 많이 참조되는 테이블이다. 전체의 20% 넘는 테이블이 참조하는 허브는 영역에 넣지 않고 따로 적는다")
    void splitsErdByRelationClusters() throws Exception {
        GraphModel g = plainNames().graph();

        // 별 둘이 이어진 요소(12개)가 한도를 넘는다: 가장 많이 참조되는 테이블을 중심으로 나눈다
        Map<String, String> files = export(g, "erd-plain", Map.of("export.mermaid.maxChars", "5500"));

        // 관계가 없는 27개는 이름의 공통 접두사(COMTHLOG)로 묶인다(F-013-AC25): 기타는 없다
        assertThat(files.keySet().stream().filter(k -> k.startsWith("erd/erd-"))).containsExactly(
                "erd/erd-COMTHLOG.mmd", "erd/erd-COMTNBBS.mmd", "erd/erd-COMTNUSER.mmd", "erd/erd-overview.mmd");
        files.forEach((name, text) -> {
            if (name.startsWith("erd/erd-") && !name.equals("erd/erd-overview.mmd")) {
                assertThat(text.length()).as(name).isLessThanOrEqualTo(5500);
                assertThat(MermaidSyntax.checkEr(text)).as(name).isEmpty();
            }
        });
        String bbs = files.get("erd/erd-COMTNBBS.mmd");
        // 허브와 다른 영역의 테이블은 이름만 있는 외곽 엔티티다
        assertThat(bbs).contains("    COMTNBBS {\n").contains("    COMTNSCRAP {\n").contains("\n    COMTCCODE\n").contains("\n    COMTNUSER\n")
                .doesNotContain("COMTCCODE {").doesNotContain("COMTNUSER {").doesNotContain("COMTNROLE").doesNotContain("COMTHLOG");
        assertThat(files.get("erd/erd-COMTNUSER.mmd")).contains("    COMTNUSER {\n").contains("    COMTNROLE {\n").contains("\n    COMTCCODE\n")
                .contains("\n    COMTNBBS\n");
        assertThat(files.get("erd/erd-COMTHLOG.mmd")).contains("    COMTHLOGA0 {\n").doesNotContain("COMTCCODE");
        String index = files.get("erd/index.md");
        // 영역 표: 영역 | 테이블 수 | 영역 안 관계 | 다른 영역과의 관계 | 허브와의 관계 | 파일 (허브와의 관계는 따로 센다, F-013-AC24)
        assertThat(index).contains("관계로 이어진 테이블끼리")
                .contains("| COMTNBBS | 6 | 5 | 1 | 4 | [erd-COMTNBBS.mmd](erd-COMTNBBS.mmd) |")
                .contains("| COMTNUSER | 6 | 5 | 1 | 5 | [erd-COMTNUSER.mmd](erd-COMTNUSER.mmd) |")
                .contains("| COMTHLOG* | 27 | 0 | 0 | 0 | [erd-COMTHLOG.mmd](erd-COMTHLOG.mmd) |")
                .contains("| COMTNBBS | COMTNUSER | 1 |")
                .contains("## 허브 테이블\n").contains("| COMTCCODE | 9 |")
                .doesNotContain("ALL-").doesNotContain("| 기타 |");

        // 요소 전체가 한도 안이면 나누지 않는다: 영역 이름은 가장 많이 참조되는 COMTNUSER(6개가 참조)
        Map<String, String> loose = export(g, "erd-plain-loose", Map.of("export.mermaid.maxChars", "7000"));
        assertThat(loose.keySet().stream().filter(k -> k.startsWith("erd/erd-"))).containsExactly(
                "erd/erd-COMTHLOG.mmd", "erd/erd-COMTNUSER.mmd", "erd/erd-overview.mmd");
        assertThat(loose.get("erd/index.md")).contains("| COMTNUSER | 12 | 11 | 0 | 9 | [erd-COMTNUSER.mmd](erd-COMTNUSER.mmd) |")
                .doesNotContain("## 중심 테이블");

        // 입력 순서가 달라도 같다
        assertThat(export(plainNames().reversedCopy(), "erd-plain-reversed", Map.of("export.mermaid.maxChars", "5500"))).isEqualTo(files);
    }

    @Test
    @DisplayName("F-013-AC20 접두사 영역이 있어도, 접두사로 묶이지 않은 나머지 테이블은 관계로 묶는다(관계가 없으면 전처럼 기타)")
    void clustersLeftoverTablesBesidePrefixAreas() throws Exception {
        GraphFixture f = plainNames();
        for (int t = 0; t < 4; t++) {
            f.table("SYS_T" + t);
            f.column("SYS_T" + t, "ID", "NUMBER(19)", 1, "pk");
        }
        Map<String, String> files = export(f.graph(), "erd-mixed", Map.of("export.mermaid.maxChars", "5500"));

        assertThat(files.keySet().stream().filter(k -> k.startsWith("erd/erd-"))).containsExactly(
                "erd/erd-COMTHLOG.mmd", "erd/erd-COMTNBBS.mmd", "erd/erd-COMTNUSER.mmd", "erd/erd-SYS.mmd", "erd/erd-overview.mmd");
        assertThat(files.get("erd/index.md")).contains("| SYS | 4 | 0 | 0 | 0 |").contains("| COMTHLOG* | 27 | 0 | 0 | 0 |");
    }

    // ---------------------------------------------------------------- 5차 수정: 허브와의 관계, 이름 접두 군집, 중심 테이블, 논리명

    private static List<String> rows(String markdown, String section) {
        int start = markdown.indexOf("## " + section + "\n");
        if (start < 0) {
            return List.of();
        }
        int end = markdown.indexOf("\n## ", start + 1);
        return (end < 0 ? markdown.substring(start) : markdown.substring(start, end)).lines()
                .filter(line -> line.startsWith("| ") && !line.startsWith("|---")).skip(1).toList();
    }

    @Test
    @DisplayName("F-013-AC24 erd/index.md의 영역 표는 허브와의 관계를 다른 영역과의 관계와 따로 센다: 다른 영역과의 관계 수의 합이 영역 간 관계 수의 두 배다")
    void countsHubRelationsApartFromOtherAreas() throws Exception {
        Map<String, String> files = export(plainNames().graph(), "erd-hub", Map.of("export.mermaid.maxChars", "5500"));
        String index = files.get("erd/index.md");

        assertThat(index).contains("| 영역 | 테이블 수 | 영역 안 관계 | 다른 영역과의 관계 | 허브와의 관계 | 파일 | 글자 수 |\n|---|---|---|---|---|---|---|\n");
        int otherAreas = 0;
        int hub = 0;
        for (String row : rows(index, "영역")) {
            String[] cells = row.split(" \\| ");
            otherAreas += Integer.parseInt(cells[3]);
            hub += Integer.parseInt(cells[4]);
        }
        int cross = 0;
        for (String row : rows(index, "영역 간 관계")) {
            cross += Integer.parseInt(row.split(" \\| ")[2].replace(" |", "").strip());
        }
        // 전에는 "다른 영역과의 관계"가 5·6인데 "영역 간 관계"는 1건뿐이었다(나머지는 허브와의 관계)
        assertThat(cross).isEqualTo(1);
        assertThat(otherAreas).isEqualTo(2 * cross);
        assertThat(hub).isEqualTo(9);
        assertThat(index).contains("허브와의 관계는 \"다른 영역과의 관계\"에 넣지 않고 따로 센다");

        // 허브가 없으면(접두사 영역뿐) 표의 모양은 전과 같다
        String prefixOnly = export(schema().graph(), "erd-nohub", Map.of("export.mermaid.maxChars", "12000")).get("erd/index.md");
        assertThat(prefixOnly).contains("| 영역 | 테이블 수 | 영역 안 관계 | 다른 영역과의 관계 | 파일 | 글자 수 |\n|---|---|---|---|---|---|\n")
                .doesNotContain("허브와의 관계").doesNotContain("## 중심 테이블").doesNotContain("이름의 공통 접두사");
        assertThat(ErdSplitterProbe.split(plainNames().graph(), 5500).areas()).extracting(a -> a.name() + " " + a.outerRelations() + " " + a.hubRelations())
                .containsExactly("COMTHLOG* 0 0", "COMTNBBS 1 4", "COMTNUSER 1 5");
    }

    /** 관계가 없는 테이블 15개(밑줄 없는 이름). 컬럼이 많아 전체 ERD가 한도(3000자)를 넘는다. */
    private static GraphFixture unrelatedNames() {
        GraphFixture f = GraphFixture.create();
        for (String table : List.of("COMTNBBSA", "COMTNBBSB", "COMTNBBSC", "COMTNSMS", "COMTNSMSLOG", "COMTNSMSRECPTN", "COMTNX", "COMTHLOG",
                "ROLES", "USERS", "COMTSA", "COMTSB", "COMTSC1", "COMTSC2", "COMTSC3")) {
            f.table(table);
            f.column(table, "ID", "NUMBER(19)", 1, "pk", "notnull");
            for (int c = 0; c < 6; c++) {
                f.column(table, "ATTRIBUTE_COLUMN_" + c, "VARCHAR2(200)", 2 + c, "nullable");
            }
        }
        return f;
    }

    @Test
    @DisplayName("F-013-AC25 관계로 묶이지 않고 남은 테이블은 이름의 공통 접두사로 묶는다(가장 긴 접두사부터, 3개 이상·접두사 3자 이상). 묶이지 않는 나머지만 기타다")
    void clustersLeftoverTablesByNamePrefix() throws Exception {
        Map<String, String> files = export(unrelatedNames().graph(), "erd-names", Map.of("export.mermaid.maxChars", "3000"));

        // COMTSC*가 먼저 묶이고, COMTS의 나머지(A, B)는 3개가 안 돼 위로 올라가 COMT의 나머지(COMTHLOG, COMTNX)와 함께 COMT*가 된다
        assertThat(files.keySet().stream().filter(k -> k.startsWith("erd/erd-"))).containsExactly(
                "erd/erd-COMT.mmd", "erd/erd-COMTNBBS.mmd", "erd/erd-COMTNSMS.mmd", "erd/erd-COMTSC.mmd", "erd/erd-etc.mmd", "erd/erd-overview.mmd");
        String index = files.get("erd/index.md");
        assertThat(rows(index, "영역")).extracting(row -> row.substring(0, row.indexOf(" | [")))
                .containsExactly("| COMT* | 4 | 0 | 0", "| COMTNBBS* | 3 | 0 | 0", "| COMTNSMS* | 3 | 0 | 0", "| COMTSC* | 3 | 0 | 0", "| 기타 | 2 | 0 | 0");
        assertThat(index).contains("이름의 공통 접두사").doesNotContain("관계로 이어진 테이블끼리");
        assertThat(files.get("erd/erd-COMT.mmd")).contains("    COMTHLOG {\n").contains("    COMTNX {\n").contains("    COMTSA {\n").contains("    COMTSB {\n")
                .doesNotContain("COMTSC1").doesNotContain("COMTNSMS");
        assertThat(files.get("erd/erd-etc.mmd")).contains("    ROLES {\n").contains("    USERS {\n").doesNotContain("COMT");
        // 입력 순서가 달라도 같다
        assertThat(export(unrelatedNames().reversedCopy(), "erd-names-reversed", Map.of("export.mermaid.maxChars", "3000"))).isEqualTo(files);

        // 접두사가 3자에 못 미치면 묶지 않는다. 접두사로도 관계로도 이름으로도 묶이는 것이 없으면 전처럼 ALL이다
        GraphFixture shortNames = GraphFixture.create();
        List.of("ABX", "ABY", "ABZ", "Q").forEach(shortNames::table);
        assertThat(ErdSplitterProbe.split(shortNames.graph(), 100_000).areas()).extracting(a -> a.name()).containsExactly("ALL");
        // 이름 접두 군집도 30개를 넘지 않는다: 넘으면 이름순 조각으로 자른다
        GraphFixture many = GraphFixture.create();
        for (int i = 0; i < 35; i++) {
            many.table("COMTNLOG" + (char) ('A' + i % 26) + (i < 26 ? "" : "X"));
        }
        many.table("USERS");
        assertThat(ErdSplitterProbe.split(many.graph(), 100_000).areas()).extracting(a -> a.name() + " " + a.fileName() + " " + a.tables())
                .containsExactly("COMTNLOG*-1 COMTNLOG-1 18", "COMTNLOG*-2 COMTNLOG-2 17", "기타 etc 1");
    }

    /**
     * COMTNUSER를 6개 테이블이 참조한다(33개 가운데 6개: 허브는 아니다). COMTNUSER를 빼면 사슬 셋(COMTNBBS 12개, COMTNFILE 12개,
     * COMTNMENU 4개)과 작은 조각(COMTNLOGIN, COMTNPASSWD, COMTNNOTE·COMTNNOTERECPTN)으로 갈린다.
     */
    private static GraphFixture centered() {
        GraphFixture f = GraphFixture.create();
        Map<String, String> comments = Map.of("COMTNUSER", "사용자", "COMTNBBS", "게시판\n|마스터", "COMTNFILE01", "첨부 파일 상세");
        List<String[]> links = new java.util.ArrayList<>(List.of(new String[] {"COMTNLOGIN", "COMTNUSER"}, new String[] {"COMTNPASSWD", "COMTNUSER"},
                new String[] {"COMTNNOTE", "COMTNUSER"}, new String[] {"COMTNNOTERECPTN", "COMTNNOTE"}));
        List<String> names = new java.util.ArrayList<>(List.of("COMTNUSER", "COMTNLOGIN", "COMTNPASSWD", "COMTNNOTE", "COMTNNOTERECPTN"));
        for (String[] chain : new String[][] {{"COMTNBBS", "11"}, {"COMTNFILE", "11"}, {"COMTNMENU", "3"}}) {
            names.add(chain[0]);
            String previous = chain[0];
            for (int i = 1; i <= Integer.parseInt(chain[1]); i++) {
                String name = chain[0] + String.format("%02d", i);
                names.add(name);
                links.add(new String[] {name, previous});
                previous = name;
            }
        }
        links.add(new String[] {"COMTNBBS", "COMTNUSER"});
        links.add(new String[] {"COMTNFILE", "COMTNUSER"});
        links.add(new String[] {"COMTNMENU03", "COMTNUSER"});
        for (String name : names) {
            f.table(name, comments.get(name));
            f.column(name, "ID", "NUMBER(19)", 1, "pk", "notnull");
            f.column(name, "PARENT_ID", "NUMBER(19)", 2, "nullable");
        }
        links.forEach(link -> f.fk(link[0], link[1], "FK_" + link[0], List.of("PARENT_ID"), List.of("ID")));
        // 접두사 영역 하나(컬럼이 많다): 전체 ERD가 한도를 넘게 한다
        for (int t = 0; t < 12; t++) {
            f.table("SYS_T" + t);
            f.column("SYS_T" + t, "ID", "NUMBER(19)", 1, "pk", "notnull");
            for (int c = 0; c < 14; c++) {
                f.column("SYS_T" + t, "ATTRIBUTE_COLUMN_" + c, "VARCHAR2(200)", 2 + c, "nullable");
            }
        }
        return f;
    }

    @Test
    @DisplayName("F-013-AC26 관계로 묶은 영역이 테이블 30개를 넘으면 가장 많이 참조되는 테이블(중심)을 빼고 나머지를 다시 묶는다. 중심은 자기 영역에 있고 다른 영역에는 이름만 나온다")
    void splitsLargeRelationAreaAroundCenterTable() throws Exception {
        GraphModel g = centered().graph();
        Map<String, String> files = export(g, "erd-center", Map.of("export.mermaid.maxChars", "9000"));

        // 33개가 한 묶음이면 글자 수 한도 안이지만 테이블 30개를 넘는다
        assertThat(files.keySet().stream().filter(k -> k.startsWith("erd/erd-"))).containsExactly("erd/erd-COMTNBBS.mmd", "erd/erd-COMTNFILE.mmd",
                "erd/erd-COMTNMENU.mmd", "erd/erd-COMTNUSER.mmd", "erd/erd-SYS.mmd", "erd/erd-overview.mmd");
        ErdSplitterProbe.Result split = ErdSplitterProbe.split(g, 9000);
        assertThat(split.areas()).extracting(a -> a.name() + " " + a.tables() + " " + a.innerRelations() + " " + a.outerRelations())
                .containsExactly("COMTNBBS 12 11 1", "COMTNFILE 12 11 1", "COMTNMENU 4 3 1", "COMTNUSER 5 4 3", "SYS 12 0 0");
        assertThat(split.areas()).allMatch(a -> a.name().equals("SYS") || a.tables() <= 30);
        assertThat(split.centers()).extracting(c -> c.name() + " " + c.referencedBy() + " " + c.areas())
                .containsExactly("COMTNUSER 6 [COMTNBBS, COMTNFILE, COMTNMENU]");
        // 중심의 영역: 중심 + 중심을 빼면 3개 미만으로 남는 조각. 중심을 참조하는 2차 영역에는 중심이 이름만 나온다
        assertThat(files.get("erd/erd-COMTNUSER.mmd")).contains("    COMTNUSER {\n").contains("    COMTNLOGIN {\n").contains("    COMTNNOTE {\n")
                .contains("    COMTNNOTERECPTN {\n").contains("    COMTNPASSWD {\n").contains("\n    COMTNBBS\n").contains("\n    COMTNMENU03\n")
                .doesNotContain("COMTNBBS01");
        assertThat(files.get("erd/erd-COMTNBBS.mmd")).contains("    COMTNBBS {\n").contains("    COMTNBBS11 {\n").contains("\n    COMTNUSER\n")
                .doesNotContain("COMTNUSER {").doesNotContain("COMTNFILE");
        String index = files.get("erd/index.md");
        assertThat(index).contains("| COMTNMENU | 4 | 3 | 1 | [erd-COMTNMENU.mmd](erd-COMTNMENU.mmd) |")
                .contains("## 중심 테이블\n").contains("| COMTNUSER | 6 | 게시판 \\|마스터(COMTNBBS), COMTNFILE, COMTNMENU |")
                .contains("테이블 30개");
        assertThat(rows(index, "영역 간 관계")).containsExactly("| 게시판 \\|마스터(COMTNBBS) | 사용자(COMTNUSER) | 1 |",
                "| COMTNFILE | 사용자(COMTNUSER) | 1 |", "| COMTNMENU | 사용자(COMTNUSER) | 1 |");
        assertThat(export(centered().reversedCopy(), "erd-center-reversed", Map.of("export.mermaid.maxChars", "9000"))).isEqualTo(files);

        // 중심을 빼도 갈리지 않는 묶음(고리 40개)은 중심 여럿에 가까운 테이블끼리 나눠 30개 상한을 지킨다
        GraphFixture ring = GraphFixture.create();
        for (int i = 0; i < 40; i++) {
            ring.table(String.format("RING%02d", i));
            ring.column(String.format("RING%02d", i), "ID", "NUMBER", 1, "pk");
        }
        for (int i = 0; i < 40; i++) {
            ring.fk(String.format("RING%02d", i), String.format("RING%02d", (i + 1) % 40), "FK_RING" + i, List.of("ID"), List.of("ID"));
        }
        ErdSplitterProbe.Result ringSplit = ErdSplitterProbe.split(ring.graph(), 100_000);
        assertThat(ringSplit.areas().size()).isGreaterThanOrEqualTo(2);
        assertThat(ringSplit.areas()).allMatch(a -> a.tables() <= 30);
        assertThat(ringSplit.areas().stream().mapToInt(a -> a.tables()).sum()).isEqualTo(40);
        assertThat(ringSplit.centers()).isEmpty();

        // 긴 사슬(70개)도 서로 먼 테이블들을 중심으로 고르게 나눈다(이웃한 테이블들이 중심이 돼 낱개로 흩어지지 않는다)
        GraphFixture chain = GraphFixture.create();
        for (int i = 0; i < 70; i++) {
            chain.table(String.format("CHAIN%02d", i));
            chain.column(String.format("CHAIN%02d", i), "ID", "NUMBER", 1, "pk");
        }
        for (int i = 1; i < 70; i++) {
            chain.fk(String.format("CHAIN%02d", i), String.format("CHAIN%02d", i - 1), "FK_CHAIN" + i, List.of("ID"), List.of("ID"));
        }
        assertThat(ErdSplitterProbe.split(chain.graph(), 100_000).areas()).extracting(a -> a.name() + " " + a.tables())
                .containsExactly("CHAIN00 9", "CHAIN09 17", "CHAIN26 26", "CHAIN52 18");
    }

    @Test
    @DisplayName("F-013-AC27 영역 이름이 된 테이블에 논리명(comment)이 있으면 영역 이름은 논리명(물리명)이다. 파일 이름은 물리명 그대로다")
    void labelsRelationAreasWithLogicalName() throws Exception {
        Map<String, String> files = export(centered().graph(), "erd-label", Map.of("export.mermaid.maxChars", "9000"));

        assertThat(rows(files.get("erd/index.md"), "영역")).extracting(row -> row.substring(0, row.indexOf(".mmd]")))
                .containsExactly("| 게시판 \\|마스터(COMTNBBS) | 12 | 11 | 1 | [erd-COMTNBBS", "| COMTNFILE | 12 | 11 | 1 | [erd-COMTNFILE",
                        "| COMTNMENU | 4 | 3 | 1 | [erd-COMTNMENU", "| 사용자(COMTNUSER) | 5 | 4 | 3 | [erd-COMTNUSER", "| SYS | 12 | 0 | 0 | [erd-SYS");
        assertThat(ErdSplitterProbe.split(centered().graph(), 9000).areas()).extracting(a -> a.name() + "=" + a.label() + "=" + a.fileName())
                .containsExactly("COMTNBBS=게시판 |마스터(COMTNBBS)=COMTNBBS", "COMTNFILE=COMTNFILE=COMTNFILE", "COMTNMENU=COMTNMENU=COMTNMENU",
                        "COMTNUSER=사용자(COMTNUSER)=COMTNUSER", "SYS=SYS=SYS");
        // 긴 논리명은 40자에서 자른다
        GraphFixture chain = GraphFixture.create();
        for (int i = 1; i <= 6; i++) {
            chain.table("A" + i, i == 1 ? "가".repeat(45) : null);
            chain.column("A" + i, "ID", "NUMBER", 1, "pk");
            if (i > 1) {
                chain.fk("A" + i, "A" + (i - 1), "FK_A" + i, List.of("ID"), List.of("ID"));
            }
        }
        assertThat(ErdSplitterProbe.split(chain.graph(), 100_000).areas()).extracting(a -> a.label()).containsExactly("가".repeat(40) + "…(A1)");
    }

    /** {@code ErdSplitter.split}을 기본 옵션의 모델로 부른다. */
    private static final class ErdSplitterProbe {
        record Result(List<io.codeatlas.export.erd.ErdSplitter.Area> areas, List<io.codeatlas.export.erd.ErdSplitter.Center> centers) {
        }

        static Result split(GraphModel graph, int maxChars) {
            io.codeatlas.export.erd.ErdOptions options = io.codeatlas.export.erd.ErdOptions.defaults();
            io.codeatlas.export.erd.ErdSplitter.Split split = io.codeatlas.export.erd.ErdSplitter.split(
                    io.codeatlas.export.erd.Erd.model(graph, options), options, maxChars);
            return new Result(split.areas(), split.centers());
        }
    }

    @Test
    @DisplayName("F-014-AC16 패키지의 클래스 다이어그램이 Mermaid 글자 수 한도를 넘으면 멤버를 public만 → 없음 순으로 줄이고, 그래도 넘으면 이름순으로 나눈다")
    void reducesAndSplitsClassDiagrams() throws Exception {
        GraphFixture f = GraphFixture.create();
        for (int t = 0; t < 10; t++) {
            String type = "com.big.vo.Order" + (char) ('A' + t) + "VO";
            f.type(type, "class", "public");
            for (int m = 0; m < 12; m++) {
                f.field(type, "privateFieldNumber" + m, "java.lang.String", "private");
                f.method(type, "privateHelperMethod" + m, List.of("java.lang.String"), "java.lang.String", "private");
            }
            for (int m = 0; m < 4; m++) {
                f.method(type, "publicBusinessMethod" + m, List.of("java.lang.String"), "java.lang.String", "public");
            }
            if (t > 0) {
                // 사슬로 상속한다: B extends A, C extends B …
                f.typeEdge(type, "com.big.vo.Order" + (char) ('A' + t - 1) + "VO", EdgeKind.EXTENDS);
            }
        }
        f.type("com.small.Tiny", "class", "public");
        f.method("com.small.Tiny", "run");
        GraphModel g = f.graph();

        Map<String, String> all = export(g, "cd-all", Map.of());
        int fullLength = all.get("class-diagram/com.big.vo.mmd").length();
        assertThat(all.get("class-diagram/com.big.vo.mmd")).contains("-privateHelperMethod0(").contains("+publicBusinessMethod0(");
        assertThat(all.get("class-diagram/index.md")).doesNotContain("한도");

        // 1) public 멤버만 남기면 들어간다
        Map<String, String> reduced = export(g, "cd-public", Map.of("export.mermaid.maxChars", String.valueOf(fullLength / 2)));
        String publicOnly = reduced.get("class-diagram/com.big.vo.mmd");
        assertThat(publicOnly.length()).isLessThanOrEqualTo(fullLength / 2);
        assertThat(publicOnly).contains("+publicBusinessMethod0(").doesNotContain("privateHelperMethod").contains("OrderAVO <|-- OrderBVO");
        assertThat(MermaidSyntax.checkClass(publicOnly)).isEmpty();
        assertThat(reduced.get("class-diagram/index.md")).contains("| `com.big.vo` | 10 | [mmd](com.big.vo.mmd) | [puml](com.big.vo.puml) | Mermaid 글자 수 한도 때문에 public 멤버만 그렸다. |");
        // PlantUML은 그대로고 작은 패키지도 그대로다
        assertThat(reduced.get("class-diagram/com.big.vo.puml")).isEqualTo(all.get("class-diagram/com.big.vo.puml")).contains("privateHelperMethod0");
        assertThat(reduced.get("class-diagram/com.small.mmd")).isEqualTo(all.get("class-diagram/com.small.mmd"));

        // 2) public만으로도 넘으면 멤버 없이
        int publicLength = publicOnly.length();
        Map<String, String> none = export(g, "cd-none", Map.of("export.mermaid.maxChars", String.valueOf(publicLength - 100)));
        assertThat(none.get("class-diagram/com.big.vo.mmd")).doesNotContain("publicBusinessMethod").contains("class OrderAVO").contains("OrderIVO <|-- OrderJVO");
        assertThat(none.get("class-diagram/index.md")).contains("Mermaid 글자 수 한도 때문에 멤버를 생략했다.");

        // 3) 멤버 없이도 넘으면 이름순으로 나눈다. 다른 조각의 타입과의 관계는 그 타입을 이름만 그려 잇는다
        Map<String, String> split = export(g, "cd-split", Map.of("export.mermaid.maxChars", "330"));
        List<String> parts = split.keySet().stream().filter(k -> k.matches("class-diagram/com\\.big\\.vo-\\d+\\.mmd")).toList();
        assertThat(parts.size()).isGreaterThanOrEqualTo(2);
        assertThat(parts.get(0)).isEqualTo("class-diagram/com.big.vo-1.mmd");
        StringBuilder joined = new StringBuilder();
        for (String part : parts) {
            assertThat(split.get(part).length()).as(part).isLessThanOrEqualTo(330);
            assertThat(MermaidSyntax.checkClass(split.get(part))).as(part).isEmpty();
            joined.append(split.get(part));
        }
        for (int t = 0; t < 10; t++) {
            assertThat(joined.toString()).contains("class Order" + (char) ('A' + t) + "VO");
        }
        assertThat(split.get(parts.get(parts.size() - 1))).contains("OrderIVO <|-- OrderJVO");
        assertThat(split.get("class-diagram/com.big.vo.mmd")).startsWith("classDiagram\n    %% 주의: 이 다이어그램은 ");
        assertThat(split.get("class-diagram/index.md")).contains("[1](com.big.vo-1.mmd) [2](com.big.vo-2.mmd)").contains("조각으로 나눴다");
        assertThat(export(f.reversedCopy(), "cd-split-reversed", Map.of("export.mermaid.maxChars", "330"))).isEqualTo(split);
    }

    /** 컨트롤러 n개에 엔드포인트가 둘씩. 핸들러는 서비스를 부르고 서비스가 구문을 실행한다. */
    private static GraphFixture api(int controllers) {
        GraphFixture f = GraphFixture.create();
        f.table("TB_ITEM");
        f.table("TB_LOG");
        String select = f.statement("com.api.ItemMapper", "select", "SELECT");
        String insert = f.statement("com.api.ItemMapper", "insertLog", "INSERT");
        f.reads(select, "TB_ITEM");
        f.writes(insert, "TB_LOG", "INSERT");
        f.attr(f.type("com.api.ItemVO", "class", "public"), "stereotype", null == null ? "component" : null);
        f.field("com.api.ItemVO", "name", "java.lang.String", "private");
        String getter = f.method("com.api.ItemVO", "getName", List.of(), "java.lang.String", "public");
        String lombokSetter = f.method("com.api.ItemVO", "setName", List.of("java.lang.String"), "void", "public");
        f.attr(lombokSetter, "synthetic", true).attr(lombokSetter, "lombok", "Setter");
        String computed = f.method("com.api.ItemVO", "getTotal", List.of(), "long", "public");
        String external = f.externalMethod("org.lib.Strings", "trim", "java.lang.String");
        f.externalType("org.lib.Strings", "class");
        String service = f.method("com.api.ItemService", "find");
        f.type("com.api.ItemService", "class", "public");
        String mapper = f.method("com.api.ItemMapper", "select", List.of(), "java.util.List", "public", "abstract");
        String logMapper = f.method("com.api.ItemMapper", "insertLog", List.of(), "int", "public", "abstract");
        f.type("com.api.ItemMapper", "interface", "public");
        f.edge(mapper, select, EdgeKind.EXECUTES);
        f.edge(logMapper, insert, EdgeKind.EXECUTES);
        f.calls(service, mapper);
        f.calls(service, getter);
        f.calls(service, lombokSetter);
        f.calls(service, computed);
        f.calls(service, external);
        f.calls(computed, getter);
        for (int c = 0; c < controllers; c++) {
            String controller = "com.api.c" + (c % 2) + ".Item" + c + "Controller";
            f.attr(f.type(controller, "class", "public"), "stereotype", "controller");
            String list = f.method(controller, "list");
            String save = f.method(controller, "save");
            f.calls(list, service);
            f.calls(save, logMapper);
            f.endpoint("GET", "/item" + c + "/list", list);
            f.endpoint("POST", "/item" + c + "/save", save);
        }
        // 핸들러가 없는 엔드포인트는 URL의 첫 조각으로 묶는다
        f.endpoint("GET", "/orphan/a", null);
        f.endpoint("GET", "/orphan/b", null);
        // 단순 이름이 같은 컨트롤러
        f.attr(f.type("com.api.other.Item0Controller", "class", "public"), "stereotype", "controller");
        String dup = f.method("com.api.other.Item0Controller", "dup");
        f.calls(dup, service);
        f.endpoint("GET", "/dup", dup);
        return f;
    }

    @Test
    @DisplayName("F-015-AC40 엔드포인트가 많으면(기본 50개 초과) 컨트롤러별 calltree/endpoints/<이름>.md로 나누고 endpoints.md는 색인(엔드포인트 → 파일, 닿는 테이블)이 된다")
    void splitsEndpointCallTreesByController() throws Exception {
        GraphModel g = api(30).graph();   // 엔드포인트 63개

        Map<String, String> files = export(g, "many", Map.of());

        assertThat(files.keySet().stream().filter(k -> k.startsWith("calltree/endpoints/")).count()).isEqualTo(32);
        assertThat(files).containsKeys("calltree/endpoints/Item7Controller.md", "calltree/endpoints/orphan.md",
                "calltree/endpoints/com.api.c0.Item0Controller.md", "calltree/endpoints/com.api.other.Item0Controller.md");
        String one = files.get("calltree/endpoints/Item7Controller.md");
        assertThat(one).startsWith("# 엔드포인트 콜트리: Item7Controller\n").contains("`com.api.c1.Item7Controller`").contains("표기: ")
                .contains("## GET /item7/list\n\n```text\n[endpoint] GET /item7/list").contains("## POST /item7/save\n")
                .contains("[table] TB_ITEM (R)").doesNotContain("/item8/");
        String index = files.get("calltree/endpoints.md");
        assertThat(index).startsWith("# 엔드포인트 콜트리\n").contains("엔드포인트 63개").contains("export.calltree.splitOver")
                .contains("| 엔드포인트 | 파일 | 닿는 테이블 |\n|---|---|---|\n")
                .contains("| `GET /item7/list` | [Item7Controller](endpoints/Item7Controller.md) | TB_ITEM(R) |\n")
                .contains("| `POST /item7/save` | [Item7Controller](endpoints/Item7Controller.md) | TB_LOG(C) |\n")
                .contains("| `GET /orphan/a` | [orphan](endpoints/orphan.md) |  |\n")
                .contains("| `GET /dup` | [com.api.other.Item0Controller](endpoints/com.api.other.Item0Controller.md) | TB_ITEM(R) |\n")
                .doesNotContain("```text");

        // 기준 이하이면 전처럼 한 파일이다. 기준은 옵션으로 바꾼다(0이면 나누지 않는다)
        Map<String, String> few = export(api(3).graph(), "few", Map.of());
        assertThat(few.keySet().stream().filter(k -> k.startsWith("calltree/"))).containsExactly("calltree/endpoints.md");
        assertThat(few.get("calltree/endpoints.md")).contains("## GET /item0/list\n\n```text\n");
        assertThat(export(api(3).graph(), "few-split", Map.of("export.calltree.splitOver", "5"))).containsKey("calltree/endpoints/Item1Controller.md");
        assertThat(export(g, "many-one-file", Map.of("export.calltree.splitOver", "0")).keySet().stream().filter(k -> k.startsWith("calltree/")))
                .containsExactly("calltree/endpoints.md");
        assertThat(export(api(30).reversedCopy(), "many-reversed", Map.of())).isEqualTo(files);
    }

    @Test
    @DisplayName("F-015-AC46 표준 산출물의 콜트리는 접근자(Lombok 합성 멤버, 호출 없는 getter/setter)와 external 메서드 호출 줄을 기본으로 접는다 (조회 API의 기본은 그대로)")
    void standardCallTreesHideAccessorsAndExternal() throws Exception {
        GraphModel g = api(1).graph();

        String standard = export(g, "std", Map.of()).get("calltree/endpoints.md");
        assertThat(standard).contains("ItemService#find()").contains("ItemMapper#select()")
                // 다른 메서드를 부르는 getter는 접근자가 아니다
                .contains("ItemVO#getTotal()")
                .doesNotContain("ItemVO#getName()").doesNotContain("ItemVO#setName(").doesNotContain("Strings#trim");
        assertThat(standard).contains("접근자").contains("`export.calltree.hideAccessors=false`").contains("`export.calltree.hideExternal=false`");

        String shown = export(g, "shown", Map.of("export.calltree.hideAccessors", "false", "export.calltree.hideExternal", "false"))
                .get("calltree/endpoints.md");
        assertThat(shown).contains("ItemVO#getName()").contains("ItemVO#setName(String)").contains("Strings#trim(String)");

        // 조회 API의 기본값은 그대로(전부 보인다). 옵션으로 접는다
        String id = Ids.httpEndpoint("GET", "/item0/list");
        String api = io.codeatlas.export.calltree.CallTrees.toText(
                io.codeatlas.export.calltree.CallTrees.build(g, id, io.codeatlas.export.calltree.CallTreeOptions.callees()), false);
        assertThat(api).contains("ItemVO#getName()").contains("ItemVO#setName(String)").contains("Strings#trim(String)");
        String hidden = io.codeatlas.export.calltree.CallTrees.toText(io.codeatlas.export.calltree.CallTrees.build(g, id,
                io.codeatlas.export.calltree.CallTreeOptions.builder().hideAccessors(true).build()), false);
        assertThat(hidden).doesNotContain("ItemVO#getName()").doesNotContain("ItemVO#setName(").contains("ItemVO#getTotal()").contains("Strings#trim");
    }
}
