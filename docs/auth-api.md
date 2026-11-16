# 账号与 JWT API

当前实现四个账号接口和 GET /api/health。响应统一为 `{code,message,data}`；HTTP 状态保持真实语义。ID 为字符串，时间 UTC ISO-8601（Z）。注册/登录/当前用户响应与安全链错误响应均不可缓存，不创建登录 Session 或 Cookie。

## 接口

| 方法 / 路径 | 认证 | 请求 | 成功 |
| --- | --- | --- | --- |
| POST /api/auth/email-codes | 公开、邮箱/IP 限流 | email、purpose=REGISTER | 200，data.retryAfterSeconds |
| POST /api/auth/register | 公开、邮箱/IP 限流 | email、username、verificationCode、password、confirmPassword | 201，data=UserVO；不自动登录 |
| POST /api/auth/login | 公开、账号/IP 失败限流 | account、password | 200，data=LoginVO |
| GET /api/auth/me | Authorization Bearer | 无请求体 | 200，data=UserVO |
| GET /api/health | 公开 | 无 | 200，进程 UP |

只精确放行上面的 GET health 和三个 POST 公开接口，OPTIONS 预检放行。其余请求均要求登录；匿名访问其他路径为 401，认证后不存在的路径仍为 404。未实现的 /password/reset 不放行，也没有 logout、刷新或验证码调试接口。

### 发送验证码

```json
{"email":"user@example.com","purpose":"REGISTER"}
```

```json
{"code":"OK","message":"如可处理，验证码将发送至该邮箱，请查收","data":{"retryAfterSeconds":60}}
```

仅接受 REGISTER，RESET_PASSWORD 返回 400 INVALID_ARGUMENT。已注册邮箱经过同样的邮箱/IP 限流后返回相同成功提示，不再投递邮件；这是防账号探测的处理，不代表投递。需要发送的新邮箱在功能关闭或 SMTP 失败时返回 503 MAIL_UNAVAILABLE，不伪造发送成功。成功响应和日志都没有验证码。

### 注册

```json
{"email":"user@example.com","username":"user_1","verificationCode":"012345","password":"example-password","confirmPassword":"example-password"}
```

成功 201：

```json
{"code":"OK","message":"success","data":{"id":"123","email":"user@example.com","username":"user_1","nickname":"user_1","avatarUrl":null,"status":"ACTIVE","createdAt":"2026-11-16T02:00:00Z"}}
```

五个字段必填。邮箱/用户名去首尾空白并小写；邮箱保留点和 + 后缀。用户名 3～32 位 ASCII 字母/数字/下划线，不允许 @。密码 8～64 字符，原样保留，不强制字符组合；确认密码必须一致。没有 nickname/userId 输入，昵称默认规范化用户名。

### 登录

```json
{"account":"user_1","password":"example-password"}
```

account 可替换为邮箱；规范化后含 @ 按邮箱查，否则按用户名。成功：

```json
{"code":"OK","message":"success","data":{"accessToken":"<JWT>","tokenType":"Bearer","expiresIn":7200,"user":{"id":"123","email":"user@example.com","username":"user_1","nickname":"user_1","avatarUrl":null,"status":"ACTIVE","createdAt":"2026-11-16T02:00:00Z"}}}
```

更新 lastLoginAt。成功响应 `Cache-Control: no-store`。不存在、密码错误、禁用均返回相同 401 INVALID_CREDENTIALS / “账号或密码错误”，未查到用户也执行密码哈希比较。数据库故障保持 503，不伪装为密码错误。

### 当前用户

请求头 `Authorization: Bearer <JWT>`，成功 data 同注册 UserVO，`Cache-Control: no-store`。每个携带有效 JWT 的请求都查数据库，只允许存在且 ACTIVE 的用户；删除/禁用立即使旧 Token 返回 401。数据库故障返回 503 SERVICE_UNAVAILABLE。

## 错误与前端处理

| HTTP | code | 含义 |
| --- | --- | --- |
| 400 | INVALID_ARGUMENT | 格式、密码确认不一致、不支持的验证码用途 |
| 400 | VERIFICATION_INVALID | 错误、过期、发送未成功、旧码、已使用或次数耗尽；统一提示 |
| 401 | INVALID_CREDENTIALS | 登录账号或密码错误，包括禁用 |
| 401 | UNAUTHORIZED | 受保护请求缺少/无效/过期/篡改 Token，或用户已禁用/删除 |
| 403 | FORBIDDEN | 已认证但没有权限 |
| 409 | CONFLICT | 邮箱或用户名唯一冲突；不回显 SQL |
| 429 | RATE_LIMITED | 限流；Retry-After 响应头为应等待的整数秒数 |
| 503 | MAIL_UNAVAILABLE | 邮件关闭或 SMTP 发送失败 |
| 503 | SERVICE_UNAVAILABLE | 数据库/事务存储暂时不可用 |
| 500 | INTERNAL_ERROR | 未预期服务端异常，通用提示 |

404/405 等正常框架 HTTP 错误保留状态；CORS 非法来源不会获得跨域授权。请求方不应依赖错误堆栈或内部配置字段。CORS 暴露 Retry-After 头供前端读取。

公开登录/注册/验证码请求不要自动附带旧 Token：即使路径公开，框架也会拒绝附带的无效 Bearer。受保护请求统一带 Authorization，不从 Cookie 或 URL 参数传 Token，不设置跨域 Cookie 凭证。遇 401 清理本地登录并避免跳转循环；503 显示重试提示，不清理为“密码错误”。未替前端选定长期 Token 存储方案。

## Token 契约

- HS256，独立环境密钥 JWT_SECRET（Base64 解码至少 32 字节），框架签发/验签。
- 仅含 sub（正 Long 用户 ID 字符串）、iss、aud、iat、exp、jti，不放邮箱、密码、验证码。
- issuer=formaspace-server，audience=formaspace-web；有效期默认 2 小时，expiresIn 为秒。固定算法、签名、issuer/audience、时间、sub 和必要 claims 均验证，允许时钟偏差最多 30 秒。
- STATELESS，关闭表单登录、Basic、请求缓存和后端 logout；只使用 Authorization Bearer，因此不需要 Cookie CSRF 获取步骤。
- 前端退出只删除本地 Token，复制出去的 Token 在有效期内仍有效；账号删除/禁用例外。没有刷新、黑名单。将来实现改密时必须同步决定旧 Token 撤销。

## 验证码、限流与事务

- SecureRandom 六位数字，可带前导零；默认 5 分钟，错误上限 5 次。
- HMAC-SHA256 使用独立 AUTH_CODE_HMAC_KEY，绑定规范化邮箱、用途、随机 challenge_id、验证码；常量时间比较。数据库不存明文码，不使用无密钥短码哈希。
- 每邮箱用途精确重发冷却 60 秒；每邮箱用途每小时 5 次、IP 每小时 20 次。注册默认邮箱 10/IP 30 次每 15 分钟。登录默认账号 10/IP 50 次**失败**每 15 分钟；成功不增加失败数，也不永久锁死。
- 除精确冷却外均为固定窗口，窗口边界附近允许连续两个窗口配额。限流桶键使用 HMAC，不存原始邮箱/IP；原子 upsert 与数据库事务保护计数，超限响应前已提交。
- 发送先在短事务中锁定槽并保存 PENDING 挑战（重发替换旧挑战），事务外发送 SMTP，再按 email/purpose/challenge_id/PENDING 条件改为 SENT 或 FAILED。不会在 SMTP 等待中持有行锁。只有 SENT 且有效、未消费的挑战可注册。
- SMTP 完成时已被新的挑战替换，条件更新为 0，旧请求返回 MAIL_UNAVAILABLE，不能覆盖新挑战。SMTP 成功后进程崩溃/确认失败可能收到不可用的码；PENDING/FAILED 都不可验证，等待冷却后重发。这不是 exactly-once 投递系统。
- 注册先做限流和密码哈希，再在单事务内行锁验证码槽、验证、插入用户、消费。唯一冲突或后续异常整体回滚，避免半完成用户和误消费。
- 错码分支更新失败计数并正常提交事务，再在外层抛出 VERIFICATION_INVALID，避免异常导致计数回滚。并发对同一成功挑战仅允许一次消费。
- 默认仅取连接 IP，不读取任意 X-Forwarded-For；可信代理支持另行实现。

## curl

以下为占位示例；验证码从实际邮件获取，Token 用登录响应替换。先按 README 配置数据库、独立密钥和 SMTP 并启动：

```sh
curl -i http://127.0.0.1:8080/api/health

curl -i http://127.0.0.1:8080/api/auth/email-codes   -H 'Content-Type: application/json'   -d '{"email":"user@example.com","purpose":"REGISTER"}'

curl -i http://127.0.0.1:8080/api/auth/register   -H 'Content-Type: application/json'   -d '{"email":"user@example.com","username":"user_1","verificationCode":"<邮件中的六位验证码>","password":"example-password","confirmPassword":"example-password"}'

curl -i http://127.0.0.1:8080/api/auth/login   -H 'Content-Type: application/json'   -d '{"account":"user_1","password":"example-password"}'

curl -i http://127.0.0.1:8080/api/auth/me   -H 'Authorization: Bearer <accessToken>'
```

不要将真实密码/Token 贴入共享命令历史、工单或日志。验证码模板在 resources/templates/mail/register-code.txt；测试使用捕获邮件的 mock，不提供线上旁路。
