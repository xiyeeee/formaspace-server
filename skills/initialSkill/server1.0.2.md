# FormaSpace 第二步：邮箱注册、密码登录与 JWT 鉴权

> 将本文交给项目中的 AI 执行。请直接修改现有项目、完成验证并更新文档，不要只输出代码示例。本文是实现要求，尚不代表功能已经完成。

## 1. 先检查项目

- 阅读 AGENTS.md、pom.xml、已有配置、迁移、Entity、Mapper、DTO、VO 和异常处理。
- 项目预计为 formaspace-server，根包 com.xiye.formaspace，以实际代码为准。
- 延续上一阶段的统一响应、时间和 ID 格式，不重复搭建基础工程。
- 保留已有 Spring Boot、Java 版本。查询官方文档确认依赖兼容，不强行升级。
- 已执行的 Flyway 迁移禁止修改，新增后续版本补齐字段或表。
- 参考 https://github.com/xiyeeee/focus-garden-server 的邮件发送、验证码与限流实现；必须实际阅读后说明复用了什么。不能访问则明确说明，不声称已检查。
- 不复制 garden 的 Session、GARDEN_SESSION Cookie、CSRF、Spring Session 表与登录过滤器，不复制任何真实凭证。

## 2. 本轮范围与账号规则

完成真实的发送验证码、注册、登录、获取当前用户，以及 JWT 保护接口。

注册字段：email、username、verificationCode、password、confirmPassword。

登录字段：account、password。account 接受用户名或邮箱，包含 @ 时按邮箱处理。

- 用户名 3～32 位，只允许字母、数字、下划线；禁止 @；第一版不可修改。
- 邮箱和用户名分别唯一，使用统一规范化策略并与数据库唯一约束一致。用户名大小写不敏感；邮箱沿用经确认的项目策略，不擅自删除点或 + 后缀。
- 密码 8～64 字符，不静默 trim，不修改原始密码；确认密码只校验、不保存。
- 昵称默认使用用户名，与唯一登录用户名分开。
- 注册成功不自动登录，前端跳转登录页。
- 第一版不做刷新 Token、第三方登录、积分、支付、模型业务和后台管理。
- 找回密码本轮只保留已有 DTO 和用途，不开放未实现接口。

## 3. 依赖与安全实现方式

在已有依赖上增加 Spring Security。JWT 优先使用与当前 Boot 兼容的 Spring Security OAuth2 Resource Server / JOSE 支持，由 JwtEncoder 签发、JwtDecoder 验证，避免自己编写签名和解析算法。如项目已有成熟 JWT 库则评估后保持单一实现，不混用多套库。

- 使用框架 Bearer Token 处理链；不重复手写一套解析 Authorization 的过滤器。
- 密码使用 DelegatingPasswordEncoder，选择 PBKDF2 等适配当前版本的成熟算法；哈希包含算法标识。不要明文、MD5、普通 SHA256 存密码。
- 无论是否查到用户，都执行适当的密码哈希比较，统一返回账号或密码错误，避免明显时间差和账号探测。
- SecurityFilterChain 使用 STATELESS，禁用表单登录、HTTP Basic、请求缓存。
- 仅通过 Authorization 请求头认证，不从 Cookie、URL 参数读取 Token，不建立登录 Session。
- 在这个前提下关闭 API 的 CSRF 校验，配置精确 CORS；不启用跨域 Cookie 凭证。
- OPTIONS 预检正常放行；其余接口默认要求登录。

## 4. JWT 约定

第一版单后端使用 HS256，密钥来自环境变量中的 Base64 随机值，解码后至少 32 字节。缺失或强度不足时启动失败，不提供生产默认密钥；文档给出生成命令，不输出实际密钥。

配置项建议：

- issuer：formaspace-server。
- audience：formaspace-web。
- access-token-ttl：默认 2 小时，可配置。
- clock-skew：最多 30 秒，可配置。

Claims：sub（字符串用户 ID）、iss、aud、iat、exp、jti。不放密码、验证码、邮箱或其他非必要个人信息。

验证签名、固定算法、issuer、audience、有效期，并保证 sub 合法。拒绝无签名、错误算法、格式错误和已过期 Token。返回登录结果中的 expiresIn 单位为秒，tokenType 为 Bearer。

成功认证后，通过统一 CurrentUser 工具或参数解析器取得用户 ID。禁止 Controller 自行解析 Token；禁止信任请求体中的 userId。

为保证禁用账号立即生效，每次已认证请求检查用户存在且 status=ACTIVE。此次明确采用数据库检查，不引入 Redis。安全链中的数据库故障返回统一 503，不能伪装成密码错误或未登录。

第一版退出由前端删除 Token，不创建误导性的后端 logout 接口。不实现 Token 黑名单；已复制的 Token 在有效期内仍有效（账号禁用除外）。本轮没有重置密码接口，未来实现时须同步决定旧 Token 撤销方案。

## 5. 接口契约

沿用 ApiResponse：code、message、data。成功 code=OK；HTTP 状态码表达真实结果。登录和用户信息响应使用 Cache-Control: no-store。

### 5.1 发送注册验证码

POST /api/auth/email-codes，公开，但必须限流。

```json
{"email":"example@qq.com","purpose":"REGISTER"}
```

本轮仅接受 REGISTER。虽然枚举可预留 RESET_PASSWORD，但请求其他用途必须明确拒绝，不能提供未完成的业务。

成功 200：

```json
{"code":"OK","message":"如可处理，验证码将发送至该邮箱，请查收","data":{"retryAfterSeconds":60}}
```

已注册邮箱使用统一提示，不向外泄露是否存在。没有实际发送的分支也执行适当限流。SMTP 故障返回通用 503，不返回假成功；响应和日志不得泄露验证码或邮箱账户凭证。

### 5.2 注册

POST /api/auth/register，公开。

```json
{"email":"example@qq.com","username":"xiye","verificationCode":"123456","password":"example-password","confirmPassword":"example-password"}
```

成功 201，data 返回 UserVO（id、email、username、nickname、avatarUrl、status、createdAt），绝不返回 passwordHash。

密码不一致、格式错误返回 400；邮箱或用户名冲突返回 409。验证码错误、过期、已使用统一返回 400 VERIFICATION_INVALID。由唯一索引处理并发重复注册，不能只先查询再插入。

### 5.3 登录

POST /api/auth/login，公开，按 IP 和规范化 account 限流。

```json
{"account":"xiye","password":"example-password"}
```

也支持 account 为邮箱。成功 200：

```json
{"code":"OK","message":"success","data":{"accessToken":"<JWT>","tokenType":"Bearer","expiresIn":7200,"user":{"id":"123","email":"example@qq.com","username":"xiye","nickname":"xiye","avatarUrl":null,"status":"ACTIVE","createdAt":"2026-11-16T02:00:00Z"}}}
```

账号不存在、密码错误、账号禁用均返回 401 INVALID_CREDENTIALS，提示“账号或密码错误”。成功更新 lastLoginAt（若现有表没有，通过新迁移增加可空字段）。Token 和密码不可进入日志。

### 5.4 当前用户

GET /api/auth/me，必须携带：

```http
Authorization: Bearer <accessToken>
```

成功 200，data 为 UserVO。缺少、过期、被篡改或无效 Token 返回统一 401。已登录但无权限才返回 403。不要返回 302 登录页或 HTML。

### 5.5 其他公开接口

仅精确放行 GET /api/health 以及上述三个 POST 公共接口，不放行整个 /api/auth/**。未实现的密码重置接口不能因预留文档而被开放。

## 6. 邮箱验证码实现

优先提取 garden 中可复用设计，按当前规范适配，保持单体且不增加中间件。

- SecureRandom 生成 6 位数字，可包含前导零，作为字符串传输。
- 默认 5 分钟有效；同一邮箱、用途重发后旧码失效。
- 默认邮箱发送间隔 60 秒、每小时最多 5 次；IP 每小时最多 20 次，均可配置。
- 单个验证码最多错误尝试 5 次，超限使其失效；注册请求还需按邮箱/IP 限流，不能只限制发送。
- 登录默认账号维度 15 分钟 10 次失败、IP 维度 15 分钟 50 次失败；使用临时窗口限制，不永久锁死账号。
- 超限返回 429、业务码 RATE_LIMITED，并提供 Retry-After 秒数。
- 单实例也使用数据库保存必要计数，通过原子更新、行锁或约束保证并发正确。不能只用线程不安全的内存 Map。
- 验证码只保存 HMAC-SHA256 等带服务端秘密的摘要，绑定规范化邮箱、用途与挑战标识；摘要秘密与 JWT 密钥分开，禁止保存明文或普通短码哈希。
- 使用常量时间摘要比较。禁止在返回值、日志和调试接口泄露验证码。
- 发送过程不要在数据库长事务/行锁中等待 SMTP。先持久化挑战和发送状态，事务外发送，再用挑战标识有条件更新状态，避免并发重发互相覆盖。
- 验证只接受成功发送且仍有效的挑战；发送失败使该挑战不可用。明确崩溃时可能需要等待冷却后重试，不引入消息队列或复杂投递系统。
- 注册成功时，验证码消费与用户插入在同一事务内完成，只允许成功消费一次。
- 验证错误的计数必须在失败响应后仍持久化，不能因抛业务异常被事务回滚；重复注册失败不得误消费有效验证码。请明确事务边界并测试。
- 限流所用 IP 默认取实际连接地址。只有配置了可信代理时才读取受信转发头，不信任客户端随意提供的 X-Forwarded-For。

## 7. 文件组织建议

遵循现有分层，无须机械创建以下每个类，也不要一方法一接口。可以按现有命名调整：

- config/SecurityConfig、JwtConfig。
- auth/config/JwtProperties、EmailVerificationProperties。
- auth/controller/AuthController。
- auth/service/AuthService、EmailVerificationService、MailService、AuthRateLimiter。
- auth/security/CurrentUser、统一 JSON AuthenticationEntryPoint 和 AccessDeniedHandler。
- auth/entity、mapper 中实际需要的验证码、限流记录。
- 复用现有 DTO、VO、UserMapper、异常体系和账号规范化工具。
- 邮件模板放 resources/templates/mail 或约定目录，不把 HTML 长字符串塞进 Controller。

安全过滤链异常不会自动进入 ControllerAdvice，必须单独接入统一 JSON 响应。日志禁止记录 Authorization、密码、验证码和 SMTP 凭证。

## 8. 数据库与配置

新迁移只补缺失内容：用户名及邮箱唯一索引、密码哈希、用户状态、last_login_at、验证码记录、限流记录及必要索引。不要重建已有用户表或删除数据。不需要 Session 表、刷新 Token 表。

更新 application 配置、.env.example、README：

- JWT 密钥、issuer、audience、有效期。
- 验证码摘要密钥、有效期、次数与窗口。
- SMTP 主机、端口、账号、授权码、发件人、SSL/STARTTLS 与超时。
- 允许的前端 CORS 来源。

秘密只能由环境变量注入，.env 不提交；说明 Spring Boot 不会自动读取 .env。SMTP SSL 和 STARTTLS 按服务商配置，不盲目同时启用。

不在缺少 SMTP 凭证时发送真实邮件。可使邮件功能默认关闭，但调用接口必须明确返回 MAIL_UNAVAILABLE；生产启用邮件功能时校验必需配置。禁止开发环境打印验证码作为旁路。

## 9. 验证与验收

执行编译和以下有意义的测试。使用测试邮件发送器捕获邮件，不依赖个人 SMTP 发真实邮件，不提供生产可访问的测试验证码接口。

1. 发送验证码 → 注册成功 → 用户名登录 → 邮箱登录 → JWT 获取当前用户。
2. 密码哈希正确，响应无密码、摘要或验证码；日志不泄露敏感字段。
3. 确认密码不一致、用户名非法、重复用户名/邮箱。
4. 错误、过期、旧码、已消费验证码被拒绝；错误次数递增且不会回滚。
5. 同一验证码并发注册仅成功一次；重复注册事务不产生半完成账号。
6. 重发冷却、邮箱/IP 限流、登录限流返回 429。
7. SMTP 发送失败、并发发送状态处理。
8. Token 缺失、过期、篡改、错误算法、错误 issuer/audience、非法 sub 均返回 401 JSON。
9. 用户禁用后已有 Token 不再可用；数据库故障返回 503。
10. CORS 允许来源通过，非法来源不获授权，OPTIONS 正常处理。
11. 没有登录 Session Cookie，没有 CSRF 获取步骤；GET /api/auth/me 不会被匿名访问。

真实数据库不可用时明确哪些集成测试未执行，不能将编译成功写成全部验收通过。需要 MySQL 行锁和并发语义的测试用 MySQL 验证，不以 H2 替代后声称已验证。

## 10. 完成交付

更新 docs/auth-api.md，作为前端对接的最终依据，包含请求、响应、HTTP 状态、错误码、Token 有效期和退出语义。

前端约定：公开登录/注册请求不自动附带旧 Token；受保护请求统一附带 Authorization；401 清理本地登录并跳转时避免循环。本文不要求修改前端或擅自选择长期 Token 存储方案。

最后汇报：

1. 实际新增/修改文件与迁移。
2. 采用的依赖和兼容性依据。
3. 从 garden 实际复用的文件和适配内容。
4. 需要用户设置的环境变量及启动步骤，不输出秘密值。
5. 编译、测试结果和未验证项。
6. 可直接使用的 curl 示例（Token 用占位符）。

请完成整个账号流程，不停留在创建 DTO、空接口或签发假 Token。
