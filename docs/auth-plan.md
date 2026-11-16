# 第一阶段鉴权规划（历史记录）

> 第二阶段已实现注册验证码、注册、登录和 JWT 鉴权，当前行为以 [auth-api.md](auth-api.md) 为准。下面保留第一阶段规划，10 分钟有效期等历史值已被第二阶段要求替换为 5 分钟。

本文件仅定义后续契约，不代表本轮存在对应 Controller。统一响应 `{code,message,data}`；失败保持 400/401/403/409/429/503 等 HTTP 状态，不以 200 包装错误。DTO/VO 字段使用 camelCase，ID 使用十进制字符串，时间为 UTC ISO-8601（带 Z）。

| 接口 | 请求 | 成功 data |
| --- | --- | --- |
| `POST /api/auth/email-codes` | `SendEmailCodeDTO`：email、purpose（REGISTER；RESET_PASSWORD 先预留） | `EmailCodeSendVO`：retryAfterSeconds；永不返回验证码 |
| `POST /api/auth/register` | `RegisterDTO`：email、username、verificationCode、password、confirmPassword 五个必填字段 | `UserVO`；注册成功后通过登录接口取得 token |
| `POST /api/auth/login` | `LoginDTO`：account、password | `LoginVO`：accessToken、tokenType=Bearer、expiresIn（秒）、user |
| `GET /api/auth/me` | `Authorization: Bearer <accessToken>` | `UserVO` |
| `POST /api/auth/password/reset` | `ResetPasswordDTO`：email、verificationCode、newPassword、confirmPassword | 空 data；后续实现 |

## 账号与校验

- 只提供一个统一注册入口；邮箱和用户名都必填且唯一，邮箱必须经 REGISTER 验证码验证。
- 邮箱去首尾空白并整体 Locale.ROOT 小写，只接受 ASCII；保留点和 + 后缀。用户名去首尾空白并小写存储，3～32 位字母/数字/下划线，不允许 @，按大小写不敏感唯一；数据库唯一约束是最终保障。
- account 规范化后含 @ 则按邮箱查找，否则按用户名查找。登录错误统一提示，避免泄露账号是否存在；不要打印请求密码。
- 密码 8～64 字符，不强制复杂字符组合，不 trim 或静默修改；两次密码必须一致。只保存安全哈希，密码算法与成本在实现阶段明确。
- 第一版 username 不可修改。nickname 可重复可修改，注册时默认等于规范化用户名，注册请求不接受 nickname/userId。
- 验证码为六位数字（包含前导 0），REGISTER 和 RESET_PASSWORD 严格隔离；10 分钟有效，单次消费，最多 5 次错误；无明文持久化。确认密码不进入持久层。

## JWT 和请求边界

- JWT 只通过 Authorization 请求头携带，不使用 Session 或 Cookie 登录；本轮不实现 CSRF，第一版不实现刷新 Token。
- 前端删除 JWT 只能清理本地登录，不能使已经复制的 JWT 立即失效。
- 下一轮必须决定 access token 有效期，以及改密/封号后是否立即撤销旧 JWT。可评估短有效期或服务端凭证版本检查；现有表不提前虚构尚未决定的撤销字段。
- JWT 签名密钥、算法、issuer/audience、过期检查、异常响应和可信代理 IP 策略在实现时明确，不能只解析 token 而不验签。
- CORS 仅允许配置的前端来源、Authorization/Content-Type/Accept，credentials=false；前端不要启用 Cookie 凭证。
- 上传来源文件、生成任务、作品查询/下载/改名都必须通过当前身份检查数据归属；不能相信请求中的 userId。供应商错误先适配为适合用户阅读的信息，再返回 VO。

## 限流、事务与并发

- 发送验证码、注册、登录和验证码验证都需要适当的邮箱/账号/IP 限流。邮件发送按用途+邮箱每小时 5 次、跨用途 IP 每小时 20 次、每邮箱用途重发冷却 60 秒起步，数据库固定窗口与 HMAC 桶键设计见 email-reuse.md。
- 同一邮箱/用途使用唯一验证码槽；创建槽和行锁需处理首次并发。重发应原子替换旧码并清零失败次数，sent_at 更新而 created_at 保留审计含义。
- 验证失败计数需要真正提交，不能随业务异常回滚；可用独立、明确的事务路径，不直接照搬整个 noRollbackFor。
- **成功消费验证码和创建账号必须在同一事务内提交**。用户名/邮箱唯一冲突、密码哈希失败或其他创建异常时，不能留下已消费验证码或半完成账号。不得在跨事务预消费后单独创建用户。
- 并发注册最终依靠唯一索引；对同一验证码的并发消费依靠行锁或条件更新。通过测试覆盖失败计数、过期/消费/重发竞争、重复账号和 SMTP 失败路径。
- SMTP 与数据库无法天然原子提交；下一轮明确发送后提交失败、锁持有时间、重试和用户重发语义，不宣称 exactly-once 投递。
- 过期验证码与限流桶需要批量清理，使用 expires_at 索引；服务/调度下一轮实现。

## 实现顺序

1. 确定邮件开关、SMTP/TLS/HMAC 配置验证，完成验证码生成/摘要、数据库限流、发送与失败处理。
2. 完成验证码验证/错误计数/一次性消费，结合统一注册、密码哈希、唯一冲突和事务并发测试。
3. 确定 JWT 有效期和撤销策略，实现 account+password 登录、签发/验签、Bearer 过滤器与 `/me`。
4. 接入前端鉴权契约，再逐步实现文件/任务/作品的归属校验；密码重置与旧 token 处理单独交付。
