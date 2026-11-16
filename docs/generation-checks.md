# 图片与生成任务阶段交付记录

日期：2026-11-16。按用户提供的 server1.0.3.md 实现上传、参数能力、持久化任务、模拟推理与最小只读结果链路；保留 Java 21 / Spring Boot 4.1.2 / MyBatis-Plus 3.5.18，不新增 Maven 依赖，不修改前端，不重写 JWT。

## 实际范围

- FileStorage / LocalFileStorage：外部配置目录、随机键、临时写与原子移动、边读边限制大小、不跟随符号链接或目录穿越。
- 上传 JPEG/PNG：大小/扩展名/MIME/实际格式、先头部尺寸后完整解码、像素限制与解码警告拒绝；单 file 上传；DB 失败补偿清理。WebP 未启用。
- 源图片、任务、作品、模型、下载全部按 CurrentUser 归属校验，越权/不存在统一 404；图片和模型流式读取、nosniff、private/no-store，安全附件名。
- 创建任务需 UUID Idempotency-Key；规范化请求摘要与快照、实际 seed/profile/schemaVersion 持久化；用户行锁 + 唯一索引保护幂等与单用户未结束容量。
- GenerationParameterResolver 合并默认/预设/高级覆盖并做组合校验；不可变 resolved 参数完整保留。高级 overrides 的 null 意图保留在 requested 快照；不开放 num_chunks、face_count、多图、贴图或任意未知参数透传。
- DISABLED 默认不创建任务且不启动工作器；已有幂等重放可查询。MOCK 仅 local/dev/test，prod 拒绝。能力接口完整 JSON 示例已与代码序列化结果做一致性测试。
- 单实例数据库模拟工作器，条件更新推进状态、重建实例可恢复，终态不回退；无真实百分比 progress=null。无演示文件明确失败，有本地合法自包含 GLB 才复制为用户作品，事务保存文件/作品/成功状态。
- 文件系统与数据库非分布式事务；正常回滚清理，提交结果不明则核对引用，无法核对时保留 opaque key 日志待人工核对，避免删除已提交模型。进程崩溃孤立文件的运维清理不冒充自动完成。
- 新增 V3__generation_parameters_and_idempotency.sql，V1/V2 未修改，保留历史数据；历史无参数快照时返回 null。

## 上游源码检查

已实际读取腾讯 Hunyuan3D-2 提交 f8db63096c8282cb27354314d896feba5ba6ff8a 的 api_server.py、gradio_app.py、hy3dgen/shapegen/pipelines.py、rembg.py、texgen/pipelines.py。记录与固定提交链接见 [hunyuan-source-review.md](hunyuan-source-review.md)。

没有下载/部署真实模型，没有 Python 服务，没有 M5 16GB 参数/性能实测。MOCK 的去背景/参数/质量档位仅用于模拟联调，不执行真实预处理或推理；同一演示 GLB 不随输入或参数变化。

## 已验证

项目外临时 Maven Central settings 用于绕过本机全局不可用内网仓库，未修改全局配置：

```sh
./mvnw -s /tmp/formaspace-maven-settings.xml -ntp package
```

- 依赖解析、Java 编译、可执行 JAR 打包通过。
- 发现 **128 项测试，112 通过、16 跳过、0 失败、0 错误**。原鉴权与基础测试通过。
- 本轮新增 40 项无真实 MySQL 测试通过：图片/存储 5、文件失败补偿 3、参数解析与模式限制 12、任务快照/幂等服务 4、配置绑定与文档一致性 3、模拟生命周期 5、受保护 HTTP 接口 8。
- 覆盖图片格式伪装、损坏、尺寸/字节超限，路径穿越和根目录/文件符号链接，原子写失败无残留。
- 覆盖默认/null、显式覆盖、有限数值、范围/离散值、组合预算、随机 seed 范围、历史快照不随 profile 改变、同 key 不重算与不同请求 409。
- 覆盖 SMTP/鉴权原回归、所有新增业务接口需要认证、同一用户上下文透传、模型/图片二进制响应头、413、自定义幂等头 CORS、400/404 语义。
- 覆盖模型复制失败补偿、终态迟到结果丢弃、数据库故障保留可恢复状态、重建 mock 工作器继续状态推进、无演示 GLB 明确失败、损坏 GLB 拒绝。
- capabilities 文档完整 JSON 与实际默认 mock profile 序列化一致。Mock 显式 local+空演示路径能完成配置初始化；prod mock 在上下文初始化失败。
- git diff --check 通过；产物不包含测试 Probe、测试 fixture 或演示模型。

## 明确未验证

本机 127.0.0.1:3306 不可连接，Docker daemon 未运行，没有配置测试数据库。因此以下真实 MySQL 测试跳过：

- 原 AuthMysqlTests 10 项。
- 新 GenerationMysqlTests 6 项：完整上传/模型文件与作品成功链路、跨用户读取拒绝、同 key 并发一次创建/复用 seed、不同 key 并发容量、disabled 不残留任务/允许重放、无演示失败及终态保护。

V3 实际迁移、MySQL JSON/Mapper UTC/ID 往返、真实行锁/隔离/唯一约束与完整启动尚未验证。unit 的事务边界/模拟状态机测试不代表 MySQL 并发已验证，没有使用 H2 替代。重建 worker 的恢复测试不代表已做真实进程 kill/restart 的崩溃测试。

真实 SMTP、Hunyuan 模型、M5 性能与 Three.js 实际渲染均不在本轮验证结论中。GLB 仅验证容器/JSON/自包含引用等，不宣称全面 glTF 语义验证。

## 用户启动与验证

继续使用 README 的 MySQL、JWT_SECRET、AUTH_CODE_HMAC_KEY 配置，启动自动应用 V3；默认上传目录 ~/.formaspace/storage，默认 INFERENCE_MODE=DISABLED。

模拟成功验证：设置 SPRING_PROFILES_ACTIVE=local、INFERENCE_MODE=MOCK、MOCK_DEMO_GLB 为你本地合法自包含 GLB 的实际绝对路径，再启动。该文件仅作为演示副本，不是图片生成结果；不配置该路径则验证明确失败。各变量见 .env.example。不要将模型、上传目录或秘密提交到 Git。

[接口文档](generation-api.md) 包含请求/响应、完整能力 JSON、HTTP 错误、Blob 与幂等重试、curl 与本地自有 GLB 示例。README 给出独立 _test MySQL 的 GENERATION_TEST_DB_* 配置及测试命令；集成测试会清空该测试库业务表。

## 文件清单

修改：

- `.env.example`
- `README.md`
- `src/main/java/com/xiye/formaspace/asset/entity/ModelAssetEntity.java`
- `src/main/java/com/xiye/formaspace/asset/vo/ModelAssetVO.java`
- `src/main/java/com/xiye/formaspace/common/exception/ErrorCode.java`
- `src/main/java/com/xiye/formaspace/common/exception/GlobalExceptionHandler.java`
- `src/main/java/com/xiye/formaspace/config/WebConfig.java`
- `src/main/java/com/xiye/formaspace/generation/dto/CreateGenerationDTO.java`
- `src/main/java/com/xiye/formaspace/generation/entity/GenerationTaskEntity.java`
- `src/main/java/com/xiye/formaspace/generation/mapper/GenerationTaskMapper.java`
- `src/main/java/com/xiye/formaspace/generation/vo/GenerationTaskVO.java`
- `src/main/java/com/xiye/formaspace/user/mapper/UserMapper.java`
- `src/main/resources/application.yml`
- `src/test/java/com/xiye/formaspace/FormaspaceServerApplicationTests.java`

新增：

- `docs/generation-api.md`
- `docs/generation-checks.md`
- `docs/hunyuan-source-review.md`
- `skills/initialSkill/server1.0.3.md`
- `src/main/java/com/xiye/formaspace/asset/controller/ModelAssetController.java`
- `src/main/java/com/xiye/formaspace/asset/service/ModelAssetService.java`
- `src/main/java/com/xiye/formaspace/config/FileStorageConfig.java`
- `src/main/java/com/xiye/formaspace/config/GenerationConfig.java`
- `src/main/java/com/xiye/formaspace/config/MockSchedulingConfig.java`
- `src/main/java/com/xiye/formaspace/file/config/FileStorageProperties.java`
- `src/main/java/com/xiye/formaspace/file/controller/FileController.java`
- `src/main/java/com/xiye/formaspace/file/service/FileService.java`
- `src/main/java/com/xiye/formaspace/file/service/SourceImageValidator.java`
- `src/main/java/com/xiye/formaspace/file/storage/FileStorage.java`
- `src/main/java/com/xiye/formaspace/file/storage/LocalFileStorage.java`
- `src/main/java/com/xiye/formaspace/generation/config/GenerationProperties.java`
- `src/main/java/com/xiye/formaspace/generation/controller/GenerationController.java`
- `src/main/java/com/xiye/formaspace/generation/dto/AdvancedGenerationOptionsDTO.java`
- `src/main/java/com/xiye/formaspace/generation/enums/GenerationMode.java`
- `src/main/java/com/xiye/formaspace/generation/enums/GenerationQuality.java`
- `src/main/java/com/xiye/formaspace/generation/inference/GlbValidator.java`
- `src/main/java/com/xiye/formaspace/generation/inference/MockModelInferenceClient.java`
- `src/main/java/com/xiye/formaspace/generation/inference/ModelInferenceClient.java`
- `src/main/java/com/xiye/formaspace/generation/parameter/GenerationParameterResolver.java`
- `src/main/java/com/xiye/formaspace/generation/parameter/GenerationSnapshots.java`
- `src/main/java/com/xiye/formaspace/generation/parameter/RequestedGenerationParameters.java`
- `src/main/java/com/xiye/formaspace/generation/parameter/ResolvedGenerationParameters.java`
- `src/main/java/com/xiye/formaspace/generation/service/GenerationCapabilitiesService.java`
- `src/main/java/com/xiye/formaspace/generation/service/GenerationResultPublisher.java`
- `src/main/java/com/xiye/formaspace/generation/service/GenerationTaskService.java`
- `src/main/java/com/xiye/formaspace/generation/vo/GenerationCapabilitiesVO.java`
- `src/main/java/com/xiye/formaspace/generation/worker/MockGenerationWorker.java`
- `src/main/resources/db/migration/V3__generation_parameters_and_idempotency.sql`
- `src/test/java/com/xiye/formaspace/file/FileLifecycleTests.java`
- `src/test/java/com/xiye/formaspace/file/SourceImageAndStorageTests.java`
- `src/test/java/com/xiye/formaspace/generation/GenerationConfigurationTests.java`
- `src/test/java/com/xiye/formaspace/generation/GenerationHttpTests.java`
- `src/test/java/com/xiye/formaspace/generation/GenerationMysqlTests.java`
- `src/test/java/com/xiye/formaspace/generation/GenerationParameterTests.java`
- `src/test/java/com/xiye/formaspace/generation/GenerationTaskServiceTests.java`
- `src/test/java/com/xiye/formaspace/generation/MockLifecycleTests.java`
- `src/test/java/com/xiye/formaspace/testsupport/MediaFixtures.java`
- `src/test/java/com/xiye/formaspace/testsupport/TestTransactions.java`
