package io.codeatlas.java;

import io.codeatlas.core.model.Edge;
import io.codeatlas.core.model.EdgeKind;
import io.codeatlas.core.model.GraphModel;
import io.codeatlas.core.model.Ids;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.codeatlas.java.Fx.edge;
import static org.assertj.core.api.Assertions.assertThat;

/** F-002-R15: 상위 타입 엣지(EXTENDS, IMPLEMENTS)에 남기는 타입 인자. */
class SupertypeTypeArgumentsTest {

    private static String t(String name) {
        return Ids.javaType(name);
    }

    @Test
    @DisplayName("F-002-AC19 제네릭 상위 타입에 넘긴 타입 인자를 EXTENDS·IMPLEMENTS 엣지의 typeArguments에 선언 순서대로 남긴다")
    void typeArgumentsOnSupertypeEdges() {
        GraphModel g = Fx.analyze(
                "src/com/x/CrudService.java", """
                        package com.x;
                        public abstract class CrudService<D, T> {
                            protected D dao;
                        }
                        """,
                "src/com/x/Finder.java", "package com.x; public interface Finder<K, V> { }",
                "src/com/x/Named.java", "package com.x; public interface Named { }",
                "src/com/x/ArticleDao.java", "package com.x; public interface ArticleDao { }",
                "src/com/x/Article.java", "package com.x; public class Article { public static class Key { } }",
                "src/com/x/ArticleService.java", """
                        package com.x;
                        import java.util.List;
                        public class ArticleService extends CrudService<ArticleDao, Article>
                                implements Finder<Article.Key, List<Article>>, Named, Comparable<ArticleService> {
                            public int compareTo(ArticleService o) { return 0; }
                        }
                        """,
                "src/com/x/Middle.java", """
                        package com.x;
                        public abstract class Middle<T, X extends Named> extends CrudService<ArticleDao, T> implements Finder<X, T[]> {
                            Finder<?, int[]> anon() {
                                return new Finder<String, int[]>() { };
                            }
                            CrudService<ArticleDao, Article> diamond() {
                                return new CrudService<>() { };
                            }
                        }
                        """,
                "src/com/x/KeyFinder.java", "package com.x; public interface KeyFinder<V> extends Finder<String, V> { }");

        Edge extendsEdge = edge(g, t("com.x.ArticleService"), EdgeKind.EXTENDS, t("com.x.CrudService"));
        assertThat(extendsEdge.attrList("typeArguments")).containsExactly("com.x.ArticleDao", "com.x.Article");
        // 인터페이스가 여럿이면 엣지마다: 중첩 타입은 바이너리 이름, 제네릭 인자는 erasure
        assertThat(edge(g, t("com.x.ArticleService"), EdgeKind.IMPLEMENTS, t("com.x.Finder")).attrList("typeArguments"))
                .containsExactly("com.x.Article$Key", "java.util.List");
        assertThat(edge(g, t("com.x.ArticleService"), EdgeKind.IMPLEMENTS, t("java.lang.Comparable")).attrList("typeArguments"))
                .containsExactly("com.x.ArticleService");
        // 타입 인자가 없으면 attrs도 없다
        assertThat(edge(g, t("com.x.ArticleService"), EdgeKind.IMPLEMENTS, t("com.x.Named")).attrs()).doesNotContainKey("typeArguments");

        // 타입 변수는 이름 그대로, 배열은 [] 접미사
        assertThat(edge(g, t("com.x.Middle"), EdgeKind.EXTENDS, t("com.x.CrudService")).attrList("typeArguments"))
                .containsExactly("com.x.ArticleDao", "T");
        assertThat(edge(g, t("com.x.Middle"), EdgeKind.IMPLEMENTS, t("com.x.Finder")).attrList("typeArguments"))
                .containsExactly("X", "T[]");
        // 인터페이스가 인터페이스를 확장하는 EXTENDS도 같다
        assertThat(edge(g, t("com.x.KeyFinder"), EdgeKind.EXTENDS, t("com.x.Finder")).attrList("typeArguments"))
                .containsExactly("java.lang.String", "V");
        // 익명 클래스: 소스에 쓴 인자, 다이아몬드는 컴파일러가 추론한 인자
        assertThat(edge(g, t("com.x.Middle$1"), EdgeKind.IMPLEMENTS, t("com.x.Finder")).attrList("typeArguments"))
                .containsExactly("java.lang.String", "int[]");
        assertThat(edge(g, t("com.x.Middle$2"), EdgeKind.EXTENDS, t("com.x.CrudService")).attrList("typeArguments"))
                .containsExactly("com.x.ArticleDao", "com.x.Article");
    }

    @Test
    @DisplayName("F-002-AC20 classpath에 없는 상위 타입·타입 인자는 AST에서 읽어 import로 복원한 FQN(복원하지 못하면 쓴 이름)으로 남긴다")
    void typeArgumentsWithoutBindings() {
        GraphModel g = Fx.analyze(
                "src/com/y/UserService.java", """
                        package com.y;
                        import org.ext.base.BaseService;
                        import org.ext.model.User;
                        import org.ext.dao.*;
                        public class UserService extends BaseService<UserMapper, User, java.lang.Long> implements Local<User, Unknown> {
                        }
                        """,
                "src/com/y/Local.java", "package com.y; public interface Local<A, B> { }",
                // 컴파일되지 않는 코드(최상위 와일드카드)도 쓴 그대로 남긴다
                "src/com/y/Wild.java", "package com.y; public class Wild implements Local<? extends Number, String> { }");

        assertThat(edge(g, t("com.y.UserService"), EdgeKind.EXTENDS, t("org.ext.base.BaseService")).attrList("typeArguments"))
                .containsExactly("UserMapper", "org.ext.model.User", "java.lang.Long");
        assertThat(edge(g, t("com.y.UserService"), EdgeKind.IMPLEMENTS, t("com.y.Local")).attrList("typeArguments"))
                .containsExactly("org.ext.model.User", "Unknown");
        assertThat(edge(g, t("com.y.Wild"), EdgeKind.IMPLEMENTS, t("com.y.Local")).attrList("typeArguments"))
                .containsExactly("?", "java.lang.String");
    }
}
