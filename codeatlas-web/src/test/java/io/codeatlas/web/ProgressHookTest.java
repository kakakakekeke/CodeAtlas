package io.codeatlas.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.codeatlas.core.model.GraphModel;
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

/** F-010-R26: 파일 하나를 처리하기 시작할 때 그 상대 경로를 컨텍스트에 알린다(메모리 부족 안내에 실린다). */
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
    @DisplayName("F-010-AC38 JS·TS 파일을 읽기 시작할 때마다 그 파일의 상대 경로를 progress로 알린다")
    void reportsEachScriptFile() {
        try (TestProject project = TestProject.create()
                .file("static/js/a.js", "function a() { return 1; }\n")
                .file("static/js/b.js", "function b() { return a(); }\n")) {
            Recording ctx = new Recording(project.context(), new ArrayList<>());
            TestProject.run(ctx, new JsAnalyzer());

            assertThat(ctx.activities()).contains("static/js/a.js", "static/js/b.js");
            assertThat(ctx.activities().indexOf("static/js/a.js")).isLessThan(ctx.activities().indexOf("static/js/b.js"));
        }
    }
}
