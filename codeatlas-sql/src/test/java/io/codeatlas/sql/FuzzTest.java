package io.codeatlas.sql;

import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.Node;
import io.codeatlas.core.model.NodeKind;
import io.codeatlas.core.model.SourceLocation;
import io.codeatlas.core.spi.AnalysisContext;
import io.codeatlas.core.spi.EmbeddedSource;
import io.codeatlas.core.testing.TestProject;
import io.codeatlas.sql.parser.DdlParser;
import io.codeatlas.sql.script.RoutineHeader;
import io.codeatlas.sql.script.ScriptSplitter;
import io.codeatlas.sql.script.ScriptUnit;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 퍼즈성 테스트: 스크립트 분할기, DDL 파서, 루틴 헤더, 분석기 전체가 임의로 망가뜨린 입력에 예외 없이 끝나는지 본다. */
class FuzzTest {

    private static final String SCRIPT = """
            --#SET TERMINATOR @
            CREATE TABLE SHOP.T_ORDER (
                ID NUMBER(12) NOT NULL, NM VARCHAR2(100 CHAR) DEFAULT 'x', DT DATE DEFAULT SYSDATE,
                P_ID NUMBER CONSTRAINT FK_P REFERENCES PARENT (ID) ON DELETE CASCADE,
                CONSTRAINT PK_T PRIMARY KEY (ID), CONSTRAINT UK_T UNIQUE (NM, DT)
            ) TABLESPACE TS PARTITION BY RANGE (DT) (PARTITION P1 VALUES LESS THAN (MAXVALUE));
            ALTER TABLE T_ORDER ADD (C1 NUMBER, CONSTRAINT FK2 FOREIGN KEY (C1) REFERENCES OTHER);
            ALTER TABLE T_ORDER MODIFY C1 VARCHAR2(10) NOT NULL;
            COMMENT ON COLUMN T_ORDER.ID IS '주문 ''ID''; 설명';
            CREATE UNIQUE INDEX UX1 ON T_ORDER (UPPER(NM), DT DESC);
            CREATE OR REPLACE FORCE VIEW V (A, B) AS SELECT O.ID, NVL(O.NM, '-') FROM T_ORDER O WITH READ ONLY;
            CREATE SEQUENCE SEQ_A START WITH 1; CREATE PUBLIC SYNONYM S FOR SHOP.T_ORDER;
            CREATE OR REPLACE PACKAGE BODY PKG AS
              PROCEDURE A(X NUMBER) IS
                V NUMBER;
              BEGIN
                IF X > 0 THEN UPDATE T_ORDER SET NM = 'a;b' WHERE ID = X; END IF;
                FOR R IN (SELECT ID FROM T_ORDER) LOOP V := CASE WHEN R.ID > 1 THEN 1 ELSE 0 END; END LOOP;
              END A;
              FUNCTION B RETURN NUMBER IS BEGIN RETURN 1; END B;
            END PKG;
            /
            CREATE TRIGGER TRG AFTER UPDATE OF NM ON SHOP.T_ORDER FOR EACH ROW
            DECLARE V NUMBER;
            BEGIN INSERT INTO HIST (ID) VALUES (:NEW.ID); END;
            /
            CREATE PROCEDURE SP_D (IN P INT) LANGUAGE SQL BEGIN ATOMIC DELETE FROM X WHERE ID = P; END@
            INSERT INTO T_ORDER (ID, NM) SELECT ID, NM FROM STAGE S WHERE EXISTS (SELECT 1 FROM PARENT P WHERE P.ID = S.P_ID);

            MERGE INTO T_ORDER T USING STAGE S ON (T.ID = S.ID) WHEN MATCHED THEN UPDATE SET T.NM = S.NM
            /
            BEGIN PKG.A(1); END;
            /
            SET DEFINE OFF
            PROMPT it's done
            EXIT
            """;

    private static final String JUNK = "()',;.=*@{}:?\"-/\n \t%&|<>!#$[]\\";
    private static final List<String> WORDS = List.of(" CREATE ", " TABLE ", " BEGIN ", " END ", " END;", " IS ", " AS ", " ( ", " ) ",
            " SELECT ", " FROM ", "\n/\n", "\n\n", " PROCEDURE ", " PACKAGE ", " DECLARE ", " CASE ", " REFERENCES ", " PRIMARY KEY ",
            " CONSTRAINT ", " ALTER ", " COMMENT ON ", "--#SET TERMINATOR !\n", " DEFAULT ", " VIEW ", "/*", "*/", "--", " q'[", "${x}", "#{y}");

    private static List<String> mutations(Random random, int count) {
        List<String> list = new ArrayList<>();
        for (int i = 0; i <= SCRIPT.length(); i += 7) {
            list.add(SCRIPT.substring(0, i));
            list.add(SCRIPT.substring(i));
        }
        list.add(SCRIPT.replace("(", ""));
        list.add(SCRIPT.replace(")", ""));
        list.add(SCRIPT.replace(";", ""));
        list.add(SCRIPT.replace("'", ""));
        list.add(SCRIPT.replace("END", ""));
        list.add(SCRIPT.replace("BEGIN", "BEGIN BEGIN"));
        for (int n = 0; n < count; n++) {
            StringBuilder sb = new StringBuilder(SCRIPT);
            int edits = 1 + random.nextInt(6);
            for (int e = 0; e < edits && sb.length() > 0; e++) {
                int at = random.nextInt(sb.length());
                switch (random.nextInt(4)) {
                    case 0 -> sb.deleteCharAt(at);
                    case 1 -> sb.insert(at, JUNK.charAt(random.nextInt(JUNK.length())));
                    case 2 -> sb.delete(at, Math.min(sb.length(), at + random.nextInt(40)));
                    default -> sb.insert(at, WORDS.get(random.nextInt(WORDS.size())));
                }
            }
            list.add(sb.toString());
        }
        return list;
    }

    @Test
    @DisplayName("F-006-AC11 F-007-AC10 퍼즈: 분할기, DDL 파서, 루틴 헤더가 예외 없이 끝난다")
    void splitterAndDdlParser() {
        Random random = new Random(7L);
        Assertions.assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            for (String text : mutations(random, 1500)) {
                try {
                    for (boolean embedded : new boolean[] {false, true}) {
                        List<ScriptUnit> units = embedded ? ScriptSplitter.splitEmbedded(text) : ScriptSplitter.split(text);
                        for (ScriptUnit unit : units) {
                            assertThat(unit.tokens()).isNotEmpty();
                            if (unit.kind() != ScriptUnit.Kind.STATEMENT) {
                                RoutineHeader.parse(unit.tokens());
                            } else if (DdlParser.isDdl(unit.tokens())) {
                                new DdlParser(new ArrayList<>(unit.tokens())).parse();
                            }
                        }
                    }
                } catch (RuntimeException | StackOverflowError e) {
                    throw new AssertionError("예외 발생: [" + text + "]", e);
                }
            }
        });
    }

    @Test
    @DisplayName("F-006-AC11 퍼즈: 분석기 전체가 망가진 파일과 내장 소스에 대해 ERROR 진단 없이 끝난다")
    void analyzer() {
        Random random = new Random(11L);
        List<String> texts = mutations(random, 150);
        Assertions.assertTimeoutPreemptively(Duration.ofSeconds(120), () -> {
            try (TestProject project = TestProject.create()) {
                for (int i = 0; i < texts.size(); i++) {
                    project.file("db/f" + i + ".sql", texts.get(i));
                }
                AnalysisContext ctx = project.context();
                for (int i = 0; i < texts.size(); i++) {
                    String owner = Ids.mybatisStatement("ns", "s" + i);
                    ctx.graph().declare(Node.builder(owner, NodeKind.SQL_STATEMENT).build());
                    ctx.addEmbedded(new EmbeddedSource(EmbeddedSource.SQL, owner, texts.get(i),
                            SourceLocation.of("m.xml", i + 1), i % 2 == 0, Map.of()));
                }
                GraphModel g = TestProject.run(ctx, new SqlAnalyzer()).graph();
                assertThat(g.diagnostics()).noneMatch(d -> d.severity() == io.codeatlas.core.model.Severity.ERROR);
                assertThat(g.nodeCount()).isGreaterThan(texts.size());
            }
        });
    }
}
