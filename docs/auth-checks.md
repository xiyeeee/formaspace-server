# 第二阶段鉴权实现与验证

日期：2026-11-16。基于现有 formaspace-server 实现，没有重建项目或修改前端。按用户提供的第二阶段要求完成账号流程；用户在工作期间将提示词文件改名为 skills/initialSkill/server1.0.1.md 与 server1.0.2.md，这些改名保持原样。

## 实现

- POST email-codes / register / login、GET me 和原有 GET health。真实验证码投递流程与真实框架 JWT，不提供假 Token、假发送接口或后端 logout。
- Boot 4.1.2 / Java 21 保留；新增 Boot Security、Security OAuth2 Resource Server starter 和 security-test，均由 Boot BOM 管理。解析版本 Spring Security 7.1.2；没有新增另一套 JWT 库或中间件。
- 密码为 DelegatingPasswordEncoder + PBKDF2；JWT HS256 框架签发/固定算法验签、必要 claims/issuer/audience/时间/sub 校验，默认 2 小时。
- STATELESS、精确公开路径、仅 Authorization Bearer、默认要求认证、关闭表单/Basic/请求缓存/Session 登录/CSRF。统一安全链 401/403/数据库 503 JSON；MVC 中的权限异常同样保持 403。
- 每次认证查 ACTIVE 用户，数据库错误使用不携带 JDBC 详情的专用 AuthenticationStorageException，避免框架将 AuthenticationServiceException 重新抛出而丢失统一 503 响应。
- 邮箱验证码使用挑战绑定 HMAC、数据库精确冷却/固定窗口配额；登录仅累积失败数。SMTP 在事务外，按挑战条件确认发送状态；注册原子插入与消费，错误计数提交后返回失败。
- V1 **没有修改**。新增 V2__authentication_challenges.sql：last_login_at、challenge_id、PENDING/SENT/FAILED、可配置失败上限约束；历史无挑战摘要失效。V1 已有邮箱/用户名唯一索引、验证码槽/限流桶索引，因此不重复创建。
- SMTP 默认关闭时发送返回 MAIL_UNAVAILABLE；启用时验证主机/用户名/密码/发件人/TLS。JWT 和 HMAC 两把独立 Base64 密钥均必填，缺失或过短拒绝启动。配置/命令见 README 和 .env.example。

官方兼容性依据：[Spring Security Resource Server JWT](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html)、[密码存储](https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html)、[Boot 4.1 BOM](https://docs.spring.io/spring-boot/4.1/appendix/dependency-versions/coordinates.html)。

## 已通过

使用项目外临时 Maven Central settings，绕过本机全局不可用内网仓库，没有修改全局配置：

```sh
./mvnw -s /tmp/formaspace-maven-settings.xml -ntp package
```

- Java 编译、依赖解析、可执行 JAR 打包成功。
- 共发现 82 项测试，**72 通过、10 跳过、0 失败、0 错误**。
- AuthSecurityTests 21 项：真实 Encoder/Decoder、最小 claims、默认 TTL、Bearer /me、无 Session Cookie、no-store、13 种坏 Token、禁用/删除、数据库 503、精确放行、CORS、429 Retry-After、403 JSON。
- AuthServiceTests 8 项：PBKDF2 带盐哈希、原样密码、未知/禁用账号仍比对、注册提交顺序、错码提交、重复账号回滚、消费失败回滚、不可验证状态拒绝。
- EmailVerificationServiceTests 5 项：SMTP 不在事务中、失败标记、过期挑战确认拒绝、已注册邮箱限流且不投递、关闭功能/未开放用途明确拒绝。
- AuthRateLimiterTests 2 项：失败计数独立提交后 429、HMAC 维度与精确冷却向上取秒。
- VerificationProtectorTests 2 项：摘要绑定邮箱/用途/挑战/码、常量时间接口、随机码格式、缺失/非法/过短密钥拒绝。
- 原有 34 项校验、HTTP 基础、分页防护与 UTC 测试通过；其中原 MVC 基础测试显式关闭安全过滤器，只验证原基础行为，新安全链由 AuthSecurityTests 独立真实加载。
- 测试日志中未发现测试用敏感异常消息、密码和 Bearer Token；异常只记录类型与安全栈帧。
- JAR 中业务 Controller 仅 AuthController 和 HealthController，没有测试 Probe 或验证码旁路。
- git diff --check 通过。

## 未验证

本机 MySQL 端口不可用、Docker daemon 未运行，未提供 AUTH_TEST_DB_URL。因此 **AuthMysqlTests 的 10 项均跳过**。没有将 mock 事务测试描述为真实数据库验证，也没有使用 H2 冒充 MySQL。

待真实 MySQL 执行的项目：

1. Flyway V1→V2 首次/升级迁移、完整启动、Mapper 自增 ID/枚举/UTC/审计真实读写。
2. 发送→注册→用户名与邮箱登录→JWT /me 完整 HTTP 流程和 last_login_at。
3. 五次错码真实持久化，唯一冲突真实回滚且保留有效挑战。
4. 同挑战并发仅成功一次，旧码/过期/已消费/PENDING/FAILED 拒绝。
5. 精确冷却、邮箱/IP 限流、登录失败窗口与注册限流。
6. SMTP 模拟失败、并发重发时旧 SMTP 完成不覆盖新挑战，以及封禁后 Token 失效。

这些场景已编写在 AuthMysqlTests，可按 README 在**独立可清空且名称以 _test 结尾**的 MySQL 测试库执行。该类用 mock 邮件捕获验证码，不连接个人 SMTP。真实 SMTP 连通性与投递亦未测试；开启邮件功能前按实际供应商配置验证。

## garden 参考

本轮实际读取 /tmp/formaspace-garden-reference 中的 VerificationProtector、EmailVerificationService、AuthRateLimiter、VerificationMailSender、AccountIdentifier（仓库提交 a9b88117aed4e6ba4a0ddb6e0b144098e44dd073）。可核对的上游链接和差异见 [email-reuse.md](email-reuse.md)。未复制 Session、Cookie、CSRF 或真实凭证。

## 前端对接与启动

配置 DB_URL/DB_USERNAME/DB_PASSWORD、JWT_SECRET、AUTH_CODE_HMAC_KEY；需要注册邮件时另配 SMTP_* 并启用 EMAIL_CODE_ENABLED。Spring Boot 不读取 .env，生产凭证没有默认值。README 包含建库、生成密钥（不打印值）、启动及 MySQL 测试步骤。

[auth-api.md](auth-api.md) 提供完整请求/响应、错误码、Token 和退出语义及可直接替换占位值的 curl 示例。公开请求不要附旧 Token；401 清理本地登录时避免跳转循环，503 不按密码错误处理。没有修改前端或替用户选择长期 Token 存储方式。

## 本轮文件清单

修改：

- `.env.example`
- `README.md`
- `docs/auth-plan.md`
- `docs/email-reuse.md`
- `pom.xml`
- `src/main/java/com/xiye/formaspace/auth/entity/EmailVerificationEntity.java`
- `src/main/java/com/xiye/formaspace/auth/mapper/AuthRateLimitMapper.java`
- `src/main/java/com/xiye/formaspace/auth/mapper/EmailVerificationMapper.java`
- `src/main/java/com/xiye/formaspace/common/exception/BusinessException.java`
- `src/main/java/com/xiye/formaspace/common/exception/ErrorCode.java`
- `src/main/java/com/xiye/formaspace/common/exception/GlobalExceptionHandler.java`
- `src/main/java/com/xiye/formaspace/config/EmailCodeProperties.java`
- `src/main/java/com/xiye/formaspace/config/WebConfig.java`
- `src/main/java/com/xiye/formaspace/user/entity/UserEntity.java`
- `src/main/java/com/xiye/formaspace/user/mapper/UserMapper.java`
- `src/main/java/com/xiye/formaspace/user/vo/UserVO.java`
- `src/main/resources/application.yml`
- `src/test/java/com/xiye/formaspace/FormaspaceServerApplicationTests.java`

新增：

- `docs/auth-api.md`
- `docs/auth-checks.md`
- `src/main/java/com/xiye/formaspace/auth/config/AuthRateLimitProperties.java`
- `src/main/java/com/xiye/formaspace/auth/config/JwtProperties.java`
- `src/main/java/com/xiye/formaspace/auth/controller/AuthController.java`
- `src/main/java/com/xiye/formaspace/auth/enums/EmailSendStatus.java`
- `src/main/java/com/xiye/formaspace/auth/security/ActiveUserAuthenticationConverter.java`
- `src/main/java/com/xiye/formaspace/auth/security/AuthenticationStorageException.java`
- `src/main/java/com/xiye/formaspace/auth/security/CurrentUser.java`
- `src/main/java/com/xiye/formaspace/auth/security/SecretKeys.java`
- `src/main/java/com/xiye/formaspace/auth/security/SecurityJsonResponse.java`
- `src/main/java/com/xiye/formaspace/auth/security/UserPrincipal.java`
- `src/main/java/com/xiye/formaspace/auth/service/AuthRateLimiter.java`
- `src/main/java/com/xiye/formaspace/auth/service/AuthService.java`
- `src/main/java/com/xiye/formaspace/auth/service/EmailVerificationService.java`
- `src/main/java/com/xiye/formaspace/auth/service/TokenService.java`
- `src/main/java/com/xiye/formaspace/auth/service/VerificationMailSender.java`
- `src/main/java/com/xiye/formaspace/auth/service/VerificationProtector.java`
- `src/main/java/com/xiye/formaspace/config/JwtConfig.java`
- `src/main/java/com/xiye/formaspace/config/SecurityConfig.java`
- `src/main/resources/db/migration/V2__authentication_challenges.sql`
- `src/main/resources/templates/mail/register-code.txt`
- `src/test/java/com/xiye/formaspace/auth/AuthMysqlTests.java`
- `src/test/java/com/xiye/formaspace/auth/AuthRateLimiterTests.java`
- `src/test/java/com/xiye/formaspace/auth/AuthSecurityTests.java`
- `src/test/java/com/xiye/formaspace/auth/AuthServiceTests.java`
- `src/test/java/com/xiye/formaspace/auth/EmailVerificationServiceTests.java`
- `src/test/java/com/xiye/formaspace/auth/VerificationProtectorTests.java`
