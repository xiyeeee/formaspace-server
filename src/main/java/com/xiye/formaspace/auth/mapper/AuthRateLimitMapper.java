package com.xiye.formaspace.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xiye.formaspace.auth.entity.AuthRateLimitEntity;
import java.time.Instant;
import org.apache.ibatis.annotations.*;

@Mapper
public interface AuthRateLimitMapper extends BaseMapper<AuthRateLimitEntity> {
    @Insert("""
            INSERT INTO fs_auth_rate_limit (bucket_key, window_start, attempts, expires_at, created_at, updated_at)
            VALUES (#{key}, #{start}, 1, #{expires}, #{now}, #{now})
            ON DUPLICATE KEY UPDATE attempts = LEAST(attempts + 1, 2147483647), updated_at = #{now}
            """)
    void hit(@Param("key") String key, @Param("start") long start, @Param("expires") Instant expires, @Param("now") Instant now);

    @Select("SELECT attempts FROM fs_auth_rate_limit WHERE bucket_key = #{key} AND window_start = #{start}")
    Integer attempts(@Param("key") String key, @Param("start") long start);

    @Insert("""
            INSERT INTO fs_auth_rate_limit (bucket_key, window_start, attempts, expires_at, created_at, updated_at)
            VALUES (#{key}, 0, 0, #{now}, #{now}, #{now}) ON DUPLICATE KEY UPDATE id = id
            """)
    void ensureCooldown(@Param("key") String key, @Param("now") Instant now);

    @Select("SELECT * FROM fs_auth_rate_limit WHERE bucket_key = #{key} AND window_start = 0 FOR UPDATE")
    AuthRateLimitEntity lockCooldown(@Param("key") String key);

    @Update("UPDATE fs_auth_rate_limit SET expires_at = #{expires}, updated_at = #{now}, attempts = 1 WHERE id = #{id}")
    void reserveCooldown(@Param("id") long id, @Param("expires") Instant expires, @Param("now") Instant now);
}
