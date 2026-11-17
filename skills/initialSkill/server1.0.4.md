请直接修改当前 formaspace-server，接入本地运行的腾讯 Hunyuan3D 模型，完成真实图片生成模型流程。

不要只输出计划或示例。先检查实际代码、AGENTS.md 和本地模型服务，再实现。

## 一、目标和范围

实现：

前端上传图片 → Spring Boot 创建任务 → 本地混元真实推理 → 保存 GLB → 更新作品与任务 → 前端预览下载。

保留现有 DISABLED、MOCK，并新增真实模式 HUNYUAN_LOCAL。

本轮不做实时点云和 WebSocket，不伪造百分比。生成期间 progress=null，前端继续轮询自己的任务接口。

不重写登录、JWT、上传、分页和已有前端接口。不使用腾讯云商业 API。

## 二、先检查本地混元的实际入口

用户之前启动的是：

gradio_app.py，模型 tencent/Hunyuan3D-2mini，device=mps，disable_tex，端口 7860。

这只是历史信息，必须检查当前运行实例及本地源码，不能假设与历史一致。

优先查找相邻目录 formaspace-inference，读取实际：

- gradio_app.py。
- api_server.py。
- 模型加载及参数处理代码。
- 本地修改记录。
- 当前进程、端口及 Gradio 版本。

先确认：

1. 服务是否启动，模型是否已加载，还是仍在下载。
2. 能否完成一次单图生成。
3. 哪个接口直接返回可获取的模型文件。
4. 是否存在多步 Gradio 事件链：生成、转换、导出。
5. API 名称、参数顺序、文件上传结构、结果类型和错误协议。

通过正在运行应用的 Use via API、接口元数据和源码共同确认。必要时用一次独立客户端请求验证。

不得把 gradio_app.py 当成 api_server.py；7860 上没有 /send，就不能硬编码调用 /send。

本轮优先复用已运行 Gradio 的 HTTP API，不新增 Python 中转服务。若现有 API 无法返回模型、只返回 HTML 展示页面：

- 优先检查是否有导出接口可衔接。
- 如确实需要改 Python，只做增加明确 api_name 和文件输出等最小修改。
- 不擅自更换模型、重装环境或下载另一套权重。

如果服务未运行或权重未下载完，可完成代码和测试，但明确真实推理联调未完成。

## 三、当前 Java 项目的关键改造

以本地最新代码为准，目前参考仓库存在：

- generation/inference/ModelInferenceClient。
- MockModelInferenceClient。
- generation/worker/MockGenerationWorker。
- GenerationTaskService。
- GenerationTaskMapper。
- GenerationResultPublisher。
- GenerationParameterResolver。
- GenerationCapabilitiesService。
- GenerationProperties。
- config/GenerationConfig、MockSchedulingConfig。

其中：

- GenerationMode 只有 DISABLED、MOCK。
- create() 固定 simulated=true。
- Mapper 多条状态 SQL 固定 simulated=TRUE。
- Publisher 拒绝真实任务，作品固定“模拟作品”，simulated=true。
- capabilities 固定模拟说明，只有 MOCK 可生成。
- 调度启用条件只有 MOCK。

这些需要成套调整，不能只新增一个客户端。

## 四、真实客户端

新增 HunyuanLocalInferenceClient，实现现有推理抽象。

可调整 ModelInferenceClient，使 submit 返回远程任务引用，而不是 void：

- 定义 SubmissionResult。
- 查询应使用持久化的远程引用，不靠内存 Map 查 internalTaskId。
- QueryResult 明确区分执行中、成功、失败。
- 兼容调整 Mock 客户端及测试。

如果 Gradio 一次生成需要多次调用，持久化事件 ID、当前调用阶段和必要的文件引用，能在 Java 重启后继续追踪。

Java HTTP 客户端优先使用与当前 Spring Boot 匹配的 RestClient；Gradio 返回 SSE 时正确读取完整事件，不能把它当普通 JSON 或对事件连接使用过短超时。

后台处理 SSE 不等于本轮新增前端实时推送。不要为了 SSE 强行迁移整个工程到 WebFlux。

文件上传：

- 通过现有 FileService 获取当前任务的源图片。
- 上传真实文件内容，不把 Mac 路径当作远端可读地址。
- 按 Gradio 实际上传协议构造文件参数。
- 使用稳定的已解析参数，不重新随机 seed。

结果：

- 只接受模型文件结果，不把 HTML、图片、日志文件当 GLB。
- 下载结果时限制大小、超时和重定向。
- 只访问配置的模型服务及明确允许的文件来源，防止 SSRF。
- 不允许前端决定模型服务地址或任意下载 URL。
- 错误信息转换为用户可读业务错误，内部细节仅在日志记录。
- 清理临时文件和连接。

不要使用未核对的 Gradio 版本路径和事件格式。

## 五、参数映射

复用 ResolvedGenerationParameters：

- seed → 实际模型 seed。
- inferenceSteps → 实际生成步数。
- guidanceScale → 模型引导强度。
- octreeResolution → 几何提取分辨率。
- numChunks → 服务端解码配置。
- removeBackground → 实际去背景参数。
- generateTexture → 第一版只允许 false。
- format → GLB。

quality 是 Java 产品预设，不直接作为腾讯模型参数。

特别注意：

- Gradio 的随机种子选项必须关闭，使用 Java 已生成和持久化的 seed。
- 实际模型如果不支持某字段，明确拒绝或在能力中禁用。
- 如果选择的 API 总是去背景，就不能宣称支持关闭去背景。
- 如果只有生成接口没有单独格式输出，核对导出链路后再处理。
- 不静默忽略高级设置。
- 不把 mock 预设宣称为真实模型最佳配置。

本地 MPS 初期采用低资源 profile；质量档位和参数范围按实际模型验证结果开放。只验证了一个档位时，可以先只启用 STANDARD。

## 六、调度与数据库

创建任务接口仍快速返回 PENDING，不等待生成结束。

新增真实任务调度或将现有 Worker 抽取为明确的共享逻辑：

- MOCK 和真实模式按配置选择正确客户端。
- 第一版真实全局并发固定或默认 1。
- 禁止同一模型 pipeline 被多个任务同时访问。
- 不在数据库事务和行锁中等待 HTTP、SSE 或模型生成。

数据库持久化：

- providerTaskId 或等价远程引用。
- 需要时增加 Gradio 阶段/事件引用。
- 提交开始时间、查询重试信息等实际必需字段。

只新增 Flyway 迁移，不修改已执行文件。

处理提交结果不确定：

- 模型服务可能已接收，但 Java 尚未拿到事件 ID。
- 不盲目重提并宣称恰好一次。
- 没有远端幂等支持时，超时进入明确的待确认/失败处理，说明人工核对方式。
- 不让任务永久卡在 SUBMITTING。

查询临时网络失败使用有界退避，不能一次连接错误就立即认定模型失败，也不能无限等待。

区分：

- 单次 HTTP 连接超时。
- Gradio 排队/执行等待。
- 整项任务截止时间。

使用条件更新保护状态，终态不可被迟到消息覆盖。Java 重启后能恢复已持久化远程引用的任务；Python 重启导致引用丢失时有明确失败处理。

Mapper 不要直接删除所有 simulated 条件而导致跨模式处理。任务查询、认领、状态更新需同时约束预期状态与对应执行模式。

## 七、真实结果发布

复用 GenerationResultPublisher 的文件和数据库一致性处理。

修改：

- 允许真实 PROCESSING 任务发布结果。
- 作品 simulated 从任务继承，真实任务必须 false。
- 真实作品名称不要带“模拟”。
- 使用已有 GlbValidator 检查真实 GLB，不能只检查文件后缀。
- 验证、下载等重工作不放数据库长事务内。
- 使用已有唯一约束保证同一任务不会重复创建作品。
- 保存文件、作品并成功提交后，任务才标记 SUCCEEDED。
- 数据库失败清理未引用文件，保留现有提交结果不确定时的保护逻辑。
- 临时 Gradio 文件及时落到自有存储，不作为长期作品地址。

保持现有 /api/assets/{id}、/model、/download 的归属校验。

## 八、配置与能力

新增 HunyuanLocalProperties 或等价配置：

- base-url，开发示例 http://127.0.0.1:7860。
- 实际 API 名称与必要阶段配置。
- 连接、读、任务超时。
- 重试间隔和次数。
- 并发默认 1。
- 实际 model-id、profile-id。
- 如需要服务凭证，使用环境变量，禁止硬编码。

base-url 可配置，未来换云 GPU 不需要修改业务代码。

GenerationCapabilitiesService：

- HUNYUAN_LOCAL 下 simulated=false。
- 返回真实 modelId/profileId、支持参数和可用性。
- 不再显示“仅模拟”的说明。
- readiness 检查要轻量，不通过生成一次来探活。
- 服务不可用时上传/历史仍可用，新任务明确返回 503。
- 不把 HTTP 页面可访问等同于权重已就绪。
- 保留 DISABLED/MOCK 行为和生产环境禁止 MOCK 的规则。

## 九、测试与文档

保留已有测试，补充：

1. 提交、事件完成/失败、文件下载的协议测试。
2. 超时、断线、服务重启、无效结果、超大结果。
3. 真实任务 simulated=false，mock 仍为 true。
4. 调度模式隔离、幂等重放、并发限制。
5. 结果发布只生成一份作品，状态不回退。
6. 不支持参数明确失败。

使用本地 stub HTTP/SSE 服务测试 Java 协议，模型集成测试显式开关，默认测试不能下载权重或启动推理。

真实联调至少：

- 先在本地混元页面生成同一输入确认服务正常。
- 通过 FormaSpace 上传一张图并创建任务。
- 最终得到真实 GLB 和 assetId。
- 通过现有接口下载、预览。
- 确认未使用 mock 文件且所有参数实际传递。

更新 README、.env.example、docs/generation-api.md。
另生成 docs/hunyuan-local-integration.md：

- 实际调用的 Gradio API 与参数顺序。
- 本地混元启动前提。
- Java 配置和启动命令。
- 图片上传、事件等待、结果保存流程。
- 如何诊断 Java 未连接、模型未加载、推理失败。
- 与未来 GPU 服务器对接的方法。
- 哪些能力已实测、哪些未验证。

完成后输出文件清单、依赖变化、迁移、测试结果和真实联调结果。没有成功调用本地模型时不得写“已完成真实生成验证”。
