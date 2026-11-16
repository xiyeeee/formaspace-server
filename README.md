# FormaSpace Server

Spring Boot 单体后端。当前仅实现 `GET /api/health`；其余为后续业务所需的持久化结构、校验与契约。原始初始化提示词保存在 [skills/initialSkill/v1.0.1.md](skills/initialSkill/v1.0.1.md)。

## 环境与依赖

- JDK 21、Maven Wrapper（现有 Maven 3.10.0）。
- 保留项目既有 Spring Boot **4.1.2**，使用其管理的 Web MVC、Validation、Lombok、MySQL Connector/J、Mail、Flyway 和测试依赖。
- MyBatis-Plus **3.5.18**：集中在 `mybatis-plus.version`，使用 `mybatis-plus-spring-boot4-starter`，分页另引入同版本 `mybatis-plus-jsqlparser`。
- MySQL **8.0.16+**（需要真正执行 CHECK 约束；建议使用受支持的 MySQL 8.4 LTS）。
- 未引入 Spring Security、JWT、Redis、消息队列或任何 AI/对象存储 SDK。

版本依据：[MyBatis-Plus Boot 4 starter](https://baomidou.com/getting-started/)、[分页插件独立依赖](https://baomidou.com/plugins/pagination/)、[Spring Boot 4.1.2 依赖管理](https://docs.spring.io/spring-boot/4.1/appendix/dependency-versions/coordinates.html)、[Flyway 初始化](https://docs.spring.io/spring-boot/4.1/how-to/data-initialization.html)、[邮件与超时](https://docs.spring.io/spring-boot/4.1/reference/io/email.html)。Boot 4 使用 `spring-boot-starter-flyway` 引入自动配置及核心，MySQL 另加 `flyway-mysql`；没有自行覆盖 Boot 管理的版本。

## 分层

`com.xiye.formaspace` 按业务域组织：

| 路径 | 职责 |
| --- | --- |
| `common/response` | 统一响应、分页参数与结果适配 |
| `common/exception` | 业务异常、HTTP 状态与全局异常处理 |
| `common/validation` | 账号规范化、邮箱与密码一致性校验 |
| `common/enums` | 用户状态、文件用途 |
| `config` | MyBatis-Plus 分页/防全表更新删除、UTC 映射、审计字段、CORS、邮箱配置 |
| `health/controller` | 进程存活检查 |
| `auth/{dto,vo,entity,mapper,enums}` | 下一阶段鉴权契约、验证码槽、数据库限流桶 |
| `user/{entity,mapper,vo}` | 用户持久化与展示结构 |
| `file/{entity,mapper,vo}` | 文件元信息与上传结果契约 |
| `generation/{dto,vo,entity,mapper,enums}` | 生成任务与状态 |
| `asset/{dto,vo,entity,mapper}` | 模型作品与查询/改名契约 |

没有空 Service/ServiceImpl，也没有占位业务 Controller。尚未选择 AI 供应商，`provider` 暂用受服务端控制的字符串，待接入真实供应商时再增加对应枚举，避免发明供应商值和空目录。

## 建库和启动

1. 启动 MySQL，使用具有建库权限的账号执行：

   ```sh
   mysql -u root -p < sql/create_database.sql
   ```

2. 由数据库管理员创建应用账号，授予 `formaspace` 库的业务读写和迁移 DDL 权限。不要让应用使用 root。此项目不预置数据库账号/密码。
3. 在终端导出环境变量，或在 IDEA Run Configuration 中配置。`.env.example` 是变量清单；**Spring Boot 不会自动加载 `.env`**。不要直接 `source .env.example`，其中 JDBC URL 包含 shell 特殊字符。

   ```sh
   export SPRING_PROFILES_ACTIVE=local
   export DB_USERNAME=formaspace_app
   read -rs 'DB_PASSWORD?数据库密码: '
   export DB_PASSWORD
   export DB_URL='jdbc:mysql://localhost:3306/formaspace?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&preserveInstants=true&characterEncoding=UTF-8'
   ./mvnw spring-boot:run
   ```

   密码输入示例适用于当前 macOS zsh；其他 shell 可通过 IDE 或秘密管理工具注入。生产使用 `SPRING_PROFILES_ACTIVE=prod`，必须提供 `DB_URL`、`DB_USERNAME`、`DB_PASSWORD`，并按实际前端域名配置 `CORS_ALLOWED_ORIGINS`（逗号分隔）。生产未配置 CORS 时不允许跨域来源，通配符会拒绝启动。

4. 验证进程：

   ```sh
   curl http://127.0.0.1:8080/api/health
   ```

   返回 `{"code":"OK","message":"success","data":{"status":"UP","application":"formaspace-server"}}`（JSON 字段顺序无要求）。该接口不探测数据库或 SMTP；但应用正常启动本身需要数据库可连接并成功迁移。

默认监听 `127.0.0.1:8080`；可以使用 `SERVER_ADDRESS` / `SERVER_PORT` 调整。当前尚无鉴权，不应将业务接口直接开放到公网。CORS 仅管理浏览器跨域，不是访问控制；不会启用 Cookie 跨域凭证。

## 数据库与 Flyway

- `sql/create_database.sql` 只手动建库，Flyway 自动执行 `src/main/resources/db/migration/V1__init_schema.sql` 建表。
- 已检查项目原先没有迁移文件。以后新增 `V2__...sql` 等，**不修改已经应用的迁移**；不启用自动 baseline，不使用 ORM 自动建表、`schema.sql` 或启动数据插入。
- 业务表：`fs_user`、`fs_email_verification`、`fs_auth_rate_limit`、`fs_file`、`fs_generation_task`、`fs_model_asset`（共六张）。所有表采用自增 BIGINT 主键、InnoDB、utf8mb4；实体 ID 为 Long，所有对外 VO ID 为 String。
- 邮箱使用 ASCII、去首尾空白并整体小写，保留点和 `+` 后缀；数据库二进制唯一索引与规范化 CHECK 配合。用户名只允许 3～32 个 ASCII 字母、数字、下划线，统一小写存储；不区分大小写的唯一索引及 CHECK 防止绕过应用规则。
- `nickname` 注册时使用规范化后的用户名，允许重复；用户名第一版不可修改。密码不 trim、不改写，只能保存带盐的自适应安全哈希，算法及参数在下一阶段选定；确认密码和验证码不进入用户表。
- 所有时间使用 Instant，JSON 输出 ISO-8601 的 `Z` 时间。`UtcInstantTypeHandler` 显式将 Instant 和 UTC LocalDateTime 转换，MySQL 以 DATETIME(6) 存储；JDBC 会话时区也设为 UTC，不依赖主机时区。createdAt/updatedAt 由审计处理器填充，业务写入应通过实体以触发填充。
- 枚举通过 `@EnumValue` 显式保存稳定名称，主键明确为 AUTO。分页最大 100 条，查询参数不接受数据库排序字段；后续按服务端固定的 `created_at DESC, id DESC` 等排序。
- 第一版物理删除，所有外键都为 RESTRICT；删除前由业务检查引用、数据归属并明确删除顺序。分页、调度、供应商任务、验证码槽和过期清理均有对应索引。
- 一条成功生成任务最多一个主要作品。供应商任务编号按供应商唯一，未提交时允许 NULL。progress 未知时为 NULL，有值仅允许 0～100。

## 邮件配置

本轮不发送邮件、不实现验证码校验/限流服务。没有 SMTP 凭证或 HMAC 密钥不会阻止基础工程启动；`formaspace.email-code.enabled=false` 只是后续功能开关预留，当前没有邮件业务消费者。

| 变量 | 用途 |
| --- | --- |
| `SMTP_HOST` / `SMTP_PORT` | SMTP 服务器及端口（默认 587） |
| `SMTP_USERNAME` / `SMTP_PASSWORD` | SMTP 认证凭证 |
| `SMTP_FROM` | 后续邮件发送方 |
| `SMTP_AUTH` | 是否认证，默认 true |
| `SMTP_STARTTLS` | 默认 true，同时要求 STARTTLS 成功 |
| `SMTP_SSL` | 默认 false，隐式 TLS 供应商通常使用 465 |
| `AUTH_CODE_HMAC_KEY` | 后续 HMAC 密钥，Base64 编码且解码后至少 32 随机字节 |

默认采用 STARTTLS/587；若服务商要求 SSL/465，应同时设 `SMTP_PORT=465`、`SMTP_STARTTLS=false`、`SMTP_SSL=true`。不同时启用 STARTTLS 和隐式 SSL。连接、读取、写入超时均为 5 秒，关闭启动邮件连接测试。正式启用服务时必须校验密钥长度、发件地址、凭证及 TLS 配置；不要提供演示密钥或明文短码日志。

设计与源文件对应关系见 [docs/email-reuse.md](docs/email-reuse.md)。

## API 约定和本轮边界

- `ApiResponse<T>` 为 `{code,message,data}`，业务 code 是字符串，失败使用对应 HTTP 状态；框架参数/JSON/类型错误返回 400，保留 404、405 等语义。
- 未知异常只返回通用提示；服务端记录异常类型和栈帧，不记录可能含 SQL/凭证的异常消息、请求或原因链，并抑制 MVC 异常解析器可能输出原始异常的 DEBUG 日志。DTO 不生成包含密码/验证码的 toString，LoginVO 不输出 token。
- DTO 接收请求、VO 返回响应、Entity 仅持久化；Controller 应使用 `@Valid`（分页查询也要校验），不能直接返回 Entity。`PageResult.from` 通过显式适配函数将实体分页转为 VO 分页。
- sourceFileId 接收十进制字符串并校验正 Long 范围，避免前端数值精度损失；不接受 userId 决定归属。文件标识保存 storage_key，下载/预览 URL 后续按权限临时生成。
- 目前不实现注册、登录、邮箱发送/校验、JWT 签发/过滤器、刷新 Token、Session/Cookie 登录、CSRF、AI、对象存储、积分、支付、管理后台。
- 后续接口与事务要求见 [docs/auth-plan.md](docs/auth-plan.md)。

## 检查

```sh
./mvnw test
./mvnw package
```

自动测试覆盖账号规范化、账号/验证码/密码校验、两次密码一致、分页边界、HTTP 响应/异常语义、CORS 和 UTC 映射。数据库可用后还应验证真实 MySQL 上的 Flyway 首次迁移/重复启动、唯一/CHECK/外键约束以及 Mapper 读写。环境验证结果见 [docs/initialization-checks.md](docs/initialization-checks.md)。
