package com.example.pg.mapper;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 고객 매퍼. SQL 은 mapper/CustomerMapper.xml 에 있다.
 */
@Mapper
public interface CustomerMapper {

    Long upsertCustomer(Map<String, Object> customer);

    List<Map<String, Object>> selectByProfile(@Param("city") String city, @Param("tag") String tag, @Param("filter") String filter);

    List<Map<String, Object>> selectLatestOrders(@Param("ids") Long[] ids);

    List<Map<String, Object>> selectDailyOrderCounts(@Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to);

    List<Map<String, Object>> selectReferralTree(Long rootId);

    List<Map<String, Object>> selectVipCustomers(String pattern);

    int putSession(@Param("key") String key, @Param("value") String value);

    List<Map<String, Object>> selectOrderSummary(@Param("customerId") Long customerId, @Param("minAmount") BigDecimal minAmount);

    List<Map<String, Object>> selectTableSizes(@Param("names") String[] names);
}
