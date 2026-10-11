package io.codeatlas.java;

import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.spi.AnalysisContext;
import io.codeatlas.core.spi.AnalysisOptions;
import io.codeatlas.core.spi.EmbeddedSource;
import io.codeatlas.core.spi.ProjectFiles;
import io.codeatlas.core.testing.TestProject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** F-002-R16: 메모리 부족 안내에 실릴 "처리 중이던 것"을 컨텍스트에 알린다. */
class ProgressHookTest {

    /** {@code progress} 호출을 순서대로 적어 두는 컨텍스트. 나머지는 원래 컨텍스트에 맡긴다. */
    private record Recording(AnalysisContext delegate, List<String> activities) implements AnalysisContext {
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
        }
    }

    @Test
    @DisplayName("F-002-AC21 일괄 파싱 전에 파일 수를, 파일별 추출을 시작할 때 그 파일의 상대 경로를 progress로 알린다")
    void reportsBatchParseAndEachFile() {
        try (TestProject project = TestProject.create()
                .file("src/p/A.java", "package p; public class A { }")
                .file("src/p/B.java", "package p; public class B extends A { }")) {
            Recording ctx = new Recording(project.context(), new ArrayList<>());
            TestProject.run(ctx, new JavaAnalyzer());

            assertThat(ctx.activities().get(0)).isEqualTo("Java 소스 2개 일괄 파싱");
            assertThat(ctx.activities()).contains("src/p/A.java", "src/p/B.java");
            assertThat(ctx.activities().indexOf("src/p/A.java")).isGreaterThan(0);
            // 파일을 다 본 뒤의 단계는 마지막 파일이 아니라 그 단계 이름으로 남는다
            assertThat(ctx.activities().get(ctx.activities().size() - 1)).doesNotEndWith(".java");
        }
    }
}
