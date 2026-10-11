package io.codeatlas.mybatis;

import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.model.DiagnosticCodes;
import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.spi.AnalysisContext;
import io.codeatlas.core.spi.EmbeddedSource;
import io.codeatlas.core.testing.TestProject;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 현장에서 흔한 매퍼 작성 패턴을 본뜬 픽스처(src/test/resources/fixtures/field)의 전개 결과. */
class FieldPatternsTest {

    private static final String BOARD = "com.field.board.mapper.BoardMapper";
    private static final String BATCH = "fieldBatchDao";

    private static AnalysisContext ctx;

    @BeforeAll
    static void analyze() {
        try (TestProject project = TestProject.at(Path.of("src/test/resources/fixtures/field"))) {
            ctx = Mb.run(project);
        }
    }

    @Test
    @DisplayName("F-009-AC24 페이징 공통 조각 include + 검색 조건 if 묶음 + foreach IN + choose 정렬")
    void pagedSearchList() {
        String head = "SELECT * FROM ( SELECT ROWNUM AS RNUM, PAGE_T.* FROM ("
                + " SELECT B.BOARD_ID, B.TITLE, B.WRITER_ID, U.USER_NM, B.REG_DT"
                + " FROM TB_BOARD B LEFT OUTER JOIN TB_USER U ON U.USER_ID = B.WRITER_ID"
                + " WHERE B.DEL_YN = 'N'"
                + " AND B.TITLE LIKE '%' || ? || '%'"
                + " AND B.WRITER_ID = ?"
                + " AND B.REG_DT BETWEEN TO_DATE(?, 'YYYYMMDD') AND TO_DATE(?, 'YYYYMMDD') + 1"
                + " AND B.CATEGORY_CD IN ( ? )"
                + " AND EXISTS (SELECT 1 FROM TB_BOARD_FILE F WHERE F.BOARD_ID = B.BOARD_ID)"
                + " ORDER BY ";
        String tail = " ) PAGE_T WHERE ROWNUM <= ? ) WHERE RNUM > ?";

        assertThat(Mb.texts(ctx, BOARD, "selectBoardList")).containsExactly(
                head + "B.REG_DT DESC" + tail,
                head + "B.VIEW_CNT DESC, B.BOARD_ID DESC" + tail,
                head + "B.BOARD_ID DESC" + tail);
        Node node = Mb.stmt(ctx, BOARD, "selectBoardList");
        assertThat(node.attr("dynamic")).isEqualTo(true);
        assertThat(node.attr("variants")).isEqualTo(3);
        assertThat(Mb.edges(ctx, node.id(), EdgeKind.INCLUDES)).extracting(Edge::to).containsExactlyInAnyOrder(
                Ids.mybatisFragment("field.common", "pagingHeader"),
                Ids.mybatisFragment("field.common", "pagingFooter"),
                Ids.mybatisFragment(BOARD, "boardColumns"),
                Ids.mybatisFragment(BOARD, "searchCondition"));
    }

    @Test
    @DisplayName("F-009-AC24 같은 검색 조건 조각을 쓰는 건수 조회")
    void countWithSharedCondition() {
        assertThat(Mb.text(ctx, BOARD, "selectBoardCount")).isEqualTo(
                "SELECT COUNT(*) FROM TB_BOARD B WHERE B.DEL_YN = 'N'"
                        + " AND B.TITLE LIKE '%' || ? || '%'"
                        + " AND B.WRITER_ID = ?"
                        + " AND B.REG_DT BETWEEN TO_DATE(?, 'YYYYMMDD') AND TO_DATE(?, 'YYYYMMDD') + 1"
                        + " AND B.CATEGORY_CD IN ( ? )"
                        + " AND EXISTS (SELECT 1 FROM TB_BOARD_FILE F WHERE F.BOARD_ID = B.BOARD_ID)");
    }

    @Test
    @DisplayName("F-009-AC24 trim으로 만든 INSERT 컬럼 목록 + selectKey 시퀀스")
    void insertWithTrimAndSelectKey() {
        assertThat(Mb.text(ctx, BOARD, "insertBoard")).isEqualTo(
                "INSERT INTO TB_BOARD ( BOARD_ID, TITLE, CONTENT, CATEGORY_CD, WRITER_ID, REG_DT )"
                        + " VALUES ( ?, ?, ?, ?, ?, SYSDATE )");
        List<EmbeddedSource> key = Mb.sources(ctx, BOARD, "insertBoard").stream()
                .filter(s -> "true".equals(s.attrs().get("selectKey"))).toList();
        assertThat(key).singleElement()
                .satisfies(s -> assertThat(Mb.norm(s.text())).isEqualTo("SELECT SEQ_BOARD_ID.NEXTVAL FROM DUAL"));
        assertThat(Mb.stmt(ctx, BOARD, "insertBoard").attrString("statementType")).isEqualTo("INSERT");
    }

    @Test
    @DisplayName("F-009-AC24 set + if UPDATE")
    void updateWithSet() {
        assertThat(Mb.text(ctx, BOARD, "updateBoard")).isEqualTo(
                "UPDATE TB_BOARD SET TITLE = ?, CONTENT = ?, CATEGORY_CD = ?, UPD_ID = ?, UPD_DT = SYSDATE WHERE BOARD_ID = ?");
    }

    @Test
    @DisplayName("F-009-AC24 MERGE는 정적 구문이라 위치가 정확하다")
    void merge() {
        EmbeddedSource src = Mb.variants(ctx, BOARD, "mergeViewCount").get(0);
        assertThat(src.exactPositions()).isTrue();
        assertThat(Mb.norm(src.text())).isEqualTo(
                "MERGE INTO TB_BOARD_STAT S USING (SELECT ? AS BOARD_ID FROM DUAL) D ON (S.BOARD_ID = D.BOARD_ID)"
                        + " WHEN MATCHED THEN UPDATE SET S.VIEW_CNT = S.VIEW_CNT + 1"
                        + " WHEN NOT MATCHED THEN INSERT (BOARD_ID, VIEW_CNT) VALUES (D.BOARD_ID, 1)");
        // 태그는 update지만 본문이 MERGE INTO로 시작한다
        assertThat(Mb.stmt(ctx, BOARD, "mergeViewCount").attrString("statementType")).isEqualTo("MERGE");
        assertThat(Mb.stmt(ctx, BOARD, "mergeViewCount").attrString("tag")).isEqualTo("update");
    }

    @Test
    @DisplayName("F-009-AC24 프로시저 호출")
    void procedureCall() {
        assertThat(Mb.text(ctx, BATCH, "callArchive")).isEqualTo("{call PKG_BOARD.ARCHIVE_OLD( ?, ? )}");
        assertThat(Mb.stmt(ctx, BATCH, "callArchive").attrString("statementType")).isEqualTo("CALL");
    }

    @Test
    @DisplayName("F-009-AC24 ${tableName} 동적 테이블")
    void dynamicTable() {
        assertThat(Mb.text(ctx, BATCH, "selectFromDynamicTable")).isEqualTo(
                "SELECT T.* FROM __DYNAMIC__ T WHERE T.BASE_DT = ? AND T.SEQ > ? ORDER BY __DYNAMIC__");
        assertThat(Mb.stmt(ctx, BATCH, "selectFromDynamicTable").attr("dynamic")).isEqualTo(true);
        assertThat(Mb.diags(ctx, DiagnosticCodes.DYNAMIC_SQL))
                .anySatisfy(d -> assertThat(d.message()).contains(BATCH + ".selectFromDynamicTable"));
    }

    @Test
    @DisplayName("F-009-AC24 foreach로 만든 다건 DELETE와 다른 namespace 조각의 where 조건")
    void deleteWithForeach() {
        assertThat(Mb.text(ctx, BATCH, "deleteOldBoards")).isEqualTo(
                "DELETE FROM TB_BOARD WHERE BOARD_ID IN ( ? ) AND DEL_YN = 'Y'");
    }

    @Test
    @DisplayName("F-009-AC24 픽스처 전체에 미해결 참조와 파싱 실패가 없다")
    void noUnexpectedDiagnostics() {
        assertThat(Mb.diags(ctx, DiagnosticCodes.PARSE_FAILED)).isEmpty();
        assertThat(Mb.diags(ctx, DiagnosticCodes.UNRESOLVED_REFERENCE)).isEmpty();
        assertThat(Mb.diags(ctx, DiagnosticCodes.UNSUPPORTED_SYNTAX)).isEmpty();
    }
}
