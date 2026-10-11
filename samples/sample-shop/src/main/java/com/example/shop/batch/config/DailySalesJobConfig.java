package com.example.shop.batch.config;

import java.util.HashMap;
import java.util.Map;

import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.batch.MyBatisPagingItemReader;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import com.example.shop.batch.CloseOrderTasklet;
import com.example.shop.batch.SalesProcessor;
import com.example.shop.batch.SalesWriter;
import com.example.shop.batch.vo.DailySalesVO;
import com.example.shop.order.vo.OrderVO;

/**
 * 일 매출 집계 잡 (Java 설정).
 *
 *   dailySalesJob
 *     ├─ salesSumStep   : 청크 스텝 (리더 = MyBatisPagingItemReader, 프로세서, 라이터)
 *     └─ closeOrderStep : 태스크릿 스텝 (주문 마감 프로시저 호출)
 */
@Configuration
public class DailySalesJobConfig {

    private static final int CHUNK_SIZE = 100;

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final SqlSessionFactory sqlSessionFactory;

    public DailySalesJobConfig(JobRepository jobRepository,
                               PlatformTransactionManager transactionManager,
                               SqlSessionFactory sqlSessionFactory) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
        this.sqlSessionFactory = sqlSessionFactory;
    }

    @Bean
    public Job dailySalesJob(Step salesSumStep, Step closeOrderStep) {
        return new JobBuilder("dailySalesJob", jobRepository)
                .start(salesSumStep)
                .next(closeOrderStep)
                .build();
    }

    @Bean
    public Step salesSumStep(SalesProcessor salesProcessor, SalesWriter salesWriter) {
        return new StepBuilder("salesSumStep", jobRepository)
                .<OrderVO, DailySalesVO>chunk(CHUNK_SIZE, transactionManager)
                .reader(closedOrderReader(null))
                .processor(salesProcessor)
                .writer(salesWriter)
                .build();
    }

    @Bean
    public Step closeOrderStep(CloseOrderTasklet closeOrderTasklet) {
        return new StepBuilder("closeOrderStep", jobRepository)
                .tasklet(closeOrderTasklet, transactionManager)
                .build();
    }

    /** 마감 대상 주문 리더. 실행할 구문은 queryId 문자열로만 알 수 있다. */
    @Bean
    @StepScope
    public MyBatisPagingItemReader<OrderVO> closedOrderReader(
            @Value("#{jobParameters['baseDt']}") String baseDt) {
        Map<String, Object> param = new HashMap<>();
        param.put("baseDt", baseDt);

        MyBatisPagingItemReader<OrderVO> reader = new MyBatisPagingItemReader<>();
        reader.setSqlSessionFactory(sqlSessionFactory);
        reader.setQueryId("com.example.shop.batch.mapper.BatchMapper.selectClosedOrders");
        reader.setParameterValues(param);
        reader.setPageSize(CHUNK_SIZE);
        return reader;
    }
}
