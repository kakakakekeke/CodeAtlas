package com.example.shop.batch;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.example.shop.batch.mapper.BatchMapper;

/**
 * 주문 일괄 마감 태스크릿. 매퍼를 거쳐 PKG_ORDER.CLOSE_DAILY 프로시저를 부른다.
 */
@Component
public class CloseOrderTasklet implements Tasklet {

    private static final Logger LOG = Logger.getLogger(CloseOrderTasklet.class.getName());

    @Autowired
    private BatchMapper batchMapper;

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) throws Exception {
        String baseDt = (String) chunkContext.getStepContext().getJobParameters().get("baseDt");

        if (batchMapper.selectIsHoliday(baseDt) > 0) {
            LOG.info("휴일이므로 마감을 건너뜁니다. baseDt=" + baseDt);
            return RepeatStatus.FINISHED;
        }

        Map<String, Object> param = new HashMap<>();
        param.put("baseDt", baseDt);
        batchMapper.callCloseDaily(param);
        LOG.info("마감 건수: " + param.get("closedCnt"));

        List<Map<String, Object>> report = batchMapper.selectDailySalesReport(baseDt);
        report.forEach(row -> LOG.fine(String.valueOf(row)));

        return RepeatStatus.FINISHED;
    }

    /** 수동 재집계: 기존 집계를 지우고 집계 프로시저를 다시 부른다. */
    public void rerun(String baseDt) {
        batchMapper.deleteDailySales(baseDt);
        batchMapper.callDailySalesSum(baseDt);
    }
}
