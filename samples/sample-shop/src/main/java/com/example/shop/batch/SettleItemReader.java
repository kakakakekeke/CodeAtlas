package com.example.shop.batch;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.springframework.batch.item.ItemReader;

/**
 * 월 정산 대상 리더 (커스텀 ItemReader).
 * 애너테이션이 없다. batch/settle-job.xml 의 &lt;bean id="settleItemReader"&gt; 로 등록된다.
 */
public class SettleItemReader implements ItemReader<Map<String, Object>> {

    private SettleDao settleDao;
    private String settleYm;
    private Iterator<Map<String, Object>> iterator;

    public void setSettleDao(SettleDao settleDao) {
        this.settleDao = settleDao;
    }

    public void setSettleYm(String settleYm) {
        this.settleYm = settleYm;
    }

    @Override
    public Map<String, Object> read() {
        if (iterator == null) {
            // 첫 호출에서 기존 정산을 지우고 대상을 한 번에 읽어 온다
            settleDao.deleteSettle(settleYm);

            Map<String, Object> param = new HashMap<>();
            param.put("fromDt", settleYm + "01");
            param.put("toDt", settleYm + "31");
            iterator = settleDao.selectSettleTargets(param).iterator();
        }
        return iterator.hasNext() ? iterator.next() : null;
    }
}
