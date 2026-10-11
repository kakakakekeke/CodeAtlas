package io.codeatlas.mybatis;

import io.codeatlas.core.model.Ids;

/**
 * 매퍼 요소({@code select} 등, {@code sql}, {@code resultMap})의 id를 namespace로 한정한다.
 *
 * <p>MyBatis의 {@code MapperBuilderAssistant.applyCurrentNamespace}와 같은 규칙이다. id가 {@code <namespace>.}로 시작하면
 * 이미 한정된 것으로 보고 그대로 쓴다(전자정부 템플릿의 {@code <mapper namespace="loginDAO"><select id="loginDAO.actionLogin">}).
 * id에 점이 있는데 namespace로 시작하지 않으면 MyBatis는 오류로 본다. 여기서는 원문을 그대로 한정된 ID로 쓰고
 * 호출하는 쪽이 경고를 남긴다.
 */
final class MapperIds {

    /**
     * @param qualifiedId namespace까지 붙은 ID
     * @param shortId namespace를 뗀 id. {@code foreign}이면 원문 그대로
     * @param foreign 점이 있는데 이 매퍼의 namespace로 시작하지 않는 id인가
     */
    record ElementId(String qualifiedId, String shortId, boolean foreign) {
    }

    private MapperIds() {
    }

    static ElementId of(String namespace, String id) {
        String prefix = namespace + ".";
        if (id.startsWith(prefix) && id.length() > prefix.length()) {
            return new ElementId(id, id.substring(prefix.length()), false);
        }
        if (id.indexOf('.') >= 0) {
            return new ElementId(id, id, true);
        }
        return new ElementId(prefix + id, id, false);
    }

    /** 한정된 ID의 구문 노드 ID {@code mybatis:stmt:<qualifiedId>}. */
    static String statementNodeId(String qualifiedId) {
        int dot = qualifiedId.lastIndexOf('.');
        return Ids.mybatisStatement(qualifiedId.substring(0, dot), qualifiedId.substring(dot + 1));
    }

    /** 한정된 ID의 조각 노드 ID {@code mybatis:fragment:<qualifiedId>}. */
    static String fragmentNodeId(String qualifiedId) {
        int dot = qualifiedId.lastIndexOf('.');
        return Ids.mybatisFragment(qualifiedId.substring(0, dot), qualifiedId.substring(dot + 1));
    }
}
