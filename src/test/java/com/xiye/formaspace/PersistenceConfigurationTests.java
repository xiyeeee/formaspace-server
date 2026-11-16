package com.xiye.formaspace;

import com.baomidou.mybatisplus.extension.plugins.inner.BlockAttackInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.xiye.formaspace.config.MybatisPlusConfig;
import com.xiye.formaspace.config.UtcInstantTypeHandler;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDateTime;
import org.apache.ibatis.type.JdbcType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class PersistenceConfigurationTests {
    @Test
    void utcDatetimeMappingDoesNotDependOnJvmTimezone() throws Exception {
        var handler = new UtcInstantTypeHandler();
        var statement = mock(PreparedStatement.class);
        Instant instant = Instant.parse("2026-11-16T01:02:03.123456Z");
        LocalDateTime utc = LocalDateTime.parse("2026-11-16T01:02:03.123456");
        handler.setNonNullParameter(statement, 1, instant, JdbcType.TIMESTAMP);
        verify(statement).setObject(1, utc);
        var result = mock(ResultSet.class);
        when(result.getObject("created_at", LocalDateTime.class)).thenReturn(utc);
        assertThat(handler.getNullableResult(result, "created_at")).isEqualTo(instant);
        when(result.getObject("created_at", LocalDateTime.class)).thenReturn(null);
        assertThat(handler.getNullableResult(result, "created_at")).isNull();
    }

    @Test
    void paginationAndUnconditionalMutationProtectionAreActive() {
        var interceptors = new MybatisPlusConfig().mybatisPlusInterceptor().getInterceptors();
        assertThat(interceptors.getLast()).isInstanceOf(PaginationInnerInterceptor.class);
        assertThat(((PaginationInnerInterceptor) interceptors.getLast()).getMaxLimit()).isEqualTo(100L);
        var guard = (BlockAttackInnerInterceptor) interceptors.getFirst();
        assertThatThrownBy(() -> guard.parserSingle("UPDATE fs_user SET status = 'DISABLED'", null))
                .isInstanceOf(Exception.class);
        assertThatThrownBy(() -> guard.parserSingle("DELETE FROM fs_file", null))
                .isInstanceOf(Exception.class);
        guard.parserSingle("UPDATE fs_user SET status = 'DISABLED' WHERE id = 1", null);
    }
}
