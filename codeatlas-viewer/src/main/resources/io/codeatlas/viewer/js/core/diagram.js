// 그려진 다이어그램의 도형과 그래프 노드를 잇는 계산. DOM을 쓰지 않는다.
(function (root) {
  'use strict';
  var CA = root.CodeAtlas = root.CodeAtlas || {};

  // Mermaid가 붙이는 도형 ID: "[<다이어그램 ID>-]classId-<별칭>-<번호>", "[<다이어그램 ID>-]entity-<별칭>-<번호>"
  var SHAPE_ID = /(?:^|-)(?:classId|entity)-(.+)-\d+$/;

  /** 도형 ID에서 Mermaid 소스의 별칭(클래스 별칭, 엔터티 ID)을 꺼낸다. 클래스·엔터티 도형이 아니면 null. */
  function aliasOfShapeId(shapeId) {
    var m = SHAPE_ID.exec(shapeId || '');
    return m ? m[1] : null;
  }

  /** aliases(Map: 별칭 → 노드 ID)에서 도형이 가리키는 노드 ID를 찾는다. 없으면 null. */
  function nodeIdOfShape(shapeId, aliases) {
    var alias = aliasOfShapeId(shapeId);
    return alias != null && aliases && aliases.has(alias) ? aliases.get(alias) : null;
  }

  // Mermaid가 erDiagram 관계 선에 붙이는 ID: "[<다이어그램 ID>-]id_entity-<부모>-<n>_entity-<자식>-<m>_<관계 순번>"
  var RELATION_ID = /(?:^|-)id_entity-.+_entity-.+_(\d+)$/;

  /** 관계 선의 ID에서 관계 순번(소스에 적힌 차례, 0부터)을 꺼낸다. 관계 선이 아니면 null. */
  function relationIndexOfEdgeId(edgeId) {
    var m = RELATION_ID.exec(edgeId || '');
    return m ? Number(m[1]) : null;
  }

  var api = { aliasOfShapeId: aliasOfShapeId, nodeIdOfShape: nodeIdOfShape, relationIndexOfEdgeId: relationIndexOfEdgeId };
  CA.diagram = api;
  if (typeof module === 'object' && module.exports) module.exports = api;
})(typeof globalThis !== 'undefined' ? globalThis : this);
