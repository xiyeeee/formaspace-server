# Spring Boot → 浏览器实时生成 API

核对日期：2026-11-17。本文对应当前 Java 实现及 Flyway V6；取代此前“浏览器 WS 未实现”的文档。只支持单 Java 实例，不依赖 Redis、消息队列、STOMP 或 SockJS。

Java→Python 仍使用独立服务令牌；浏览器只使用用户 JWT 领取一次性 ticket。所有 taskId 都是 Java 业务 ID 字符串，浏览器不会获得 inferenceTaskId、Python 地址、服务令牌或本机路径。

## 1. 接入顺序

1. 使用现有 JWT 上传图片、创建业务任务，取得业务 taskId。
2. `POST /api/ws/tickets` 领取一次性票据。
3. 连接同一 Java 服务的 `/ws/generation?ticket=...`。
4. 发送 subscribe；该订阅的第一条任务消息为 task_snapshot。
5. 根据业务事件更新真实采样步数、阶段和点云。断线后重新领票据、重新订阅，用快照和 HTTP 恢复。
6. 收到 task_completed 后，用 assetId 调用现有作品/model/download 接口。

`PYTHON_REALTIME` 提供持续事件。旧 HUNYUAN_LOCAL、MOCK 链路不因新增浏览器 WS 自动换协议，仍可读取任务快照/HTTP；原工作器没有产生实时采样事件时，不伪造步骤推送。

## 2. 获取连接票据

`POST /api/ws/tickets`，使用现有 `Authorization: Bearer <用户JWT>`，不需要请求体。

成功 HTTP 201，响应头包含 `Cache-Control: no-store`：

```json
{
  "code": "OK",
  "message": "success",
  "data": {
    "ticket": "<一次性随机票据>",
    "expiresIn": 30,
    "webSocketPath": "/ws/generation"
  }
}
```

- ticket 是 32 个安全随机字节生成的 URL-safe Base64（无填充，43 字符），至少 256 位熵。
- 30 秒内仅可原子消费一次。服务端只保存 SHA-256 摘要、用户 ID、票据到期时间和原 JWT 到期时间。
- 消费失败、重复使用、票据过期或原 JWT 已过期均拒绝；JWT 的校验时钟宽限不会延长 WS 连接有效期。
- 30 秒仅是握手期限，不是建连 30 秒后断开。连接受原 JWT 到期时间限制。
- 默认全局最多 10000 张未消费票据，每用户最多 8 张；领取/消费时清理过期记录。达到容量返回 HTTP 429 / RATE_LIMITED。
- HTTP 未认证/账号无效返回 401；仍复用原 JWT 校验、ACTIVE 用户检查和统一错误响应。

不要把长期 JWT 放在 URL，也不要在日志中打印 ticket 或完整 WS URL。

## 3. 浏览器连接与 Origin

连接 Java（不是 Python）：

```text
ws://<Java或开发代理地址>/ws/generation?ticket=<一次性票据>
wss://<生产域名>/ws/generation?ticket=<一次性票据>
```

浏览器原生 WebSocket 无需也不能任意添加 Authorization 请求头。只允许一个 ticket 查询参数；不通过 query/form 接收 JWT，不接受客户端 userId 作为身份。

握手流程先检查 Origin，再消费票据，确认账号存在且 ACTIVE，最后建立服务端 Principal。Origin 拒绝不会消费票据。缺失 Origin 也拒绝：非浏览器验收工具需显式发送允许的 Origin。

- local 且非 prod：沿用 HTTP CORS 策略，允许 HTTP/HTTPS 的 localhost、127.0.0.1 任意端口。
- 其他环境：只允许 `CORS_ALLOWED_ORIGINS` 的明确来源。prod 默认不放行跨来源 WS。
- Origin 必须是合法来源，不允许携带用户信息、路径、查询或 fragment。不是任意域名通配。
- 票据无效或账号无效：握手 HTTP 401；Origin 不允许：403；账号查询临时失败：503。
- GET 握手路径在安全链中单独放行到票据拦截器，不代表匿名可用；其他 API 继续要求 JWT。

Java 在进入框架日志/WS 会话 URI 构造之前移除 query 及参数映射，票据仅暂存在握手请求属性中，消费时移除。网关自身也必须避免记录查询串，见代理配置。

## 4. 客户端消息

每条消息是独立 JSON 文本，protocolVersion 必须为整数 1，taskId 必须为正整数的**字符串**。

订阅：

```json
{"protocolVersion":1,"type":"subscribe","taskId":"456"}
```

取消订阅：

```json
{"protocolVersion":1,"type":"unsubscribe","taskId":"456"}
```

心跳：

```json
{"protocolVersion":1,"type":"ping","requestId":"client-random-id"}
```

每连接只有一个任务订阅。再次 subscribe 会原子切换并清理旧任务待发队列；失败的订阅不替换原订阅。每次订阅重新确认账号有效、任务属于当前 Principal。不存在和越权统一 TASK_NOT_FOUND，不泄露归属。

unsubscribe 仅在 taskId 匹配当前订阅时清理该订阅。取消订阅、关闭浏览器连接都不会取消生成，也不会修改任务状态。

ping 的 requestId 必须为非空字符串，最多 128 字符；pong 原样回显。建议每 20～30 秒发送一次应用心跳。服务端 90 秒没有收到客户端消息会正常关闭，即使期间仍有业务推送。

## 5. 统一服务端业务事件

```json
{
  "protocolVersion": 1,
  "taskId": "456",
  "sequence": 12,
  "type": "sampling_step",
  "stage": "sampling",
  "completedSteps": 10,
  "totalSteps": 30,
  "previewVersion": 0,
  "timestamp": "2026-11-17T08:00:00Z",
  "payload": {}
}
```

sequence 是 Java 单任务持久化业务序号，不直接复用 Python sequence。业务帧递增；心跳、错误和 connection_status 没有 sequence。快照使用对应的最新序号，不因读取/发送快照本身递增。初始尚无业务事件时可以是 0。

V6 为任务保存最新序号、快照和来源去重键。接收层忽略 Python 重复增量；浏览器适配层再次去重。必要状态/序号提交成功后才排入发送队列。状态提交后广播丢失时，重新订阅从持久化状态协调快照恢复；不提供完整历史重放。

| type | stage | payload |
| --- | --- | --- |
| task_queued | queued | 空对象 |
| task_started | preprocessing | 空对象 |
| sampling_step | sampling | 空对象 |
| preview_started | preview_decoding | 空对象 |
| preview_ready | sampling | 完整点云与 Java 恢复地址，见后文 |
| preview_failed | sampling | code、message、fatal |
| finalizing | final_decoding | 空对象 |
| result_saving | result_saving | 空对象 |
| task_completed | completed | status=SUCCEEDED、assetId |
| task_failed | failed | status=FAILED、code、message |

completedSteps/totalSteps 仅表示形状采样。30/30 仍可处于 final_decoding、result_saving，不能显示成整项任务已完成。previewVersion 只引用 Java 已成功保存的版本，初始为 0。

Python completed 后先发送 result_saving，此时业务仍 PROCESSING。仅最终 GLB 已保存、文件/作品/任务事务成功提交后，才能推送 task_completed。失败消息只使用 Java 已清洗的业务错误，不透传 Python 原始堆栈。

## 6. 首次快照与竞态

每次成功 subscribe 的第一条该任务消息都是 task_snapshot，使用同一 envelope：

```json
{
  "protocolVersion": 1,
  "taskId": "456",
  "sequence": 12,
  "type": "task_snapshot",
  "stage": "sampling",
  "completedSteps": 10,
  "totalSteps": 30,
  "previewVersion": 0,
  "timestamp": "2026-11-17T08:00:00Z",
  "payload": {
    "status": "PROCESSING",
    "simulated": false,
    "connectionState": "CONNECTED",
    "latestPreview": null,
    "assetId": null,
    "error": null
  }
}
```

- status：PENDING、SUBMITTING、PROCESSING、SUCCEEDED、FAILED。
- latestPreview：null 或 version/sourceStep/pointCount/positionsPath，只指向 Java 可读版本。
- error：null 或 code/message；与原 HTTP 平铺 errorCode/errorMessage 的结构不同。
- 原 HTTP stage=saving_result 在浏览器协议中适配为 result_saving，HTTP 老接口不改名。
- 浏览器 connectionState 固定为 CONNECTED、RECONNECTING、DISCONNECTED。
- 内部 CONNECTING/RECONCILING/RECONNECTING 映射为 RECONNECTING；CLOSED/SERVICE_CHANGED/null 等映射为 DISCONNECTED。CONNECTED 表示 Java 已接受上游连接状态，不是浏览器自身连接状态。

读取快照、建立订阅及事件序号提交使用同一任务锁协调；新订阅队列先快照，再发送比快照序号更大的事件。切换订阅时旧帧带订阅代次检查，不会在新快照之后继续发送旧队列。已经在网络中发送的旧任务消息无法撤回，客户端仍应按当前 taskId 过滤。

终态任务仍可订阅，立即得到 SUCCEEDED/FAILED 快照。状态协调不会让终态快照回退为运行中。可选预览若在任务结束后才补存，仍可能收到 preview_ready；它只更新点云，不应把已知业务终态改回 PROCESSING。

## 7. 点云推送与 HTTP 恢复

preview_ready.payload：

```json
{
  "sourceStep": 24,
  "pointCount": 2,
  "positions": [[0.1,0.2,0.3],[-0.1,0.4,0.2]],
  "positionsPath": "/api/generation/tasks/456/previews/1",
  "decodeSeconds": 6.2
}
```

此处使用 2 点完整结构示例；实际默认推送 5000 点，以 pointCount 为准。每点恰好 3 个有限数值，positions.length 与 pointCount 一致，最多 5000。Java 保留 Python 坐标，不逐预览归一化，也不使用 latent 或最终模型伪造预览。

点云完整保存并提交预览引用后才向浏览器发送 preview_ready。若 WS 接收时有有效 decodeSeconds，则保存该耗时；HTTP 恢复未提供耗时时该字段为 null，不编造数值。

恢复接口：`GET /api/generation/tasks/{taskId}/previews/{version}`，必须携带用户 JWT；成功是 ApiResponse 包装：

```json
{
  "code":"OK",
  "message":"success",
  "data":{
    "taskId":"456",
    "previewVersion":1,
    "sourceStep":24,
    "pointCount":2,
    "positions":[[0.1,0.2,0.3],[-0.1,0.4,0.2]]
  }
}
```

无效 JWT 为 401；不存在、无权限、版本不存在统一 404 / NOT_FOUND；存储暂不可读为 503 / STORAGE_UNAVAILABLE。不向客户端暴露存储键或 Python URL。

恢复接口保持原结构，没有新增 decodeSeconds。前端按 taskId + version 缓存点云；baseURL 已含 /api 时避免把 positionsPath 拼成 /api/api/...。

preview_failed.payload：

```json
{"code":"PREVIEW_DECODE_FAILED","message":"本次中间预览生成失败","fatal":false}
```

普通预览失败不终止任务、不增加版本。若 Python 报告致命预览问题，可透传 fatal=true，但业务终态仍以后续 task_failed 或权威快照为准。已保存的预览在任务失败后仍可通过 HTTP 读取。

## 8. 完成与控制帧

完成 payload：

```json
{"status":"SUCCEEDED","assetId":"789"}
```

失败 payload：

```json
{"status":"FAILED","code":"INFERENCE_FAILED","message":"模型生成失败，请检查图片或稍后重试"}
```

收到完成后，用现有 JWT 调用 `/api/assets/{assetId}`、`/model` 和 `/download`；模型/下载为 GLB 二进制，不走 JSON 解包。

上游断线只是通信状态变化，不直接推送 task_failed：

```json
{"protocolVersion":1,"type":"connection_status","taskId":"456","payload":{"connectionState":"RECONNECTING"}}
```

恢复后可收到 CONNECTED。控制帧没有 sequence。业务截止时间等既有规则仍可能使任务真正失败，届时有明确的 task_failed 错误语义。

pong：

```json
{"protocolVersion":1,"type":"pong","requestId":"client-random-id"}
```

错误：

```json
{"protocolVersion":1,"type":"error","code":"TASK_NOT_FOUND","message":"任务不存在或不可访问"}
```

错误的 taskId 为可选字段。支持 INVALID_MESSAGE、UNSUPPORTED_PROTOCOL、TASK_NOT_FOUND、SUBSCRIPTION_LIMIT、RATE_LIMITED。错误与 pong 不进入业务序号计数。

## 9. 容量、关闭码和重连

当前默认限制写在 Java 实现中，不存在未实现的 WS_* 环境变量：

| 项目 | 当前限制 |
| --- | --- |
| 全局连接数 / 每用户连接数 | 64 / 4 |
| 全局同时订阅数 / 每连接订阅数 | 32 / 1 |
| 每连接发送队列 | 8 条，有界；独立单发送器，无并发 sendMessage |
| 浏览器入站控制消息 | 8192 字节容器限制，并检查消息长度 |
| 每连接消息频率 | 每秒最多 20 条；连续超频/恶意消息关闭 |
| 非法 JSON/格式 | 累积 3 次关闭 |
| 客户端空闲时间 | 90 秒；建议每 20～30 秒发送 ping |
| 账号复查 | 每条客户端消息及约每 10 秒维护检查 |
| 原 JWT 到期 | 按原到期时间关闭，发送前也检查 |

慢连接仅占自己的发送器，队列溢出关闭该连接，不阻塞上游推理或其他连接。Tomcat 单次阻塞发送超时设置为 5 秒。连接关闭、服务停止会清理订阅、发送器、认证到期任务和维护线程；不取消 Python 任务。

| 关闭码 | 当前语义 | 前端建议 |
| --- | --- | --- |
| 1000 | 正常关闭、空闲清理、应用关闭 | 主动退出/终态后不自动重连 |
| 1008 | 非文本消息、反复非法或超频请求 | 修正协议，避免盲目重连 |
| 1009 | 入站消息过大 | 缩小控制消息 |
| 1013 | 连接/订阅资源过载、慢消费者、传输或认证查询暂时故障 | 退避后重新取票据 |
| 4001 | 原 JWT 到期、账号删除/禁用等认证失效 | 先恢复有效登录，不复用旧票据 |

浏览器恢复：

1. 区分主动退出和意外断线；已知终态无需因断线重新生成。
2. 意外断线做有上限的指数退避并加抖动，例如 1～30 秒；这是客户端策略建议。
3. 使用有效 JWT 重新 POST ticket；旧 ticket 即使尚未过 30 秒也不能复用。
4. 重连、发送同一个业务 taskId 的 subscribe。
5. 用首快照重建状态；如有 latestPreview，通过 JWT HTTP 恢复完整坐标。
6. 增量按 sequence 去重；发现跳号/状态不确定时重新订阅取快照，不假定服务端历史重放。

浏览器重连不重新创建任务。Python 连接恢复仍由 Java 自动协调，浏览器不能直连 8001/7860，也不参与服务令牌管理。

## 10. 前端和反向代理

本轮未修改前端。开发 Vite 除现有 /api 外，还需增加 /ws 的 Upgrade 代理，保留路径：

```ts
'/ws': { target: 'http://127.0.0.1:8080', ws: true, changeOrigin: true }
```

浏览器可基于当前页面 origin 创建 URL，使用 data.webSocketPath，按 HTTPS→WSS / HTTP→WS 切换协议，并通过 searchParams.set('ticket', data.ticket) 加入一次性票据。不要把 URL 输出到控制台。

Nginx 示例（需要按部署 upstream 修改）：

```nginx
location /ws/ {
    proxy_pass http://java_backend;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
    proxy_set_header Origin $http_origin;
    proxy_read_timeout 120s;
    access_log off;
}
```

生产使用 WSS，设置明确 CORS_ALLOWED_ORIGINS。网关日志若必须开启，应使用不含查询串的 $uri，避免默认 $request/$request_uri 把 ticket 写入日志。代理消息大小应覆盖 5000 点完整 JSON，建议至少 2 MiB。

## 11. 持久化、单实例和与原约定的差异

新增 Flyway V6：

- fs_generation_browser_state：业务最新 sequence 和安全快照，不保存点云数组。
- fs_generation_browser_dedup：来源去重键，防止 Python 重复事件生成重复业务通知。
- fs_generation_preview.decode_seconds：可空的真实预览解码耗时。

V1～V5 不修改。业务库重启更新后的 Java 后自动迁移。单实例内通过固定数量任务锁协调事件/订阅；连接和一次性票据仅在该 Java 进程中，重启后重新领票，不支持跨实例负载分发，不能无状态扩容。

与此前约定的适配/差异：

| 项目 | 当前实现 |
| --- | --- |
| ticket、WS 路径、订阅消息、业务 envelope | 按约定实现；不再是待实现草案 |
| Java 独立业务 sequence | 已持久化，不复用 Python 序号；只恢复最新快照，不做历史重放 |
| HTTP saving_result | 保持原 HTTP 字段；浏览器适配为约定的 result_saving |
| 内部七种 connectionState | 浏览器适配为约定三种；HTTP 老接口仍保留诊断值 |
| decodeSeconds | 正常 WS 路径保存并返回真实耗时；HTTP 恢复没有耗时时为 null，不能保证所有恢复帧都有数字 |
| 致命 preview_failed | 可为 fatal=true；普通失败仍 false，不自行提前判业务失败 |
| 预览晚于业务终态补存 | 允许随后推 preview_ready；前端保持已知终态，仅补显示预览 |
| 日志和代理 | Java 已移除握手查询串；外部代理日志仍需按本文配置 |
| 旧模式 | 不凭空生成采样事件；新实时推送使用 PYTHON_REALTIME 的真实上游事件 |

## 12. 源码和验证

主要文件：generation/browser/WsTicketService、WsTicketController、WsQuerySanitizer、WsHandshake、BrowserStateMapper、BrowserEventStore、GenerationWebSocketHandler，及 config/BrowserWebSocketConfig。复用并扩展原 GenerationEventPublisher、PythonRealtimeWorker、GenerationPreviewService；不修改 Python、前端。

默认 Maven 回归 179 项，156 项通过、23 项按环境条件跳过，无失败；显式启用的原生 WS/MySQL 集成测试 3 项全部通过。

协议测试先使用受控业务事件与独立 MySQL，覆盖票据原子消费/过期/容量、真实原生 WS 握手、Origin、任务归属、首快照与并发订阅竞态、独立序号去重、5000 点发送及 HTTP 恢复、30/30 不提前完成、通信状态控制帧、JWT 到期关闭、慢消费者隔离。

真实联调使用独立 formaspace_browser_live_test、已运行的 mini/MPS Python 服务、真实 004.png、seed=1234、30 步、第 24 步预览、最终分辨率 256：已观察 sampling_step、preview_started、preview_ready、finalizing、result_saving、task_completed；5000 点经浏览器协议完整接收，断线后用新票据和首快照恢复，HTTP 点云完全一致。最终作品 GLB 为 7,158,596 字节，完成帧 assetId 与已提交数据库记录一致，模型鉴权读取成功，终态可再次订阅。执行日志在 /tmp/formaspace-browser-ws-live.log。

这里的“浏览器协议验收”使用 JDK 原生 WebSocket 客户端并发送真实 Origin，HTTP/WS、MySQL、Python 推理和文件存储均为真实调用。没有运行 React 浏览器 UI、Vite/Nginx 代理、多 Java 实例或远程 TLS 部署，不宣称这些已验收。

复现命令：

```sh
./mvnw -s scripts/maven-local-settings.xml -o -Dtest=WsTicketTests,BrowserWebSocketQueueTests test
```

显式设置 BROWSER_WS_TEST_DB_URL/USERNAME/PASSWORD 后运行 BrowserWebSocketMysqlTests；库名必须以 _test 结尾。真实模型验收另需 BROWSER_LIVE_TEST_DB_URL/USERNAME/PASSWORD、INFERENCE_API_TOKEN、BROWSER_LIVE_IMAGE，然后运行 BrowserWebSocketLiveTests。测试仅使用独立测试库，默认未设置条件时集成测试跳过，不会自动启动或停止 Python 服务。

## 13. 每批 50 点的打印预览（2026-11-18）

原生 WS 订阅可增加布尔字段 `pointBatches: true`。未设置的客户端保持原来的完整点云事件。

启用后，`preview_ready` 仍是有 sequence 的业务事件，payload 不带 positions，增加 `streamed: true`、`batchSize: 50`、`bounds: {minimum: [x,y,z], maximum: [x,y,z]}`，保留 sourceStep、pointCount、positionsPath、decodeSeconds。包围盒覆盖完整点云，前端应据此固定展示坐标变换。

随后按顺序发送 `point_batch` 控制帧：

```json
{
  "protocolVersion": 1,
  "type": "point_batch",
  "taskId": "123",
  "baseSequence": 12,
  "previewVersion": 1,
  "payload": {
    "sourceStep": 24,
    "totalPointCount": 5000,
    "offset": 0,
    "pointCount": 50,
    "positions": [[0.1, 0.2, 0.3]]
  }
}
```

示例 positions 省略其余 49 点；实际数组长度始终等于 pointCount。最后一批允许少于 50 点。控制帧没有 sequence，不推进业务序号；baseSequence 指向声明该预览的 preview_ready。offset 从 0 开始，稳定点标识可用 offset + 批内索引。

Java 在持久化前按 Y 高度分层、Z 分行、X 蛇形排序，仅改变点顺序，保留坐标和重复点。HTTP 完整恢复与 WS 批次使用同一顺序。客户端按版本、baseSequence、sourceStep 校验，匹配重复批次可忽略，出现缺口、冲突或超时则通过 HTTP 恢复完整点云。重连继续使用快照 + HTTP，不重放旧批次。

每个预览只入队一个发送任务，在其中顺序发送声明和所有批次，避免 100 个批次占满 8 槽发送队列。每帧发送前检查订阅与凭据有效性；慢客户端仍按既有策略关闭并恢复。发送过程不 sleep、不接触 GPU；前端自行控制打印动画速度。

Python 保持完整预览接口，在真实解码完成前不会发送伪造粒子。前端已实现打印头、平台、逐点显示、减少动效和 HTTP 降级，详见前端 docs/point-printing.md。

本次验证：默认 Maven 183 项（159 通过、24 条件跳过），独立 MySQL/原生 WS 4 项通过。额外真实 mini/MPS 联调通过：5000 点拆为 100 批，每批 50，完整旧协议、HTTP 文件和新批次坐标一致，最终 GLB 7,158,596 字节，重连通过。真实模型日志：/tmp/formaspace-point-batches-real-live.log。另有 Chromium 受控 HTTP/WS 界面测试验证打印头、逐点累积、重复/缺批/超时恢复与最终模型切换；不将其表述为真实模型的端到端浏览器验收。


## 多阶段预览更新（2026-11-18）

Java 新任务默认持久化三阶段计划，30 步对应 12/18/24，分辨率 64/96/128，点数 1000/2500/5000。`REALTIME_PREVIEW_STAGE_COUNT` 默认 3，仅支持 3 或 6；设为 6 并重启 Java 可使用保留的六阶段 12/15/18/21/24/27（64/64/96/96/128/128、1000/1500/2500/3500/4500/5000）。三阶段按 0.4/0.6/0.8、六阶段按 0.4/0.5/0.6/0.7/0.8/0.9 比例取整，合并重复步数，同一步保留较高预算。原有 pointCount、previewResolution 上限仍生效。`REALTIME_MULTI_STAGE_PREVIEW=false` 可让新任务回到原单阶段配置，已创建任务不受当前配置变更影响。无需新增迁移：原 python_parameters JSON 和按 task/version 主键的预览表支持此计划及多版存储。

浏览器消息字段和路径不变，每次成功 preview_ready 的 previewVersion 增加。前端应接受不断增加的版本，使用该帧 sourceStep、pointCount，不固定第 24 步或 5000 点。普通失败不消耗版本，保留上一版，所以第一版可能来自后续阶段。快照仅描述最新成功版本；Java 会补齐断线期间错过的各版，所有成功版均可通过原鉴权 HTTP 接口读取。已存三阶段和六阶段任务继续按原计划校验；旧任务缺省 previewStages 时仍按持久化单阶段参数校验。Python 最多接收六个有序阶段，拒绝七阶段以上请求。

此前真实 6/15/24 候选中第 6 步没有零等值面，因此默认后移为已验证的 12/18/24。mini/MPS 真实 30 步验收三版全部成功（1000/2500/5000 点），完整帧与每包 50 点数据一致，断线恢复及每版 HTTP 比对通过，最终 GLB 7,158,596 字节，总推理约 70.73 秒；预览增加同步解码开销，不缩短总耗时。详细真实性、失败处理、MPS 预算及实际投影视图见 inference 项目的 docs/multistage-preview.md。Java 最新回归 191 项，167 通过、24 条件跳过，无失败。


六阶段实测补充：mini/MPS 在 12/15/18/21/24/27 步分别产出 1000/1500/2500/3500/4500/5000 点，解码耗时约 0.90/0.85/2.67/2.71/6.59/6.67 秒；六版完整帧、50 点分包、HTTP 恢复逐点一致，首版后重连与完成后六版读取通过，最终 GLB 7,158,596 字节。总耗时约 88.89 秒，无 OOM，所有阶段 RNG 指纹不变；未测峰值显存。此结果来自独立 formaspace_six_preview_live_test、真实模型与 JDK 原生 WS 客户端，非完整 React 网络联调。相比此前三阶段测试，总耗时增加，不宣传预览提速。Python 35 项受控测试通过。详见 inference docs/multistage-preview.md，真实日志 /tmp/formaspace-six-live.log。
