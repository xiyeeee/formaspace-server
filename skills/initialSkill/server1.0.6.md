请直接修改 formaspace-server，打通 Spring Boot → React 的 WebSocket 实时进度与点云推送。

本轮只修改 Java，不修改 Python、前端。先检查 AGENTS.md、现有实现和实际接口文档，复用已完成的 Python HTTP/WS 对接、登录JWT、任务、预览存储和模型发布。

Java代码使用显式类型声明，不使用var。不为统一风格批量修改无关代码。

## 1. 目标

React通过HTTP创建任务，通过原生WebSocket订阅业务任务。

Java接收Python事件后，持久化必要状态，再向拥有该任务的用户推送：

- 真实采样步数。
- 阶段变化。
- 完整中间点云。
- 预览失败。
- 业务完成或失败。

不把Python内部地址、服务令牌、inferenceTaskId和本机路径暴露给浏览器。

本轮不使用STOMP、SockJS，不重新搭建鉴权。先支持单Java实例，不引入Redis或消息队列；文档明确单实例限制。

## 2. 统一浏览器协议

以下为双方共同契约，必须保持一致。若已有实现冲突，增加适配并说明，不能静默更换字段。

### 2.1 获取连接票据

POST /api/ws/tickets

使用现有Authorization: Bearer JWT，无请求体。

成功201：

{
"code":"OK",
"message":"success",
"data":{
"ticket":"<随机一次性票据>",
"expiresIn":30,
"webSocketPath":"/ws/generation"
}
}

票据：

- 使用安全随机数，至少256位熵。
- 有效期30秒，仅用于一次WS握手。
- 服务端只保存摘要、用户ID、到期时间和原JWT到期时间。
- 票据消费必须原子，重复使用拒绝。
- 有容量上限和过期清理，不无限增长。
- 不在日志记录票据或完整查询字符串。
- 响应Cache-Control: no-store。

浏览器连接：

/ws/generation?ticket=<一次性票据>

禁止把长期JWT放URL。浏览器原生WebSocket不能任意设置Authorization请求头，不能要求前端这样做。

### 2.2 握手

- 精确校验允许的Origin，沿用可配置的前端来源。
- 只对WS握手路径使用票据认证；其他API仍使用JWT。
- 从票据建立Principal，不信任浏览器传来的userId。
- 账号不存在、禁用、票据无效或过期拒绝握手。
- 连接有效期不超过原JWT有效期，到期关闭4001。
- 票据过期仅限制握手，不是建连30秒就关闭。
- 定期检查账号有效性，订阅时再次校验。
- 本地Vite代理和部署反向代理需要支持WebSocket Upgrade。
- 未授权请求不得因为放行握手路径而变成匿名可用。

## 3. 客户端消息

订阅：

{
"protocolVersion":1,
"type":"subscribe",
"taskId":"456"
}

取消订阅：

{
"protocolVersion":1,
"type":"unsubscribe",
"taskId":"456"
}

心跳：

{
"protocolVersion":1,
"type":"ping",
"requestId":"<随机字符串>"
}

每个连接第一版只允许订阅一个任务。切换订阅时原子移除旧任务。

每次subscribe都校验当前用户是任务所有者。不存在或越权统一返回TASK_NOT_FOUND，不泄露归属。

unsubscribe只取消通知，不能取消Python推理任务。

## 4. 服务端业务事件

统一结构：

{
"protocolVersion":1,
"taskId":"456",
"sequence":12,
"type":"sampling_step",
"stage":"sampling",
"completedSteps":10,
"totalSteps":30,
"previewVersion":0,
"timestamp":"<UTC ISO-8601>",
"payload":{}
}

taskId永远为Java业务任务ID，字符串。

事件：

- task_queued / queued。
- task_started / preprocessing。
- sampling_step / sampling。
- preview_started / preview_decoding。
- preview_ready / sampling。
- preview_failed / sampling。
- finalizing / final_decoding。
- result_saving / result_saving。
- task_completed / completed。
- task_failed / failed。

sequence是Java面向浏览器的单任务事件序号：

- 持久化、严格递增。
- 不直接复用Python序号，两边事件数量不同。
- 心跳、错误、快照不递增。
- Python重复事件不能产生重复业务事件。
- 任务级串行处理，数据库提交成功后再通知订阅者。
- 接受Python事件与浏览器事件顺序有明确约束。
- 首版不要求保存全部历史重放，但至少持久化最新序号、快照和预览引用。
- 状态已提交而广播丢失时，客户端可通过重连快照恢复。

completedSteps表示形状采样进度，不是整项任务百分比。30/30后仍可final_decoding、result_saving。

previewVersion从0开始，只对应已成功保存、可读取的真实预览版本。

## 5. 订阅快照与竞态

subscribe成功后的第一条该任务消息必须为task_snapshot：

{
"protocolVersion":1,
"taskId":"456",
"sequence":12,
"type":"task_snapshot",
"stage":"sampling",
"completedSteps":10,
"totalSteps":30,
"previewVersion":0,
"timestamp":"<UTC ISO-8601>",
"payload":{
"status":"PROCESSING",
"simulated":false,
"connectionState":"CONNECTED",
"latestPreview":null,
"assetId":null,
"error":null
}
}

status沿用Java：PENDING、SUBMITTING、PROCESSING、SUCCEEDED、FAILED。

connectionState表示Java与Python的连接状态：
CONNECTED、RECONNECTING、DISCONNECTED。

latestPreview有值时：

{
"version":1,
"sourceStep":24,
"pointCount":5000,
"positionsPath":"/api/generation/tasks/456/previews/1"
}

error有值时包含code、message。

- 快照sequence等于对应最后业务事件序号。
- 建立订阅与读取快照必须协调，避免中间事件丢失。
- 保证先快照，再发送比快照sequence大的事件。
- 终态任务仍可订阅，立即返回终态快照。
- 不要求完整历史重放。
- 任何快照都不能倒退业务终态。

## 6. 点云事件和恢复

Java确认预览有效、保存成功后推送preview_ready：

payload：

{
"sourceStep":24,
"pointCount":5000,
"positions":[[0.1,0.2,0.3]],
"positionsPath":"/api/generation/tasks/456/previews/1",
"decodeSeconds":6.2
}

实际positions长度必须与pointCount一致，最多5000点，每个点3个有限数值，不包含NaN/Infinity。

保持Python坐标，不逐预览归一化。不输出绝对路径。

恢复接口：

GET /api/generation/tasks/{taskId}/previews/{version}

使用JWT和归属校验，成功返回ApiResponse：

{
"code":"OK",
"message":"success",
"data":{
"taskId":"456",
"previewVersion":1,
"sourceStep":24,
"pointCount":5000,
"positions":[[0.1,0.2,0.3]]
}
}

以上positions仅示意，真实返回必须满足点数校验。不存在、越权统一404。

若已有接口返回裸JSON，增加兼容适配并将最终契约统一为此包装；不能让前端猜测。

preview_failed payload：
{
"code":"PREVIEW_DECODE_FAILED",
"message":"本次中间预览生成失败",
"fatal":false
}

普通预览失败不终止任务、不增加预览版本。

## 7. 完成语义

Python完成后，Java先发送result_saving，仍保持业务PROCESSING。

只有最终GLB已保存、作品及任务数据库事务成功提交后，才能发送task_completed：

payload：
{
"status":"SUCCEEDED",
"assetId":"789"
}

前端通过现有作品详情、model和download接口获取结果。

失败task_failed payload：
{
"status":"FAILED",
"code":"INFERENCE_FAILED",
"message":"可对外展示的简短说明"
}

不返回Python原始异常或堆栈。

Java与Python断开不发送task_failed。新增非序号控制消息：

{
"protocolVersion":1,
"type":"connection_status",
"taskId":"456",
"payload":{"connectionState":"RECONNECTING"}
}

恢复后发送CONNECTED。实际生成状态和连接状态分开。

## 8. 控制消息

pong：
{
"protocolVersion":1,
"type":"pong",
"requestId":"与ping相同"
}

错误：
{
"protocolVersion":1,
"type":"error",
"taskId":"可选",
"code":"TASK_NOT_FOUND",
"message":"任务不存在或不可访问"
}

支持：
INVALID_MESSAGE、UNSUPPORTED_PROTOCOL、TASK_NOT_FOUND、SUBSCRIPTION_LIMIT、RATE_LIMITED。

错误和心跳没有sequence，不进入任务业务事件计数。

关闭码：

- 1000：正常关闭。
- 1008：策略或恶意请求。
- 1009：消息过大。
- 1013：慢消费者或临时过载。
- 4001：认证到期、账号失效。

非法JSON和超频要限制，不将解析错误传播到其他连接。

## 9. 连接与发送管理

- 连接注册、任务订阅线程安全。
- 每连接有界发送队列，单发送器保证顺序，不并发sendMessage。
- WS回调不阻塞Python事件消费。
- 消息大小上限适配5000点；浏览器入站控制消息设置更小上限。
- 慢消费者队列溢出关闭1013，让其重连快照，不无限积压。
- 服务端心跳/空闲清理与客户端ping相容。
- 关闭清理连接和订阅，不取消生成。
- 应用关闭释放执行器、定时器及WS资源。
- 日志仅记录用户/业务任务/事件类型和序号，禁止完整点云、票据、JWT。

可新增：
WebSocketConfig、WsTicketService、HandshakeInterceptor、
GenerationWebSocketHandler、GenerationSubscriptionRegistry、
GenerationEventPublisher。

根据现有结构合理调整，不机械堆空类。

## 10. 文档与测试

更新docs/frontend-realtime-api.md，必须完整包含上述协议、示例、鉴权、Origin、关闭码和代理说明。

测试：

- 无效/过期/重复ticket握手拒绝。
- Origin拒绝。
- A不能订阅B任务。
- 首快照和订阅竞态。
- 业务序号递增、Python事件去重。
- 5000点完整传输。
- 重连后HTTP恢复点云。
- 30/30不提前完成。
- 保存作品后才completed。
- Python断线只更新通信状态。
- 慢连接不阻塞推理和其他连接。
- 原JWT到期关闭4001。
- 退出连接不取消任务。

先用假事件做协议测试，再用真实模型联调。未运行浏览器或Python时明确未验证，不宣称全链路成功。

最后输出文件、迁移、配置、测试结果和供前端使用的最终对接文档。若协议确需调整，列出差异供双方同步，不能单方面改字段。
