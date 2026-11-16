package com.xiye.formaspace.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xiye.formaspace.auth.entity.EmailVerificationEntity;
import java.time.Instant;
import org.apache.ibatis.annotations.*;

@Mapper
public interface EmailVerificationMapper extends BaseMapper<EmailVerificationEntity> {
    @Insert("""
            INSERT INTO fs_email_verification
              (email, purpose, code_hash, expires_at, failed_attempts, sent_at, created_at, updated_at, send_status)
            VALUES (#{email}, 'REGISTER', REPEAT('0', 64), #{now}, 0, #{initialSentAt}, #{now}, #{now}, 'FAILED')
            ON DUPLICATE KEY UPDATE id = id
            """)
    void ensureSlot(@Param("email") String email, @Param("now") Instant now, @Param("initialSentAt") Instant initialSentAt);

    @Select("SELECT * FROM fs_email_verification WHERE email = #{email} AND purpose = 'REGISTER' FOR UPDATE")
    EmailVerificationEntity lockRegistration(@Param("email") String email);

    @Update("""
            UPDATE fs_email_verification SET challenge_id = #{challenge}, code_hash = #{hash},
              send_status = 'PENDING', expires_at = #{expires}, sent_at = #{now}, consumed_at = NULL,
              failed_attempts = 0, updated_at = #{now} WHERE id = #{id}
            """)
    int reserve(@Param("id") long id, @Param("challenge") String challenge, @Param("hash") String hash,
            @Param("now") Instant now, @Param("expires") Instant expires);

    @Update("""
            UPDATE fs_email_verification SET send_status = #{status}, updated_at = #{now}
            WHERE email = #{email} AND purpose = 'REGISTER' AND challenge_id = #{challenge} AND send_status = 'PENDING'
            """)
    int finishSend(@Param("email") String email, @Param("challenge") String challenge,
            @Param("status") String status, @Param("now") Instant now);

    @Update("UPDATE fs_email_verification SET failed_attempts = failed_attempts + 1, updated_at = #{now} WHERE id = #{id}")
    int recordFailure(@Param("id") long id, @Param("now") Instant now);

    @Update("UPDATE fs_email_verification SET consumed_at = #{now}, updated_at = #{now} WHERE id = #{id} AND consumed_at IS NULL")
    int consume(@Param("id") long id, @Param("now") Instant now);
}
