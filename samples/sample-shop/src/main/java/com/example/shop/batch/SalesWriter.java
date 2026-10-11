package com.example.shop.batch;

import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.stereotype.Component;

import com.example.shop.batch.mapper.BatchMapper;
import com.example.shop.batch.vo.DailySalesVO;

/**
 * 일 매출 항목을 TB_DAILY_SALES 에 MERGE 한다.
 */
@Component
public class SalesWriter implements ItemWriter<DailySalesVO> {

    private final BatchMapper batchMapper;

    public SalesWriter(BatchMapper batchMapper) {
        this.batchMapper = batchMapper;
    }

    @Override
    public void write(Chunk<? extends DailySalesVO> chunk) {
        for (DailySalesVO vo : chunk) {
            batchMapper.mergeDailySales(vo);
        }
    }
}
