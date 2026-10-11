package io.codeatlas.cli;

import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.export.classdiagram.ClassDiagramFormat;
import io.codeatlas.export.classdiagram.ClassDiagramModel;
import io.codeatlas.export.classdiagram.ClassDiagramOptions;
import io.codeatlas.export.classdiagram.ClassDiagramOptions.MemberLevel;
import io.codeatlas.export.classdiagram.ClassDiagrams;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** {@code classdiagram} 명령(설계 4.5): 저장된 그래프에서 클래스 다이어그램(F-014)을 낸다. */
final class ClassDiagramCommand extends QueryCommand {

    private static final Map<String, ClassDiagramFormat> FORMATS = new LinkedHashMap<>();
    private static final Map<String, MemberLevel> MEMBERS = new LinkedHashMap<>();
    private static final Map<String, EdgeKind> RELATIONS = new LinkedHashMap<>();

    static {
        FORMATS.put("mermaid", ClassDiagramFormat.MERMAID);
        FORMATS.put("plantuml", ClassDiagramFormat.PLANTUML);
        MEMBERS.put("all", MemberLevel.ALL);
        MEMBERS.put("public", MemberLevel.PUBLIC);
        MEMBERS.put("none", MemberLevel.NONE);
        RELATIONS.put("extends", EdgeKind.EXTENDS);
        RELATIONS.put("implements", EdgeKind.IMPLEMENTS);
        RELATIONS.put("associates", EdgeKind.ASSOCIATES);
        RELATIONS.put("depends", EdgeKind.DEPENDS_ON);
        RELATIONS.put("injects", EdgeKind.INJECTS);
    }

    @Override
    public String name() {
        return "classdiagram";
    }

    @Override
    public String synopsis() {
        return """
                codeatlas classdiagram [<graph.json 또는 출력 폴더>] [--package <접두>] [--no-subpackages] [--type <이름>]...
                                       [--depth <n>] [--members all|public|none] [--hide-accessors]
                                       [--relations extends,implements,associates,depends,injects] [--external]
                                       [--external-supertypes] [--no-nested] [--anonymous] [--max-types <n>] [--group-by-package]
                                       [--format mermaid|plantuml] [--graph <경로>] [--out <파일>]""";
    }

    @Override
    public String help() {
        return """
                classdiagram: 저장된 그래프의 Java 타입과 관계로 클래스 다이어그램을 낸다. --package와 --type을 둘 다 주면 합친 범위다.
                  --package <접두>     이 패키지(와 하위 패키지)의 타입. 주지 않으면 전체 타입이다.
                  --no-subpackages     --package의 하위 패키지를 뺀다.
                  --type <이름>        시작 타입(단순 이름, 바이너리 이름, 노드 ID). 여러 번 줄 수 있다.
                  --depth <n>          --type에서 관계를 양방향으로 따라 넓힐 깊이. 기본값 1, 0이면 시작 타입만.
                  --members <수준>     all(기본값), public, none(타입만)
                  --hide-accessors     getter, setter를 뺀다.
                  --relations <목록>   그릴 관계. 쉼표로 잇는다. 기본값 extends,implements,associates,depends (injects는 골라야 나온다)
                  --external           소스가 없는 라이브러리 타입도 넣는다.
                  --external-supertypes  범위 안 타입의 라이브러리 상위 타입을 이름만 그린다.
                  --no-nested          중첩 타입(Outer$Inner)을 뺀다.
                  --anonymous          익명·로컬 타입도 넣는다.
                  --max-types <n>      타입 수 상한. 넘으면 멤버를 생략한다. 기본값 60, 0이면 상한이 없다.
                  --group-by-package   패키지별로 묶어 그린다.
                  --format <형식>      mermaid(기본값), plantuml
                """ + COMMON_HELP;
    }

    @Override
    List<String> flags() {
        return List.of("--no-subpackages", "--hide-accessors", "--external", "--external-supertypes", "--no-nested", "--anonymous",
                "--group-by-package");
    }

    @Override
    List<String> valueOptions() {
        return List.of("--package", "--type", "--depth", "--members", "--relations", "--max-types", "--format");
    }

    @Override
    Query prepare(CommandLine line, String query) {
        ClassDiagramFormat format = line.choice("--format", FORMATS, ClassDiagramFormat.MERMAID);
        List<String> types = line.values("--type");
        String packagePrefix = line.value("--package");
        boolean external = line.has("--external");
        boolean nested = !line.has("--no-nested");
        boolean anonymous = line.has("--anonymous");
        ClassDiagramOptions.Builder builder = ClassDiagramOptions.builder()
                .packagePrefix(packagePrefix)
                .includeSubpackages(!line.has("--no-subpackages"))
                .types(types)
                .neighborDepth(line.number("--depth", 1, 0))
                .members(line.choice("--members", MEMBERS, MemberLevel.ALL))
                .hideAccessors(line.has("--hide-accessors"))
                .includeExternal(external)
                .showExternalSupertypes(line.has("--external-supertypes"))
                .includeNested(nested)
                .includeAnonymous(anonymous)
                .maxTypes(line.number("--max-types", 60, 0))
                .groupByPackage(line.has("--group-by-package"));
        if (line.has("--relations")) {
            Set<EdgeKind> kinds = EnumSet.noneOf(EdgeKind.class);
            for (String value : line.values("--relations")) {
                for (String part : value.split(",")) {
                    if (!part.isBlank()) {
                        kinds.add(CommandLine.choice("--relations", part, RELATIONS));
                    }
                }
            }
            builder.relationKinds(kinds);
        }
        ClassDiagramOptions options = builder.build();
        return (graph, err) -> {
            ClassDiagramModel model = ClassDiagrams.model(graph, options);
            // 찾지 못한 시작 타입은 조회 API가 모델과 함께 돌려준다.
            List<String> missing = model.unresolvedNames();
            String notFound = missing.isEmpty() ? "" : "타입을 찾지 못했다: " + String.join(", ", missing);
            if (model.types().isEmpty()) {
                throw new CommandFailure(emptyReason(notFound, packagePrefix, types.isEmpty()));
            }
            if (!notFound.isEmpty()) {
                err.println("경고: " + notFound);
            }
            for (String warning : model.warnings()) {
                err.println("경고: " + warning);
            }
            return ClassDiagrams.render(model, options, format);
        };
    }

    private static String emptyReason(String notFound, String packagePrefix, boolean noTypesGiven) {
        String hint = ". 이름은 'codeatlas find <검색어> --kind JAVA_TYPE'으로 찾는다";
        if (packagePrefix == null && noTypesGiven) {
            return "그래프에 그릴 Java 타입이 없다(라이브러리 타입만 있으면 --external을 준다)";
        }
        StringBuilder reason = new StringBuilder("범위에 드는 타입이 없다");
        if (packagePrefix != null) {
            reason.append(": --package ").append(packagePrefix.isEmpty() ? "''" : packagePrefix);
        }
        if (!notFound.isEmpty()) {
            reason.append(". ").append(notFound);
        }
        return reason + hint;
    }
}
