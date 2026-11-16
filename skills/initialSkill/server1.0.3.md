# FormaSpace 第三步：图片上传、生成参数与任务后端

> 交给后端项目中的 AI 直接执行。本文是开发要求，不代表接口已实现。登录/JWT 已完成，本轮继续建设 Spring Boot 后端；不重写鉴权，不要求同时部署模型，不修改前端工程。

## 1. 本轮完成什么

完成登录后的后端基础链路：上传源图片 → 获取可用参数 → 创建生成任务 → 查询任务状态和参数。

真实推理尚未接通时，通过显式 mock 模式验证业务；默认 disabled 模式禁止创建无法执行的任务。不得把模拟模型描述为由上传图片生成。

后续真实链路：React → Spring Boot → 自己部署的 Python 推理服务 → 腾讯 Hunyuan3D 开源模型。不是腾讯云商业 API，也不是在 Java 中加载模型。

本轮包括质量档位、贴图、去背景、随机种子、推理步数、引导强度、几何提取分辨率的请求结构、校验、持久化与回显。不会因为模型还未部署就删掉这些结构，也不能让尚未支持的选项静默生效。

不做：积分扣费、多图、第三方云 API、真实模型下载部署、复杂消息队列、支付、模型编辑器、作品重命名和删除。

## 2. 先检查，不重复搭建

阅读 AGENTS.md、pom.xml、现有登录实现、当前用户获取工具、ApiResponse、异常、分页、文件/任务/作品实体、Flyway 和接口文档。

沿用现有 Java、Spring Boot、MyBatis-Plus 版本、包名和风格。新增功能前确认已有代码，不重复创建同名 DTO、VO、Mapper。

检查腾讯官方源码以确认适配边界：

- https://github.com/Tencent-Hunyuan/Hunyuan3D-2/blob/main/api_server.py
- https://github.com/Tencent-Hunyuan/Hunyuan3D-2/blob/main/gradio_app.py
- https://github.com/Tencent-Hunyuan/Hunyuan3D-2/tree/main/hy3dgen

优先面向 Hunyuan3D-2 / mini，但本轮没有实测部署，不能宣称某组参数已在 M5 16GB 上通过。记录检查的 commit；不能访问源码时明确说明，不猜测官方协议。

注意：官方 Python 管线、Gradio 界面和示例 API 不完全一致。seed、num_inference_steps、guidance_scale、octree_resolution 属于已检查的形状参数；去背景是预处理；texture 涉及独立贴图模型；API 中 face_count 在贴图分支使用。质量 STANDARD/HIGH 是我们自己的产品预设。不要把 UI 控件范围当成安全资源上限。

## 3. 图片存储

实现 FileStorage 与 LocalFileStorage，仅提供实际需要的保存、读取和删除能力。默认本地目录通过配置指定，不放 src/main/resources、public 或仓库中。

POST /api/files/upload：

- JWT 必须有效，用户 ID 取认证上下文。
- multipart/form-data，字段 file，只接受一个源图片。
- 本轮支持 JPEG、PNG，默认最大 10MiB，最多 1600 万像素，均可配置。此处是产品限制，不是官方模型要求。
- 检查大小、扩展名、内容格式、尺寸和可解码性；先检查头部尺寸再进行完整解码，不能先分配超大图片。
- WebP 仅在选定可靠解码器并验证后启用，否则明确不支持，不盲目返回成功。
- 存储键随机生成；originalName 只作展示，不参与磁盘路径拼接。
- 路径规范化并限制在配置根目录，禁止目录穿越、跟随越界链接。
- 先写临时文件再原子移动，限制读取内存；数据库写入失败清理新文件，清理失败记录可排查日志。
- 创建 fs_file，purpose=SOURCE_IMAGE，返回 FileUploadVO；不泄露 storageKey/绝对路径。

响应 HTTP 201（若已有明确上传状态码约定可沿用并统一文档）：

```json
{"code":"OK","message":"success","data":{"id":"123","originalName":"chair.png","contentType":"image/png","sizeBytes":102400,"previewUrl":null}}
```

GET /api/files/{id}/content：

- JWT + 归属校验，本轮仅读取自己的源图片。
- 不存在、不属于自己、用途不符统一 404。
- 正确设置 Content-Type、Content-Length、Cache-Control: private, no-store、X-Content-Type-Options: nosniff。
- 不公开映射整个存储目录，不通过查询参数接收文件系统路径。

previewUrl 可为空：前端选图后可用 Object URL 本地预览；服务器图片使用携带 Authorization 的 fetch/Axios 获取 Blob 再预览。img 标签不自动携带 Bearer，请勿把 JWT 拼进 URL。

## 4. 参数设计

创建或扩展 CreateGenerationDTO：

```json
{
  "sourceFileId":"123",
  "quality":"STANDARD",
  "generateTexture":false,
  "advancedOptions":{
    "removeBackground":true,
    "seed":null,
    "inferenceSteps":null,
    "guidanceScale":null,
    "octreeResolution":null
  }
}
```

| 字段 | 处理 |
| --- | --- |
| sourceFileId | 必填、有效 ID、当前用户的源图片 |
| quality | STANDARD/HIGH，缺省 STANDARD；产品预设，不直接传给官方模型 |
| generateTexture | 缺省 false；部署未支持 true 时返回参数能力错误 |
| removeBackground | 缺省 true；由预处理层执行 |
| seed | 缺省/null 随机；显式值限制 0～2147483647，作为我们的一致性策略 |
| inferenceSteps | null 使用预设，非空须为正整数且在部署允许范围内 |
| guidanceScale | null 使用预设，非空为有限数值且符合部署允许范围 |
| octreeResolution | null 使用预设，非空必须命中部署允许的离散列表 |

advancedOptions 整体缺省/null 也应正常补默认值。显式高级参数覆盖质量预设；超限或不支持应拒绝，不偷偷钳制，不静默忽略。使用 @Valid 和跨字段校验。

定义 GenerationParameterResolver：能力检查 → 读取选定质量预设 → 应用非空覆盖值 → 生成实际 seed → 校验组合 → 得到 immutable ResolvedGenerationParameters。

请求结构允许 null，但解析后的参数必须完整；保留用户原始请求和最终生效参数，保证以后切换默认配置不改变历史记录。

num_chunks 先是服务端性能配置，不开放前端。目标面数/导出格式后续作为后处理选项，本轮固定 GLB，不把 face_count 当成几何精度。多图需不同模型与输入协议，本轮不实现。

## 5. 能力接口，前端据此渲染设置

GET /api/generation/capabilities，需登录。

返回：

- mode：DISABLED/MOCK。
- generationAvailable。
- simulated。
- modelId：真实未确定时 null，不假称已加载。
- supportedInputModes：本轮 SINGLE_IMAGE。
- qualityPresets：可用档位及对应参数。
- textureSupported。
- removeBackgroundSupported。
- advancedParameters：各参数 supported、默认值、上下界或 allowedValues。
- uploadConstraints：格式、最大字节、最大像素。

mock 可以提供示例质量配置用于界面联调，但必须 simulated=true。示例配置可使用 STANDARD：5 步、guidance=5、octree=128；HIGH：10 步、guidance=5、octree=256；这些仅为 mock 测试值，不是对模型质量、Mac 能力或官方最佳参数的承诺。

真实模式本轮不开放。后续上线实际模型时，必须替换成经过该模型验证的 profile，不能自动继承 mock 范围。DISABLED 下 generationAvailable=false，界面禁用生成；参数结构仍可返回供预览。

## 6. 创建任务、幂等与限流

POST /api/generation/tasks，需登录，请求为第 4 节 DTO。

要求 Idempotency-Key 请求头：前端为一次逻辑提交生成 UUID；网络重试复用同一个 key，用户主动重新生成使用新 key。

- 数据库按 userId + key 建唯一约束。
- 相同 key、相同规范化请求返回原任务；相同 key 不同请求返回 409 IDEMPOTENCY_CONFLICT。
- 随机 seed 只在首次创建时生成。重试不能重新随机，也不能因配置变化重新解析已有任务。
- 校验源图片归属、参数能力、任务额度；用户 ID 不由请求指定。
- 默认每用户最多一个未结束任务，使用事务内用户行锁等可解释方案保证并发，不只 count 后 insert。
- DISABLED 下新任务返回 503 INFERENCE_UNAVAILABLE，不插入任务；已有幂等 key 的查询性重放可返回原任务。
- MOCK 以持久化任务工作，不依赖内存计时器才能恢复。

首次创建 HTTP 201；幂等重放 HTTP 200，保持响应一致。任务初始 PENDING，progress=null，provider=HUNYUAN_LOCAL；新增 simulated 字段区分模拟任务，不把 provider 当模拟标记。

GenerationTaskVO 至少包含：id、sourceFileId、status、progress、simulated、requestedParameters、resolvedParameters、assetId、errorCode、errorMessage、createdAt、startedAt、finishedAt。不要输出 providerTaskId、内部路径和供应商原始错误。

## 7. 查询任务

- GET /api/generation/tasks/{id}。
- GET /api/generation/tasks?page=1&size=20&status=PROCESSING。

只查当前用户；详情越权统一 404；分页复用现有结构，size 最大 100。按 createdAt DESC、id DESC 稳定排序。参数回显读取数据库快照，不按当前配置重算。

状态合法转移：PENDING → SUBMITTING → PROCESSING → SUCCEEDED/FAILED；提交前失败也允许转 FAILED。终态不能被迟到更新覆盖。任务失败更新可读的公开错误信息。

前端可每 2～3 秒轮询，终态停止；页面离开不等于取消任务。没有真实百分比时 progress=null，不伪造。

## 8. 推理对接与 mock

定义内部 ModelInferenceClient，表达 submit、query、result 引用。请求包含内部任务标识、完整已解析参数和受控的图片输入；后续具体使用文件流还是内部文件接口，按 Python 部署决定。不同机器不能直接共用本地绝对路径，不让 Python 访问任意用户提供 URL。

本轮不虚构官方 HTTP 协议，不编写未核对的真实 Hunyuan 客户端。接口 DTO 为我们内部契约，不宣称官方同名支持。

模式：

1. disabled：默认，不启动推理调度，不创建新生成任务。
2. mock：仅 local/dev 或 test 可用，prod 配置 mock 必须启动失败。参数可影响请求记录，但不会改变示例模型，文档须说明。

mock 工作器定时读取数据库，按可配置延时推进状态；默认全局并发 1。使用原子状态更新或任务认领避免重复处理；重启后恢复 mock 未结束任务。仅支持单应用实例，明确说明，暂不加入分布式锁基础设施。

有配置且验证可读取的演示 GLB 时，成功前复制为该用户管理的文件，事务中保存模型文件记录、fs_model_asset 和成功状态。没有演示 GLB 则模拟失败 MOCK_RESULT_NOT_CONFIGURED，不生成假下载地址，不要求联网下载演示模型。

事务失败清理结果文件，不能出现任务成功但作品缺失。结果演示来源写入 simulated，不能作为真实混元能力测试。

为成功任务提供最小只读结果链路：

- GET /api/assets/{id}：当前用户作品详情，含 simulated，返回受控访问路径而非磁盘路径。
- GET /api/assets/{id}/model：带 JWT 读取自己的 GLB，可用 Blob URL 在 Three.js 中展示。
- GET /api/assets/{id}/download：带 JWT 获取附件，设置安全 Content-Disposition，前端 Blob 下载。

以上越权统一 404，不开放上传目录。大文件流式响应；Blob 方式第一版可用，后续再优化签名 URL。不要让 Three.js 直接访问一个不携带 JWT 的受保护 URL 后假称可用。

## 9. 数据库与代码组织

复用 fs_file、fs_generation_task、fs_model_asset。新迁移补齐：幂等 key、规范化请求摘要、requested/resolved 参数 JSON、参数 schemaVersion、模型/profile 标识、simulated 和必要索引。

JSON 使用明确类型序列化，不用 Java 默认序列化；敏感路径不进入对外参数。providerTaskId 预留但真实提交前为空。若存储 raw JSON，限制长度、禁止无限未知字段透传给 Python。

已执行迁移不修改，不删除用户数据。Controller 处理 HTTP，Service 做业务，Mapper 访问数据库，Storage 管文件，Resolver 管参数。不要一方法一个接口，也不要引入不需要的基础依赖。

## 10. 错误与配置

沿用 ApiResponse、真实 HTTP 状态：400 参数不合法、401 未登录、404 文件/任务/作品不存在、409 幂等冲突、413 文件过大、415 文件类型不支持、429 任务容量/限流、503 推理未启用或服务不可用。

建议业务码：INVALID_GENERATION_PARAMETERS、UNSUPPORTED_CAPABILITY、INFERENCE_UNAVAILABLE、IDEMPOTENCY_CONFLICT、GENERATION_LIMIT_REACHED、MOCK_RESULT_NOT_CONFIGURED。与已有错误码风格统一并记录最终值。

新增配置：存储根目录、上传限制、推理模式、单用户未结束任务上限、mock 延时/演示模型、参数 profile。配置不含用户可控制的任意脚本/磁盘路径。CORS 跨域时允许 Idempotency-Key、Authorization、Content-Type。

不重写现有 JWT，不重新引入 Session/CSRF。所有业务对象校验归属，日志不输出 Token 和内部异常给前端。

## 11. 验证

至少验证：

1. 合法图片上传、读取；伪装格式、损坏图片、像素超限、文件过大被拒绝。
2. 用户 A 无法读 B 的图片、任务、模型和下载。
3. 参数缺省、覆盖、随机 seed、非法数值、超限和未支持贴图正确处理。
4. 快照不因配置改变而变化，VO 返回实际 seed 和生效参数。
5. 并发同 key 仅一条任务；同 key 不同请求 409；重试不重复随机。
6. 不同 key 并发不突破单用户上限。
7. disabled 不残留任务；mock 标记明确，prod 禁止 mock。
8. mock 成功有真实可读 GLB 文件与作品；无文件则失败；服务重启可恢复。
9. 终态不会回退，数据库/磁盘失败不会返回假成功。
10. Maven 编译与必要测试通过。MySQL 不可用明确未验证的事务/并发项，不以 H2 结果冒充 MySQL 验证。

## 12. 交付

更新 README、配置示例和 docs/generation-api.md，给出每个接口请求、响应、状态码、Blob 访问和幂等重试方式。能力接口给出完整 JSON 示例，和实际代码一致。

最后列出修改文件、新迁移、启动步骤、如何用本地自有 GLB 验证、测试结果和未验证项；明确本轮真实腾讯模型尚未接入。

下一阶段单独进行：在 Mac M5 16GB 验证选定 mini 模型 → 确认可用参数和性能 → 封装 Python HTTP 服务 → 增加真实客户端和后台任务对接。不能将 mock 成功当作这一步已完成。
