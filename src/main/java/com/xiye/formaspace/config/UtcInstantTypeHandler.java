package com.xiye.formaspace.config;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

/** DATETIME(6) stores UTC wall time; never interpret it in the host timezone. */
@MappedTypes(Instant.class)
public class UtcInstantTypeHandler extends BaseTypeHandler<Instant> {
    @Override
    public void setNonNullParameter(PreparedStatement statement, int index, Instant value, JdbcType jdbcType)
            throws SQLException {
        statement.setObject(index, LocalDateTime.ofInstant(value, ZoneOffset.UTC));
    }

    @Override
    public Instant getNullableResult(ResultSet result, String column) throws SQLException {
        return toInstant(result.getObject(column, LocalDateTime.class));
    }

    @Override
    public Instant getNullableResult(ResultSet result, int column) throws SQLException {
        return toInstant(result.getObject(column, LocalDateTime.class));
    }

    @Override
    public Instant getNullableResult(CallableStatement statement, int column) throws SQLException {
        return toInstant(statement.getObject(column, LocalDateTime.class));
    }

    private Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
