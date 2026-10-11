package com.example.shop.batch;

import java.util.Map;

import org.springframework.batch.item.ItemProcessor;

/**
 * 정산 대상 행에 정산년월을 채운다. (XML 빈 settleProcessor)
 */
public class SettleProcessor implements ItemProcessor<Map<String, Object>, Map<String, Object>> {

    @Override
    public Map<String, Object> process(Map<String, Object> item) {
        Object totalAmt = item.get("TOTAL_AMT");
        if (totalAmt == null || ((Number) totalAmt).longValue() == 0L) {
            return null;
        }
        String fromDt = String.valueOf(item.getOrDefault("FROM_DT", ""));
        item.put("settleYm", fromDt.length() >= 6 ? fromDt.substring(0, 6) : fromDt);
        return item;
    }
}
