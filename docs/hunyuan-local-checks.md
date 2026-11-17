# Hunyuan 本地对接交付记录

日期：2026-11-17。已完成 Java 代码对接，**未完成真实图片生成的端到端验证**。

## 本轮完成

- 新增 HUNYUAN_LOCAL，保留 DISABLED/MOCK。沿用 Java 21、Spring Boot 4.1.2、MyBatis-Plus、RestClient 与现有 JWT/上传/分页/作品接口，Maven 无依赖变化。
- HunyuanLocalInferenceClient：配置服务同源 HTTP、实际图片 multipart 上传、经本机 Gradio 6.32.0 源码和离线元数据核对的 shape_generation 参数、event_id、完整 SSE、FileData/update 文件结果、限制体积/重定向/来源的 GLB 下载与临时文件清理。
- ModelInferenceClient 返回 SubmissionResult，查询使用持久化的远程引用并区分 RUNNING/SUCCEEDED/FAILED；Mock 同步适配。
- HunyuanGenerationWorker：单实例全局串行、任务截止时间、持久化事件/结果阶段、查询失败计数和有界退避；不持有 DB 事务等待 HTTP/SSE，提交结果未知不自动重提。Python app_id 变化或引用丢失明确失败。
- Mapper 真实与 mock SQL 各自约束 execution_mode / simulated / status，不跨模式接管；终态不回退，真实成功条件也约束 deadline。
- 新真实任务与作品 simulated=false，真实作品名不带模拟。结果发布复用文件+数据库补偿，实际使用 GlbValidator，重工作在事务外；远程临时 URL 不成为长期作品地址。
- 独立候选 STANDARD profile：默认 5 步/guidance 5/octree 128/numChunks 1000，真实 seed 上限 10000000（已核对 Slider），仅开放该 preset 的范围。配置与模型标识独立，不继承 mock HIGH；不是 MPS 性能/质量实测结论。
- 轻量 readiness 检查版本、API/参数顺序、File 返回、RGBA 输入、激活模型标识与 app_id，不靠网页 200 或生成一次探活。Python 不可用时 Java 的上传/历史仍可用，新任务 503，原幂等重放可返回。
- 新增 V4__hunyuan_remote_execution.sql；V1/V2/V3 原样保留。存储远程引用、阶段、查询失败次数、下次重试、deadline、模式索引；VO 不暴露这些内部字段。

## 本地环境实际检查

相邻 formaspace-inference 仓库 HEAD 为 f8db63096c8282cb27354314d896feba5ba6ff8a，源码工作区干净，未修改 Python、重新安装环境或下载另一套模型。本机 .venv 的 Gradio=6.32.0、gradio_client=2.7.2。

存在 gradio_app.py 进程，参数为 tencent/Hunyuan3D-2mini / hunyuan3d-dit-v2-mini / mps / disable_tex / 127.0.0.1:7860。检查时端口始终未监听，进程持有 mini 权重的下载锁与 .incomplete 文件。因此未能读取正在运行实例的实时 Use via API 或实际调用模型。

已实际读取 gradio_app.py、api_server.py、预处理/模型参数代码和本地安装 Gradio 的路由、客户端、更新值后处理实现；另外离线构建同源码 Blocks，读取 get_api_info/config（不执行 __main__、不加载权重、不启动端口），核对单图的 13 个参数及第 0 项 File 输出。shape_generation 已直接导出 GLB，无需新增 Python API 或后续 on_export_click。

协议、参数索引和参考文件见 [hunyuan-local-integration.md](hunyuan-local-integration.md)。

## 已执行检查

按用户要求仅新增 7 项小型 loopback HTTP/SSE 协议检查，并运行现有回归：

```sh
./mvnw -s /tmp/formaspace-maven-settings.xml -ntp package
```

- Java 编译和可执行 JAR 打包通过，git diff --check 通过。
- 共 135 项：**119 通过、16 跳过、0 失败、0 错误**。
- 新协议测试验证上传实际字节、13 项映射、固定 seed/randomize=false、完整 SSE、update 包装 FileData、忽略任意结果 URL并仅同源下载。
- 六个故障分支：远程 error、HTML 非文件结果、SSE 断线、app_id 变化、超大文件、302 重定向。测试只启动本地 stub，不调用真实模型、下载权重或启动 Python。
- 原 mock、上传、参数、鉴权、分页、文件补偿、GLB 校验回归通过。
- JAR 不包含测试 fixture/model，生产没有内置假 GLB。

Maven 使用项目外临时 settings 指向 Maven Central，绕过本机全局内网仓库配置；没有修改全局配置或项目依赖版本。

## 未执行、不能宣称完成

本机 MySQL 3306 不可连接，未配置真实数据库测试，原 10 项鉴权 + 6 项生成 MySQL 集成测试跳过。没有将 mock 事务测试当作真实 MySQL 验证。V4 真实迁移、DB 行锁/条件更新、真实文件/作品发布与重启恢复尚需实际数据库验证。

7860 不可连接、权重未确认就绪：未在页面成功生成同图，未通过 FormaSpace 创建真实任务得到 GLB/assetId，未实际下载或 Three.js 预览，未核对真实 Python stats/MPS 性能。7 项 stub 成功不代表这些步骤完成。

Gradio 不是持久化队列。Java 能恢复已保存、仍可查询的事件和已保存的文件引用；若事件已消费但结果引用尚未持久化就崩溃，可能只能核对 Python 缓存/日志后手工处理，不保证 exactly-once 或所有崩溃窗口无损恢复。

## 用户下一步

1. 等现有模型进程完成下载/加载，确认 7860 监听以及页面可生成 GLB；不要重复启动多个 pipeline。
2. 启动 MySQL，注入已有 DB/JWT/HMAC 配置，让 Flyway 应用 V4。
3. 设置 SPRING_PROFILES_ACTIVE=local、INFERENCE_MODE=HUNYUAN_LOCAL、HUNYUAN_BASE_URL=http://127.0.0.1:7860；启动 Java。
4. capabilities 可用后，上传→带 Idempotency-Key 创建任务→轮询终态→Bearer 下载/Blob 预览，确认 simulated=false 和实际参数。

配置、启动命令、参数能力、错误诊断、未来 GPU base-url 切换见 README、.env.example、[hunyuan-local-integration.md](hunyuan-local-integration.md)。

## 文件清单

修改：

- `.env.example`
- `README.md`
- `docs/generation-api.md`
- `src/main/java/com/xiye/formaspace/common/exception/ErrorCode.java`
- `src/main/java/com/xiye/formaspace/generation/config/GenerationProperties.java`
- `src/main/java/com/xiye/formaspace/generation/entity/GenerationTaskEntity.java`
- `src/main/java/com/xiye/formaspace/generation/enums/GenerationMode.java`
- `src/main/java/com/xiye/formaspace/generation/inference/MockModelInferenceClient.java`
- `src/main/java/com/xiye/formaspace/generation/inference/ModelInferenceClient.java`
- `src/main/java/com/xiye/formaspace/generation/mapper/GenerationTaskMapper.java`
- `src/main/java/com/xiye/formaspace/generation/parameter/GenerationParameterResolver.java`
- `src/main/java/com/xiye/formaspace/generation/service/GenerationCapabilitiesService.java`
- `src/main/java/com/xiye/formaspace/generation/service/GenerationResultPublisher.java`
- `src/main/java/com/xiye/formaspace/generation/service/GenerationTaskService.java`
- `src/main/java/com/xiye/formaspace/generation/worker/MockGenerationWorker.java`
- `src/main/resources/application.yml`
- `src/test/java/com/xiye/formaspace/generation/GenerationConfigurationTests.java`
- `src/test/java/com/xiye/formaspace/generation/GenerationHttpTests.java`
- `src/test/java/com/xiye/formaspace/generation/GenerationTaskServiceTests.java`
- `src/test/java/com/xiye/formaspace/generation/MockLifecycleTests.java`

新增：

- `docs/hunyuan-local-checks.md`
- `docs/hunyuan-local-integration.md`
- `skills/initialSkill/server1.0.4.md`
- `src/main/java/com/xiye/formaspace/config/HunyuanSchedulingConfig.java`
- `src/main/java/com/xiye/formaspace/generation/config/HunyuanLocalProperties.java`
- `src/main/java/com/xiye/formaspace/generation/inference/HunyuanLocalInferenceClient.java`
- `src/main/java/com/xiye/formaspace/generation/inference/InferenceClientException.java`
- `src/main/java/com/xiye/formaspace/generation/parameter/GenerationProfile.java`
- `src/main/java/com/xiye/formaspace/generation/service/GenerationReadiness.java`
- `src/main/java/com/xiye/formaspace/generation/worker/HunyuanGenerationWorker.java`
- `src/main/resources/db/migration/V4__hunyuan_remote_execution.sql`
- `src/test/java/com/xiye/formaspace/generation/HunyuanProtocolTests.java`
