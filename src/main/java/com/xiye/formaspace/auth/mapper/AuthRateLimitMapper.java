package com.xiye.formaspace.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xiye.formaspace.auth.entity.AuthRateLimitEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AuthRateLimitMapper extends BaseMapper<AuthRateLimitEntity> {
}
