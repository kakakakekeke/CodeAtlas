package io.codeatlas.export.calltree;

import io.codeatlas.export.calltree.CrudMatrix.Subject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 표준 산출물 {@code crud/README.md}: CRUD 매트릭스 CSV의 표기 설명. CSV에는 머리 주석을 둘 수 없어서(엑셀이 첫 줄을 머리글로 읽는다)
 * 같은 폴더에 따로 쓴다. 같은 입력이면 바이트까지 같다.
 */
public final class CrudReadme {

    private CrudReadme() {
    }

    /** 주체별 파일 이름(표준 산출물의 이름). */
    public static String fileName(Subject subject) {
        return switch (subject) {
            case STATEMENT -> "table-statement.csv";
            case METHOD -> "table-method.csv";
            case ENDPOINT -> "table-endpoint.csv";
            case ROUTINE -> "table-routine.csv";
        };
    }

    /**
     * @param written 실제로 쓴 매트릭스의 주체(행이 없는 매트릭스는 파일을 쓰지 않는다)
     * @param includeDataLoad 데이터 적재 구문을 넣었는지({@code export.includeDataLoad})
     * @param depth 걸음 수 상한({@code export.crud.depth})
     */
    public static String render(Collection<Subject> written, boolean includeDataLoad, int depth) {
        StringBuilder out = new StringBuilder("# CRUD 매트릭스\n\n");
        out.append("행은 주체, 열은 테이블(뷰 포함)이고 칸은 그 주체가 그 테이블에 하는 접근이다. 접근이 없는 칸은 비어 있다. ")
                .append("둘째 열 `위치`는 주체의 정의 위치(`파일:줄`)다. 행은 노드 ID 순, 열은 테이블 이름순이다.\n");
        out.append("\n## 파일\n\n| 파일 | 행(주체) | 칸에 드는 접근 |\n|---|---|---|\n");
        for (Subject subject : Subject.values()) {
            if (!written.contains(subject)) {
                continue;
            }
            out.append("| `").append(fileName(subject)).append("` | ").append(switch (subject) {
                case STATEMENT -> "SQL 구문 | 그 구문의 SQL이 직접 하는 접근과, 구문이 부르는 DB 루틴의 접근";
                case METHOD -> "구문을 직접 실행(`EXECUTES`)하는 Java 메서드 | 실행하는 구문의 접근과, 그 구문이 부르는 DB 루틴의 접근";
                case ENDPOINT -> "엔드포인트 | 핸들러에서 호출 사슬(콜트리와 같은 규칙)로 닿는 구문의 접근. forward로 넘어간 핸들러의 접근은 넣고(같은 요청), "
                        + "redirect로 넘어간 핸들러의 접근은 넣지 않는다(브라우저가 새로 보내는 별개의 요청). 화면(JSP)과 레이아웃 너머도 따라가지 않는다";
                case ROUTINE -> "DB 루틴(프로시저·함수·트리거) | 본문의 구문이 하는 접근과, 부르는 루틴의 접근";
            }).append(" |\n");
        }
        out.append("\n## 칸의 표기\n\n| 표기 | 뜻 |\n|---|---|\n");
        out.append("| `C` `R` `U` `D` | 삽입, 조회, 수정, 삭제. `MERGE`는 `CU`다. `R`은 테이블을 실제로 읽을 때(테이블 수준 `READS`)만 붙는다: ")
                .append("`DELETE FROM T WHERE x = ?`는 `D`다 |\n");
        out.append("| `W` | 종류를 알 수 없는 변경 |\n");
        out.append("| 대문자 | 주체가 직접 하는 접근(구문은 자기 SQL, 메서드는 직접 실행한 구문, 루틴은 자기 본문, 엔드포인트는 호출 사슬로 닿은 구문) |\n");
        out.append("| 소문자 | DB 루틴(프로시저·함수·트리거)을 거쳐서만 닿는 간접 접근. 같은 글자가 직접·간접 둘 다면 대문자만 적는다. ")
                .append("예: `Ru`는 직접 읽고 루틴을 거쳐 수정한다는 뜻이다 |\n");
        out.append("| 끝의 `?` | 추론한 관계를 거쳐서만 닿은 칸. 예: `R?`, `ru?`. 그 테이블에 닿는 확정 경로가 하나라도 있으면 붙지 않는다(아래 참고) |\n");
        out.append("| `(동적)` 열 | 대상 테이블을 정적으로 알 수 없는 구문(동적 SQL)의 접근. 어느 테이블에도 넣을 수 없어 마지막 열에 따로 모은다")
                .append("(그런 구문에 닿는 주체가 있을 때만 생기는 열). 구문 목록은 `report.md`의 \"대상 테이블을 정적으로 알 수 없는 구문\"에 있다 |\n");

        out.append("\n## `?`가 붙는 경우\n\n");
        if (written.contains(Subject.ENDPOINT)) {
            out.append("- `table-endpoint.csv`: 엔드포인트에서 그 테이블까지 가는 길마다 추론 걸음이 끼어 있을 때다. 추론 걸음은 콜트리(`calltree/`)에서 ")
                    .append("`~`(추론한 관계: 오버로드를 가리지 못해 후보 전부로 이은 호출 `ambiguous`, 구문 ID 접두 일치 `prefixMatch`, 인자로 넘긴 구문 ID `viaArgument`, 동적 뷰 이름의 후보 `dynamicSegment`(프로퍼티 값이나 모듈 이름으로 좁힌 것 `narrowedBy` 포함), 추론한 재정의 등), ")
                    .append("`[?]`(수신자 타입에 맞는 대상을 고르지 못해 전부 따라감), `[trigger?]`(그 쓰기에 일어나는지 알 수 없는 트리거)가 붙는 줄이다.\n");
            out.append("  - 추론으로 치지 않는 것: `[before]`(요청 전 처리. 스프링이 늘 부른다), `[forward]`(같은 요청 안의 서버 내부 이동. 대상을 후보 여럿 가운데서 ")
                    .append("맞췄거나 다른 모듈의 핸들러로 맞춘 forward는 추론이다), 수신자 타입이 대상 하나를 고른 상속 매퍼 메서드의 구문과 리플렉션 호출.\n");
            out.append("  - 콜트리에 표시가 없어도 추론으로 치는 것: 상속 매퍼 메서드의 구문을 수신자 타입을 몰라 전부 따라갔거나, 수신자 타입이 매퍼 여럿과 맞은 경우")
                    .append("(어느 구문이 실행되는지 정해지지 않았다).\n");
        }
        if (written.contains(Subject.METHOD)) {
            out.append("- `table-method.csv`: 메서드에서 그 구문으로 가는 `EXECUTES` 자체가 추론일 때다(상위 인터페이스에서 물려받은 매퍼 메서드가 ")
                    .append("하위 매퍼마다 실행하는 구문, 구문 ID 접두 일치 등).\n");
        }
        List<String> unmarked = new ArrayList<>();
        for (Subject subject : List.of(Subject.STATEMENT, Subject.ROUTINE)) {
            if (written.contains(subject)) {
                unmarked.add("`" + fileName(subject) + "`");
            }
        }
        if (!unmarked.isEmpty()) {
            out.append("- ").append(String.join(", ", unmarked)).append(": 붙지 않는다.\n");
        }
        out.append("- 어느 관계가 추론인지는 `codeatlas calltree <엔드포인트>`의 줄 표시나 `codeatlas impact <테이블>`의 `~` 항목(`추론:` 뒤에 그 엣지와 까닭)으로 본다.\n");

        out.append("\n## 넣지 않는 것\n\n");
        out.append(includeDataLoad
                ? "- 데이터 적재 구문(SQL 스크립트의 리터럴 INSERT를 파일·테이블 단위로 묶은 구문)도 넣었다(`export.includeDataLoad=true`).\n"
                : "- 데이터 적재 구문(SQL 스크립트의 리터럴 INSERT를 파일·테이블 단위로 묶은 구문)은 넣지 않는다. 애플리케이션이 실행하는 구문이 아니라서다. "
                        + "`export.includeDataLoad=true`로 넣는다. 요약은 `report.md`의 \"데이터 적재 스크립트\"에 있다.\n");
        out.append("- 접근이 하나도 없는 주체와 테이블은 행·열이 되지 않는다. 행이 없는 매트릭스는 파일을 쓰지 않는다.\n");
        out.append("- 걸음 수 상한은 ").append(depth).append("이다(`export.crud.depth`: 엔드포인트에서 구문까지, 구문·메서드·루틴에서 루틴 호출까지).\n");
        return out.toString();
    }
}
