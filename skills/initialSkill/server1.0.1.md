# FormaSpace 基础工程初始化提示词 · 版本 1

版本：1
保存日期：2026-11-16

请直接修改当前 FormaSpace 后端项目，完成基础工程初始化。先检查已有代码，再创建或修改文件，不要重复创建项目。

一、项目与本轮范围

项目：

- 名称：formaspace-server。
- 根包预计为 com.xiye.formaspace，以现有项目为准。
- React + TypeScript 前端，Spring Boot 后端，前后端分离。
- 使用 Spring Boot 单体、MySQL、MyBatis-Plus、Flyway。
- 后续使用 Authorization: Bearer JWT 鉴权。
- 核心业务是上传图片、调用第三方 AI 生成 3D 模型、查看作品和下载模型。

本轮完成：

1. Maven 依赖与基础配置。
2. 业务目录、实体、Mapper、DTO、VO。
3. 数据库初始化脚本。
4. 统一响应、异常处理、分页。
5. 参考已有 garden 项目，整理邮箱验证码的复用方案。
6. 启动说明和后续接口约定。

本轮不要实现：

- 注册、登录、发送验证码等业务接口。
- JWT 签发、校验、过滤器。
- Session、Cookie 登录和 CSRF。
- AI 调用、对象存储上传。
- 积分、支付、后台管理。

不要生成返回假成功的占位接口，不要批量创建空 Service、ServiceImpl。

二、参考 garden 的邮箱实现

参考仓库：

https://github.com/xiyeeee/focus-garden-server

请先检查实际代码，重点查看：

- 邮件发送与配置。
- 邮箱验证码生成、保存、验证和消费。
- 验证码用途、有效期。
- 发送频率、错误尝试次数限制。
- 邮箱规范化与唯一性。
- 注册及密码重置时如何使用验证码。

复用经过确认的设计，不照搬整个鉴权模块：

- 不复制 Session、GARDEN_SESSION Cookie、CSRF 和 Session 相关表。
- 不复制真实邮箱、SMTP 密码或其他秘密。
- 本轮只建立后续所需的配置、数据结构和文档，不实现发送与校验业务。
- 如果仓库不可访问，明确说明；不要假装已检查，也不要自行编造其实现。

三、先检查项目与依赖兼容性

检查：

- AGENTS.md。
- pom.xml。
- Java 和 Spring Boot 版本。
- 现有包名、配置和健康检查接口。

保留已有可用版本，不擅自升级或降级 Spring Boot。

添加或确认：

- Spring Web。
- Validation。
- Lombok。
- MySQL Connector/J，runtime。
- 与当前 Spring Boot 主版本匹配的 MyBatis-Plus starter。
- 对应版本分页插件所需依赖。
- Flyway，以及对应版本需要的 MySQL 支持模块。
- Spring Boot Mail。
- Spring Boot Test，test。

依赖版本优先使用 Spring Boot 管理；其他版本集中管理。通过官方文档确认兼容性。

本轮暂不添加 Spring Security、JWT 库、Redis、消息队列、Spring Cloud、AI SDK 和对象存储 SDK。

四、账号规则

只提供一个统一注册入口，不区分“邮箱注册”和“用户名注册”。

注册页面字段：

- 邮箱。
- 用户名。
- 邮箱验证码。
- 密码。
- 确认密码。

注册规则：

- 邮箱必填、唯一，必须完成验证码验证。
- 用户名必填、唯一，3～32 位，只允许字母、数字、下划线，不允许 @。
- 用户名按不区分大小写进行唯一性判定，明确统一的规范化策略。
- 邮箱采用与参考项目一致且合理的规范化方式，不擅自删除邮箱中的点或 + 后缀。
- 密码建议 8～64 个字符，不强制复杂字符组合，不静默 trim 或修改密码。
- 确认密码必须与密码一致。
- 密码只保存安全哈希，不保存明文。
- 验证码、确认密码不进入用户表。

登录规则：

- 一个 account 输入框，接受用户名或邮箱。
- 加一个 password 输入框。
- 用户名不允许 @，可据此区分账号类型。
- 用户名第一版不可修改。
- nickname 为展示昵称，可重复、可修改，注册时默认使用用户名。

本轮只定义结构与校验，业务实现放到下一轮。

五、包结构

按业务划分，再在业务包内分层：

com.xiye.formaspace
├── common
│   ├── response
│   ├── exception
│   ├── validation
│   └── enums
├── config
├── health
│   └── controller
├── auth
│   ├── dto
│   ├── vo
│   ├── entity
│   ├── mapper
│   └── enums
├── user
│   ├── entity
│   ├── mapper
│   └── vo
├── file
│   ├── entity
│   ├── mapper
│   └── vo
├── generation
│   ├── dto
│   ├── vo
│   ├── entity
│   ├── mapper
│   └── enums
├── asset
│   ├── dto
│   ├── vo
│   ├── entity
│   └── mapper
└── provider
    └── enums

只创建有实际用途的文件，不创建空目录或空接口。

六、公共基础

实现：

1. ApiResponse<T>

字段：

- code：字符串业务码。
- message。
- data。

提供成功、失败工厂方法，错误使用对应 HTTP 状态码，不统一返回 HTTP 200。

2. PageQuery

- page：默认 1，最小 1。
- size：默认 20，范围 1～100。
- 不允许前端直接传任意数据库排序字段。

3. PageResult<T>

- records。
- total。
- page。
- size。

4. BusinessException、ErrorCode

提供必要的业务码、提示和 HTTP 状态信息，保持简单。

5. GlobalExceptionHandler

处理参数校验、参数绑定、JSON 格式、类型转换、业务异常和未知异常。

- 未知异常记录服务端日志，返回通用提示。
- 不向前端暴露堆栈、SQL、密码、验证码或内部配置。
- 保持 404、405 等正常 HTTP 错误语义。

6. MybatisPlusConfig

- MySQL 分页。
- 单页数量限制。
- 防止无条件全表更新、删除。

7. MetaObjectHandler

统一填充 createdAt、updatedAt。

七、数据库与实体

使用 Flyway 管理表结构，不自动建表。

基础表：

1. fs_user

- id。
- email，唯一。
- username，唯一。
- password_hash。
- nickname。
- avatar_url，可空。
- status：ACTIVE、DISABLED。
- created_at。
- updated_at。

邮箱、用户名的数据库唯一约束应与规范化和大小写规则一致。不能只依赖业务查询查重。

2. 邮箱验证码及限流相关表

先检查 garden 实现，再决定复用哪些表结构。

应支持后续实现：

- 注册用途 REGISTER。
- 找回密码用途 RESET_PASSWORD，先预留。
- 验证码与邮箱、用途绑定。
- 有效期。
- 一次性消费。
- 验证失败次数限制。
- 重发冷却时间和按邮箱、IP 限制频率。

不在数据库保存明文验证码。参考项目如果使用带服务端秘密的摘要等机制，应保留其必要配置与设计，不要简单改成容易被穷举的无密钥短码哈希。

本轮仅创建已确认所需的表、实体、Mapper、枚举和配置属性。不实现发送、校验和限流服务，也不引入新中间件。

3. fs_file

- id。
- user_id。
- original_name。
- storage_key。
- content_type。
- size_bytes，BIGINT。
- purpose：SOURCE_IMAGE、MODEL、THUMBNAIL。
- created_at。
- updated_at。

保存 storage_key，不把临时签名 URL 作为永久文件标识。

4. fs_generation_task

- id。
- user_id。
- source_file_id。
- provider。
- provider_task_id，可空。
- status。
- progress，可空。
- error_code，可空。
- error_message，可空。
- started_at，可空。
- finished_at，可空。
- created_at。
- updated_at。

状态：

PENDING、SUBMITTING、PROCESSING、SUCCEEDED、FAILED。

progress 有值时为 0～100，供应商不提供进度时允许为空，不伪造精确进度。

5. fs_model_asset

- id。
- user_id。
- generation_task_id，唯一。
- name。
- model_file_id。
- thumbnail_file_id，可空。
- format，例如 GLB。
- size_bytes。
- created_at。
- updated_at。

第一版一条成功任务对应一个主要模型作品。

通用要求：

- 自增 BIGINT 主键，Java 使用 Long。
- 对外 VO 中的 ID 输出为字符串，避免 JavaScript 精度问题。
- utf8mb4。
- 时间统一使用 UTC，API 输出明确带 Z 或偏移量。
- 明确配置 MyBatis-Plus 主键、枚举映射与时间映射。
- 根据用户分页、任务调度、供应商任务查询、验证码查询添加必要索引。
- 第一版物理删除。
- 如使用外键，采用 RESTRICT，不隐式级联删除。

创建：

- sql/create_database.sql：手动创建 formaspace 数据库。
- src/main/resources/db/migration/V1__init_schema.sql：创建业务表。

若项目已有迁移记录，不修改已经应用的迁移文件，新增后续版本。

不插入默认管理员、真实邮箱或明文密码。

八、DTO / VO

原则：

- DTO 接收请求，VO 返回响应，Entity 用于持久化。
- 不直接返回 Entity。
- 不接受客户端传 userId 来决定归属。
- passwordHash、验证码摘要、供应商密钥不进入 VO。
- 使用 Jakarta Validation。
- 所有校验规则与账号规则一致。
- DTO 中涉及密码的类型不要生成会输出密码的 toString，日志不能打印完整请求。

auth/dto：

1. SendEmailCodeDTO
- email。
- purpose。

2. RegisterDTO
- email。
- username。
- verificationCode。
- password。
- confirmPassword。

实现密码一致性的类级别校验。确认密码只参与校验，不持久化。

3. LoginDTO
- account。
- password。

4. ResetPasswordDTO
- email。
- verificationCode。
- newPassword。
- confirmPassword。

只定义，业务后续实现。

验证码长度和字符规则以检查 garden 后确定的规则为准。

auth/vo：

1. LoginVO
- accessToken。
- tokenType，后续为 Bearer。
- expiresIn，秒。
- user。

2. EmailCodeSendVO
- retryAfterSeconds：允许再次发送前的等待秒数。

不要返回验证码本身。

user/vo：

UserVO：
- id。
- email。
- username。
- nickname。
- avatarUrl。
- status。
- createdAt。

file/vo：

FileUploadVO：
- id。
- originalName。
- contentType。
- sizeBytes。
- previewUrl，可空。

本轮不创建上传 DTO，后续由 MultipartFile 接收文件。

generation/dto：

- CreateGenerationDTO：sourceFileId。
- GenerationQueryDTO：分页字段、status。

generation/vo：

GenerationTaskVO：
- id。
- sourceFileId。
- status。
- progress。
- errorCode。
- errorMessage。
- assetId。
- createdAt。
- finishedAt。

返回的错误信息应适合用户阅读，不直接透传供应商原始错误。

asset/dto：

- AssetQueryDTO：分页字段、keyword。
- RenameAssetDTO：name。

asset/vo：

ModelAssetVO：
- id。
- generationTaskId。
- name。
- format。
- sizeBytes。
- modelUrl。
- thumbnailUrl。
- createdAt。

URL 后续根据权限与存储配置生成。

九、配置文件

整理：

- application.yml。
- application-local.yml。
- application-prod.yml。
- .env.example。

若已有 .yaml，统一扩展名，不保留重复配置。

要求：

- 应用名 formaspace-server。
- 默认端口 8080。
- 数据库和 SMTP 凭证通过环境变量提供。
- SMTP 属性根据 garden 和实际邮件服务要求配置，不盲目同时开启 SSL 与 STARTTLS。
- 邮件发送、连接和读取配置合理超时。
- 生产环境没有默认密码或演示密钥。
- .env.example 只有变量名和安全示例。
- 说明 Spring Boot 不会自动加载 .env。
- 本轮不发送真实邮件。
- 未实现邮件服务前，不因缺少 SMTP 凭证阻止基础工程启动；正式启用邮件功能时再做配置校验。
- 开启下划线转驼峰。
- 生产环境不打印敏感参数。

CORS：

- 允许来源可配置。
- 本地示例 http://localhost:5173。
- 允许 Authorization、Content-Type 等实际需要的请求头。
- 不启用 Cookie 跨域凭证。
- 生产环境不使用任意来源。

本轮没有鉴权，不能把业务接口直接开放到公网。

十、健康检查

保留或实现 GET /api/health。

返回统一结构：

{
  "code": "OK",
  "message": "success",
  "data": {
    "status": "UP",
    "application": "formaspace-server"
  }
}

仅表示进程存活，不暴露数据库或 SMTP 配置。

十一、文档

创建或更新 README.md，说明：

- 目录和分层。
- 依赖、JDK、MySQL 要求。
- 建库、环境变量和启动步骤。
- Flyway 使用方式。
- 健康检查方式。
- 邮件配置变量。
- 当前尚未实现的功能。

创建 docs/auth-plan.md，记录下一阶段接口约定：

- POST /api/auth/email-codes。
- POST /api/auth/register。
- POST /api/auth/login。
- GET /api/auth/me。
- POST /api/auth/password/reset，后续实现。

明确：

- 注册使用五个字段。
- 登录使用 account + password。
- JWT 通过 Authorization 请求头携带。
- 不使用 Session 或 Cookie 登录。
- 第一版不实现刷新 Token。
- 前端删除 JWT 只能清理本地登录，不能使已复制的 JWT 立即失效。
- 改密、封号后是否立即撤销已有 JWT，需要下一阶段明确方案。
- 发送验证码、注册、登录都需要适当限流。
- 验证码消费与账号创建必须考虑事务和并发，失败不能产生半完成账号。
- 来源文件、任务、作品接口必须检查当前用户的数据归属。

文档中的接口仅为约定，本轮不要生成对应业务 Controller。

十二、检查与交付

执行编译和必要检查：

- Maven 依赖兼容。
- Java 编译。
- Validation 规则，尤其密码一致性。
- 数据库可用时验证 Flyway 与启动。
- 数据库不可用则明确未验证项。
- 不为简单 getter、空壳类编写测试。

完成后输出：

1. 实际新增、修改的文件。
2. garden 中哪些邮箱设计可复用，以及对应源文件。
3. 依赖及版本选择依据。
4. 已通过的检查和未验证项。
5. 我需要执行的建库、环境变量与启动步骤。
6. 下一轮实现邮箱验证码、注册与 JWT 登录的顺序。

请直接完成项目文件修改，不要仅输出计划。