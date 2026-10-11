package io.codeatlas.export;

import io.codeatlas.core.model.Diagnostic;
import io.codeatlas.core.model.DiagnosticCodes;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.spi.Exporter;
import io.codeatlas.export.calltree.BatchFlow;
import io.codeatlas.export.calltree.CallTree;
import io.codeatlas.export.calltree.CallTreeNode;
import io.codeatlas.export.calltree.CallTreeOptions;
import io.codeatlas.export.calltree.CallTrees;
import io.codeatlas.export.calltree.CrudMatrix;
import io.codeatlas.export.calltree.CrudMatrix.Layout;
import io.codeatlas.export.calltree.CrudMatrix.Subject;
import io.codeatlas.export.calltree.CrudReadme;
import io.codeatlas.export.classdiagram.ClassDiagramFormat;
import io.codeatlas.export.classdiagram.ClassDiagramModel;
import io.codeatlas.export.classdiagram.ClassDiagramOptions;
import io.codeatlas.export.classdiagram.ClassDiagramOptions.MemberLevel;
import io.codeatlas.export.classdiagram.ClassDiagramSplitter;
import io.codeatlas.export.classdiagram.ClassDiagrams;
import io.codeatlas.export.common.GraphContents;
import io.codeatlas.export.common.NodeLabels;
import io.codeatlas.export.common.Texts;
import io.codeatlas.export.erd.Erd;
import io.codeatlas.export.erd.ErdFormat;
import io.codeatlas.export.erd.ErdModel;
import io.codeatlas.export.erd.ErdOptions;
import io.codeatlas.export.erd.ErdOptions.ColumnLevel;
import io.codeatlas.export.erd.ErdSplitter;
import io.codeatlas.export.erd.TableSpecRenderer;
import io.codeatlas.export.report.SummaryReport;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 기본 출력기. {@code analyze}가 끝나면 출력 폴더에 표준 산출물을 쓴다.
 *
 * <pre>
 * erd/erd.mmd, erd/erd.puml, erd/erd.dbml, erd/tables.md
 * class-diagram/&lt;패키지&gt;.mmd, class-diagram/&lt;패키지&gt;.puml, class-diagram/index.md
 * crud/table-statement.csv, crud/table-method.csv, crud/table-endpoint.csv, crud/table-routine.csv, crud/README.md (칸의 표기 설명)
 * calltree/endpoints.md (엔드포인트가 많으면 색인이고 트리는 calltree/endpoints/&lt;컨트롤러&gt;.md), calltree/filters.md, calltree/batch-jobs.md
 * report.md
 * </pre>
 *
 * <p>옵션(전부 선택, {@code Map<String,String>}):
 * <ul>
 * <li>{@code export.erd}, {@code export.classDiagram}, {@code export.crud}, {@code export.calltree}, {@code export.report}:
 *     {@code false}면 그 산출물을 쓰지 않는다.</li>
 * <li>{@code export.includeDataLoad}(false): 데이터 적재 구문({@code attrs.dataLoad=true})을 CRUD 매트릭스, 테이블 명세의 구문 수,
 *     콜트리, 리포트의 테이블 점검에 넣는다.</li>
 * <li>{@code export.erd.views}(false), {@code export.erd.inferred}(true), {@code export.erd.minEvidence}(1),
 *     {@code export.erd.columns}(all|keys|none), {@code export.erd.comments}(true)</li>
 * <li>{@code export.mermaid.maxChars}(45000, 0이면 보지 않는다): Mermaid 다이어그램 하나의 글자 수 한도. 넘으면 ERD는
 *     {@code erd/erd-<영역>.mmd}·{@code erd/erd-overview.mmd}·{@code erd/index.md}로 나누고, 클래스 다이어그램은 멤버를 줄이거나
 *     {@code <패키지>-<n>.mmd}로 나눈다. PlantUML·DBML은 나누지 않는다.</li>
 * <li>{@code export.classDiagram.maxPackages}(200), {@code export.classDiagram.maxTypes}(60),
 *     {@code export.classDiagram.members}(all|public|none), {@code export.classDiagram.hideAccessors}(false),
 *     {@code export.classDiagram.externalSupertypes}(false)</li>
 * <li>{@code export.crud.bom}(true), {@code export.crud.depth}(20: 엔드포인트에서 구문까지, 그리고 구문·메서드·루틴에서 루틴 호출을
 *     따라가는 걸음 수)</li>
 * <li>{@code export.calltree.depth}(20), {@code export.calltree.maxNodes}(500),
 *     {@code export.calltree.hideExternal}(true: 소스 없는 메서드의 호출 줄을 접는다),
 *     {@code export.calltree.hideAccessors}(true: 접근자 호출 줄을 접는다),
 *     {@code export.calltree.expandViews}(false: 컨트롤러가 그리는 JSP 아래의 화면 이동과 redirect·forward로 넘어간 핸들러의 아래는 펼치지 않는다),
 *     {@code export.calltree.splitOver}(50, 0이면 나누지 않는다: 엔드포인트가 이 수를 넘으면 컨트롤러별 파일로 나눈다)</li>
 * <li>{@code export.report.maxItems}(20)</li>
 * </ul>
 * 잘못된 값은 WARN 진단 {@code INVALID_OPTION}을 남기고 기본값을 쓴다. 출력은 UTF-8, 줄 끝 {@code \n}이고
 * 같은 그래프와 옵션이면 바이트까지 같다. 읽는 키의 전체 목록은 {@link #OPTION_KEYS}다.
 *
 * <p>대상이 없는 산출물은 빈 내용으로 쓰지 않고 생략한다: 테이블·뷰가 없으면 {@code erd/}와 {@code crud/}, 테이블에 접근하는 주체가
 * 없는 CRUD 매트릭스(예: 루틴 본문을 분석하지 않았을 때의 {@code crud/table-routine.csv}), 그릴 Java 타입이 없으면
 * {@code class-diagram/}, 엔드포인트가 없으면 {@code calltree/endpoints.md}, 필터·인터셉터가 없으면 {@code calltree/filters.md},
 * 배치 잡과 스텝이 없으면 {@code calltree/batch-jobs.md}.
 * 그 사실은 {@code report.md}의 머리에 한 줄씩 남는다({@link SummaryReport}). 전에 쓴 파일을 지우지는 않는다.
 */
public final class StandardExporter implements Exporter {

    /** 출력기 이름. */
    public static final String NAME = "export";

    /**
     * 표준 산출물 콜트리의 기본 깊이. 화면 → 엔드포인트 → 컨트롤러 → 서비스 → 매퍼 → 구문 → 프로시저 → 테이블 → 트리거처럼
     * 긴 경로가 테이블에 닿기 전에 잘리지 않을 만큼 둔다. 트리가 커지는 것은 트리당 노드 수 상한이 막는다.
     */
    public static final int DEFAULT_CALLTREE_DEPTH = 20;

    /**
     * Mermaid 다이어그램 하나의 글자 수 한도 기본값. Mermaid의 기본 {@code maxTextSize}가 50,000자라서 그보다 조금 작게 둔다.
     * 넘으면 ERD는 주제 영역별로 나누고, 클래스 다이어그램은 멤버를 줄이거나 나눈다.
     */
    public static final int DEFAULT_MERMAID_MAX_CHARS = 45_000;

    /** 엔드포인트가 이 수를 넘으면 {@code calltree/endpoints.md}를 컨트롤러별 파일로 나눈다(기본값). */
    public static final int DEFAULT_CALLTREE_SPLIT_OVER = 50;

    /** 깊이 제한에 잘린 트리 끝에 붙이는 안내. */
    static final String DEPTH_NOTE = "깊이 제한으로 잘림: `export.calltree.depth`로 조정";

    /** 이 출력기가 읽는 옵션 키 전부(설계 3.8의 출력기 키). 이 밖의 키는 무시한다. */
    public static final Set<String> OPTION_KEYS = Set.of(
            "export.erd", "export.classDiagram", "export.crud", "export.calltree", "export.report", "export.includeDataLoad",
            "export.erd.views", "export.erd.inferred", "export.erd.minEvidence", "export.erd.columns", "export.erd.comments",
            "export.mermaid.maxChars",
            "export.classDiagram.maxPackages", "export.classDiagram.maxTypes", "export.classDiagram.members",
            "export.classDiagram.hideAccessors", "export.classDiagram.externalSupertypes",
            "export.crud.bom", "export.crud.depth",
            "export.calltree.depth", "export.calltree.maxNodes", "export.calltree.hideExternal", "export.calltree.hideAccessors",
            "export.calltree.expandViews", "export.calltree.splitOver",
            "export.report.maxItems");

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void export(GraphModel graph, Path outputDir, Map<String, String> options) throws IOException {
        Options opts = new Options(graph, options);
        boolean dbObjects = GraphContents.hasDbObjects(graph);
        boolean includeDataLoad = opts.flag("export.includeDataLoad", false);
        if (opts.flag("export.erd", true) && dbObjects) {
            writeErd(graph, outputDir.resolve("erd"), opts, includeDataLoad);
        }
        if (opts.flag("export.classDiagram", true)) {
            writeClassDiagrams(graph, outputDir.resolve("class-diagram"), opts);
        }
        if (opts.flag("export.crud", true) && dbObjects) {
            writeCrud(graph, outputDir.resolve("crud"), opts, includeDataLoad);
        }
        if (opts.flag("export.calltree", true)) {
            writeCallTrees(graph, outputDir.resolve("calltree"), opts, includeDataLoad);
        }
        if (opts.flag("export.report", true)) {
            // 리포트는 마지막에 쓴다. 앞 단계가 남긴 INVALID_OPTION 진단도 들어간다.
            write(outputDir.resolve("report.md"), SummaryReport.render(graph,
                    opts.number("export.report.maxItems", SummaryReport.DEFAULT_MAX_ITEMS, 0), includeDataLoad));
        }
    }

    // ---------------------------------------------------------------- ERD

    private static void writeErd(GraphModel graph, Path dir, Options opts, boolean includeDataLoad) throws IOException {
        ErdOptions erd = ErdOptions.builder()
                .includeViews(opts.flag("export.erd.views", false))
                .includeInferred(opts.flag("export.erd.inferred", true))
                .minEvidence(opts.number("export.erd.minEvidence", 1, 0))
                .columnLevel(opts.choice("export.erd.columns", ColumnLevel.class, ColumnLevel.ALL))
                .showComments(opts.flag("export.erd.comments", true))
                .build();
        ErdModel model = Erd.model(graph, erd);
        int maxChars = opts.number("export.mermaid.maxChars", DEFAULT_MERMAID_MAX_CHARS, 0);
        for (ErdFormat format : ErdFormat.values()) {
            String text = Erd.render(model, erd, format);
            if (format == ErdFormat.MERMAID && maxChars > 0 && text.length() > maxChars) {
                // PlantUML·DBML은 나누지 않는다. Mermaid만 영역별로 나누고 전체 파일에는 한도를 넘는다는 주석을 단다.
                writeSplitErd(graph, dir, model, erd, text.length(), maxChars);
                text = oversizeNote(text, "erd/index.md의 영역별 다이어그램(erd-<영역>.mmd)과 개요(erd-overview.mmd)를 본다");
            }
            write(dir.resolve("erd." + format.extension()), text);
        }
        write(dir.resolve("tables.md"), TableSpecRenderer.render(graph, ErdOptions.builder().includeViews(true).build(), includeDataLoad));
    }

    /** 첫 줄(다이어그램 종류) 바로 아래에 "Mermaid 기본 한도를 넘는다"는 주석을 넣는다. */
    private static String oversizeNote(String mermaid, String hint) {
        int firstLine = mermaid.indexOf('\n') + 1;
        return mermaid.substring(0, firstLine) + "    %% 주의: 이 다이어그램은 " + mermaid.length() + "자라서 Mermaid의 기본 한도(maxTextSize 50,000자)에 걸려 "
                + "그려지지 않을 수 있다. " + hint + ".\n" + mermaid.substring(firstLine);
    }

    /**
     * 한도를 넘는 ERD: 주제 영역별 {@code erd-<영역>.mmd}, 키 컬럼만 남긴 {@code erd-overview.mmd}, 색인 {@code index.md}
     * (영역, 영역 간 관계 수, 관계로 묶은 영역이 있으면 허브 테이블과 중심 테이블). 영역 표의 "다른 영역과의 관계"에는 허브와의 관계를
     * 넣지 않는다(허브가 있으면 "허브와의 관계" 열에 따로 적는다): 그 합이 "영역 간 관계"의 관계 수 합의 두 배와 같다.
     */
    private static void writeSplitErd(GraphModel graph, Path dir, ErdModel model, ErdOptions erd, int fullLength, int maxChars)
            throws IOException {
        ErdSplitter.Split split = ErdSplitter.split(model, erd, maxChars);
        // 개요: 컬럼은 키만. 그래도 넘으면 컬럼 없이.
        String overviewColumns = "키 컬럼만";
        String overview = Erd.render(graph, withColumns(erd, ColumnLevel.KEYS), ErdFormat.MERMAID);
        if (erd.columnLevel() == ColumnLevel.NONE || overview.length() > maxChars) {
            overviewColumns = "컬럼 없음";
            overview = Erd.render(graph, withColumns(erd, ColumnLevel.NONE), ErdFormat.MERMAID);
        }
        write(dir.resolve("erd-overview.mmd"), overview);

        StringBuilder index = new StringBuilder("# ERD\n\n");
        long inferred = model.relations().stream().filter(ErdModel.Relation::inferred).count();
        index.append("- 테이블 ").append(model.entities().size()).append("개, 관계 ").append(model.relations().size()).append("개 (추론 ")
                .append(inferred).append("개)\n");
        index.append("- 전체 다이어그램 [erd.mmd](erd.mmd)는 ").append(fullLength).append("자라서 Mermaid의 기본 한도(50,000자)에 걸린다. ")
                .append("다이어그램 하나가 ").append(maxChars).append("자(`export.mermaid.maxChars`)를 넘지 않게 주제 영역(테이블 이름 접두사)별로 나눴다.");
        if (split.byRelations()) {
            index.append(" 접두사로 묶이지 않는 테이블은 관계로 이어진 테이블끼리 묶었고, 그 영역의 이름은 그 안에서 가장 많이 참조되는 테이블이다")
                    .append("(논리명이 있으면 `논리명(물리명)`. 파일 이름은 물리명이다).");
        }
        if (split.byNames()) {
            index.append(" 관계로 묶이지 않고 남은 테이블은 이름의 공통 접두사로 묶었다(`*`로 끝나는 영역. 더 긴 접두사로 먼저 묶고, 남은 테이블을 ")
                    .append("짧은 접두사로 묶는다). 묶이지 않는 나머지만 ").append(ErdSplitter.ETC).append("다.");
        }
        if (split.byRelations() || split.byNames()) {
            index.append(" 관계로 묶은 영역과 이름으로 묶은 영역은 테이블 ").append(ErdSplitter.MAX_AREA_TABLES).append("개를 넘지 않는다.");
        }
        index.append('\n');
        index.append("- 개요: [erd-overview.mmd](erd-overview.mmd) (전체 테이블과 관계, ").append(overviewColumns).append(", ")
                .append(overview.length()).append("자)\n");
        index.append("- 영역 다이어그램에서 컬럼 없이 이름만 나오는 테이블은 관계로 이어진 다른 영역의 테이블")
                .append(split.hubs().isEmpty() ? "" : "이나 허브 테이블").append("이다.\n");
        index.append("- PlantUML([erd.puml](erd.puml))과 DBML([erd.dbml](erd.dbml))은 나누지 않았다.\n");
        if (!model.systemCatalogNames().isEmpty()) {
            index.append("- 시스템 카탈로그 ").append(model.systemCatalogNames().size())
                    .append("개는 ERD에서 제외했다(이름은 [tables.md](tables.md)의 \"").append(TableSpecRenderer.SYSTEM_CATALOG_SECTION).append("\" 절)\n");
        }
        // 허브가 있으면 허브와의 관계를 따로 센다: "다른 영역과의 관계"에 섞으면 "영역 간 관계"의 수와 어긋난다(허브는 어느 영역에도 없다).
        boolean hubs = !split.hubs().isEmpty();
        index.append("\n## 영역\n\n| 영역 | 테이블 수 | 영역 안 관계 | 다른 영역과의 관계 | ").append(hubs ? "허브와의 관계 | " : "")
                .append("파일 | 글자 수 |\n|---|---|---|---|---|---|").append(hubs ? "---|" : "").append('\n');
        Map<String, String> labels = new java.util.HashMap<>();
        for (ErdSplitter.Area area : split.areas()) {
            String file = "erd-" + area.fileName() + ".mmd";
            write(dir.resolve(file), area.mermaid());
            labels.put(area.name(), area.label());
            index.append("| ").append(Texts.mdCell(area.label())).append(" | ").append(area.tables()).append(" | ").append(area.innerRelations())
                    .append(" | ").append(area.outerRelations()).append(hubs ? " | " + area.hubRelations() : "").append(" | [").append(file)
                    .append("](").append(file).append(") | ").append(area.mermaid().length()).append(" |\n");
        }
        index.append("\n## 영역 간 관계\n\n");
        if (split.crossRelations().isEmpty()) {
            index.append("없음\n");
        } else {
            index.append("| 영역 | 영역 | 관계 수 |\n|---|---|---|\n");
            split.crossRelations().forEach((pair, count) -> {
                String[] names = pair.split("\t");
                index.append("| ").append(Texts.mdCell(labels.getOrDefault(names[0], names[0]))).append(" | ")
                        .append(Texts.mdCell(labels.getOrDefault(names[1], names[1]))).append(" | ").append(count).append(" |\n");
            });
        }
        if (hubs) {
            index.append("\n## 허브 테이블\n\n전체 테이블의 ").append(ErdSplitter.HUB_PERCENT).append("%를 넘는 테이블이 참조하는 테이블이다. ")
                    .append("관계로 영역을 묶을 때 영역에 넣지 않았고, 그것을 참조하는 영역마다 이름만 나온다. ")
                    .append("허브와의 관계는 \"다른 영역과의 관계\"에 넣지 않고 따로 센다(영역 표의 \"허브와의 관계\"). ")
                    .append("컬럼은 개요와 [tables.md](tables.md)에 있다.\n\n")
                    .append("| 테이블 | 참조하는 테이블 수 |\n|---|---|\n");
            for (ErdSplitter.Hub hub : split.hubs()) {
                index.append("| ").append(Texts.mdCell(hub.name())).append(" | ").append(hub.referencedBy()).append(" |\n");
            }
        }
        if (!split.centers().isEmpty()) {
            index.append("\n## 중심 테이블\n\n관계로 이어진 묶음이 테이블 ").append(ErdSplitter.MAX_AREA_TABLES)
                    .append("개(또는 글자 수 한도)를 넘어, 그 안에서 가장 많이 참조되는 테이블(중심 테이블)을 빼고 나머지를 다시 묶었다. ")
                    .append("중심 테이블은 자기 이름의 영역에 있고(중심을 빼면 ").append(ErdSplitter.MIN_AREA_SIZE)
                    .append("개 미만으로 남는 테이블과 함께), 그것을 참조하는 다른 영역에는 이름만 나온다.\n\n")
                    .append("| 중심 테이블 | 참조하는 테이블 수 | 중심을 빼고 나눈 영역 |\n|---|---|---|\n");
            for (ErdSplitter.Center center : split.centers()) {
                index.append("| ").append(Texts.mdCell(center.name())).append(" | ").append(center.referencedBy()).append(" | ")
                        .append(Texts.mdCell(String.join(", ", center.areas().stream().map(name -> labels.getOrDefault(name, name)).toList())))
                        .append(" |\n");
            }
        }
        write(dir.resolve("index.md"), index.toString());
    }

    private static ErdOptions withColumns(ErdOptions erd, ColumnLevel level) {
        return ErdOptions.builder().includeViews(erd.includeViews()).includeInferred(erd.includeInferred()).minEvidence(erd.minEvidence())
                .columnLevel(level).showComments(erd.showComments()).build();
    }

    // ---------------------------------------------------------------- 클래스 다이어그램

    private static void writeClassDiagrams(GraphModel graph, Path dir, Options opts) throws IOException {
        int maxPackages = opts.number("export.classDiagram.maxPackages", 200, 1);
        ClassDiagramOptions.Builder base = ClassDiagramOptions.builder()
                .maxTypes(opts.number("export.classDiagram.maxTypes", 60, 0))
                .members(opts.choice("export.classDiagram.members", MemberLevel.class, MemberLevel.ALL))
                .hideAccessors(opts.flag("export.classDiagram.hideAccessors", false))
                .showExternalSupertypes(opts.flag("export.classDiagram.externalSupertypes", false));

        // 타입이 직접 들어 있는 패키지(기본 옵션으로 그려지는 타입만 센다).
        ClassDiagramModel all = ClassDiagrams.model(graph, ClassDiagramOptions.builder().members(MemberLevel.NONE).maxTypes(0).build());
        if (all.types().isEmpty()) {
            return;
        }
        Set<String> packages = new TreeSet<>();
        all.types().forEach(t -> packages.add(t.packageName()));

        // 패키지가 상한을 넘으면 앞에서 d개 조각까지만 남긴 상위 패키지로 묶는다.
        boolean grouped = packages.size() > maxPackages;
        Set<String> groups = packages;
        if (grouped) {
            int deepest = packages.stream().mapToInt(p -> p.isEmpty() ? 0 : p.split("\\.").length).max().orElse(1);
            for (int depth = deepest - 1; depth >= 1; depth--) {
                groups = truncate(packages, depth);
                if (groups.size() <= maxPackages) {
                    break;
                }
            }
        }

        StringBuilder index = new StringBuilder("# 클래스 다이어그램\n\n");
        index.append("- 패키지 ").append(packages.size()).append("개, 타입 ").append(all.types().size()).append("개, 다이어그램 ")
                .append(groups.size()).append("개\n");
        if (grouped) {
            index.append("- 패키지 수가 상한 ").append(maxPackages).append("개를 넘어 상위 패키지로 묶었다. 묶은 다이어그램은 하위 패키지를 포함한다.\n");
        }
        index.append("\n| 패키지 | 타입 수 | Mermaid | PlantUML | 비고 |\n|---|---|---|---|---|\n");
        int maxChars = opts.number("export.mermaid.maxChars", DEFAULT_MERMAID_MAX_CHARS, 0);
        Set<String> usedNames = new TreeSet<>();
        for (String group : groups) {
            boolean withSubpackages = grouped && !group.isEmpty();
            ClassDiagramOptions options = base.packagePrefix(group).includeSubpackages(withSubpackages).groupByPackage(withSubpackages)
                    .build();
            ClassDiagramModel model = ClassDiagrams.model(graph, options);
            String fileName = group.isEmpty() ? "_default_" : Texts.fileSafe(group);
            for (int n = 2; !usedNames.add(fileName.toLowerCase(Locale.ROOT)); n++) {
                fileName = Texts.fileSafe(group) + "_" + n;
            }
            write(dir.resolve(fileName + ".puml"), ClassDiagrams.render(model, options, ClassDiagramFormat.PLANTUML));
            List<String> notes = new ArrayList<>(model.warnings());
            String mermaidLinks = "[mmd](" + fileName + ".mmd)";
            String mermaid = ClassDiagrams.render(model, options, ClassDiagramFormat.MERMAID);
            if (maxChars > 0 && mermaid.length() > maxChars) {
                // Mermaid만 줄인다: 멤버를 public만 → 없음 순으로, 그래도 넘으면 이름순 조각으로.
                ClassDiagramOptions fitted = null;
                for (MemberLevel level : List.of(MemberLevel.PUBLIC, MemberLevel.NONE)) {
                    if (level.ordinal() <= options.members().ordinal()) {
                        continue;
                    }
                    ClassDiagramOptions reduced = base.members(level).build();
                    String text = ClassDiagrams.render(ClassDiagrams.model(graph, reduced), reduced, ClassDiagramFormat.MERMAID);
                    if (text.length() <= maxChars) {
                        fitted = reduced;
                        mermaid = text;
                        notes.add(level == MemberLevel.PUBLIC ? "Mermaid 글자 수 한도 때문에 public 멤버만 그렸다." : "Mermaid 글자 수 한도 때문에 멤버를 생략했다.");
                        break;
                    }
                }
                base.members(options.members());
                if (fitted == null) {
                    List<ClassDiagramModel> levels = new ArrayList<>();
                    for (MemberLevel level : MemberLevel.values()) {
                        if (level.ordinal() >= options.members().ordinal()) {
                            levels.add(ClassDiagrams.model(graph, base.members(level).build()));
                        }
                    }
                    List<String> parts = ClassDiagramSplitter.split(levels, options, maxChars);
                    StringBuilder links = new StringBuilder();
                    for (int i = 0; i < parts.size(); i++) {
                        String part = fileName + "-" + (i + 1) + ".mmd";
                        write(dir.resolve(part), parts.get(i));
                        links.append(i == 0 ? "" : " ").append('[').append(i + 1).append("](").append(part).append(')');
                    }
                    mermaidLinks = links + " (전체: [mmd](" + fileName + ".mmd))";
                    notes.add("Mermaid 글자 수 한도 때문에 이름순 " + parts.size() + "개 조각으로 나눴다(다른 조각의 타입은 이름만 그린다).");
                    ClassDiagramOptions none = base.members(MemberLevel.NONE).build();
                    base.members(options.members());
                    mermaid = oversizeNote(ClassDiagrams.render(ClassDiagrams.model(graph, none), none, ClassDiagramFormat.MERMAID),
                            "나눈 조각(" + fileName + "-1.mmd …)을 본다");
                }
            }
            write(dir.resolve(fileName + ".mmd"), mermaid);
            long count = model.types().stream().filter(t -> !t.nameOnly()).count();
            index.append("| ").append(group.isEmpty() ? "(이름 없는 패키지)" : Texts.mdCode(group)).append(" | ").append(count)
                    .append(" | ").append(mermaidLinks).append(" | [puml](").append(fileName).append(".puml) | ")
                    .append(Texts.mdCell(String.join(" ", notes))).append(" |\n");
        }
        write(dir.resolve("index.md"), index.toString());
    }

    /** 패키지 이름을 앞에서 depth개 조각까지만 남긴다. 조각이 depth개 이하인 패키지는 그대로다. */
    private static Set<String> truncate(Set<String> packages, int depth) {
        Set<String> result = new TreeSet<>();
        for (String p : packages) {
            String[] parts = p.split("\\.");
            result.add(parts.length <= depth ? p : String.join(".", List.of(parts).subList(0, depth)));
        }
        return result;
    }

    // ---------------------------------------------------------------- CRUD

    private static void writeCrud(GraphModel graph, Path dir, Options opts, boolean includeDataLoad) throws IOException {
        boolean bom = opts.flag("export.crud.bom", true);
        int depth = opts.number("export.crud.depth", CrudMatrix.DEFAULT_MAX_DEPTH, 1);
        List<Subject> written = new ArrayList<>();
        for (Subject subject : Subject.values()) {
            CrudMatrix matrix = CrudMatrix.of(graph, subject, depth, includeDataLoad);
            // 테이블에 접근하는 주체가 없는 매트릭스는 머리글만 든 파일로 쓰지 않는다.
            if (!matrix.rows().isEmpty()) {
                write(dir.resolve(CrudReadme.fileName(subject)), matrix.toCsv(Layout.MATRIX, bom));
                written.add(subject);
            }
        }
        // CSV에는 머리 주석을 둘 수 없어서 표기(대·소문자, ?, (동적) 열, 데이터 적재 제외)는 같은 폴더의 README에 적는다.
        if (!written.isEmpty()) {
            write(dir.resolve("README.md"), CrudReadme.render(written, includeDataLoad, depth));
        }
    }

    // ---------------------------------------------------------------- 콜트리

    private static final String LEGEND = "표기: `~` 추론한 관계, `?` 미해결, `[impl]` 인터페이스·추상 메서드의 구현, `[cycle]` 순환, "
            + "`[see above]` 앞에서 펼친 노드, `[depth limit]` 깊이 제한으로 펼치지 않음, "
            + "`[trigger?]` 그 쓰기에 실제로 일어나는지 알 수 없는 트리거, "
            + "`[?]` 수신자 타입에 맞는 대상을 고르지 못해 상속 메서드의 구문(또는 리플렉션 호출의 대상)을 전부 따라간 줄, "
            + "`[reflect]` 리플렉션으로 추론한 호출, `[before]` 요청으로 진입한 핸들러보다 먼저 실행되는 `@ModelAttribute` 메서드, "
            + "`[redirect]`·`[forward]` 다른 엔드포인트의 핸들러로 넘어감, `[route]` 화면 안의 이동(클라이언트 라우트), "
            + "`[layout]` 레이아웃(데코레이터, tiles template·조각: 펼치지 않는다), "
            + "`[ambiguous n]` 오버로드를 가리지 못해 후보 n개로 이은 호출, 후보 엔드포인트 n개와 맞은 요청, 가리지 못한 동적 데코레이터의 "
            + "후보 레이아웃 n개(아래 줄이 후보다. n은 2 이상). "
            + "구문 뒤의 `(다른 방언 …에서 보충)`은 선택한 방언의 매퍼 파일에 없어 다른 방언 파일의 정의를 쓴 구문이다. "
            + "추론 줄 뒤의 `(접두 일치)`는 구문 ID가 접두만 맞아 이은 구문, `(인자 구문)`은 구문 ID를 인자로 받는 메서드에 넘긴 문자열로 이은 구문, "
            + "`(동적 뷰 이름 후보)`는 동적 조각이 든 뷰 이름으로 찾은 화면이다(프로퍼티 값이나 모듈 이름으로 좁힌 것은 "
            + "`(동적 뷰 이름: 프로퍼티 값으로 정함)`, `(동적 뷰 이름: 모듈 이름으로 정함)`). 요청 이름의 `POST|PUT`은 호출에 따라 메서드가 달라지는 요청, "
            + "`(자기 페이지로 제출)`은 action이 없는 폼이다(맞는 엔드포인트가 없으면 `? [unresolved] POST <경로> (자기 페이지로 제출: …)`로 찾던 주소와 "
            + "까닭을 적고, 후보가 여럿이면 `[ambiguous n] 후보 엔드포인트` 아래에 묶는다). "
            + "구문 뒤의 `(다른 방언 파일: …)`은 고른 방언에 그 테이블의 적재가 없어 남긴 다른 방언 파일의 데이터 적재 묶음이다. "
            + "테이블 뒤의 `(CRUD)`는 접근 종류다. 자식은 호출 지점 순이다.\n";

    private static final String VIEW_NOTE = "컨트롤러가 그리는 JSP와 redirect·forward로 넘어가는 핸들러는 한 줄만 적고 그 아래"
            + "(그 화면에서 나가는 요청, 넘어간 핸들러의 처리)는 펼치지 않는다(`export.calltree.expandViews=true`로 켠다).\n";

    private static void writeCallTrees(GraphModel graph, Path dir, Options opts, boolean includeDataLoad) throws IOException {
        int depth = opts.number("export.calltree.depth", DEFAULT_CALLTREE_DEPTH, 0);
        int maxNodes = opts.number("export.calltree.maxNodes", 500, 1);
        int splitOver = opts.number("export.calltree.splitOver", DEFAULT_CALLTREE_SPLIT_OVER, 0);
        CallTreeOptions options = CallTreeOptions.builder().maxDepth(depth).maxNodes(maxNodes)
                .hideExternal(opts.flag("export.calltree.hideExternal", true))
                .hideAccessors(opts.flag("export.calltree.hideAccessors", true))
                .expandRenderedViews(opts.flag("export.calltree.expandViews", false)).includeDataLoad(includeDataLoad).build();
        String notes = notes(options);

        List<String> endpoints = ids(graph, NodeKind.HTTP_ENDPOINT);
        if (!endpoints.isEmpty()) {
            String limits = "깊이 " + depth + ", 트리당 노드 " + maxNodes + "개까지.";
            if (splitOver > 0 && endpoints.size() > splitOver) {
                writeEndpointsByController(graph, dir, endpoints, options, limits, notes, splitOver);
            } else {
                StringBuilder out = new StringBuilder("# 엔드포인트 콜트리\n\n");
                out.append("엔드포인트 ").append(endpoints.size()).append("개. ").append(limits).append("\n\n").append(LEGEND).append(notes);
                for (String id : endpoints) {
                    section(out, CallTrees.build(graph, id, options), NodeLabels.label(graph, id));
                }
                write(dir.resolve("endpoints.md"), out.toString());
            }
        }

        writeFilterTrees(graph, dir, options, "깊이 " + depth + ", 트리당 노드 " + maxNodes + "개까지.", notes);

        List<String> jobs = ids(graph, NodeKind.BATCH_JOB);
        // 어느 잡에도 들어 있지 않은 스텝(잡을 모르는 스텝)
        List<String> looseSteps = new ArrayList<>();
        for (String step : ids(graph, NodeKind.BATCH_STEP)) {
            boolean contained = false;
            for (Edge e : graph.incoming(step, EdgeKind.CONTAINS)) {
                contained |= graph.node(e.from()).map(n -> n.kind() == NodeKind.BATCH_JOB).orElse(false);
            }
            if (!contained) {
                looseSteps.add(step);
            }
        }
        if (jobs.isEmpty() && looseSteps.isEmpty()) {
            return;
        }
        StringBuilder out = new StringBuilder("# 배치 잡 콜트리\n\n");
        out.append("잡 ").append(jobs.size()).append("개");
        if (!looseSteps.isEmpty()) {
            out.append(", 잡을 모르는 스텝 ").append(looseSteps.size()).append("개");
        }
        out.append(". 깊이 ").append(depth).append(", 트리당 노드 ").append(maxNodes).append("개까지.\n\n").append(LEGEND).append(notes);
        for (String id : jobs) {
            section(out, CallTrees.build(graph, id, options), NodeLabels.label(graph, id));
        }
        // 잡을 모르는 스텝도 NEXT 흐름 순서로 낸다.
        for (String id : BatchFlow.order(graph, looseSteps)) {
            section(out, CallTrees.build(graph, id, options), "(잡 미상) " + NodeLabels.label(graph, id));
        }
        write(dir.resolve("batch-jobs.md"), out.toString());
    }

    /** 필터·인터셉터의 진입 메서드 이름. 컨테이너가 요청마다 부르는 것만(초기화·종료는 뺀다). */
    private static final Set<String> FILTER_ENTRY_METHODS = Set.of("doFilter", "doFilterInternal", "preHandle", "postHandle",
            "afterCompletion", "afterConcurrentHandlingStarted");

    /**
     * 필터·인터셉터({@code JAVA_TYPE}의 {@code stereotype}이 {@code filter}·{@code interceptor})의 진입 메서드에서 시작하는 콜트리.
     * 엔드포인트의 트리에는 나오지 않는 요청 전·후 처리(로그인 검사의 redirect 등)를 보인다. 대상이 없으면 파일을 쓰지 않는다.
     */
    private static void writeFilterTrees(GraphModel graph, Path dir, CallTreeOptions options, String limits, String notes)
            throws IOException {
        List<String> entries = new ArrayList<>();
        Set<String> types = new TreeSet<>();
        for (Node type : graph.nodes(NodeKind.JAVA_TYPE)) {
            String stereotype = type.attrString("stereotype");
            if (!"filter".equals(stereotype) && !"interceptor".equals(stereotype)) {
                continue;
            }
            for (Edge e : graph.outgoing(type.id(), EdgeKind.CONTAINS)) {
                Node method = graph.node(e.to()).orElse(null);
                if (method != null && method.kind() == NodeKind.JAVA_METHOD && FILTER_ENTRY_METHODS.contains(method.name())) {
                    entries.add(method.id());
                    types.add(type.id());
                }
            }
        }
        if (entries.isEmpty()) {
            return;
        }
        entries.sort(Comparator.naturalOrder());
        StringBuilder out = new StringBuilder("# 필터·인터셉터 콜트리\n\n");
        out.append("필터·인터셉터 ").append(types.size()).append("개, 진입 메서드 ").append(entries.size()).append("개. ").append(limits)
                .append(" 요청마다 컨테이너가 부르는 메서드(`doFilter`, `preHandle` 등)에서 시작한다.\n\n").append(LEGEND).append(notes);
        for (String id : entries) {
            section(out, CallTrees.build(graph, id, options), NodeLabels.label(graph, id));
        }
        write(dir.resolve("filters.md"), out.toString());
    }

    /** 표기 설명 아래에 붙이는, 접은 줄에 대한 안내. */
    private static String notes(CallTreeOptions options) {
        StringBuilder out = new StringBuilder();
        if (!options.expandRenderedViews()) {
            out.append('\n').append(VIEW_NOTE);
        }
        if (options.hideAccessors()) {
            out.append("\n접근자 호출(Lombok이 만들 멤버, 같은 타입에 대응하는 필드가 있고 다른 호출이 없는 `getXxx`/`isXxx`/`setXxx`)과 "
                    + "부수 효과 없는 생성자(다른 메서드를 부르지 않는 `<init>`), MyBatis Generator의 `*Example`·`*Example$Criteria` 빌더 호출은 접었다"
                    + "(`export.calltree.hideAccessors=false`로 편다).\n");
        }
        if (options.hideExternal()) {
            out.append("\n소스가 없는 메서드(라이브러리, JDK)의 호출은 접었다(`export.calltree.hideExternal=false`로 편다).\n");
        }
        return out.toString();
    }

    /**
     * 엔드포인트가 많을 때: 컨트롤러(핸들러 메서드를 가진 타입)별 {@code endpoints/<이름>.md}에 트리를 쓰고,
     * {@code endpoints.md}는 엔드포인트 → 파일, 닿는 테이블의 색인으로 쓴다. 핸들러를 모르는 엔드포인트는 URL의 첫 조각으로 묶는다.
     */
    private static void writeEndpointsByController(GraphModel graph, Path dir, List<String> endpoints, CallTreeOptions options,
            String limits, String notes, int splitOver) throws IOException {
        // 묶음 키(컨트롤러의 타입 ID 또는 "/첫 조각") → 엔드포인트
        Map<String, List<String>> groups = new TreeMap<>();
        for (String endpoint : endpoints) {
            groups.computeIfAbsent(controllerOf(graph, endpoint), k -> new ArrayList<>()).add(endpoint);
        }
        // 파일 이름: 컨트롤러의 단순 이름. 단순 이름이 겹치면 전체 이름을 쓴다.
        Map<String, Integer> simpleNames = new TreeMap<>();
        groups.keySet().forEach(key -> simpleNames.merge(groupName(key, false).toLowerCase(Locale.ROOT), 1, Integer::sum));
        Map<String, String> fileNames = new LinkedHashMap<>();
        Set<String> used = new TreeSet<>();
        for (String key : groups.keySet()) {
            String base = Texts.fileSafe(groupName(key, simpleNames.get(groupName(key, false).toLowerCase(Locale.ROOT)) > 1));
            String name = base;
            for (int n = 2; !used.add(name.toLowerCase(Locale.ROOT)); n++) {
                name = base + "_" + n;
            }
            fileNames.put(key, name);
        }

        StringBuilder index = new StringBuilder("# 엔드포인트 콜트리\n\n");
        index.append("엔드포인트 ").append(endpoints.size()).append("개, 컨트롤러 ").append(groups.size()).append("개. ").append(limits).append('\n');
        index.append("\n엔드포인트가 ").append(splitOver).append("개(`export.calltree.splitOver`)를 넘어 트리를 컨트롤러별 파일(`endpoints/<컨트롤러>.md`)로 나눴다. ")
                .append("이 파일은 색인이다. \"닿는 테이블\"은 그 엔드포인트의 트리에 나온 테이블과 접근 종류다(트리가 잘렸으면 일부만 나온다).\n");
        Map<String, String> rows = new TreeMap<>();
        for (Map.Entry<String, List<String>> group : groups.entrySet()) {
            String fileName = fileNames.get(group.getKey());
            StringBuilder out = new StringBuilder("# 엔드포인트 콜트리: ").append(fileName).append("\n\n");
            if (group.getKey().startsWith("java:type:")) {
                out.append("컨트롤러 ").append(Texts.mdCode(Ids.remainder(group.getKey()))).append(", ");
            }
            out.append("엔드포인트 ").append(group.getValue().size()).append("개. ").append(limits).append(" 전체 목록은 [endpoints.md](../endpoints.md).\n\n")
                    .append(LEGEND).append(notes);
            for (String id : group.getValue()) {
                CallTree tree = CallTrees.build(graph, id, options);
                section(out, tree, NodeLabels.label(graph, id));
                rows.put(id, "| " + Texts.mdCode(NodeLabels.label(graph, id)) + " | [" + fileName + "](endpoints/" + fileName + ".md) | "
                        + Texts.mdCell(touchedTables(tree)) + " |\n");
            }
            write(dir.resolve("endpoints").resolve(fileName + ".md"), out.toString());
        }
        index.append("\n| 엔드포인트 | 파일 | 닿는 테이블 |\n|---|---|---|\n");
        rows.values().forEach(index::append);
        write(dir.resolve("endpoints.md"), index.toString());
    }

    /** 엔드포인트의 묶음 키: 핸들러 메서드(ROUTES_TO, ID가 앞선 것)를 가진 타입의 ID, 핸들러를 모르면 "/" + URL의 첫 조각. */
    private static String controllerOf(GraphModel graph, String endpointId) {
        String handler = null;
        for (Edge e : graph.outgoing(endpointId, EdgeKind.ROUTES_TO)) {
            if (e.to().startsWith("java:method:") && (handler == null || e.to().compareTo(handler) < 0)) {
                handler = e.to();
            }
        }
        if (handler != null) {
            String rest = Ids.remainder(handler);
            int hash = rest.indexOf('#');
            return Ids.javaType(hash < 0 ? rest : rest.substring(0, hash));
        }
        String path = graph.node(endpointId).map(n -> n.attrString("path")).orElse(null);
        if (Texts.isBlank(path)) {
            String rest = Ids.remainder(endpointId);
            path = rest.substring(rest.indexOf(' ') + 1);
        }
        for (String segment : path.split("/")) {
            if (!segment.isBlank() && !segment.startsWith("{") && !segment.startsWith("$")) {
                return "/" + segment;
            }
        }
        return "/";
    }

    private static String groupName(String key, boolean qualified) {
        if (key.startsWith("java:type:")) {
            String binaryName = Ids.remainder(key);
            return qualified ? binaryName : Texts.simpleName(binaryName);
        }
        return key.length() > 1 ? key.substring(1) : "root";
    }

    /** 트리에 나온 테이블·뷰와 접근 종류: {@code TB_ORDER(RU), TB_CODE(R)}. 이름순, 8개까지. */
    private static String touchedTables(CallTree tree) {
        Map<String, Set<Character>> access = new TreeMap<>();
        collectTables(tree.root(), access);
        List<String> cells = new ArrayList<>();
        for (Map.Entry<String, Set<Character>> e : access.entrySet()) {
            StringBuilder letters = new StringBuilder();
            for (char c : "CRUDW".toCharArray()) {
                if (e.getValue().contains(c)) {
                    letters.append(c);
                }
            }
            cells.add(e.getKey() + (letters.isEmpty() ? "" : "(" + letters + ")"));
        }
        int shown = Math.min(8, cells.size());
        return String.join(", ", cells.subList(0, shown)) + (shown < cells.size() ? " 외 " + (cells.size() - shown) + "개" : "");
    }

    private static void collectTables(CallTreeNode node, Map<String, Set<Character>> access) {
        if (node.kind() == NodeKind.DB_TABLE || node.kind() == NodeKind.DB_VIEW) {
            Set<Character> letters = access.computeIfAbsent(node.label(), k -> new TreeSet<>());
            if ((node.via() == EdgeKind.READS || node.via() == EdgeKind.WRITES) && node.detail() != null) {
                node.detail().chars().forEach(c -> letters.add((char) c));
            }
        }
        node.children().forEach(child -> collectTables(child, access));
    }

    private static void section(StringBuilder out, CallTree tree, String title) {
        out.append("\n## ").append(Texts.oneLine(title)).append("\n\n```text\n").append(CallTrees.toText(tree).replace("```", "'''"))
                .append("```\n");
        if (tree.depthLimited()) {
            out.append('\n').append(DEPTH_NOTE).append('\n');
        }
    }

    private static List<String> ids(GraphModel graph, NodeKind kind) {
        List<String> ids = new ArrayList<>();
        for (Node node : graph.nodes(kind)) {
            ids.add(node.id());
        }
        ids.sort(Comparator.naturalOrder());
        return ids;
    }

    // ---------------------------------------------------------------- 보조

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    /** 옵션 해석. 잘못된 값은 키마다 한 번 INVALID_OPTION 진단을 남기고 기본값을 쓴다. */
    private static final class Options {
        private final GraphModel graph;
        private final Map<String, String> values;
        private final Set<String> reported = new TreeSet<>();

        Options(GraphModel graph, Map<String, String> values) {
            this.graph = graph;
            this.values = values == null ? Map.of() : new TreeMap<>(values);
        }

        private String raw(String key) {
            String value = values.get(key);
            return value == null || value.isBlank() ? null : value.strip();
        }

        boolean flag(String key, boolean defaultValue) {
            String value = raw(key);
            if (value == null) {
                return defaultValue;
            }
            switch (value.toLowerCase(Locale.ROOT)) {
                case "true", "yes", "on", "1":
                    return true;
                case "false", "no", "off", "0":
                    return false;
                default:
                    invalid(key, value, String.valueOf(defaultValue));
                    return defaultValue;
            }
        }

        int number(String key, int defaultValue, int min) {
            String value = raw(key);
            if (value == null) {
                return defaultValue;
            }
            try {
                int parsed = Integer.parseInt(value);
                if (parsed >= min) {
                    return parsed;
                }
            } catch (NumberFormatException e) {
                // 아래에서 진단을 남긴다
            }
            invalid(key, value, String.valueOf(defaultValue));
            return defaultValue;
        }

        <E extends Enum<E>> E choice(String key, Class<E> type, E defaultValue) {
            String value = raw(key);
            if (value == null) {
                return defaultValue;
            }
            for (E constant : type.getEnumConstants()) {
                if (constant.name().equalsIgnoreCase(value)) {
                    return constant;
                }
            }
            invalid(key, value, defaultValue.name().toLowerCase(Locale.ROOT));
            return defaultValue;
        }

        private void invalid(String key, String value, String fallback) {
            if (reported.add(key)) {
                graph.report(Diagnostic.warn(DiagnosticCodes.INVALID_OPTION,
                        "출력 옵션 " + key + "의 값 '" + value + "'을 해석하지 못해 기본값 " + fallback + "을 쓴다", null));
            }
        }
    }
}
