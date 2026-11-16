# FormaSpace Server

基于现有 Spring Boot 单体的图片转 3D 应用后端。已完成基础工程、注册邮箱验证码、统一注册、用户名/邮箱密码登录、JWT 鉴权和当前用户查询。前端对接以 [docs/auth-api.md](docs/auth-api.md) 为准。

## 环境和依赖

- JDK 21；保留 Spring Boot **4.1.2** 和现有 Maven Wrapper。
- MySQL 8.0.16+（需要 CHECK 约束；可使用 MySQL 8.4 LTS），MyBatis-Plus **3.5.18** Boot 4 starter + 同版本 jsqlparser。
- Boot 管理 Web MVC、Validation、Mail、MySQL Connector/J、Flyway、Lombok 和 Test 版本；Spring Security **7.1.2** 及 OAuth2 Resource Server/JOSE 同样跟随 Boot BOM，不自行覆盖版本。
- JWT 使用框架 JwtEncoder/JwtDecoder、Bearer 处理链和 HS256。密码使用带 `{pbkdf2@SpringSecurity_v5_8}` 标识的 DelegatingPasswordEncoder（PBKDF2-HMAC-SHA256，Spring 默认 310000 次迭代、随机盐）。

官方依据：[Boot 4.1 依赖版本](https://docs.spring.io/spring-boot/4.1/appendix/dependency-versions/coordinates.html)、[Spring Security JWT](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html)、[密码存储](https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html)、[MyBatis-Plus Boot 4](https://baomidou.com/getting-started/)、[分页插件](https://baomidou.com/plugins/pagination/)。Resource Server starter 引入 resource-server 与 JOSE；没有另加第二套 JWT 库。

## 建库与启动

1. 启动 MySQL，使用有建库权限的账号执行：

   ```sh
   mysql -u root -p < sql/create_database.sql
   ```

2. 创建应用数据库账号，授予 formaspace 库业务读写和 Flyway 所需 DDL 权限。应用不使用 root，不预置账号或密码。
3. 按 `.env.example` 在终端或 IDE 设置环境变量。**Spring Boot 不会自动加载 .env**；不要直接 source 示例文件，JDBC URL 含 shell 特殊字符。

   ```sh
   export SPRING_PROFILES_ACTIVE=local
   export DB_USERNAME=formaspace_app
   read -rs 'DB_PASSWORD?数据库密码: '
   export DB_PASSWORD
   export DB_URL='jdbc:mysql://localhost:3306/formaspace?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&preserveInstants=true&characterEncoding=UTF-8'
   export JWT_SECRET="$(openssl rand -base64 32)"
   export AUTH_CODE_HMAC_KEY="$(openssl rand -base64 32)"
   ./mvnw spring-boot:run
   ```

   密码输入示例适用于 macOS zsh。两个密钥必须独立生成，解码后至少 32 字节；缺失、格式错误、长度不足或二者相同会启动失败。部署时通过秘密管理系统持久注入；不要每次启动重新生成，否则已有 Token、验证码与限流桶标识会变化。以上生成命令不会打印密钥。

4. 邮件功能默认关闭，基础启动不需要 SMTP 凭证；发送接口此时返回 **503 MAIL_UNAVAILABLE**。要完成注册，设置 SMTP 变量并启用 `EMAIL_CODE_ENABLED=true`。
5. 启动后通过 `curl http://127.0.0.1:8080/api/health` 检查进程。返回 `{code:"OK",message:"success",data:{status:"UP",application:"formaspace-server"}}`，不暴露数据库/SMTP。应用启动需要 MySQL 与迁移成功。

默认监听 `127.0.0.1:8080`。生产设置 `SPRING_PROFILES_ACTIVE=prod`，明确注入数据库地址/凭证、两把密钥和前端 CORS 来源。生产接入 HTTPS；不要把 SMTP/JWT 凭证放入 Git、请求日志或命令行示例值中。

## 配置

| 变量 | 默认值与用途 |
| --- | --- |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | 数据库；生产无默认凭证 |
| `JWT_SECRET`, `AUTH_CODE_HMAC_KEY` | 必填、不同的 Base64 随机密钥，解码至少 32 字节 |
| `JWT_ISSUER`, `JWT_AUDIENCE` | formaspace-server / formaspace-web |
| `JWT_ACCESS_TOKEN_TTL`, `JWT_CLOCK_SKEW` | 2h / 30s；偏差限制 0～30 秒 |
| `EMAIL_CODE_ENABLED` | false；启用时校验 SMTP、发件人和 TLS |
| `EMAIL_CODE_TTL`, `EMAIL_CODE_COOLDOWN` | 5m / 60s，均至少 1 秒 |
| `EMAIL_CODE_MAX_FAILURES` | 5，单挑战错误上限 |
| `EMAIL_CODE_EMAIL_HOURLY`, `EMAIL_CODE_IP_HOURLY` | 每邮箱用途 5 / IP 20 次每小时 |
| `AUTH_LOGIN_WINDOW` | 15m，固定窗口 |
| `AUTH_LOGIN_ACCOUNT_FAILURES`, `AUTH_LOGIN_IP_FAILURES` | 10 / 50 次失败；成功不增加计数，不永久锁定账号 |
| `AUTH_REGISTER_WINDOW` | 15m，固定窗口 |
| `AUTH_REGISTER_EMAIL_ATTEMPTS`, `AUTH_REGISTER_IP_ATTEMPTS` | 10 / 30 次注册尝试 |
| `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD`, `SMTP_FROM` | 邮件主机、端口、认证用户名、授权码/密码、发件人 |
| `SMTP_AUTH`, `SMTP_STARTTLS`, `SMTP_SSL` | 默认 true / true / false |
| `CORS_ALLOWED_ORIGINS` | 逗号分隔精确来源；local 默认 http://localhost:5173，prod 默认拒绝跨域 |

默认邮件使用 STARTTLS/587。若供应商要求 SSL/465，同时设 `SMTP_PORT=465`、`SMTP_STARTTLS=false`、`SMTP_SSL=true`。邮件开启时要求主机、用户名、密码、合法发件人，并且 TLS 两种模式只启用一种。连接/读取/写入超时均为 5 秒；关闭启动 SMTP 连通性测试，本轮开发验证不发送真实邮件。

IP 限流直接读取实际连接地址，`server.forward-headers-strategy=none`，不信任任意 X-Forwarded-For。当前没有可信代理转发功能；反向代理部署时在实现可信代理白名单前，限流会按代理连接地址聚合，不能直接开启全信任转发头。

## 分层与持久化

`com.xiye.formaspace` 按业务域组织，域内区分 controller / service / dto / vo / entity / mapper / enums。`auth/security` 集中身份访问和安全错误输出；`config` 负责 Security、JWT、CORS、UTC 与 MyBatis；`common` 保存响应、异常和共享校验。文件、任务与作品业务尚未实现，没有占位 Controller 或 Service 空壳。

- Entity 只持久化，VO 输出 ID 字符串，时间为 Instant/UTC ISO-8601 Z。MySQL DATETIME(6) 通过 UtcInstantTypeHandler 显式映射 UTC；Java 不依赖主机时区。
- 邮箱 strip + Locale.ROOT 小写，保留点和 + 后缀，ASCII 最大 254；用户名统一小写、3～32 位字母/数字/下划线。唯一索引和 CHECK 与应用规则对齐，不能只查询查重。昵称默认用户名，用户名第一版不可改。
- 密码不 trim，不保存确认密码或明文；密码 DTO 不生成敏感 toString，LoginVO 不输出 Token。SQL 参数、Security DEBUG、SMTP DEBUG 不启用；错误日志只记录异常类型和安全栈帧。
- 分页默认 1/20，最大 100，不接受任意数据库排序字段。MyBatis-Plus 阻止无条件全表 UPDATE/DELETE。
- 物理删除、外键 RESTRICT；未来文件/任务/作品仍须检查 CurrentUser.id() 所属权，不能信任客户端 userId。

## Flyway

`sql/create_database.sql` 仅手动建库；Flyway 启动时管理迁移，禁用其他自动建表方式和自动 baseline。

- V1 原样保留：六张业务表，含验证码槽与数据库限流桶。
- V2 新增 fs_user.last_login_at、验证码 challenge_id / send_status；原验证码因没有挑战绑定摘要而置为不可验证状态。sent_at 表示预留发送的时间，失败/待发送也遵守冷却。
- 以后继续新增 V3 等，禁止修改已应用迁移；不创建 Session、刷新 Token 表或默认用户。

验证码/限流数据库流程、SMTP 崩溃边界见 [auth-api.md](docs/auth-api.md)。过期记录不参与当前窗口计算；运维可按 expires_at 索引分批清理过期限流桶（本轮没有新增清理调度）。

## 测试

```sh
./mvnw test
./mvnw package
```

默认测试不连接个人 SMTP；使用模拟邮件发送器。真实 MySQL 集成测试默认跳过，不以 H2 替代。要验证完整流程、Flyway、行锁和并发，**先创建独立可清空、名称以 `_test` 结尾的数据库**（例如 formaspace_auth_test），再设置：

```sh
export AUTH_TEST_DB_URL='jdbc:mysql://localhost:3306/formaspace_auth_test?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&preserveInstants=true'
export AUTH_TEST_DB_USERNAME=formaspace_test
# 通过 IDE 或秘密管理工具注入 AUTH_TEST_DB_PASSWORD
./mvnw -Dtest=AuthMysqlTests test
```

该类每个测试前清空此测试库的业务表，不可指向生产或开发库；测试密钥自动随机生成、邮件由 mock 捕获。实际检查结果、文件清单与未验证项见 [docs/auth-checks.md](docs/auth-checks.md)。全局 Maven 若有不可用内网仓库，可用 `-s` 指定项目外临时的 Maven Central settings，不需要修改项目依赖版本。

## 当前边界

注册成功不自动登录；Token 默认 2 小时，没有刷新或后端 logout。前端删除 Token 只清理本地登录，已复制 Token 在有效期内仍可用（账号被删除/禁用除外，每请求查库立即生效）。未来实现改密时再决定旧 Token 撤销，不假装已有黑名单。

找回密码只预留 DTO/用途，不提供接口；未实现 AI、对象存储、积分、支付、后台。前端文件未修改。初始化提示词保留在 skills/initialSkill 中；第一阶段历史记录见 docs/initialization-checks.md，第二阶段以当前接口文档为准。
