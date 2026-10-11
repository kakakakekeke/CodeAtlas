package com.example.pg.mapper;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 주문 매퍼. SQL 은 mapper/OrderMapper.xml 에 있다.
 */
@Mapper
public interface OrderMapper {

    List<Map<String, Object>> selectOrderPage(Map<String, Object> cond);

    List<Map<String, Object>> selectOrdersForUpdate(@Param("ids") List<Long> ids);

    int insertOrder(Map<String, Object> order);

    int insertOrderItems(@Param("orderId") Long orderId, @Param("items") List<Map<String, Object>> items);

    List<Long> updateStatusByEmail(@Param("email") String email, @Param("status") String status, @Param("memo") String memo);

    int deleteCancelledPayments(@Param("before") LocalDate before);

    Map<String, Object> markPaidOrders();

    List<Map<String, Object>> selectProductStats();

    Integer callCloseOrder(@Param("orderId") Long orderId, @Param("memo") String memo);

    void callArchiveOrders(Map<String, Object> param);

    List<Map<String, Object>> selectTimeline(Long orderId);
}
