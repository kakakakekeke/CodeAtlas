package com.example.shop.order.dao;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.stereotype.Repository;

import com.example.shop.order.vo.OrderVO;

/**
 * 주문/결제 DAO. 매퍼 인터페이스 없이 SqlSessionTemplate 에 구문 ID 문자열을 넘긴다.
 * 구문은 mapper/order/orderDao.xml (namespace = "orderDao") 에 있다.
 */
@Repository("orderDao")
public class OrderDao {

    private static final String NAMESPACE = "orderDao";

    /** 상수끼리 연결한 구문 ID */
    private static final String STMT_INSERT_PAYMENT = NAMESPACE + ".insertPayment";

    private final SqlSessionTemplate sqlSession;

    public OrderDao(SqlSessionTemplate sqlSessionTemplate) {
        this.sqlSession = sqlSessionTemplate;
    }

    // ------------------------------------------------------------------ 주문

    /** 문자열 리터럴 ID */
    public List<OrderVO> selectOrders(Map<String, Object> param) {
        return sqlSession.selectList("orderDao.selectOrders", param);
    }

    /** 보관 테이블 조회. 테이블 이름이 인자로 들어와 ${tableName} 에 치환된다. */
    public List<Map<String, Object>> selectOrderBackup(String tableName, String baseDt, String orderBy) {
        Map<String, Object> param = new HashMap<>();
        param.put("tableName", tableName);
        param.put("baseDt", baseDt);
        param.put("orderBy", orderBy);
        return sqlSession.selectList("orderDao.selectOrderBackup", param);
    }

    /** 상수 + 리터럴 연결 ID */
    public int insertOrder(OrderVO order) {
        return sqlSession.insert(NAMESPACE + ".insertOrder", order);
    }

    public int insertOrderHist(Long orderId, String userId) {
        Map<String, Object> param = new HashMap<>();
        param.put("orderId", orderId);
        param.put("userId", userId);
        return sqlSession.insert(NAMESPACE + ".insertOrderHist", param);
    }

    public List<Map<String, Object>> selectOrderHistory(Long orderId) {
        return sqlSession.selectList("orderDao.selectOrderHistory", orderId);
    }

    public long selectOrderAmount(Long orderId) {
        Long amount = sqlSession.selectOne("orderDao.selectOrderAmount", orderId);
        return amount == null ? 0L : amount;
    }

    public int updateDeliveryShip(Long orderId) {
        return sqlSession.update("orderDao.updateDeliveryShip", orderId);
    }

    /** PKG_ORDER.CANCEL_ORDER 호출 */
    public void callCancelOrder(Long orderId, String userId) {
        Map<String, Object> param = new HashMap<>();
        param.put("orderId", orderId);
        param.put("userId", userId);
        sqlSession.update("orderDao.callCancelOrder", param);
    }

    // ------------------------------------------------------------------ 결제

    /** 상수에 담아 둔 ID (STMT_INSERT_PAYMENT = NAMESPACE + ".insertPayment") */
    public int insertPayment(Map<String, Object> payment) {
        return sqlSession.insert(STMT_INSERT_PAYMENT, payment);
    }

    public List<Map<String, Object>> selectPaymentsByOrder(Long orderId) {
        return sqlSession.selectList("orderDao.selectPaymentsByOrder", orderId);
    }

    public int updateOrderByPayment(Long paymentId) {
        return sqlSession.update("orderDao.updateOrderByPayment", paymentId);
    }

    public int cancelPayment(Long orderId) {
        return sqlSession.update(NAMESPACE + ".cancelPayment", orderId);
    }

    // ------------------------------------------------------------------ 공통

    /** 다른 namespace(common) 의 구문을 부른다 */
    public List<Map<String, Object>> selectCodeList(String groupCd) {
        return sqlSession.selectList("common.selectCodeList", groupCd);
    }

    /**
     * 구문 ID 를 인자로 받는 범용 조회.
     * queryId 는 호출하는 쪽에서 조립하므로 여기서는 어떤 구문이 실행되는지 정적으로 알 수 없다.
     */
    public <T> List<T> selectByQueryId(String queryId, Object param) {
        return sqlSession.selectList(queryId, param);
    }
}
