请直接修改当前 formaspace-server，实现 Python 常驻推理服务的 HTTP 任务提交、WebSocket 事件订阅、中间点云保存及最终模型发布。

本轮只修改 Java 项目，不修改 Python 和前端，不实现浏览器 WebSocket 服务端。

先阅读 AGENTS.md、现有代码和 Python 提供的协议文档。复用现有登录、任务、参数快照、文件存储和作品发布逻辑，不重复搭建。

# 一、目标与边界

实现：

前端创建业务任务 → Java 向 Python 提交图片和参数 → Java 订阅 Python WebSocket → 更新真实采样状态和点云预览 → 下载最终 GLB → 发布作品。

Python 是 HTTP/WebSocket 服务端，Java 是客户端。

默认 Python 地址：

- HTTP：http://127.0.0.1:8001
- WS：ws://127.0.0.1:8001

所有连接地址可配置，未来部署到不同机器后不依赖共享文件系统。

本轮对接的是新建的 Python 常驻 API，不是 Gradio API。不要调用旧的 /send、/status、Gradio upload 或 Gradio SSE。

保留现有 DISABLED、MOCK 和普通生成链路；新增独立模式或 transport 区分实时服务，避免静默改变已有 HUNYUAN_LOCAL 的含义。

# 二、先检查现有实现

重点阅读：

- ModelInferenceClient 及其现有实现。
- GenerationTaskService、任务调度器。
- GenerationTaskEntity、GenerationTaskMapper。
- GenerationParameterResolver、参数快照。
- GenerationCapabilitiesService。
- GenerationResultPublisher。
- FileService、FileStorage、GlbValidator。
- JWT 和当前用户身份获取方式。
- Flyway、配置、现有测试和 docs/generation-api.md。

注意现有代码可能仍包含：

- simulated 固定 true。
- SQL 限定 simulated=TRUE。
- Publisher 只允许模拟任务。
- 调度仅在 MOCK 模式启用。

先确认本地版本，按实际情况修改。不得只增加客户端却遗漏这些限制，也不能删除所有模式约束导致不同客户端处理错任务。

# 三、依赖和线程

使用与当前 Spring Boot 兼容的原生 WebSocket 客户端，不使用 STOMP、SockJS。

可选择 JDK 21 HttpClient/WebSocket，或项目已有的 Spring WebSocket 客户端，统一采用一种。

- 不升级现有 Spring Boot 或固定依赖。
- 必要新增依赖说明用途。
- JSON 复用项目现有序列化方案。
- HTTP 提交、WS 等待、重连、下载不得占用 Servlet 请求线程。
- WS 回调不做数据库长事务、文件下载或大量解析后的业务操作。
- 使用有界执行器，保证单任务事件按序处理。
- 若采用 JDK WebSocket，正确拼接分片文本，设置消息大小上限并正确调用 request(n)，不能假定一次 onText 就是一条完整消息。
- 首版5000点消息较大，配置消息上限时考虑其完整 JSON 大小，不能沿用过小默认值。

# 四、配置与服务鉴权

新增对应配置：

- enabled / mode。
- Python HTTP baseUrl。
- Python WS baseUrl，或从 HTTP 地址安全推导。
- token。
- connectTimeout。
- submitTimeout。
- snapshotTimeout。
- downloadTimeout。
- taskDeadline。
- reconnectInitialDelay。
- reconnectMaxDelay。
- reconciliationInterval。
- 最大连接数、消息大小、下载大小。

服务令牌从环境变量 INFERENCE_API_TOKEN 注入。

除 GET /healthz 外，下列接口统一携带：

Authorization: Bearer <INFERENCE_API_TOKEN>

包括 HTTP 和 WebSocket 握手。不要将浏览器用户 JWT 发给 Python，也不要把服务令牌暴露给浏览器、URL或日志。

启用真实服务模式但未配置令牌时启动失败。真实服务暂时不在线，不应导致登录和历史查询整体不可用。

生产访问使用 HTTPS/WSS 或受控网络，开发可使用 localhost HTTP/WS。

# 五、任务 ID 和持久化

保留现有 Java 业务任务 ID，不强制将数据库自增 ID 改为 UUID。

为每个需要提交到 Python 的业务任务生成并持久化一个 UUID：

业务 taskId ↔ inferenceTaskId（Python taskId）

- 创建业务任务时确定 UUID，首次提交前持久化。
- HTTP 重试、Java 重启、WS 重连始终使用同一 UUID。
- Python 服务变更后，已有任务仍须关联原服务实例/配置，不错误查询另一台机器上的同名任务。
- 不从前端接收任意 Python UUID 来决定任务归属。

新增 Flyway 迁移，仅补实际缺失内容，例如：

- inferenceTaskId，唯一索引。
- 推理服务标识/transport。
- lastPythonSequence。
- Python stage。
- completedSteps、totalSteps。
- previewVersion。
- 最新预览元数据和受控存储引用。
- 提交状态、重试时间、任务截止时间。
- 必要的恢复状态。

已有 requested/resolved 参数快照继续保留，补充实际 Python 参数快照，避免重试时因配置变化改变请求。

不修改已执行迁移、不删除数据。

# 六、HTTP 提交协议

POST /api/v1/tasks

multipart/form-data：

- taskId：持久化的 inferenceTaskId UUID。
- image：真实图片文件内容。
- parameters：JSON 字符串。

参数示例：

{
"seed": 1234,
"steps": 30,
"guidanceScale": 5.0,
"finalResolution": 256,
"previewEnabled": true,
"previewStep": 24,
"previewResolution": 128,
"pointCount": 5000,
"numChunks": 4000
}

参数映射：

- Java seed → seed。
- inferenceSteps → steps。
- guidanceScale → guidanceScale。
- octreeResolution → finalResolution。
- numChunks → numChunks。
- 预览参数来自经过校验的服务端 profile。

协议当前没有 removeBackground、texture、format 请求字段：

- 不把这些字段随意追加到 Python parameters。
- 明确 Python 当前固定的预处理和无纹理能力。
- Java 能力接口与参数解析器必须一致。
- 不支持的选项明确拒绝，不能静默忽略。
- 本轮格式为 GLB，无纹理。

默认采用30步、第24步一次预览。允许 steps 调整时，必须确保 previewStep < steps；不支持的组合返回参数错误，不能把24/5之类请求发给 Python。

图片从现有 FileService 流式读取，不把 Mac 绝对路径当成 Python 可读路径。

成功202：

{
"taskId": "<UUID>",
"status": "queued",
"snapshotPath": "/api/v1/tasks/<UUID>",
"eventsPath": "/api/v1/tasks/<UUID>/events"
}

校验返回 taskId 与提交 UUID 一致。

路径相对于 Python 服务，不是 Java 地址。仅允许协议规定的路径和配置的服务来源，拒绝任意外部地址、路径穿越、错误任务 ID和未经允许的重定向。

幂等：

- 相同 UUID、相同图片内容、相同规范化参数只生成一次。
- 冲突409不能自动换 UUID 重提。
- 提交超时后使用原 UUID查询快照，必要时用完全相同请求有界重试。
- 429是容量不足，延迟再提交，不忙循环。
- Java 自身重试次数耗尽不等于 Python 推理已取消。

Java 创建业务任务的 HTTP 接口仍快速返回，不等待 Python完成。

# 七、快照协议

GET /api/v1/tasks/{inferenceTaskId}

{
"taskId": "<UUID>",
"status": "running",
"sequence": 27,
"stage": "sampling",
"completedSteps": 24,
"totalSteps": 30,
"previewVersion": 1,
"latestPreview": {
"version": 1,
"sourceStep": 24,
"pointCount": 5000,
"positionsPath": "/api/v1/tasks/<UUID>/previews/1"
},
"result": null,
"error": null
}

无预览 latestPreview=null。

成功 result：

{
"modelPath": "/api/v1/tasks/<UUID>/result",
"format": "glb"
}

失败 error：

{
"code": "INFERENCE_FAILED",
"message": "可展示信息"
}

status 值按 Python 实际交付文档确认，双方统一 queued/running/completed/failed，不自行添加互不兼容的值。

不存在404。

# 八、WebSocket 连接和事件

Java 主动连接：

/api/v1/tasks/{inferenceTaskId}/events

握手带服务令牌。

统一事件：

{
"protocolVersion": 1,
"taskId": "<UUID>",
"sequence": 27,
"type": "preview_ready",
"stage": "sampling",
"completedSteps": 24,
"totalSteps": 30,
"previewVersion": 1,
"timestamp": "<UTC ISO-8601>",
"payload": {}
}

事件：

- task_queued / queued。
- task_started / preprocessing。
- sampling_step / sampling。
- preview_started / preview_decoding。
- preview_ready / sampling。
- preview_failed / sampling。
- finalizing / final_decoding。
- task_completed / completed。
- task_failed / failed。

连接第一条消息必须是 task_snapshot：

- 不新增持久事件序号。
- sequence 是快照的最后事件序号。
- payload 是完整 HTTP 快照。
- 已结束任务也应收到终态快照。

事件处理：

1. 校验 protocolVersion。
2. 校验 taskId 与该连接持久化映射一致。
3. 校验序号、步数、预览版本、负载大小。
4. 按单任务串行处理。
5. 持久化状态与最后已接受序号。
6. 调用内部事件发布接口，预留未来向前端转发。

增量 sequence 必须严格递增：

- 重复旧事件忽略。
- 发现跳号，停止信任该增量流，重连取得快照。
- 不要求Python历史重放，快照允许跨越多个序号。
- 快照sequence与已记录相同也要协调未完成的预览下载/结果发布，不能因为相等直接跳过全部处理。
- 不允许旧快照或迟到事件回退终态。

采样30/30仍不算任务完成：

- 保留 stage=final_decoding。
- 不把 completedSteps/totalSteps 转为整任务100%。
- 不使用计时器伪造阶段。

# 九、点云处理

preview_ready payload：

{
"sourceStep": 24,
"pointCount": 5000,
"positions": [[0.1, 0.2, 0.3]],
"positionsPath": "/api/v1/tasks/<UUID>/previews/1",
"decodeSeconds": 6.2
}

首版从 WS 接收完整点云 JSON，不额外下载重复数据。

校验：

- pointCount 与 positions.length 一致。
- 每项恰好3个有限数值。
- 点数不超过5000。
- sourceStep、previewVersion 合法。
- 不对每个预览独立居中缩放。
- 不把 latent 当 XYZ，不改变坐标系。

重连快照只有latestPreview元数据时：

GET /api/v1/tasks/{inferenceTaskId}/previews/{version}

返回 taskId、previewVersion、sourceStep、pointCount、positions。

校验和保存逻辑必须共用。

将点云保存到 Java 受控存储，数据库保存版本和引用，不把大坐标数组反复写进任务主表。文件完整保存后才标记该版本可访问。

区分：

- 已接收的 Python previewVersion。
- Java 已成功落盘的预览版本。

下载或保存失败可重试，不能因为已更新 sequence 而永远漏掉该预览。不要修改 Python 给出的版本号。

本轮增加一个受现有JWT保护的 Java 预览读取接口，例如：

GET /api/generation/tasks/{businessTaskId}/previews/{version}

仅允许任务所有者读取，返回包含业务taskId的点云 JSON。不存在或越权统一404，不暴露服务令牌、Python路径和 inferenceTaskId。

不搭建浏览器WS服务端，预留 GenerationEventPublisher 等内部接口，默认可以只日志/内部监听；禁止日志输出完整5000点数组。

普通 preview_failed、fatal=false：

- 保存预览失败信息。
- 不增加预览版本。
- 不使整任务失败。
- 等待后续采样或终态。

# 十、最终模型与任务状态

Python task_completed 只表示 Python 最终文件已保存。

Java 必须继续：

1. GET /api/v1/tasks/{inferenceTaskId}/result。
2. 流式下载并限制大小。
3. 使用已有 GlbValidator 检查。
4. 写入现有 FileStorage。
5. 事务保存文件、唯一作品记录和业务 SUCCEEDED。
6. 提交成功后才发布业务完成事件。

任务存在但结果未就绪409：

- 有界协调查询，不立即伪造结果。
- 路径404、网络错误与结果损坏分别处理。
- 结果不在本机共享目录，禁止直接读 Python 文件路径。

复用 GenerationResultPublisher，调整为真实任务可用：

- simulated=false。
- 真实作品不使用“模拟作品”名称。
- 重复完成事件不重复创建作品。
- 任务终态不可回退。
- 下载与校验不放数据库长事务。
- 保留文件清理与提交结果不确定的保护逻辑。

接到 Python completed 后，Java结果发布失败时应记录可重试协调状态，不能再次生成模型；重启后能补发布。

Java尚未保存模型时，现有业务状态继续保持PROCESSING，必要增加业务阶段表示结果保存中，不向前端提前返回SUCCEEDED。

# 十一、重连和进程重启

- WS断开不代表任务失败。
- 记录连接恢复中，任务继续执行。
- 指数退避并带抖动，最大间隔可配置。
- 重连前查询快照，连接后仍处理第一条 task_snapshot。
- 不通过WS重连重新提交模型任务。
- 终态完成业务协调后释放连接、定时器和缓存。

Java重启：

- 查询数据库中的非终态真实任务。
- 使用原 inferenceTaskId 查询Python快照并恢复订阅。
- completed 补下载发布。
- failed 同步失败。
- 404按提交记录判断是否从未接收；不能无条件重提已确认执行过的任务。
- 提交结果未知时利用Python幂等协议有界协调。

Python重启：

- 原 queued/running 会变为failed，code=SERVICE_RESTARTED。
- Java同步错误，但保留已经成功保存的中间预览。
- 已完成文件仍可补下载。

达到业务截止时间但 Python状态仍不确定时，明确记录“等待超时/状态待核对”的语义，不宣称推理已取消；不自动重复创建任务。

# 十二、能力和现有前端接口兼容

保持现有上传、创建任务、详情、历史、作品、模型下载接口。

任务VO可以增量增加：

- stage。
- completedSteps、totalSteps。
- latestPreview。
- connectionState，作为通信状态，不代替生成状态。

previewVersion仅表示Python成功生成的版本；提供给前端的latestPreview必须已经在Java可读取。

能力接口返回当前真实模式、simulated=false、实际模型和可用参数。检查 /readyz，不把 /healthz 存活当作模型已加载。

不要通过就绪检查触发模型生成。

本轮不改前端；新增字段和预览HTTP接口写入文档，为下一步前端WS转发准备。

# 十三、测试与交付

使用轻量 HTTP/WS 假服务测试：

- 202提交、令牌、multipart字段和参数。
- 同UUID重试、409冲突、429退避。
- WS分片消息和消息大小限制。
- 第一条快照、重复序号、跳号重连。
- 预览5000点校验、错误坐标拒绝。
- 第20步断线后恢复。
- 预览后重连通过HTTP恢复。
- 慢处理不造成无限队列。
- 普通预览失败不结束任务。
- 30/30仍等待final_decoding。
- completed重复处理只生成一个作品。
- Python重启SERVICE_RESTARTED。
- Java重启后恢复订阅或补发布。
- 前端预览接口归属隔离。

真实联调显式执行：
HTTP提交 → 收到快照 → 真实采样步数 → 第24步点云 → Python完成 → Java保存真实GLB。

没有运行Python不得宣称真实联调成功；仅假服务通过应明确标注。

新增 docs/python-realtime-integration.md，包含：

- 配置与启动。
- 业务ID与推理UUID映射。
- HTTP/WS协议。
- 参数映射。
- 状态与sequence处理。
- 重连和重启协调。
- 点云预览HTTP接口。
- 错误码。
- 已验证和未验证内容。

最后汇报修改文件、依赖、数据库迁移、执行命令、测试结果、真实联调结果及Python端尚需补齐的协议差异。

不要修改Python来掩盖Java协议问题；如果发现Python实际响应与约定不一致，明确列出差异。
