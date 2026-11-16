package com.xiye.formaspace.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AuditMetaObjectHandler implements MetaObjectHandler {
    private final Clock clock;

    @Override
    public void insertFill(MetaObject metaObject) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        strictInsertFill(metaObject, "createdAt", Instant.class, now);
        strictInsertFill(metaObject, "updatedAt", Instant.class, now);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        setFieldValByName("updatedAt", clock.instant().truncatedTo(ChronoUnit.MICROS), metaObject);
    }
}
