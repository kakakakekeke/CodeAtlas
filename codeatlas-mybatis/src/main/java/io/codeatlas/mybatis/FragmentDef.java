package io.codeatlas.mybatis;

/**
 * {@code <sql id>} 정의 하나.
 *
 * @param id namespace를 뗀 id
 * @param qualifiedId namespace까지 붙은 ID({@link MapperIds})
 */
record FragmentDef(MapperFile file, XmlElement element, String id, String qualifiedId) {

    String namespace() {
        return file.namespace();
    }

    String nodeId() {
        return MapperIds.fragmentNodeId(qualifiedId);
    }

    String databaseId() {
        return element.attr("databaseId");
    }
}
