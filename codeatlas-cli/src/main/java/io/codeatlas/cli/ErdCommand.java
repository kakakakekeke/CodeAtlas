package io.codeatlas.cli;

import io.codeatlas.export.erd.Erd;
import io.codeatlas.export.erd.ErdFormat;
import io.codeatlas.export.erd.ErdModel;
import io.codeatlas.export.erd.ErdOptions;
import io.codeatlas.export.erd.ErdOptions.ColumnLevel;
import io.codeatlas.export.erd.TableSpecRenderer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@code erd} 명령(설계 4.5): 저장된 그래프에서 ERD(F-013)나 테이블 명세를 낸다. */
final class ErdCommand extends QueryCommand {

    private static final Map<String, ErdFormat> FORMATS = new LinkedHashMap<>();
    private static final Map<String, ColumnLevel> COLUMNS = new LinkedHashMap<>();

    static {
        FORMATS.put("mermaid", ErdFormat.MERMAID);
        FORMATS.put("plantuml", ErdFormat.PLANTUML);
        FORMATS.put("dbml", ErdFormat.DBML);
        COLUMNS.put("all", ColumnLevel.ALL);
        COLUMNS.put("keys", ColumnLevel.KEYS);
        COLUMNS.put("none", ColumnLevel.NONE);
    }

    @Override
    public String name() {
        return "erd";
    }

    @Override
    public String synopsis() {
        return """
                codeatlas erd [<graph.json 또는 출력 폴더>] [--table <이름>]... [--depth <n>] [--pattern <glob>]...
                              [--no-inferred] [--min-evidence <n>] [--views] [--columns all|keys|none] [--no-comments]
                              [--format mermaid|plantuml|dbml] [--spec] [--graph <경로>] [--out <파일>]""";
    }

    @Override
    public String help() {
        return """
                erd: 저장된 그래프의 테이블, 컬럼, 관계(DDL 외래 키와 JOIN에서 추론한 관계)로 ERD를 낸다.
                  --table <이름>       시작 테이블(이름 또는 노드 ID). 여러 번 줄 수 있다. 주지 않으면 전체 테이블이다.
                  --depth <n>          --table에서 관계를 따라 넓힐 깊이. 기본값 1, 0이면 시작 테이블만.
                  --pattern <glob>     이름이 맞는 테이블을 더한다(*, ?, 대소문자 무시). 여러 번 줄 수 있다. 예: 'ORD_*'
                  --no-inferred        추론 관계를 빼고 DDL 외래 키만 그린다.
                  --min-evidence <n>   근거 수가 n 이상인 추론 관계만 그린다. 기본값 1
                  --views              뷰도 넣는다.
                  --columns <수준>     all(기본값), keys(PK·FK와 관계에 쓰인 컬럼만), none(테이블만)
                  --no-comments        테이블·컬럼 주석을 뺀다.
                  --format <형식>      mermaid(기본값), plantuml, dbml
                  --spec               ERD 대신 테이블 명세(Markdown: 컬럼 표, 키, 관계, 참조하는 구문 수)를 낸다. 범위 옵션(--table,
                                       --depth, --pattern, --views)과 관계 옵션(--no-inferred, --min-evidence)은 ERD와 같은 규칙으로
                                       적용한다. --columns, --no-comments, --format과는 같이 쓸 수 없다.
                """ + COMMON_HELP;
    }

    @Override
    List<String> flags() {
        return List.of("--no-inferred", "--views", "--no-comments", "--spec");
    }

    @Override
    List<String> valueOptions() {
        return List.of("--table", "--depth", "--pattern", "--min-evidence", "--columns", "--format");
    }

    /** {@code --spec}(테이블 명세)에 뜻이 없는 표시·형식 옵션. */
    private static final List<String> NOT_FOR_SPEC = List.of("--columns", "--no-comments", "--format");

    @Override
    Query prepare(CommandLine line, String query) {
        boolean spec = line.has("--spec");
        if (spec) {
            for (String option : NOT_FOR_SPEC) {
                if (line.has(option)) {
                    throw new UsageException("--spec(테이블 명세)은 " + option + " 옵션과 같이 쓸 수 없다");
                }
            }
        }
        ErdFormat format = line.choice("--format", FORMATS, ErdFormat.MERMAID);
        boolean views = line.has("--views");
        ErdOptions options = ErdOptions.builder()
                .tables(line.values("--table"))
                .neighborDepth(line.number("--depth", 1, 0))
                .patterns(line.values("--pattern"))
                .includeInferred(!line.has("--no-inferred"))
                .minEvidence(line.number("--min-evidence", 1, 0))
                .includeViews(views)
                .columnLevel(line.choice("--columns", COLUMNS, ColumnLevel.ALL))
                .showComments(!line.has("--no-comments"))
                .build();
        return (graph, err) -> {
            ErdModel model = Erd.model(graph, options);
            // 찾지 못한 시작 테이블은 조회 API가 모델과 함께 돌려준다.
            String notFound = model.unresolvedNames().isEmpty() ? "" : "테이블을 찾지 못했다: " + String.join(", ", model.unresolvedNames())
                    + (model.excludedViewNames().isEmpty() ? "" : " (뷰는 --views를 줘야 범위에 든다)");
            if (model.entities().isEmpty()) {
                if (!notFound.isEmpty()) {
                    throw new CommandFailure(notFound + ". 이름은 'codeatlas find <검색어> --kind DB_TABLE'로 찾는다");
                }
                throw new CommandFailure(options.wholeSchema() ? noTables(!views) : "범위에 드는 테이블이 없다: --pattern "
                        + String.join(", ", options.patterns()));
            }
            if (!notFound.isEmpty()) {
                err.println("경고: " + notFound);
            }
            return spec ? TableSpecRenderer.render(graph, options) : Erd.render(model, options, format);
        };
    }

    private static String noTables(boolean viewsExcluded) {
        return "그래프에 테이블이 없다. DDL(--ddl)이나 SQL을 분석한 그래프인지 확인한다"
                + (viewsExcluded ? " (뷰만 있으면 --views를 준다)" : "");
    }
}
