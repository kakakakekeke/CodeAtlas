package com.example.shop.batch;

import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

import com.example.shop.batch.vo.DailySalesVO;
import com.example.shop.order.vo.OrderVO;

/**
 * 주문 → 일 매출 항목 변환.
 */
@Component
public class SalesProcessor implements ItemProcessor<OrderVO, DailySalesVO> {

    @Override
    public DailySalesVO process(OrderVO order) {
        if (order.getTotalAmt() == null || order.getTotalAmt() <= 0) {
            // null 을 돌려주면 라이터로 넘어가지 않는다
            return null;
        }
        DailySalesVO vo = new DailySalesVO();
        vo.setSalesDt(order.getOrderDt());
        vo.setOrderId(order.getOrderId());
        vo.setSalesAmt(order.getTotalAmt());
        return vo;
    }
}
