package com.example.shop.batch.mapper;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.example.shop.batch.vo.DailySalesVO;
import com.example.shop.order.vo.OrderVO;

/**
 * 일 매출 배치 매퍼. SQL 은 mapper/batch/BatchMapper.xml 에 있다.
 */
@Mapper
public interface BatchMapper {

    /** 리더(MyBatisPagingItemReader)가 queryId 로 직접 실행한다. Java 에서 이 메서드를 부르는 곳은 없다. */
    List<OrderVO> selectClosedOrders(Map<String, Object> param);

    int mergeDailySales(DailySalesVO vo);

    int deleteDailySales(String baseDt);

    List<Map<String, Object>> selectDailySalesReport(String baseDt);

    int selectIsHoliday(String baseDt);

    /** PKG_ORDER.CLOSE_DAILY 호출 */
    void callCloseDaily(Map<String, Object> param);

    /** SP_DAILY_SALES_SUM 호출 */
    void callDailySalesSum(@Param("baseDt") String baseDt);
}
