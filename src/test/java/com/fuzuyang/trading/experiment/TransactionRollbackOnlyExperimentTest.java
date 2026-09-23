package com.fuzuyang.trading.experiment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 事务回滚陷阱实验：@Transactional 内 catch 唯一键异常后继续执行会发生什么。
 *
 * <p>用于验证底层机制，非业务代码。三个场景：</p>
 * <ol>
 *   <li>陷阱版：外层 {@code @Transactional}，内层默认 {@code REQUIRED} 加入外层事务；
 *       内层抛 {@link DuplicateKeyException} 被外层 catch 后继续，最终外层提交时抛
 *       {@link UnexpectedRollbackException}，且重试写入的数据未落库。</li>
 *   <li>修正版一：内层改用 {@code REQUIRES_NEW}，每次重试在独立事务中，提交正常。</li>
 *   <li>修正版二：外层不做事务（编排层），内层各自独立事务，提交正常。</li>
 * </ol>
 *
 * <p>基于 H2 内存库运行，无需 MySQL/Redis/Kafka。</p>
 */
@SpringBootTest
@Import(TransactionRollbackOnlyExperimentTest.ExperimentConfig.class)
class TransactionRollbackOnlyExperimentTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private OuterTxService outer;

    @BeforeEach
    void resetFixture() {
        jdbc.execute("DROP TABLE IF EXISTS exp_tx");
        jdbc.execute("CREATE TABLE exp_tx (id VARCHAR(64) PRIMARY KEY)");
        // 预置一行 A，制造唯一键冲突
        jdbc.update("INSERT INTO exp_tx(id) VALUES (?)", "A");
    }

    @Test
    void trapShouldThrowUnexpectedRollbackException() {
        boolean thrown = false;
        try {
            outer.trap();
        } catch (UnexpectedRollbackException ex) {
            thrown = true;
            System.out.println("=== [陷阱版] 外层提交时抛出 UnexpectedRollbackException ===");
            ex.printStackTrace(System.out);
        }
        assertThat(thrown).as("陷阱版应抛出 UnexpectedRollbackException").isTrue();
        assertThat(count("B")).as("陷阱版：重试写入的 B 不应落库").isZero();
    }

    @Test
    void requiresNewFixShouldPersist() {
        outer.fixedByRequiresNew();

        assertThat(count("B")).as("REQUIRES_NEW 修正版：B 应落库").isEqualTo(1);
        System.out.println("=== [REQUIRES_NEW 修正版] B 已落库，无异常 ===");
    }

    @Test
    void orchestrationFixShouldPersist() {
        outer.fixedByOrchestration();

        assertThat(count("B")).as("事务外编排修正版：B 应落库").isEqualTo(1);
        System.out.println("=== [事务外编排修正版] B 已落库，无异常 ===");
    }

    private int count(String id) {
        Integer c = jdbc.queryForObject("SELECT COUNT(*) FROM exp_tx WHERE id = ?", Integer.class, id);
        return c == null ? 0 : c;
    }

    @TestConfiguration
    static class ExperimentConfig {

        @Bean
        InnerTxService innerTxService(JdbcTemplate jdbc) {
            return new InnerTxService(jdbc);
        }

        @Bean
        OuterTxService outerTxService(InnerTxService innerTxService) {
            return new OuterTxService(innerTxService);
        }
    }

    /** 内层落库单元：默认 REQUIRED；另提供 REQUIRES_NEW 版本。 */
    static class InnerTxService {

        private final JdbcTemplate jdbc;

        InnerTxService(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        /** 默认 REQUIRED：若外层已有事务则加入它。 */
        @Transactional
        public void insert(String id) {
            jdbc.update("INSERT INTO exp_tx(id) VALUES (?)", id);
        }

        /** REQUIRES_NEW：总是开启独立事务（挂起外层事务）。 */
        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public void insertInNewTx(String id) {
            jdbc.update("INSERT INTO exp_tx(id) VALUES (?)", id);
        }
    }

    /** 外层编排：陷阱版带事务，修正版不带事务。 */
    static class OuterTxService {

        private final InnerTxService inner;

        OuterTxService(InnerTxService inner) {
            this.inner = inner;
        }

        /** 陷阱版：外层事务 + catch 后继续。 */
        @Transactional
        public void trap() {
            try {
                inner.insert("A"); // A 已存在 → 唯一键冲突
            } catch (DuplicateKeyException ex) {
                inner.insert("B"); // 在同一个（已被标记 rollback-only 的）事务中继续
            }
        }

        /** 修正版一：让易失败的写入各自独立事务。 */
        @Transactional
        public void fixedByRequiresNew() {
            try {
                inner.insertInNewTx("A");
            } catch (DuplicateKeyException ex) {
                inner.insertInNewTx("B");
            }
        }

        /** 修正版二：外层不做事务，catch 发生在事务之外，内层各自独立事务。 */
        public void fixedByOrchestration() {
            try {
                inner.insert("A");
            } catch (DuplicateKeyException ex) {
                inner.insert("B");
            }
        }
    }
}
