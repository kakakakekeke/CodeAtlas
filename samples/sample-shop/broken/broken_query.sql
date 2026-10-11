-- 일부러 깨뜨린 SQL 파일.
-- 첫 구문은 괄호가 닫히지 않았고 종결자도 없다. 둘째 구문은 따옴표가 닫히지 않았다.
-- 분석기가 죽지 않고 실패로 기록하는지 보는 용도다. 이 파일의 테이블 이름은 정답 목록에 넣지 않았다.

SELECT B.BROKEN_ID,
       (SELECT COUNT(*
          FROM TB_BROKEN_CHILD C
         WHERE C.BROKEN_ID = B.BROKEN_ID AS CHILD_CNT
  FROM TB_BROKEN B
 WHERE B.USE_YN IN ('Y', 'N'

UPDATE TB_BROKEN
   SET BROKEN_NM = '닫히지 않은 문자열
 WHERE BROKEN_ID = 1;
