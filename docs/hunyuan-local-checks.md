# Hunyuan 本地对接交付记录

日期：2026-11-17。已完成 Java 代码对接及**Java 客户端到真实模型的图片生成、SSE 与 GLB 下载验证**。MySQL 尚不可用，任务/作品入库及前端端到端验证仍未完成。

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

## 首轮本地环境检查（历史记录）

相邻 formaspace-inference 仓库 HEAD 为 f8db63096c8282cb27354314d896feba5ba6ff8a，源码工作区干净，未修改 Python、重新安装环境或下载另一套模型。本机 .venv 的 Gradio=6.32.0、gradio_client=2.7.2。

存在 gradio_app.py 进程，参数为 tencent/Hunyuan3D-2mini / hunyuan3d-dit-v2-mini / mps / disable_tex / 127.0.0.1:7860。检查时端口始终未监听，进程持有 mini 权重的下载锁与 .incomplete 文件。因此未能读取正在运行实例的实时 Use via API 或实际调用模型。

已实际读取 gradio_app.py、api_server.py、预处理/模型参数代码和本地安装 Gradio 的路由、客户端、更新值后处理实现；另外离线构建同源码 Blocks，读取 get_api_info/config（不执行 __main__、不加载权重、不启动端口），核对单图的 13 个参数及第 0 项 File 输出。shape_generation 已直接导出 GLB，无需新增 Python API 或后续 on_export_click。

协议、参数索引和参考文件见 [hunyuan-local-integration.md](hunyuan-local-integration.md)。

## 首轮检查（历史记录）

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

## 当前验证边界

本机 MySQL 3306 不可连接，未配置真实数据库测试，原 10 项鉴权 + 6 项生成 MySQL 集成测试跳过。没有将 mock 事务测试当作真实 MySQL 验证。V4 真实迁移、DB 行锁/条件更新、真实文件/作品发布与重启恢复尚需实际数据库验证。

本次 7860 已监听并成功执行真实图片生成。Python stats 已确认 seed/steps/guidance/octree/rembg/numChunks，Java 下载的真实 GLB 通过结构与 mesh 存在性校验。但尚未通过 FormaSpace 的上传/任务 REST 接口得到 assetId，未执行 Three.js 预览，也未进行多图质量与长期稳定性评测。

Gradio 不是持久化队列。Java 能恢复已保存、仍可查询的事件和已保存的文件引用；若事件已消费但结果引用尚未持久化就崩溃，可能只能核对 Python 缓存/日志后手工处理，不保证 exactly-once 或所有崩溃窗口无损恢复。

## 用户下一步

1. 当前模型已重启并启用共享串行队列，7860 可用；保持单个 pipeline 进程运行。
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

## 本次补充检查与真实结果

- 已读取正在运行服务的 `/config` 与 `/gradio_api/info`，确认 Gradio 6.32.0、mini 非 turbo、RGBA、13 个参数顺序、File 输出；Java readiness 实测通过。
- 旧进程固定参数复现截图的 index 0 越界，原进程未暴露完整 traceback；原参数重启后两次真实生成成功，不能据此声称根因已完全确定。
- Python 实际调用：`004.png`、seed=1234、steps=5、guidance=5、octree=128、removeBackground=true、numChunks=1000、randomize_seed=false。结果 51,284 顶点/102,488 面，shape generation 约 11 秒。
- Java `HunyuanLocalIntegrationTests` 实际调用相同输入和参数，经 multipart 上传、POST call、完整 SSE、FileData/update 解析和同源下载，获得 **1,846,496 字节**真实 GLB；GlbValidator 与 mesh 检查通过，整项测试约 14 秒。该测试显式由 `HUNYUAN_INTEGRATION_IMAGE` 开启；默认跳过，不启动模型、不下载权重，不要求数据库。
- 修复 Python 两个生成事件未共享并发队列的问题：shape_generation/generation_all 都使用 `hunyuan_pipeline` 且并发固定 1。离线构建实际 Blocks 的 unittest 通过；未用参数替换、假模型或换权重处理错误。
- 新增 6 项 `HunyuanGenerationWorkerTests`：重启后事件恢复、已保存结果恢复、不确定提交不重发、有界退避、截止时间、迟到完成不发布。
- 默认 `./mvnw -s /tmp/formaspace-maven-settings.xml -ntp package`：**142 项，125 通过、17 跳过、0 失败、0 错误**。跳过为 16 项需要 MySQL 的集成测试与 1 项显式开启的真实模型测试。打包成功。
- Python `.venv/bin/python -m unittest discover -s tests -v`：1 项通过。Gradio 6 的旧 theme/css 参数警告沿用上游，不影响推理协议。
- 新增文件：`src/test/java/com/xiye/formaspace/generation/HunyuanLocalIntegrationTests.java`、`src/test/java/com/xiye/formaspace/generation/HunyuanGenerationWorkerTests.java`；推理仓库修改 `gradio_app.py`，新增 `tests/test_gradio_concurrency.py`。
- 依赖无变化；数据库仍只新增 V4，未修改 V1～V3。本次没有提交或推送代码。

真实模型验证与业务端到端验证分开记录：前者已通过，后者仍需可连接的 MySQL、账号/JWT 配置及实际任务发布结果。

串行配置重启后的复验：13:17 Java 实际模型测试再次通过（11.8 秒，1,846,496 字节），确认修改已加载且协议不变。日志 `/tmp/formaspace-real-java-serial.log`。前端已补齐 HUNYUAN_LOCAL 响应枚举、生成开关、模型状态与失败提示；lint/build 和三种模式的能力/参数契约检查通过，无依赖变化。文件清单与浏览器验证边界见前端 `docs/hunyuan-local-integration.md`。


## 后续本地基础服务验证

2026-11-17，MySQL 已通过独立 Compose 容器启动（本机 3307），完整 Java 启动与 Flyway V1～V4 成功，健康接口返回 UP。本地真实 SMTP 验证亦已通过，详见 local-mysql.md 与 local-mail.md。上文 MySQL 不可用属于此前检查记录；真实生成业务任务→作品入库→前端预览/下载整条链路仍待验收。
