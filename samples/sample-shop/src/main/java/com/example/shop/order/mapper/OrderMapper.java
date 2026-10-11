package com.example.shop.order.mapper;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.example.shop.order.vo.OrderItemVO;
import com.example.shop.order.vo.OrderSearchCond;
import com.example.shop.order.vo.OrderVO;

/**
 * 주문 매퍼. SQL 은 mapper/order/OrderMapper.xml (namespace = 이 인터페이스의 FQN) 에 있다.
 */
@Mapper
public interface OrderMapper {

    List<OrderVO> selectOrderList(OrderSearchCond cond);

    int selectOrderCount(OrderSearchCond cond);

    OrderVO selectOrderDetail(Long orderId);

    List<OrderItemVO> selectOrderItems(@Param("orderId") Long orderId);

    int insertOrder(OrderVO order);

    int insertOrderItems(@Param("orderId") Long orderId, @Param("items") List<OrderItemVO> items);

    int updateOrder(OrderVO order);

    int updateOrderStatus(@Param("orderId") Long orderId,
                          @Param("status") String status,
                          @Param("userId") String userId);

    int deleteOrderItems(Long orderId);

    int deleteOrder(Long orderId);

    /** PKG_ORDER.CLOSE_ORDER 호출. OUT 파라미터 resultCd 가 param 에 담겨 돌아온다. */
    void callCloseOrder(Map<String, Object> param);

    List<Map<String, Object>> selectOrderStatusStat(@Param("fromDt") String fromDt);
}
