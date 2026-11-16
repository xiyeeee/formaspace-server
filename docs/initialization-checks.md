# 初始化检查与变更清单

检查日期：2026-11-16。在已有 formaspace-server 内修改，未重新生成项目，未改动前端。原项目无健康检查和数据库迁移；保留既有 Java 21、Spring Boot 4.1.2 及启动类。项目未发现 AGENTS.md 文件，本轮遵循会话内提供的 AGENTS.md 指令。

## 验证结果

- Maven 依赖解析与 Java 编译通过。
- 34 项自动测试通过（0 失败、0 错误、0 跳过）：账号/验证码/密码验证 25 项，HTTP/错误/CORS/序列化 7 项，UTC 映射/分页与 SQL 防护 2 项。
- 测试包括密码一致性（注册及改密）、两端长度边界、空字段、非法用户名/邮箱/验证码、规范化保留点与加号、密码保留空格、正 Long ID 范围和分页上下限。
- HTTP 测试使用 MVC slice，不连接数据库；验证健康检查统一结构、404/405 与 Allow 头、绑定/JSON/类型错误、业务 409、未知 500、敏感异常消息不进入输出日志、CORS 白名单、ID 字符串及 UTC Z 时间。
- SQL 防护测试实际解析无条件 UPDATE/DELETE 并确认阻断；时间处理器测试确认 JDBC 使用 UTC LocalDateTime 映射。它们不替代真实 MySQL Mapper 读写验证。
- 可执行 JAR 打包通过；测试专用 Controller 不在产物中。
- git diff --check 通过；未执行真实邮件发送；没有新建业务鉴权 Controller 或 Service 空壳。

本机 Maven 全局 settings.xml 配有内网仓库，构建出现连接等待。最终验证使用临时 `/tmp/formaspace-maven-settings.xml` 仅指向 Maven Central，未改全局设置，未将临时设置加入仓库：

```sh
./mvnw -s /tmp/formaspace-maven-settings.xml -ntp package
```

新环境正常使用 `./mvnw test` / `./mvnw package`。若也遇到仓库连接问题，可在项目外准备只含 Maven Central 的临时 Maven settings 并用 -s 指定，不必改项目依赖版本。

## 解析到的版本

| 组件 | 版本 | 选择依据 |
| --- | --- | --- |
| JDK | 21（验证环境 21.0.2） | 保留项目设置 |
| Spring Boot | 4.1.2 | 保留现有 parent |
| Spring Framework | 7.0.10 | Boot BOM |
| Jackson | 3.1.7 | Boot BOM，使用 Boot 4 默认 Jackson 3 |
| MyBatis-Plus starter/jsqlparser | 3.5.18 | 官方 Boot 4 starter 及独立分页依赖，同版本集中管理 |
| Flyway core/mysql | 12.4.0 | Boot BOM，Boot Flyway starter 加 MySQL 模块 |
| MySQL Connector/J | 9.7.0 | Boot BOM，runtime |
| Lombok | 1.18.48 | Boot BOM，原有注解处理器 |

官方说明链接见 README。错误展示配置使用 Boot 4 的 `spring.web.error.*`，不沿用已移除的 `server.error.*` 配置。

## 未验证项

本机 127.0.0.1:3306 不可连接、Docker daemon 未运行，没有可用 MySQL 连接或凭证。因此以下项目明确未验证：

- 真实 MySQL 上的 Flyway 首次迁移、校验与重复启动。
- 完整应用连接数据库后的启动和真实 HTTP 探活。
- 数据库唯一/CHECK/外键约束及索引执行效果。
- Mapper 的自增 ID、枚举、审计时间和 UTC 的真实 JDBC 往返读写。
- SMTP 连通性、真实投递、HMAC 生成/验证及限流事务（后几项业务本轮本就不实现）。

数据库就绪后按 README 建库并设置环境变量启动，确认 Flyway schema history 中版本 1 成功，再重启验证无重复迁移。可检查七个表（六张业务表与 flyway_schema_history）；不得手工重复执行 V1 到已迁移数据库。

## 实际变更文件

修改：

- `.gitignore`：忽略本地凭证文件，保留 .env.example。
- `pom.xml`：补齐 MyBatis-Plus、分页、Flyway/MySQL、Mail、Boot Test，保留 Boot/JDK。
- `src/test/java/com/xiye/formaspace/FormaspaceServerApplicationTests.java`：原空 contextLoads 改为实际 MVC 行为测试。

配置重命名并扩展：删除 `src/main/resources/application.yaml`，新增 `application.yml`，不保留双份配置。

新增文件（以本轮实际文件名为准）：

- `.env.example`
- `README.md`
- `docs/auth-plan.md`
- `docs/email-reuse.md`
- `docs/initialization-checks.md`
- `skills/initialSkill/v1.0.1.md`
- `sql/create_database.sql`
- `src/main/java/com/xiye/formaspace/asset/dto/AssetQueryDTO.java`
- `src/main/java/com/xiye/formaspace/asset/dto/RenameAssetDTO.java`
- `src/main/java/com/xiye/formaspace/asset/entity/ModelAssetEntity.java`
- `src/main/java/com/xiye/formaspace/asset/mapper/ModelAssetMapper.java`
- `src/main/java/com/xiye/formaspace/asset/vo/ModelAssetVO.java`
- `src/main/java/com/xiye/formaspace/auth/dto/LoginDTO.java`
- `src/main/java/com/xiye/formaspace/auth/dto/RegisterDTO.java`
- `src/main/java/com/xiye/formaspace/auth/dto/ResetPasswordDTO.java`
- `src/main/java/com/xiye/formaspace/auth/dto/SendEmailCodeDTO.java`
- `src/main/java/com/xiye/formaspace/auth/entity/AuthRateLimitEntity.java`
- `src/main/java/com/xiye/formaspace/auth/entity/EmailVerificationEntity.java`
- `src/main/java/com/xiye/formaspace/auth/enums/VerificationPurpose.java`
- `src/main/java/com/xiye/formaspace/auth/mapper/AuthRateLimitMapper.java`
- `src/main/java/com/xiye/formaspace/auth/mapper/EmailVerificationMapper.java`
- `src/main/java/com/xiye/formaspace/auth/vo/EmailCodeSendVO.java`
- `src/main/java/com/xiye/formaspace/auth/vo/LoginVO.java`
- `src/main/java/com/xiye/formaspace/common/enums/FilePurpose.java`
- `src/main/java/com/xiye/formaspace/common/enums/UserStatus.java`
- `src/main/java/com/xiye/formaspace/common/exception/BusinessException.java`
- `src/main/java/com/xiye/formaspace/common/exception/ErrorCode.java`
- `src/main/java/com/xiye/formaspace/common/exception/GlobalExceptionHandler.java`
- `src/main/java/com/xiye/formaspace/common/response/ApiResponse.java`
- `src/main/java/com/xiye/formaspace/common/response/PageQuery.java`
- `src/main/java/com/xiye/formaspace/common/response/PageResult.java`
- `src/main/java/com/xiye/formaspace/common/validation/AccountIdentifiers.java`
- `src/main/java/com/xiye/formaspace/common/validation/EmailAddress.java`
- `src/main/java/com/xiye/formaspace/common/validation/PasswordsMatch.java`
- `src/main/java/com/xiye/formaspace/common/validation/PasswordsMatchValidator.java`
- `src/main/java/com/xiye/formaspace/config/AuditMetaObjectHandler.java`
- `src/main/java/com/xiye/formaspace/config/CorsProperties.java`
- `src/main/java/com/xiye/formaspace/config/EmailCodeProperties.java`
- `src/main/java/com/xiye/formaspace/config/MybatisPlusConfig.java`
- `src/main/java/com/xiye/formaspace/config/UtcInstantTypeHandler.java`
- `src/main/java/com/xiye/formaspace/config/WebConfig.java`
- `src/main/java/com/xiye/formaspace/file/entity/FileEntity.java`
- `src/main/java/com/xiye/formaspace/file/mapper/FileMapper.java`
- `src/main/java/com/xiye/formaspace/file/vo/FileUploadVO.java`
- `src/main/java/com/xiye/formaspace/generation/dto/CreateGenerationDTO.java`
- `src/main/java/com/xiye/formaspace/generation/dto/GenerationQueryDTO.java`
- `src/main/java/com/xiye/formaspace/generation/entity/GenerationTaskEntity.java`
- `src/main/java/com/xiye/formaspace/generation/enums/GenerationStatus.java`
- `src/main/java/com/xiye/formaspace/generation/mapper/GenerationTaskMapper.java`
- `src/main/java/com/xiye/formaspace/generation/vo/GenerationTaskVO.java`
- `src/main/java/com/xiye/formaspace/health/controller/HealthController.java`
- `src/main/java/com/xiye/formaspace/user/entity/UserEntity.java`
- `src/main/java/com/xiye/formaspace/user/mapper/UserMapper.java`
- `src/main/java/com/xiye/formaspace/user/vo/UserVO.java`
- `src/main/resources/application-local.yml`
- `src/main/resources/application-prod.yml`
- `src/main/resources/application.yml`
- `src/main/resources/db/migration/V1__init_schema.sql`
- `src/test/java/com/xiye/formaspace/AccountValidationTests.java`
- `src/test/java/com/xiye/formaspace/PersistenceConfigurationTests.java`
