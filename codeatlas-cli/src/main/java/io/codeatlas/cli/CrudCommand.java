package io.codeatlas.cli;

import io.codeatlas.export.calltree.CrudMatrix;
import io.codeatlas.export.calltree.CrudMatrix.Layout;
import io.codeatlas.export.calltree.CrudMatrix.Subject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@code crud} 명령(설계 4.5): 저장된 그래프에서 CRUD 매트릭스(F-015)를 낸다. */
final class CrudCommand extends QueryCommand {

    private static final Map<String, Subject> SUBJECTS = new LinkedHashMap<>();
    private static final Map<String, Layout> LAYOUTS = new LinkedHashMap<>();
    private static final Map<String, Boolean> FORMATS = new LinkedHashMap<>();

    static {
        SUBJECTS.put("statement", Subject.STATEMENT);
        SUBJECTS.put("method", Subject.METHOD);
        SUBJECTS.put("endpoint", Subject.ENDPOINT);
        SUBJECTS.put("routine", Subject.ROUTINE);
        LAYOUTS.put("matrix", Layout.MATRIX);
        LAYOUTS.put("by-table", Layout.MATRIX_BY_TABLE);
        LAYOUTS.put("list", Layout.LIST);
        FORMATS.put("csv", Boolean.FALSE);
        FORMATS.put("md", Boolean.TRUE);
    }

    @Override
    public String name() {
        return "crud";
    }

    @Override
    public String synopsis() {
        return """
                codeatlas crud [<graph.json 또는 출력 폴더>] [--by statement|method|endpoint|routine] [--layout matrix|by-table|list]
                               [--format csv|md] [--bom] [--depth <n>] [--include-data-load] [--graph <경로>] [--out <파일>]""";
    }

    @Override
    public String help() {
        return """
                crud: 주체가 테이블마다 무엇을 하는지(C, R, U, D)를 모은 CRUD 매트릭스를 낸다. MERGE는 C와 U, 종류를 모르는 쓰기는 W다.
                  --by <주체>          statement(SQL 구문, 기본값), method(구문을 직접 실행하는 메서드),
                                       endpoint(호출 사슬로 구문에 닿는 엔드포인트), routine(프로시저, 함수, 트리거)
                                       --by endpoint: forward로 넘어간 핸들러의 접근은 그 엔드포인트에 포함하고, redirect 대상의
                                       접근은 넣지 않는다(브라우저가 새로 보내는 별개의 요청이다).
                  --layout <모양>      matrix(행이 주체, 열이 테이블. 기본값), by-table(행이 테이블, 열이 주체),
                                       list(한 줄에 테이블, 주체, CRUD, 위치)
                  --format <형식>      csv(기본값), md(Markdown 표)
                  --bom                csv 앞에 UTF-8 BOM을 붙인다(엑셀에서 한글이 깨지지 않게). --out과 같이 쓴다.
                  --depth <n>          따라갈 최대 걸음 수. 기본값 20. --by endpoint에서는 엔드포인트에서 호출을 따라 구문에 닿기까지의
                                       깊이이고, 그 밖의 기준에서는 루틴 호출(구문→루틴, 메서드→루틴, 루틴→루틴이 한 걸음)을 따라가는 걸음 수다.
                  --include-data-load  데이터 적재 구문(스크립트의 리터럴 INSERT를 테이블별로 묶은 것)도 넣는다. 기본은 뺀다.
                  대문자는 주체가 직접 하는 접근, 소문자는 DB 루틴(프로시저, 함수, 트리거)을 거쳐서만 닿는 접근이다.
                  끝의 ?는 추론한 관계를 거쳐서만 닿은 칸이다(예: R?, ru?). 그 테이블에 닿는 확정 경로가 하나라도 있으면 붙지 않는다.
                  추론한 관계에는 콜트리 줄 끝에 (인자 구문), (접두 일치), (동적 뷰 이름 후보)로 적히는 것도 든다: 구문 ID를 인자로 받는
                  메서드에 넘긴 문자열이나 구문 ID의 접두로 이은 구문 실행, 뷰 이름의 동적 자리에 실제 폴더를 넣어 찾은 화면.
                """ + COMMON_HELP;
    }

    @Override
    List<String> flags() {
        return List.of("--bom", "--include-data-load");
    }

    @Override
    List<String> valueOptions() {
        return List.of("--by", "--layout", "--format", "--depth");
    }

    @Override
    Query prepare(CommandLine line, String query) {
        Subject subject = line.choice("--by", SUBJECTS, Subject.STATEMENT);
        Layout layout = line.choice("--layout", LAYOUTS, Layout.MATRIX);
        boolean markdown = line.choice("--format", FORMATS, Boolean.FALSE);
        boolean bom = line.has("--bom");
        if (bom && markdown) {
            throw new UsageException("--bom은 csv 형식에만 쓴다");
        }
        int depth = line.number("--depth", CrudMatrix.DEFAULT_MAX_DEPTH, 1);
        boolean includeDataLoad = line.has("--include-data-load");
        return (graph, err) -> {
            CrudMatrix matrix = CrudMatrix.of(graph, subject, depth, includeDataLoad);
            if (matrix.rows().isEmpty()) {
                throw new CommandFailure("테이블을 읽거나 쓰는 주체(" + subject.title() + ")가 그래프에 없다"
                        + (subject == Subject.ENDPOINT ? " (호출 사슬이 길면 --depth를 늘린다. 지금 " + depth + ")" : ""));
            }
            return markdown ? matrix.toMarkdown(layout) : matrix.toCsv(layout, bom);
        };
    }
}
