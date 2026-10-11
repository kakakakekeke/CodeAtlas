package com.example.shop.batch;

import java.util.Map;

import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;

/**
 * 월 정산 마무리 태스크릿 (XML 빈 batchLogTasklet, 생성자 주입은 constructor-arg).
 * 정산 상세 적재 → 금액 재계산 → 로그 프로시저 호출.
 */
public class BatchLogTasklet implements Tasklet {

    private final SettleDao settleDao;

    public BatchLogTasklet(SettleDao settleDao) {
        this.settleDao = settleDao;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) throws Exception {
        Map<String, Object> jobParams = chunkContext.getStepContext().getJobParameters();
        String settleYm = (String) jobParams.get("settleYm");

        if ("Y".equals(jobParams.get("useProc"))) {
            // 프로시저로 한 번에 재정산
            int count = settleDao.callSettleMonthly(settleYm);
            settleDao.writeLog("settleJob", "settleLogStep", "OK", "proc count=" + count);
            return RepeatStatus.FINISHED;
        }

        for (Map<String, Object> row : settleDao.selectSettleList(settleYm)) {
            settleDao.insertSettleDtl(row);
        }
        settleDao.updateSettleAmt(settleYm);
        settleDao.writeLog("settleJob", "settleLogStep", "OK", "ts=" + settleDao.selectCurrentTs());
        return RepeatStatus.FINISHED;
    }
}
