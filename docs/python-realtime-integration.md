# Python 常驻 HTTP / WebSocket 对接

## 范围和实际协议来源

新增独立 `INFERENCE_MODE=PYTHON_REALTIME`，Java 是 HTTP/原生 WebSocket 客户端。DISABLED、MOCK、HUNYUAN_LOCAL 保留原有语义；HUNYUAN_LOCAL 仍是 Gradio HTTP/SSE，工作器按 execution_mode 隔离。没有修改 Python、前端，没有增加浏览器 WebSocket 服务端。

起初相邻 Python 仓库只有 `skills/1.0.2.md` 需求文档，8001 尚未监听；执行期间 Python 已交付 `31a2637`，新增 `docs/inference-service-v1.md`、`inference_service/` 和 `scripts/inference_server.py`。本轮已重新核对实际源码、readyz 与运行协议，并按该实现同步参数范围。既有 Gradio 的 7860 不是本协议服务，不能改个地址直接复用。

使用 JDK 21 HttpClient/WebSocket 处理原生 WebSocket，HTTP 复用已有 Spring RestClient，JSON 复用 Jackson。未新增依赖，未升级 Spring Boot。

## 配置和启动

Python 新服务需先启动并 ready，Java 使用同一服务令牌。准备现有数据库和鉴权配置后，在 macOS zsh 运行：

```sh
export INFERENCE_MODE=PYTHON_REALTIME
export INFERENCE_API_BASE_URL=http://127.0.0.1:8001
read -rs 'INFERENCE_API_TOKEN?Python 服务令牌: '
export INFERENCE_API_TOKEN
bash scripts/run-local.sh
```

IDE 运行时也须设置以上变量。不要将用户 JWT 当作服务令牌。仅在启用 PYTHON_REALTIME 时强制校验令牌非空；服务离线不阻止 Java 启动、登录和历史读取，capabilities 根据 `/readyz` 返回 generationAvailable。就绪检查不触发推理。真实部署使用 HTTPS/WSS 或受控网络。

本机 Python 已将共享令牌保存在仓库外 `~/.config/formaspace/inference.env`。`INFERENCE_MODE=PYTHON_REALTIME bash scripts/run-local.sh` 会在未手动设置令牌时加载该文件；不输出或复制令牌进 Git。IDE 仍须把服务令牌作为环境变量注入。默认模式未切换，原 Gradio 配置保持不变。

| 环境变量 | 默认值 / 用途 |
| --- | --- |
| INFERENCE_API_BASE_URL | http://127.0.0.1:8001 |
| INFERENCE_WS_BASE_URL | 可选；从 HTTP scheme 安全推导 ws/wss，保留基路径 |
| INFERENCE_API_TOKEN | 必填，仅放在 HTTP 与 WS 握手 Authorization 头 |
| INFERENCE_CONNECT_TIMEOUT | 5s |
| INFERENCE_SUBMIT_TIMEOUT | 20s |
| INFERENCE_SNAPSHOT_TIMEOUT | 5s |
| INFERENCE_DOWNLOAD_TIMEOUT | 2m |
| INFERENCE_TASK_DEADLINE | 30m |
| INFERENCE_RECONNECT_INITIAL_DELAY / MAX_DELAY | 2s / 30s，指数退避并附加抖动 |
| INFERENCE_RECONCILIATION_INTERVAL | 10s，HTTP 协调及连接检查 |
| INFERENCE_MAXIMUM_CONNECTIONS | 1，最多可配置 8 |
| INFERENCE_MAXIMUM_MESSAGE_BYTES | 2097152，覆盖 5000 点完整 JSON |
| INFERENCE_MAXIMUM_DOWNLOAD_BYTES | 104857600；现有文件存储上限仍生效 |
| INFERENCE_MAXIMUM_SUBMISSION_ATTEMPTS | 4；耗尽不宣称远端任务被取消 |
| REALTIME_STEPS / GUIDANCE_SCALE / FINAL_RESOLUTION | 30 / 5 / 256 |
| REALTIME_NUM_CHUNKS | 4000 |
| REALTIME_PREVIEW_ENABLED / STEP / RESOLUTION / POINT_COUNT | true / 24 / 128 / 5000 |

其他配置：`formaspace.generation.python-realtime.event-queue-capacity` 默认 16；测试用 poll-delay-ms / initial-delay-ms 不属于前端接口。

## 业务身份与迁移

仅新增 Flyway V5，V1～V4 未修改。迁移为任务增加 inference_task_id（UUID，唯一索引）、inference_service（HTTP/WS 基地址绑定摘要）、python_parameters（不可变 JSON）、last_python_sequence、采样步数、Python/Java 两套预览版本、提交状态/次数、连接状态、预览错误码。沿用 V4 deadline_at、next_poll_at、query_failures 等字段。

本轮迁移验证在独立 `_test` 库完成；业务库仍需重启更新后的 Java，由 Flyway 自动应用 V5，无需手工执行 SQL。

新增 `fs_generation_preview`，主键为业务 task_id + Python version，保存版本、source_step、point_count、随机存储键和时间；坐标不写入任务主表。

业务任务创建事务内先生成 UUID 并保存参数快照，事务提交后后台线程才向 Python 发送。幂等重放、断线、Java 重启始终使用原 UUID 和原参数 JSON，不从浏览器接受推理 UUID。服务基地址变更时任务标记 SERVICE_CHANGED，不转向新地址处理旧任务；须恢复原服务配置后协调。服务身份绑定基于配置地址，Python 进程重启状态由其持久快照中的 SERVICE_RESTARTED 识别；协议尚无额外实例 ID 字段。

## HTTP 协议及参数

除 `/healthz` 外，所有请求和 WebSocket 握手都带 `Authorization: Bearer <INFERENCE_API_TOKEN>`。不记录服务令牌、图片、点云数组或原始异常消息。

- `GET /readyz`：模型可用性；200 且 ready 未显式为 false，若返回 modelId 则必须与配置一致。
- `POST /api/v1/tasks`：multipart 的 taskId、image、parameters。图片由 FileService 读取流，不传本机路径。
- `GET /api/v1/tasks/{UUID}`：queued/running/completed/failed 快照。
- `GET /api/v1/tasks/{UUID}/previews/{version}`：断线恢复点云。
- `GET /api/v1/tasks/{UUID}/result`：受大小/时间限制的 GLB 下载。

参数映射：seed→seed、inferenceSteps→steps、guidanceScale→guidanceScale、octreeResolution→finalResolution、numChunks→numChunks；previewEnabled、previewStep、previewResolution、pointCount 来自服务端 profile。全部保存在 python_parameters，重试不重算。

协议没有 removeBackground、texture、format 字段，不向 Python 添加这些字段。Java 新模式把预处理视为 Python 固定行为：去背景不可切换（默认 true），显式 false 返回 UNSUPPORTED_CAPABILITY；贴图始终不可用，格式固定 GLB。已核对 Python `HunyuanEngine/prepare_image`：固定转换 RGBA，再启用去背景，与该能力声明一致。旧 HUNYUAN_LOCAL 去背景可切换，不受影响。

新模式默认 30 步 / 第 24 步预览。自定义 steps 时要求 previewStep < steps；按已交付 Python 实现：步数 2～50、guidance 0～10、最终分辨率仅 128/256，seed 0～10000000；预览分辨率仅 64/128，previewStep 1～49，numChunks 1000～8000。预览关闭时可使用短步数。Python 仍需验证实际模型支持和资源预算。

提交成功校验 taskId、snapshotPath、eventsPath 与持久化 UUID 完全一致。返回路径只作协议校验，实际请求均从受控基地址和 UUID 构造；禁止任意来源、路径穿越及重定向。提交 409 不更换 UUID；429 尊重 Retry-After 延后提交；超时先查询原 UUID，404 且从未确认接收时才允许同请求有界重试。已确认接收后 404 为引用丢失，不重新生成。

## 线程、sequence 和连接

HTTP 提交、恢复、下载和数据库工作由单线程有界工作队列执行，保证每个任务的事件顺序；最大连接数另有限制。JDK WS I/O 也使用有界执行器。

WS 回调只拼接有大小上限的文本并入队。分片完成后由工作线程处理，完成后 request(1)；队列满、超大文本或二进制帧使连接断开，以快照恢复，不无限排队。连接第一条必须为 task_snapshot，协议版本为 1，并校验 UUID、sequence、stage、步数、预览版本、时间及预览/模型路径。

增量序号只能 lastSequence+1；重复旧事件忽略，跳号断开并通过 HTTP 快照恢复。快照允许跨序号，相同序号也会补保存预览或补发布。迟到事件不会回退 SUCCEEDED/FAILED。

30/30 仅表示采样结束，stage=final_decoding 时仍为 PROCESSING，progress 不伪造为 100%。普通 preview_failed（fatal=false）不增加版本、不使任务失败；fatal=true 时仍等待后续 task_failed 或权威快照，不自行伪造终态。Python 的错误 message 不直接透传内部信息，未知错误码使用安全兜底。

WS 关闭、异常、输入/输出已关闭或连接检查失败时，持久化 RECONNECTING 和退避时间。连接恢复前先读取 HTTP 快照；连接后仍验证首条快照。达到截止时间且结果未知时标记 INFERENCE_STATE_UNCERTAIN，明确远端未被取消。停止/终态时释放连接和有界队列。

## 点云预览接口

WS preview_ready 中的 positions 直接校验并保存，不重复下载。同一校验器用于 HTTP 恢复：1～5000 点、长度一致、每点恰好三个有限数值、sourceStep 和预览版本正确。不居中、不缩放、不修改坐标系。

先原子保存受控 JSON 文件，再事务插入预览记录并更新 stored_preview_version。python_preview_version 与 stored_preview_version 分离；下载/保存失败后相同序号快照仍可重试。事务提交结果未知时保留可能已被引用的文件，避免删除有效预览。

`GET /api/generation/tasks/{业务taskId}/previews/{version}` 使用现有 JWT。仅任务所有者可读，不存在与越权统一 404。返回标准 ApiResponse：

```json
{
  "code":"OK","message":"success",
  "data":{"taskId":"123","previewVersion":1,"sourceStep":24,"pointCount":5000,"positions":[[0.1,0.2,0.3]]}
}
```

示例省略其余坐标。没有 inferenceTaskId、服务令牌、Python 地址或磁盘路径。任务详情/历史新增 stage、completedSteps、totalSteps、latestPreview、connectionState；latestPreview 仅指向 Java 已保存版本，包含 version/sourceStep/pointCount/positionsPath。

内部 GenerationEventPublisher 发布业务 taskId、事件类型与 sequence，预留后续浏览器转发；当前不建立浏览器 WS，也不转发完整点云日志。

## 最终发布和恢复

Python completed 后，Java 仍保持 PROCESSING/saving_result，下载模型、通过现有 GlbValidator、保存受控文件，再复用 GenerationResultPublisher 事务保存文件和唯一作品，并更新 SUCCEEDED。业务完成事件在数据库事务成功返回后发布。simulated=false，作品使用真实名称。

结果下载 409 延后协调，404 视为引用丢失，损坏 GLB 为协议错误。网络/存储/数据库暂时失败保持可补发布状态，绝不再次调用生成 POST；Java 重启后可从已保存的结果引用补发布。重复完成不重复创建作品，沿用原有文件清理和提交不确定保护。

可选预览暂时保存失败不会阻止最终模型发布。终态任务若 python_preview_version 大于 stored_preview_version，后台通过独立的、带退避时间的数据库协调记录继续补存预览，不重新连接生成 WS，不回退业务终态，也不重新生成模型。

SERVICE_RESTARTED 同步为失败，保留已经保存的预览。已完成业务任务不因 Python 断线回退。已确认任务、未知提交、容量延迟三类恢复状态分别处理，不把所有 404 都当成重提许可。

新增错误码：INFERENCE_STATE_UNCERTAIN、SERVICE_RESTARTED、INFERENCE_SUBMISSION_CONFLICT、INFERENCE_AUTH_FAILED；沿用 INFERENCE_PROTOCOL_ERROR、INFERENCE_REFERENCE_LOST、SUBMISSION_UNCERTAIN、INFERENCE_FAILED、INFERENCE_UNAVAILABLE 等。

## 执行和验证

无需启动模型的协议/恢复测试：

```sh
./mvnw -s scripts/maven-local-settings.xml -o \
  -Dtest=PythonRealtimeProtocolTests,PythonRealtimeWorkerTests test
```

独立 MySQL 测试必须显式配置 REALTIME_TEST_DB_URL、REALTIME_TEST_DB_USERNAME、REALTIME_TEST_DB_PASSWORD，库名必须以 `_test` 结束：

```sh
./mvnw -s scripts/maven-local-settings.xml -o -Dtest=PythonRealtimeMysqlTests test
```

该测试清理的是指定独立测试库，不得使用业务库。使用本机 Compose 的 formaspace_realtime_test 已实际验证 V1～V5、真实 SQL、JWT 预览隔离、断线/跳号恢复、HTTP 补取 5000 点、预览失败不中止、30/30 等待解码、Java 工作器重启、结果 409 重试、真实文件存储和 GLB 校验、唯一作品提交及鉴权模型读取。推理 HTTP/WS 和 GLB 内容来自明确的 loopback 测试替身，不是实际模型生成。

其他测试覆盖 202/multipart/令牌、同 UUID 同参数重试、409 冲突、429 退避、完整 WS 握手/分片、大消息拒绝、非法点云、结果大小/重定向限制、Python 重启和服务地址绑定。已有测试回归另行记录。

最终默认回归命令 `./mvnw -s scripts/maven-local-settings.xml -o test`：171 项，152 通过、19 项因未显式开启外部集成条件而跳过，零失败。随后显式开启的 PythonRealtimeMysqlTests 独立 MySQL 测试 1 项通过；PythonRealtimeLiveTests 真实模型链路 1 项通过（见下文）。没有将默认跳过的旧鉴权/MySQL/Gradio 测试说成已运行。

真实验收记录见下方实际执行结果；显式联调使用新 8001 服务，未将旧 Gradio 验证或测试替身结果代替真实生成。

协议差异：新 Python 实际限制比此前 Gradio 参考范围更窄，Java 新模式已按 2～50 步、128/256 最终分辨率对齐，旧 HUNYUAN_LOCAL 不变。Python 的 healthz/readyz 都无需认证，Java 对 readyz 仍附带服务令牌，可正常兼容；其余 HTTP/WS 必须认证。Python 重放返回 202，Java 兼容 200/202，均严格校验任务和路径。Python 致命 preview_failed 会带 fatal=true，Java 记录该预览失败后等待 task_failed。

前端本轮未改动；现有前端严格 mode 枚举需在下一轮增加 PYTHON_REALTIME，并读取新增采样/预览字段。Java 不把新服务伪装成 Gradio 模式。

## 真实 Java → Python 执行记录

2026-11-17 已显式执行 `PythonRealtimeLiveTests`，连接实际运行的 Python 31a2637、mini/MPS，使用缓存模型与真实 `004.png`，独立库 `formaspace_realtime_live_test`。没有修改 Python 或前端，没有替身替代此项推理。

- Java 创建测试用户并签发真实 JWT，经 Java MVC 上传接口保存真实图片、创建业务任务；Java 原生客户端向 8001 提交 multipart 并订阅真实 WS。
- seed=1234、steps=30、guidance=5、finalResolution=256、numChunks=4000、previewStep=24、previewResolution=128、pointCount=5000。
- 观察到 task_snapshot、sampling_step、preview_ready、finalizing、task_completed；阶段包含 preprocessing、sampling、preview_decoding、final_decoding，真实已完成步数达到 30。
- 第 24 步的 5000 点成功落 Java 文件存储，预览接口返回完整点云；最终 GLB 7,158,596 字节，通过现有 GlbValidator。
- 业务任务 id=1、作品 id=1（均为独立 live 测试库），simulated=false、status=SUCCEEDED；模型读取与下载两条 Java 鉴权接口内容一致。请求经过 Java MVC 测试入口，Java→Python HTTP/WS、MySQL 与文件存储均为真实调用，未执行浏览器前端验收。
- 该项测试约 81 秒（包含 Spring 启动），不作为质量或性能基准。真实下载产物保留于 `/var/folders/q5/2595gfx94td1g3587lpbzydm0000gn/T/formaspace-live-downloaded-5044715958381807474.glb`，日志 `/tmp/formaspace-python-java-live.log`。未保存令牌、JWT 或验证码到报告。

显式复现：设置独立 `REALTIME_TEST_DB_*`、实际 `INFERENCE_API_TOKEN` 与 `REALTIME_LIVE_IMAGE` 后，执行 `./mvnw -s scripts/maven-local-settings.xml -o -Dtest=PythonRealtimeLiveTests test`。该测试默认跳过，不自动启动/停止 Python、不下载权重，也不会用业务库做测试。

尚未验证：浏览器实时展示、远程 GPU/TLS/代理部署、多实例分布式调度、Java 进程实际 SIGKILL。工作器重建和恢复持久化记录已由 MySQL 测试验证；Python 重启 SERVICE_RESTARTED 使用受控快照测试，未再次强杀用户当前模型进程。
