---
type: tasks
id: F-007
updated: 2026-10-11
---

# F-007 DDL 파서 작업 목록

테스트: `mvn -q -pl codeatlas-sql test`

## 구현 (작업마다 테스트 먼저)
- [x] T1. F-007-AC1 테스트 → CREATE TABLE 파서(컬럼, 타입, 기본값, 방언별 절 건너뛰기), 카탈로그, 테이블·컬럼 노드 (F-007-R1)
- [x] T2. F-007-AC2, AC3 테스트 → PK/UNIQUE/FK(인라인, 테이블 수준, ALTER), 2패스 참조 컬럼 채우기 (F-007-R2, R3)
- [x] T3. F-007-AC4, AC5, AC6 테스트 → ALTER TABLE 컬럼 변경, COMMENT ON, CREATE INDEX (F-007-R4, R5, R6)
- [x] T4. F-007-AC7 테스트 → CREATE VIEW와 정의 쿼리 구문 (F-007-R7)
- [x] T5. F-007-AC8 테스트 → SEQUENCE, SYNONYM, ALIAS (F-007-R8)
- [x] T6. F-007-AC9 테스트 → DDL 먼저, DML 나중 순서 (F-007-R9)
- [x] T7. F-007-AC10, AC11 테스트 → 건너뛰는 구문, 모르는 구문, 중복 이름 진단 (F-007-R10, R11)
- [x] T8. F-007-AC12 테스트 → 루틴 헤더 선언, 트리거 대상 (F-007-R12)
- [x] T9. samples/sample-shop/db 의 DDL로 대조: expected/ 의 DB 노드 69개(테이블, 뷰, 시노님, 시퀀스, 패키지, 루틴, 컬럼 attrs)와 DDL REFERENCES, ALIAS_OF, TRIGGERS_ON 일치
- [x] T10. (감사 후속) F-007-AC13 테스트(`DdlAnalyzerTest.indexSortDirection`, `SampleShopRoutineTest.indexSortDirectionOfSample`) → 실패 확인 → `DdlParser.createIndex`가 `DESC`를 표시용 텍스트에 남김 (F-007-R13). AC6 테스트의 기대값 `IX1(A,B)`를 `IX1(A,B DESC)`로 고쳤다
- [x] T11. (공개 프로젝트 검증 B8) F-007-AC14 테스트(`DuplicateDefinitionTest.oneDefinitionPerTable`·`selectionOrder`·`summaryByDirectoryPair`·`deduplicatesForeignKeysAndIndexes`) → 실패 확인 → `DdlProcessor`가 DDL을 모았다가 `resolveDefinitions`에서 정의를 고르고 반영(`rank`, `variantOf`, `reportDuplicates`, `preferred`), FK·인덱스 중복 제거 (F-007-R14)
- [x] T12. (B8) F-007-AC15 테스트(`DuplicateDefinitionTest.upgradeAltersNotApplied`) → 실패 확인 → `DdlProcessor.isUpgradePath`, 진단 코드 `SqlDiagnosticCodes.DDL_NOT_APPLIED` (F-007-R15)
- [x] T13. (B8b) F-007-AC16 테스트(`ForeignDialectDdlTest.mysqlIndexClauses`) → 실패 확인 → `DdlParser.atIndexClause`·`indexClause`, `UNIQUE KEY 이름`, `DEFAULT NULL`, `Ddl.Constraint` kind `INDEX`·`OTHER`, `DdlProcessor.applyConstraint` (F-007-R16)
- [x] T14. (방언 관용) F-007-AC17 테스트(`ForeignDialectDdlTest.mysqlDump`·`hsqlAndMssql`) → 실패 확인 → `DdlParser.columnDef`(AUTO_INCREMENT, COMMENT, CHARACTER SET, ON UPDATE), `dataType`(UNSIGNED), `create`(MEMORY·CACHED·TEXT·TEMP TABLE) (F-007-R17). 렉서 쪽(백틱, `#` 주석)은 F-006 T15
- [x] T15. 공개 프로젝트 코퍼스 실측(sql 분석기만): egovframe-enterprise-business-template `DATABASE/`의 테이블 30개가 모두 `oracle/all_ebt_ddl_oracle.sql`의 정의로(전에는 위치는 altibase, 타입은 마지막 파일), DDL `REFERENCES` 90 → 18, 가짜 컬럼 `KEY` 10 → 0. jeesite-v1/db PARSE_FAILED 861 → 2, zheng/project-datamodel 295 → 0
- [x] T16. (재검증 N4) F-007-AC18·AC19·AC20 테스트(`DdlDialectSelectionTest.oracleBeforeDb2`·`requestedDialectThenNextRank`·`mapperVariantOptionAppliesToDdl`·`requestedDialectNotFound`·`oracleFamilyThenPathOrder`·`noSummaryWithoutDialectVariants`, `DialectTokensTest`) → 실패 확인(선택 테스트 5개 단언 실패) → `DialectTokens`(mybatis와 같은 토큰 표), `DialectSelection`(프로젝트에 하나인 방언 순위와 요약), `DdlProcessor.better`·`summarizeVariants`(전의 `rank`·`variantOf`·`VARIANTS` 대체), 진단 코드 `DIALECT_VARIANT_DDL`·`DIALECT_NOT_FOUND` (F-007-R18, R19). `DuplicateDefinitionTest.oneDefinitionPerTable`의 전체 진단 수 기대를 2 → 3(요약 1건)으로 고쳤다
- [x] T17. (재검증 N30) F-007-AC21 테스트(`ProjectDialectTest`, `DdlDialectSelectionTest.projectSettingDialect`) → `ProjectDialect`, `DialectSelection`의 순위와 WARN 경우 (F-007-R21)
- [x] T18. (재검증 N24) F-007-AC22 테스트(`DuplicateDefinitionTest.upgradeForeignKeysApplied`) → `DdlProcessor.upgradeAlter`·`foreignKeyApplies`·`NotApplied`, `Catalog.ForeignKey.fromUpgrade`, `REFERENCES`의 `fromUpgrade` (F-007-R22)
- [x] T19. (재검증 N3 보강) F-007-AC23 테스트(`DdlDialectSelectionTest.loadScriptsOfOneDialect`) → `SqlAnalyzer.selectLoadVariants`, `DataLoadGrouper.tables`·`drop` (F-007-R20)
- [x] T17~T19의 RED: 구현을 테스트보다 먼저 넣은 항목이라, 수정 전 클래스(`scratchpad/fix3-sql/before-sql`)로 같은 재현 프로젝트를 돌려 기대와 다른 결과(설정 무시, FK 누락, 방언별 적재 묶음 중복)가 나오는 것으로 확인했다(F-006 tasks T31 참고)

- [x] T20. (재검증 3차 M4) F-007-AC24~AC26 테스트(`DialectPolicyTest`의 5건)와 `DdlDialectSelectionTest.projectSettingDialect`의 기대 수정 → 실패 확인(설정이 mysql이면 mysql 파일을 고름, 엇갈린 설정·내용 특징·단일 대상 외 방언 안내 없음) → `DialectSelection`(순위, `registerFile`·`dialectOf`, 요약 문구, `reportSingleForeign`), `ProjectDialect.readAll`·`Reading`, `DialectDetector.contentDialect`, `SqlDiagnosticCodes.DIALECT_NOT_TARGET`, `SqlAnalyzer`·`DdlProcessor`(등록한 방언 사용) (F-007-R23~R25). 대상 외 방언뿐인 프로젝트의 테스트(`ForeignDialectDdlTest` 3건, `HashCommentDialectTest` 1건)는 "진단 없음"을 "`DIALECT_NOT_TARGET` 1건뿐"으로 고쳤다. 안내의 옵션은 `--dialect mysql`이 아니라 `--opt mybatis.dialectVariant=<방언>`이다(`--dialect`는 oracle·db2만 받는다)
- [x] T21. (재검증 3차 M8) F-007-AC27 테스트(`DuplicateDefinitionTest.upgradeForeignKeysApplied`에 신뢰도 단언 추가, `upgradeForeignKeysStayResolvedWithoutCurrentForeignKeys`) → 실패 확인(RESOLVED) → `DdlProcessor.missingFromCurrentDefinition`·`writeForeignKeys` (F-007-R26)
- [x] T22. (재검증 3차 M12-a·b) F-007-AC28 테스트(`DialectPolicyTest.unselectedDialectFilesProduceNoStatements`·`noDuplicateHintForDialectVariants`) → 실패 확인(유령 테이블 `_CUB_SCHEMA_COMMENTS`, 방언 쌍마다 `DUPLICATE_OBJECT_NAME`) → `SqlAnalyzer.excludeUnselectedDialects`, `DialectSelection.mainDialect`·`usedDefinitionFile`, `DataLoadGrouper.dropAll`, `DdlProcessor.reportDuplicates` (F-007-R27). `DdlDialectSelectionTest.loadScriptsOfOneDialect`(mysql에만 있는 테이블의 묶음도 버린다: 제외한 파일 1→2, 묶음 4→5), `DuplicateDefinitionTest.oneDefinitionPerTable`(방언 쌍의 `DUPLICATE_OBJECT_NAME` 2건 → 0건)·`summaryByDirectoryPair`(폴더 이름을 방언이 아닌 것으로)의 기대를 고쳤다
- [x] T23. (재검증 3차) 재현 프로젝트와 코퍼스 실측(mybatis+sql, 옵션 없음): `corpus-eval3/repro/c1`~`c6`은 모두 oracle 파일을 고르고 요약이 INFO다(c1·c4·c5 "설정과 다르게", c2 "설정에 따라", c3 "설정이 엇갈려", c6 설정 없음). 전자정부 공통컴포넌트·enterprise·simple-backend와 jeesite가 옵션 없이 oracle 매퍼·DDL을 고른다(전에는 mysql·hsql). simple-backend는 이름에 방언이 없는 HSQL 스크립트를 hsql로 읽는다. zheng(MySQL뿐)은 `DIALECT_NOT_TARGET` 1건. samples/sample-shop의 mybatis+sql 그래프는 세션 시작 때와 한 줄도 다르지 않다
- [x] T24. (방언 불일치 수정) F-007-AC29 테스트(`DialectPolicyTest.otherDialectsByName`·`otherDialectsOnBothSidesFirst`·`unknownDialectFileKeepsPathOrder`, `DialectTokensTest.otherDialectOrder`: mybatis 모듈과 같은 표) → 실패 확인(postgres 파일이 경로상 먼저면 postgres를 고름) → `DialectTokens.otherOrder`, `DialectSelection.of`가 매퍼·SQL 파일 경로로 순서를 계산, `DialectSelection.rank`가 그 밖의 방언에 그 순서로 한꺼번에 순위를 줌 (F-007-R28). 기존 테스트는 기대값 변경 없이 통과(픽스처의 경로순이 사전순과 같았다. 이름과 주석의 "경로순 첫 방언"만 고침)
- [x] T25. (재검증 4차 회귀: 적재 묶음을 방언 파일 단위로 버림) F-007-AC30 테스트(`LoadDialectByTableTest`의 4건: `sameDialectFixturesBothKept`·`borrowedWhenSelectedDialectHasNoLoad`·`perTableSelection`·`noGhostTablesFromOtherDialects`) → 실패 확인(4건: 정의가 쓰이지 않은 hsql 픽스처·`shtdb.sql`·mysql 적재 파일의 묶음이 하나도 없음) → `SqlAnalyzer.selectLoadVariants`(테이블마다 선택, `excludeUnselectedDialects`를 합침)·`resetKept`·`FileUnits.borrowedFrom`·`droppedLoads`·`UnitWork.resetTable`, `DataLoadGrouper.borrow`·`resetTable`(`dropAll` 삭제), `DialectSelection.borrowedLoadGroup`과 요약 문구 (F-007-R29). 기존 `DdlDialectSelectionTest.loadScriptsOfOneDialect`(AC23)·`DialectPolicyTest`(AC28·AC29)는 기대값을 고치지 않고 통과한다
- [x] T26. (T25의 코퍼스 실측, mybatis+sql, 옵션 없음. 수정 전 → 후) egovframe-common-components: 적재 묶음 1,591 → 1,597(`session/testdb.sql`의 6개가 되살아남), `DATA_LOAD_GROUPED` 119 → 120, 만들지 않은 묶음 11,095 → 11,089(방언 9벌의 중복은 되살아나지 않는다), `borrowedFrom=hsql` 13개(`security/testdb.sql` 7 + `session/testdb.sql` 6), 테이블 노드 변화 없음. jeesite-v1: 적재 묶음 4 → 22(`db/jeesite_mysql.sql`의 18개, 리터럴 INSERT 711건. 파일 2·테이블 22·행 774), 만들지 않은 묶음 30 → 12, 테이블 노드 변화 없음. mysql 파일의 22개 테이블 가운데 `GEN_SCHEME`·`GEN_TABLE`·`GEN_TABLE_COLUMN`·`SYS_MENU`는 `db/gen/example_oracle.sql`에 적재가 있어 oracle 것만 남는다. egovframe-template-simple-backend: 변화 없음(`shtdb.sql`의 10개 테이블이 모두 `all_sht_data_oracle.sql`에 있다). enterprise·web-sample·jpetstore·zheng·simple-react: 덤프가 글자 그대로 같다. 재현 `corpus-eval4/agents/v4-cc/repro/hsql1`: 묶음 2 → 4, `hsql2`: 그대로 4

## 마무리
- [x] 재검증 4차 후속(T25·T26, F-006 T44·T45) 뒤 `mvn -q -pl codeatlas-sql install` 1,361개 통과(이전 1,355 + `LoadDialectByTableTest` 4 + `SystemCatalogTest` 1 + `StatementDialectTest` 1. 2026-10-11)
- [x] 방언 불일치 수정(T24) 뒤 `mvn -q -pl codeatlas-sql test` 1,355개 통과(2026-10-11)
- [x] 재검증 3차 후속(T20~T23) 뒤 `mvn -q -pl codeatlas-sql clean test` 1,347개 통과
- [x] 모든 수용 기준 통과 확인 (2026-10-11 공개 프로젝트 검증 후속 뒤 sql 모듈 테스트 598개 통과. 2026-10-11 재검증 후속(3차) 뒤 `mvn -q -pl codeatlas-sql clean test` 834개 통과)
- [x] spec.md의 status를 implemented로 변경
- [x] features.md의 상태를 완료로 변경 (문서 정리 때 확인: features.md에 완료로 돼 있다)
- [x] 구조가 바뀌었으면 architecture.md 갱신 (해당 없음)
