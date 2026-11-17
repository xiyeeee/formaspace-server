# 本地 Hunyuan Gradio 对接

## 当前结论

2026-11-17 已完成 Java 的 HUNYUAN_LOCAL 模式、Gradio HTTP/SSE 客户端、持久化远程引用与结果、真实任务调度和 GLB 发布。真实任务与作品 simulated=false；DISABLED/MOCK 保留。没有引入腾讯云 API、Python 中转服务、WebFlux 或另一套权重。

**尚未完成真实生成验证。** 检查时本机有 mini/MPS 进程，但 7860 未监听；进程持有 Hugging Face 的权重下载锁和 .incomplete 文件。本机 MySQL 3306 同样不可连接，真实 Flyway V4 与账号→上传→生成→assetId→下载链路未执行。不能将本地 stub 协议测试或 mock GLB 当作真实生成。

## 实际检查记录

- 相邻仓库 `../formaspace-inference`，HEAD `f8db63096c8282cb27354314d896feba5ba6ff8a`；检查前后源码工作区干净，没有修改 Python、切换模型、安装环境或触发另一套权重下载。
- 进程参数：`python gradio_app.py --model_path tencent/Hunyuan3D-2mini --subfolder hunyuan3d-dit-v2-mini --device mps --disable_tex --host 127.0.0.1 --port 7860`。
- 本地 .venv：Gradio **6.32.0**，gradio_client **2.7.2**。
- 实际读取 gradio_app.py 的 _gen_shape / shape_generation / 事件绑定 / export；api_server.py；形状、背景预处理代码，以及本地安装的 gradio/routes.py、blocks.py、gradio_client/client.py。
- 因运行进程尚未监听，无法读取该进程的实时 Use via API。另在不执行 `__main__`、不加载权重、不启动端口的检查脚本中构建同一源码的 Blocks，调用 `get_api_info()` 与 config，核对下面的函数名、参数顺序和 File 输出。这个离线元数据检查不代表服务已就绪。
- shape_generation 已直接返回 white_mesh.glb 的 FileData（可能包在 gr.update/value 内），无需调用 on_export_click。后续 HTML 是展示页，Java 不将其作为模型。

## 已核对的协议

只支持目前本地安装并核对的 6.32.0 协议；升级 Gradio 应重新核对，签名/版本不符时 readiness=false，不猜测旧版路径。

1. `GET /config`：版本、api_prefix、app_id、组件与事件信息。
2. `GET /gradio_api/info`：named_endpoints 中 `/shape_generation` 的参数和返回组件。
3. `POST /gradio_api/upload`：multipart 字段 **files**，发送实际图片内容；响应为模型服务保存的路径数组。绝不是将 Java/Mac 本地绝对路径发给远端。
4. `POST /gradio_api/call/shape_generation`：`{"data":[...]}`，取得 `{"event_id":"..."}`。
5. `GET /gradio_api/call/shape_generation/{event_id}`：SSE，读取到完整 `event: complete` 或 `event: error`；heartbeat/generating 不作为最终结果。
6. 只解析最终数组第 0 项 FileData（兼容 `{"__type__":"update","value":{...}}`），忽略 HTML/stats；仅接受 .glb 远程文件标识。
7. 从配置服务的 `/gradio_api/file=<编码后的远程文件路径>` 下载。忽略 FileData 中任意 url，不跟随重定向，不访问前端指定 URL，也不允许 file=http://...；远程路径仅作为模型服务的文件标识，Java 从不把它当本地路径打开。

当前入口不是 api_server.py，**不调用 /send**。所有模型请求只去配置的 HTTP(S) base-url；可配服务凭证 HUNYUAN_AUTHORIZATION，但不转发用户 JWT。没有实现 Gradio Cookie 登录；需要鉴权时使用支持该请求头的受控反向代理。

### data 的 13 个有序元素

| 索引 | Gradio 参数 | Java 值 |
| --- | --- | --- |
| 0 | caption | null，不调用文生图 |
| 1 | image | 上传返回 path，meta._type=gradio.FileData |
| 2～5 | mv_image_front/back/left/right | null，单图 |
| 6 | steps | 已持久化 inferenceSteps |
| 7 | guidance_scale | 已持久化 guidanceScale |
| 8 | seed | 已持久化 seed |
| 9 | octree_resolution | 已持久化 octreeResolution |
| 10 | check_box_rembg | 已持久化 removeBackground |
| 11 | num_chunks | 已持久化服务端 numChunks |
| 12 | randomize_seed | **false**，禁止 Gradio 再随机 |

本地 seed Slider 上限是 **10000000**，所以真实 profile 使用 0～10000000，不能沿用 mock 的 2147483647。图片组件声明 `image_mode=RGBA`，Gradio 预处理后 _gen_shape 的去背景开关可生效；readiness 检查这个组件模式，避免入口改成强制去背景后仍宣称支持关闭。

texture 第一版固定 false，format 固定 GLB。quality 只由 Java 选择 preset，不传为模型参数；未知字段不透传。

## 模型与 profile

默认真实 profile 为 `hunyuan-mini-mps-candidate-v1`，只开放 STANDARD：5 步、guidance=5、octree=128、numChunks=1000。高级参数可显式传这些值，但超出该 profile 的 steps/guidance/octree 会拒绝，不钳制。removeBackground 支持 true/false，seed 可指定或由 Java 首次创建时生成。

这些只是低资源**候选配置**，本轮未在 M5 16GB 完成模型实测，不是质量或最佳性能保证。通过环境变量修改候选 preset 时，应在本地确认效果并更新 profile-id。HIGH 当前不开放，mock 的 HIGH 不会自动继承到真实模式。

readiness 是轻量元数据检查，不发推理请求：要求本机核对的 Gradio 版本、API 前缀、13 个参数顺序、File 输出、RGBA 输入，以及页面模型标识与配置 model-id/subfolder 一致。已检查的本地源码在加载 BackgroundRemover 和 i23d_worker 之后才启动 Gradio；因此匹配这套元数据比“HTTP 页面可访问”更严格，但不替代一次真实图片生成测试。若未来 Python 改为懒加载，应提供明确模型 readiness，再更新 Java 校验。

## Java 配置与启动

先确保 MySQL 正常，沿用 README 的数据库、JWT_SECRET、AUTH_CODE_HMAC_KEY；Flyway 只新增 V4，不修改 V1～V3。

模型进程已经存在时先等待它完成下载/加载，**不要重复启动第二个 pipeline**。确认 terminal 出现监听地址且 `/config` 与 `/gradio_api/info` 可用后，先在本地页面用同一张图生成一次，核对结果可下载为 GLB。

如需要手动启动已有环境，命令与当前已检查进程一致：

```sh
cd ../formaspace-inference
.venv/bin/python gradio_app.py --model_path tencent/Hunyuan3D-2mini \
  --subfolder hunyuan3d-dit-v2-mini --device mps --disable_tex --host 127.0.0.1 --port 7860
```

Java：

```sh
export SPRING_PROFILES_ACTIVE=local
export INFERENCE_MODE=HUNYUAN_LOCAL
export HUNYUAN_BASE_URL=http://127.0.0.1:7860
# 其余 DB/JWT/HMAC 环境变量沿用 README；Spring Boot 不自动读取 .env。
./mvnw spring-boot:run
```

| 变量 | 默认 |
| --- | --- |
| HUNYUAN_BASE_URL | http://127.0.0.1:7860 |
| HUNYUAN_API_NAME | shape_generation |
| HUNYUAN_CONNECT_TIMEOUT | 5s，单次连接 |
| HUNYUAN_READ_TIMEOUT | 60s，读取无数据的超时；SSE heartbeat 可维持长连接 |
| HUNYUAN_TASK_TIMEOUT | 30m，整个任务截止时间 |
| HUNYUAN_RETRY_INTERVAL / MAXIMUM_RETRIES | 5s / 3，查询或下载临时失败按 5/10/20 秒有界退避 |
| HUNYUAN_MODEL_ID / SUBFOLDER | tencent/Hunyuan3D-2mini / hunyuan3d-dit-v2-mini |
| HUNYUAN_PROFILE_ID | hunyuan-mini-mps-candidate-v1 |
| HUNYUAN_INFERENCE_STEPS / GUIDANCE_SCALE / OCTREE_RESOLUTION | 5 / 5 / 128，仅开放该 STANDARD preset |
| HUNYUAN_NUM_CHUNKS | 1000，仅服务端 |
| HUNYUAN_AUTHORIZATION | 可选模型服务/反向代理凭证，环境注入 |

不在服务不可用时阻止 Java 启动或影响上传/历史读取；capabilities 的 generationAvailable=false，新任务 503 INFERENCE_UNAVAILABLE，已有幂等 key 仍可重放。切换模式前先处理完旧模式未结束任务；两种工作器严格隔离，不接管另一模式任务。

## 任务持久化与恢复

- 创建接口仍立即返回 PENDING，simulated=false，modelId/profileId 来自真实配置。默认全局并发固定 1；单 Java 应用实例，Gradio 同一 shape 函数也按队列默认并发 1 运行。不要同时用其他 API/进程访问同一 pipeline，隐藏的贴图接口不属于本轮对接。
- 认领后短 SQL 写 SUBMITTING、started_at、deadline_at。图片上传和远程 POST 都在数据库事务/行锁之外。
- 收到事件 ID 后，写 provider_task_id、remote_reference、GENERATING，再进入 PROCESSING。引用内绑定 base-url/api/app_id/deadline，防止重启或配置改动后误查询别的服务。
- 串行工作器在后台消费 SSE，前端仍按现有接口轮询，progress=null，不提供 WebSocket/点云或假百分比。
- 完成事件的文件引用先写 remote_result_reference / RESULT_READY，再下载；Java 重启后优先复用它，不重复生成，也不重新随机参数。
- 下载有体积、读超时、总任务截止时间约束，禁止重定向。验证完整 GLB 容器/JSON/自包含引用后，在一个事务里保存用户 MODEL 文件、作品与成功状态。真实作品不带“模拟”，simulated=false，原下载/归属接口不变。
- 两种模式的状态 SQL 都约束 execution_mode、simulated 和预期状态，终态不回退。迁移中的其他历史 provider 标记为 LEGACY，不让真实工作器接管。同一任务作品仍受唯一索引保护；DB 回滚清理和提交不确定的文件保护逻辑保留。
- 重启后 SUBMITTING 且没有持久化事件 ID 的任务按 SUBMISSION_UNCERTAIN 失败，不盲目 POST 第二次。若远端已接受但写库失败，日志记录 taskId/eventId，供管理员核对；不会宣称 exactly-once。
- Python 重启导致 app_id 改变、引用 404，返回 INFERENCE_REFERENCE_LOST。Gradio 队列不是持久化消息系统；SSE 已被消费但 Java 尚未保存结果就崩溃时，事件可能不可重放，需核对 Python 的 gradio_cache 和日志后决定重新生成，不能保证无损恢复所有崩溃窗口。
- 断线等临时错误有界重试，耗尽 INFERENCE_UNAVAILABLE；模型 error 事件为 INFERENCE_FAILED，无效文件/协议为 INFERENCE_PROTOCOL_ERROR，整项过期 INFERENCE_TIMEOUT。硬截止检查可能受一次底层读超时延迟，成功状态 SQL 仍要求 deadline 未过。

Gradio 自身临时上传/输出文件由模型服务缓存策略管理，Java 不通过未核对的删除接口或跨机器文件路径清理它们；Java 下载与校验临时文件都会关闭和清理，结果及时复制进自有存储，不将 Gradio 地址用作长期作品 URL。

## 验证与诊断

本轮只新增小型 loopback HTTP/SSE stub 协议检查，默认测试不下载权重、不启动真实推理。保留原有回归测试。检查和文件清单见 [hunyuan-local-checks.md](hunyuan-local-checks.md)。

真实手工验收（需用户显式启动模型/Java 后进行）：

1. 页面生成同一输入，证明模型确实能运行，而非仍下载。
2. `GET /api/generation/capabilities` 必须 mode=HUNYUAN_LOCAL、simulated=false、generationAvailable=true、真实 modelId。
3. 上传图片，POST tasks，复用原 Idempotency-Key 规则；等待自己的任务终态。
4. 成功必须有真实 assetId，任务/作品 simulated=false；GET model/download 用 Bearer 获取并在 Three.js 以 Blob URL 预览。
5. 核对 Python stats/日志中的 seed/steps/guidance/octree/rembg/num_chunks，确认 randomize_seed=false，且未使用 MOCK_DEMO_GLB。

诊断：

- 连接拒绝：检查实际监听端口，不是只看 Python 进程；当前观察就是下载中且未监听。
- metadata 不匹配：检查 Gradio 版本、Use via API、输入 image_mode、model-id/subfolder。Java 不自动迁移版本或协议。
- 生成失败：按 Java taskId、DB 内 provider_task_id/remote_reference 和 Python terminal 日志核对，浏览器只显示公开错误码，不回显内部路径/原始异常。
- 提交不确定：先在 Python 缓存/日志找 eventId/生成文件，禁止自动重提同一个远端执行。
- 模型服务重启：旧事件未必能恢复，明确失败；不要把另一个 app_id 的结果当原任务。

未来 GPU 部署：运行同样已核对的 Gradio 协议，修改 HUNYUAN_BASE_URL（可含反向代理路径前缀）和服务凭证即可；上传实际字节、不共享 Mac 绝对路径。先验证 GPU 的模型/协议和 profile，再调整候选 STANDARD 与 profile-id，不需要改账号、任务、作品等业务接口。当前仅允许从该配置服务同源下载，CDN/对象存储来源需以后显式加入受控策略。
