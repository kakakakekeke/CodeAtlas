package io.codeatlas.core.spi;

import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.SourceLocation;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AnalysisContextTest {

    @Test
    @DisplayName("F-001-AC19 내장 소스는 언어별로 등록한 순서대로 꺼낸다")
    void embeddedSources() {
        AnalysisContext ctx = AnalysisContext.of(Path.of("."), ProjectFiles.of(List.of()), new GraphModel(), AnalysisOptions.defaults());
        SourceLocation origin = SourceLocation.of("mapper/A.xml", 5, 9, 7, 3);
        EmbeddedSource sql1 = new EmbeddedSource("sql", "mybatis:stmt:ns.a", "select 1", origin, true, Map.of("variant", "0"));
        EmbeddedSource sql2 = EmbeddedSource.sql("mybatis:stmt:ns.b", "select 2", origin, false);
        EmbeddedSource js = EmbeddedSource.js("js:module:a.jsp", "f()", origin, true);
        ctx.addEmbedded(sql1);
        ctx.addEmbedded(js);
        ctx.addEmbedded(sql2);

        assertThat(ctx.embedded("sql")).containsExactly(sql1, sql2);
        assertThat(ctx.embedded(EmbeddedSource.JS)).containsExactly(js);
        assertThat(ctx.embedded("css")).isEmpty();
        assertThat(sql2.attrs()).isEmpty();
        assertThat(ctx.options().dialect()).isEqualTo(Dialect.AUTO);
    }

    @Test
    @DisplayName("F-001-AC19 exactPositions이면 조각 안 위치를 호스트 파일 위치로 바꾼다")
    void embeddedLocation() {
        SourceLocation origin = SourceLocation.of("mapper/A.xml", 5, 9, 7, 3);
        EmbeddedSource exact = EmbeddedSource.sql("mybatis:stmt:ns.a", "select 1\nfrom dual", origin, true);
        assertThat(exact.locationOf(1, 8, 1, 8)).isEqualTo(SourceLocation.of("mapper/A.xml", 5, 16, 5, 16));
        assertThat(exact.locationOf(2, 1, 2, 9)).isEqualTo(SourceLocation.of("mapper/A.xml", 6, 1, 6, 9));
        assertThat(exact.locationOf(1, 1, 2, 4)).isEqualTo(SourceLocation.of("mapper/A.xml", 5, 9, 6, 4));

        EmbeddedSource inexact = EmbeddedSource.sql("mybatis:stmt:ns.a", "select 1", origin, false);
        assertThat(inexact.locationOf(2, 1, 2, 9)).isEqualTo(origin);
    }
}
