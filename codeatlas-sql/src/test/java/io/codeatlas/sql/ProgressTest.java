package io.codeatlas.sql;

import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import io.codeatlas.core.model.SourceLocation;
import io.codeatlas.core.spi.AnalysisContext;
import io.codeatlas.core.spi.AnalysisOptions;
import io.codeatlas.core.spi.EmbeddedSource;
import io.codeatlas.core.spi.ProjectFiles;
import io.codeatlas.core.testing.TestProject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 처리 중인 파일 알리기(F-006-R23). 힙이 모자라면 파이프라인이 {@code AnalysisContext.currentActivity()}를 안내에 싣는다
 * (F-001-R39). 큰 데이터 스크립트에서 메모리가 모자랐을 때 어느 파일인지 알 수 있어야 한다.
 */
class ProgressTest {

    /** 알린 값을 순서대로 기록하는 컨텍스트. 나머지는 원래 컨텍스트에 맡긴다. */
    private static final class Recording implements AnalysisContext {
        private final AnalysisContext delegate;
        private final List<String> activities = new ArrayList<>();

        Recording(AnalysisContext delegate) {
            this.delegate = delegate;
        }

        @Override
        public Path projectRoot() {
            return delegate.projectRoot();
        }

        @Override
        public ProjectFiles files() {
            return delegate.files();
        }

        @Override
        public GraphModel graph() {
            return delegate.graph();
        }

        @Override
        public AnalysisOptions options() {
            return delegate.options();
        }

        @Override
        public void addEmbedded(EmbeddedSource source) {
            delegate.addEmbedded(source);
        }

        @Override
        public List<EmbeddedSource> embedded(String language) {
            return delegate.embedded(language);
        }

        @Override
        public void progress(String activity) {
            activities.add(activity);
            delegate.progress(activity);
        }

        @Override
        public String currentActivity() {
            return delegate.currentActivity();
        }
    }

    @Test
    @DisplayName("F-006-AC23 SQL 파일과 내장 SQL을 처리하기 시작할 때마다 그 파일의 상대 경로를 AnalysisContext.progress로 알린다")
    void reportsFileBeingProcessed() {
        try (TestProject project = TestProject.create()
                .file("db/a_schema.sql", "CREATE TABLE TB_A (ID NUMBER PRIMARY KEY);\n")
                .file("db/b_query.sql", "SELECT ID FROM TB_A;\n")) {
            Recording ctx = new Recording(project.context());
            String owner = Ids.mybatisStatement("a.Mapper", "find");
            ctx.addEmbedded(EmbeddedSource.sql(owner, "select ID from TB_A", SourceLocation.of("src/mapper/AMapper.xml", 5), false));

            TestProject.run(ctx, new SqlAnalyzer());

            // 1패스(DDL·헤더)와 구문 분석 패스에서 파일마다, 그리고 내장 소스는 호스트 파일로 알린다
            assertThat(ctx.activities).containsSubsequence("db/a_schema.sql", "db/b_query.sql", "db/a_schema.sql", "db/b_query.sql",
                    "src/mapper/AMapper.xml");
            assertThat(ctx.activities).doesNotContainNull();
            assertThat(ctx.currentActivity()).isEqualTo("src/mapper/AMapper.xml");
        }
    }
}
