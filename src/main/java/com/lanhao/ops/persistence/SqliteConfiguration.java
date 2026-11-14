package com.lanhao.ops.persistence;

import com.zaxxer.hikari.*;
import org.sqlite.SQLiteConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import javax.sql.DataSource;
import java.nio.file.*;

@Configuration
@Profile("sqlite")
public class SqliteConfiguration {
    @Bean(destroyMethod="close")
    @Primary
    HikariDataSource dataSource(@Value("${agent.sqlite.file:./data/agent.db}") String file)throws Exception {
        return open(Path.of(file),"agent-sqlite");
    }
    public static HikariDataSource open(Path file,String name)throws Exception {
        Path path=file.toAbsolutePath().normalize(); Files.createDirectories(path.getParent());
        var sqlite=new SQLiteConfig();
        sqlite.enforceForeignKeys(true); sqlite.setBusyTimeout(5000);
        sqlite.setJournalMode(SQLiteConfig.JournalMode.WAL);
        // 所有写事务在读改写之前取得预留写锁，取代 PostgreSQL 行锁。
        sqlite.setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE);
        var config=new HikariConfig(); config.setPoolName(name); config.setJdbcUrl("jdbc:sqlite:"+path);
        config.setDataSourceProperties(sqlite.toProperties()); config.setMaximumPoolSize(4);
        config.setMinimumIdle(1); config.setConnectionTimeout(10000);
        return new HikariDataSource(config);
    }
}
