package io.codeatlas.link;

import io.codeatlas.link.ProjectProperties.Status;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 프로퍼티 자리표시자 조회 공용 헬퍼(F-012-R24). */
class ProjectPropertiesTest extends LinkTestBase {

    private ProjectProperties properties() {
        rescan();
        return ProjectProperties.of(ctx);
    }

    @Test
    @DisplayName("F-012-AC39 값이 하나로 모이면 치환하고, 파일마다 값이 다르면 모호라서 풀지 않는다")
    void singleValueOrAmbiguous() {
        project.file("src/main/resources/a.properties", "adminPath = /a\nmode=dev\nsame=1\n");
        project.file("src/main/resources/b.properties", "mode=prod\nsame=1\n");
        ProjectProperties p = properties();

        assertThat(p.lookup("adminPath", null).status()).isEqualTo(Status.FOUND);
        assertThat(p.lookup("adminPath", null).value()).isEqualTo("/a");
        assertThat(p.lookup("same", null).value()).as("여러 파일에 같은 값").isEqualTo("1");
        assertThat(p.lookup("mode", null).status()).isEqualTo(Status.AMBIGUOUS);
        assertThat(p.lookup("nope", null).status()).isEqualTo(Status.MISSING);

        ProjectProperties.Resolution r = p.resolve("${adminPath}/x/${mode}/${nope}", null);
        assertThat(r.text()).isEqualTo("/a/x/${mode}/${nope}");
        assertThat(r.changed()).isTrue();
        assertThat(r.complete()).isFalse();
        assertThat(r.unresolved()).containsExactly("${mode}", "${nope}");
        assertThat(p.resolve("plain", null).changed()).isFalse();
        assertThat(p.resolve(null, null).text()).isNull();
    }

    @Test
    @DisplayName("F-012-AC39 ${키:기본값}은 키가 어디에도 없을 때만 기본값을 쓰고, 값 안의 자리표시자도 푼다")
    void defaultValueAndNestedPlaceholders() {
        project.file("src/main/resources/a.properties", "base=/a\nadminPath=${base}/admin\nmode=dev\nbroken=${nope}/x\n");
        project.file("src/main/resources/b.properties", "mode=prod\n");
        ProjectProperties p = properties();

        assertThat(p.resolve("${apiBase:/api}/ping", null).text()).isEqualTo("/api/ping");
        assertThat(p.resolve("${base:/zzz}", null).text()).as("값이 있으면 기본값을 쓰지 않는다").isEqualTo("/a");
        assertThat(p.resolve("${mode:dev}", null).complete()).as("모호한 키는 기본값으로 덮지 않는다").isFalse();
        assertThat(p.resolve("${adminPath}/x", null).text()).isEqualTo("/a/admin/x");
        assertThat(p.resolve("${broken}", null).complete()).isFalse();
        assertThat(p.resolve("${empty:}/x", null).text()).isEqualTo("/x");
    }

    @Test
    @DisplayName("F-012-AC39 테스트 소스의 프로퍼티는 참조하는 쪽도 테스트 소스일 때만 보고, 같은 모듈 루트의 값을 먼저 쓴다")
    void testSourcesAndModuleRoots() {
        project.file("web-a/pom.xml", "<project/>");
        project.file("web-b/pom.xml", "<project/>");
        project.file("web-a/src/main/resources/app.properties", "ctx=/a\ndb=main\n");
        project.file("web-a/src/test/resources/app.properties", "db=test\n");
        project.file("web-b/src/main/resources/app.properties", "ctx=/b\n");
        ProjectProperties p = properties();

        assertThat(p.lookup("db", "web-a/src/main/java/A.java").value()).isEqualTo("main");
        assertThat(p.lookup("db", "web-a/src/test/java/ATest.java").status()).isEqualTo(Status.AMBIGUOUS);
        assertThat(p.lookup("ctx", "web-a/src/main/java/A.java").value()).isEqualTo("/a");
        assertThat(p.lookup("ctx", "web-b/src/main/java/B.java").value()).isEqualTo("/b");
        assertThat(p.lookup("ctx", null).status()).as("어느 모듈인지 모르면 값이 갈린다").isEqualTo(Status.AMBIGUOUS);
        assertThat(p.lookup("db", "web-b/src/main/java/B.java").value()).as("자기 모듈에 없으면 프로젝트 전체에서 찾는다").isEqualTo("main");
    }

    @Test
    @DisplayName("F-012-AC65 자기 자신을 가리키는 값(app.name=${app.name}, @app.name@: 빌드 도구의 필터링 자리)은 값으로 치지 않는다. 같은 모듈의 다른 파일(프로파일) 값이 하나로 모이면 그 값이다")
    void selfReferencingValueIsNotAValue() {
        project.file("web/pom.xml", "<project/>");
        project.file("web/src/main/resources/config.properties", "app.name=${app.name}\nonly.self=${only.self}\nat.name=@at.name@\n");
        project.file("web/src/main/resources/profiles/dev.properties", "app.name=cms-web\nat.name=x\n");
        project.file("web/src/main/resources/profiles/prod.properties", "app.name=cms-web\n");
        ProjectProperties p = properties();

        assertThat(p.lookup("app.name", "web/src/main/java/A.java").status()).isEqualTo(Status.FOUND);
        assertThat(p.lookup("app.name", "web/src/main/java/A.java").value()).isEqualTo("cms-web");
        assertThat(p.lookup("at.name", "web/src/main/java/A.java").value()).isEqualTo("x");
        assertThat(p.lookup("only.self", "web/src/main/java/A.java").status()).isEqualTo(Status.MISSING);
        assertThat(p.resolve("/${app.name}/login.html", "web/src/main/java/A.java").text()).isEqualTo("/cms-web/login.html");
    }

    @Test
    @DisplayName("F-012-AC39 YAML은 중첩 키를 점으로 이어 읽고, 문서(프로파일)마다 값이 다르면 모호다")
    void yamlDocuments() {
        project.file("src/main/resources/application.yml", """
                app:
                  admin-path: "/a"
                  mode: dev
                ---
                app:
                  mode: prod
                """);
        ProjectProperties p = properties();

        assertThat(p.lookup("app.admin-path", null).value()).isEqualTo("/a");
        assertThat(p.lookup("app.mode", null).status()).isEqualTo(Status.AMBIGUOUS);
    }
}
