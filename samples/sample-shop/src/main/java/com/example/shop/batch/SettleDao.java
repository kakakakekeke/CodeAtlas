package com.example.shop.batch;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.session.SqlSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Repository;

/**
 * 월 정산 DAO (DB2). 구문은 mapper/batch/settleDao.xml (namespace = "settleDao") 에 있다.
 * setter 주입 + @Qualifier. 빈 이름은 기본값 settleDao.
 */
@Repository
public class SettleDao {

    private SqlSession sqlSession;

    @Autowired
    public void setSqlSession(@Qualifier("batchSqlSession") SqlSession sqlSession) {
        this.sqlSession = sqlSession;
    }

    public List<Map<String, Object>> selectSettleTargets(Map<String, Object> param) {
        return sqlSession.selectList("settleDao.selectSettleTargets", param);
    }

    public List<Map<String, Object>> selectSettleList(String settleYm) {
        return sqlSession.selectList("settleDao.selectSettleList", settleYm);
    }

    public int insertSettleDtl(Map<String, Object> row) {
        return sqlSession.insert("settleDao.insertSettleDtl", row);
    }

    public int updateSettleAmt(String settleYm) {
        return sqlSession.update("settleDao.updateSettleAmt", settleYm);
    }

    /** 한 메서드에서 구문 두 개를 실행한다 (상세 먼저, 그다음 마스터) */
    public int deleteSettle(String settleYm) {
        sqlSession.delete("settleDao.deleteSettleDtl", settleYm);
        return sqlSession.delete("settleDao.deleteSettle", settleYm);
    }

    /** SP_SETTLE_MONTHLY 호출 */
    public int callSettleMonthly(String settleYm) {
        Map<String, Object> param = new HashMap<>();
        param.put("settleYm", settleYm);
        sqlSession.update("settleDao.callSettleMonthly", param);
        Object resultCnt = param.get("resultCnt");
        return resultCnt == null ? 0 : ((Number) resultCnt).intValue();
    }

    /** SP_WRITE_BATCH_LOG 호출 */
    public void writeLog(String jobNm, String stepNm, String status, String msg) {
        Map<String, Object> param = new HashMap<>();
        param.put("jobNm", jobNm);
        param.put("stepNm", stepNm);
        param.put("status", status);
        param.put("msg", msg);
        sqlSession.update("settleDao.callWriteLog", param);
    }

    public String selectCurrentTs() {
        return sqlSession.selectOne("settleDao.selectCurrentTs");
    }
}
