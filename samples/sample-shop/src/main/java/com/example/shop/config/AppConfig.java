package com.example.shop.config;

import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.example.shop.common.PageRequest;

/**
 * 애플리케이션 Java 설정.
 * 기본 sqlSessionTemplate 과 paymentService 는 applicationContext.xml 에 있다.
 */
@Configuration
@MapperScan("com.example.shop")
public class AppConfig {

    /** 배치용 SqlSession (BATCH 실행기). SettleDao 가 @Qualifier("batchSqlSession") 으로 주입받는다. */
    @Bean
    public SqlSessionTemplate batchSqlSession(SqlSessionFactory sqlSessionFactory) {
        return new SqlSessionTemplate(sqlSessionFactory, ExecutorType.BATCH);
    }

    /** 빈 이름을 name 속성으로 따로 준 경우 */
    @Bean(name = "defaultPage")
    public PageRequest defaultPageRequest() {
        return PageRequest.of(1, 20);
    }
}
