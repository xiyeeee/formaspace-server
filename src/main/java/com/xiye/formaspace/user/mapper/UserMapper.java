package com.xiye.formaspace.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xiye.formaspace.user.entity.UserEntity;
import java.time.Instant;
import org.apache.ibatis.annotations.*;

@Mapper
public interface UserMapper extends BaseMapper<UserEntity> {
    @Select("SELECT id FROM fs_user WHERE id = #{id} AND status = 'ACTIVE' FOR UPDATE")
    Long lockActiveIdentity(@Param("id") long id);

    @Select("SELECT * FROM fs_user WHERE email = #{email}")
    UserEntity findByEmail(@Param("email") String email);

    @Select("SELECT * FROM fs_user WHERE username = #{username}")
    UserEntity findByUsername(@Param("username") String username);

    @Update("UPDATE fs_user SET last_login_at = #{now}, updated_at = #{now} WHERE id = #{id} AND status = 'ACTIVE'")
    int touchLastLogin(@Param("id") long id, @Param("now") Instant now);
}
