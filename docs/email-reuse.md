# garden 邮箱设计参考记录

已实际读取公开仓库 [xiyeeee/focus-garden-server](https://github.com/xiyeeee/focus-garden-server)，参考提交 `a9b88117aed4e6ba4a0ddb6e0b144098e44dd073`（本轮读取日期 2026-11-16）。只参考经确认的设计，不复制鉴权模块或秘密。以下路径均相对参考仓库根目录；链接固定到该提交。

| 已确认设计 | 来源文件 | 本项目应用方式 |
| --- | --- | --- |
| strip + Locale.ROOT 小写；不折叠点/加号 | `auth/service/AccountIdentifier.java` | `common/validation/AccountIdentifiers` 用于邮箱、用户名及登录账号的边界规范化；ASCII 邮箱最大 254 |
| SecureRandom 六位数字；HMAC-SHA256 绑定 email、purpose、code；Base64 密钥解码后 >=32 字节；常量时间比对 | `auth/service/VerificationProtector.java` | DTO 限定 `[0-9]{6}`，持久化仅 code_hash；保留 `AUTH_CODE_HMAC_KEY`，本轮不实现生成/验证 |
| 每 email/purpose 一个槽；行锁、重发替换、10 分钟有效；成功一次性消费；失败累计 5 次失效；60 秒冷却 | `auth/service/EmailVerificationService.java`、`auth/mapper/EmailVerificationMapper.java` | `fs_email_verification` 复用槽结构及唯一约束；独立 sent_at 承担签发/冷却语义，created_at/updated_at 统一为审计字段 |
| 固定窗口数据库原子累计，限流键 HMAC；每邮箱每用途每小时 5 次、每 IP 跨用途每小时 20 次，另有 60 秒冷却 | `auth/service/AuthRateLimiter.java` | `fs_auth_rate_limit` 保存 bucket_key/window_start/attempts/expires_at；加自增 id 和审计字段，保留桶/窗口唯一约束；服务下一轮实现 |
| REGISTER、RESET_PASSWORD 两种用途 | `auth/entity/VerificationPurpose.java` | 本项目同名枚举及 SQL CHECK，重置用途先预留 |
| JavaMailSender + SimpleMailMessage；按用途区分邮件；SMTP 接受失败回滚新挑战 | `auth/service/VerificationMailSender.java`、`auth/service/EmailVerificationService.java` | 只引入 Mail starter 和 SMTP 配置，不引入邮件服务；下一轮确定 SMTP 与数据库提交间的失败边界 |
| SMTP STARTTLS 默认启用、SSL 默认关闭；连接/读取/写入 5 秒超时 | `src/main/resources/application.yaml` | 复用无秘密的变量名和超时，缺少 SMTP 时不阻止基础启动 |
| 验证码槽与限流索引、约束 | `src/main/resources/db/migration/V3__users_and_authentication.sql` | 改用 fs_ 表名；全部自增 Long 主键、UTC 审计；不复制用户会话版本/Session 表 |
| 注册/改密内部消费验证码；验证失败次数需要提交 | `auth/service/AuthService.java` | 只作为事务边界的参考。下一轮重新设计，不能整体复制 noRollbackFor 业务流程 |

Java 路径前缀为 `src/main/java/com/xiye/garden/`。可核对：[验证码服务](https://github.com/xiyeeee/focus-garden-server/blob/a9b88117aed4e6ba4a0ddb6e0b144098e44dd073/src/main/java/com/xiye/garden/auth/service/EmailVerificationService.java)、[验证码保护](https://github.com/xiyeeee/focus-garden-server/blob/a9b88117aed4e6ba4a0ddb6e0b144098e44dd073/src/main/java/com/xiye/garden/auth/service/VerificationProtector.java)、[限流](https://github.com/xiyeeee/focus-garden-server/blob/a9b88117aed4e6ba4a0ddb6e0b144098e44dd073/src/main/java/com/xiye/garden/auth/service/AuthRateLimiter.java)、[参考迁移](https://github.com/xiyeeee/focus-garden-server/blob/a9b88117aed4e6ba4a0ddb6e0b144098e44dd073/src/main/resources/db/migration/V3__users_and_authentication.sql)。

差异与后续边界：

- garden 当前允许不同注册分支，FormaSpace 只有邮箱+用户名+验证码+密码+确认密码的统一注册入口，不能沿用其注册分支。
- 不复制 Session、GARDEN_SESSION Cookie、CSRF、会话表和基于 Session 的改密撤销策略。JWT 撤销方案下一轮单独决定。
- garden 限流器还有登录（账号 10/15 分钟、IP 50/15 分钟）、注册（用户名 5/小时、IP 20/小时）、验证（邮箱 10/15 分钟、IP 30/15 分钟）策略。本轮只记录，未宣称已经实现；下一轮结合统一注册和 JWT 重新确定。
- 验证码错误计数的提交与成功消费+账号创建的原子性应分别设计。避免业务异常整体不回滚造成验证码已消费而用户创建失败。唯一冲突和并发消费需要真实数据库测试。
- 六位短码不能用无密钥 SHA-256 代替 HMAC。限流桶键也不能直接保存邮箱/IP。来源 IP 仅从可信代理策略获得，不能相信任意 X-Forwarded-For。
- `EmailCodeProperties` 的 ttl/冷却/失败次数等暂为下一轮服务配置；SQL 当前最多 5 次失败，若以后调整上限需评估新增迁移。
- 未复制真实邮箱、密码或密钥；没有执行真实邮件发送。
