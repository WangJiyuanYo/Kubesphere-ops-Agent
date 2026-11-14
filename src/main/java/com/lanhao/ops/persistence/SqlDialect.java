package com.lanhao.ops.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import java.sql.*;
import java.util.UUID;

/** 只展开仓库内声明的方言标记，不处理用户 SQL 或修改参数。 */
public final class SqlDialect {
    private final boolean sqlite;
    public SqlDialect(JdbcTemplate jdbc) {
        var source=java.util.Objects.requireNonNull(jdbc.getDataSource());
        Connection connection=DataSourceUtils.getConnection(source);
        try {
            String product=connection.getMetaData().getDatabaseProductName();
            if(!product.equals("SQLite")&&!product.equals("PostgreSQL")) throw new IllegalStateException("仅支持 SQLite 或 PostgreSQL");
            sqlite=product.equals("SQLite");
            if(sqlite) jdbc.setExceptionTranslator((task,sql,error)->switch(error.getErrorCode()&255) {
                case 19->new org.springframework.dao.DataIntegrityViolationException("数据库约束冲突",error);
                case 5,6->new org.springframework.dao.CannotAcquireLockException("SQLite 写锁繁忙，请稍后重试",error);
                default->new org.springframework.jdbc.support.SQLExceptionSubclassTranslator().translate(task,sql,error);
            });
        } catch(SQLException failure){throw new IllegalStateException("数据库类型不可读取",failure);}
        finally{DataSourceUtils.releaseConnection(connection,source);}
    }
    public boolean sqlite(){return sqlite;}
    public String sql(String template) {
        String now=sqlite?"CAST(unixepoch('subsec')*1000 AS INTEGER)":"now()";
        return template.replace("@json@",sqlite?"?":"?::jsonb").replace("@text@",sqlite?"":"::text")
                .replace("@afterSeconds@",sqlite?now+"+(? * 1000)":"now()+(? * interval '1 second')")
                .replace("@chatUntil@",sqlite?now+"+90000":"now()+interval '90 seconds'")
                .replace("@notificationUntil@",sqlite?now+"+30000":"now()+interval '30 seconds'")
                .replace("@inboxDue@",sqlite?now+"+5000":"now()+interval '5 seconds'")
                .replace("@now@",now).replace("@skipLocked@",sqlite?"":" FOR UPDATE SKIP LOCKED")
                .replace("@lock@",sqlite?"":" FOR UPDATE");
    }
    public static UUID uuid(ResultSet row,String column)throws SQLException {
        String value=row.getString(column); return value==null?null:UUID.fromString(value);
    }
    public static UUID uuid(ResultSet row,int column)throws SQLException {
        String value=row.getString(column); return value==null?null:UUID.fromString(value);
    }
}
