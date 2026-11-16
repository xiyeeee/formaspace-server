# 图片、参数与生成任务 API

本轮实现受保护的上传→参数→创建任务→查询→只读结果链路。**真实腾讯模型没有接入，没有部署 Python/模型，也没有 M5 16GB 实测结论。** 仅有 DISABLED / MOCK 两种模式；不修改前端。

所有接口使用现有 JWT：`Authorization: Bearer <accessToken>`，不接收客户端 userId 决定归属。JSON 统一 ApiResponse，ID 字符串，时间 UTC ISO-8601 Z。文件流接口直接返回二进制，出错且尚未发送响应时返回统一 JSON。

## 接口清单

| 方法与路径 | 输入 | 成功 |
| --- | --- | --- |
| POST /api/files/upload | multipart，仅一个 file 源图片 | 201 FileUploadVO |
| GET /api/files/{id}/content | 自己的 SOURCE_IMAGE | 200 图片流 |
| GET /api/generation/capabilities | 无 | 200 当前能力/profile |
| POST /api/generation/tasks | CreateGenerationDTO + Idempotency-Key | 首次 201，幂等重放 200 |
| GET /api/generation/tasks/{id} | 自己的任务 | 200 GenerationTaskVO |
| GET /api/generation/tasks?page=1&size=20&status=PROCESSING | status 可省略 | 200 PageResult |
| GET /api/assets/{id} | 自己的作品 | 200 ModelAssetVO |
| GET /api/assets/{id}/model | 自己的 GLB | 200 model/gltf-binary 流 |
| GET /api/assets/{id}/download | 自己的 GLB | 200 附件流 |

没有作品改名、删除、取消任务、积分、支付、多图或第三方云 API。本轮不重写已有登录/JWT。

## 上传与读取

默认 JPEG/PNG、单文件最大 **10MiB**、最多 **1600 万像素**，均是产品限制而非模型要求。检查扩展名、声明 MIME（若提供）、实际图片格式；读取头部尺寸后才完整解码。伪装、损坏、解码警告与超限被拒绝；不支持 WebP。

原始文件名去路径与控制字符后仅用于展示，磁盘使用随机 UUID 键。存储目录默认在用户主目录 `.formaspace/storage`，不在 resources/public/仓库中，不映射为静态目录。配置目录与父目录禁止符号链接；可用 `pwd -P` 取得实际路径。读取拒绝穿越和符号链接，限制在根目录内；暂存后同文件系统原子移动，不回退到非原子写入。

```json
{"code":"OK","message":"success","data":{"id":"123","originalName":"chair.png","contentType":"image/png","sizeBytes":102400,"previewUrl":null}}
```

源图片只允许当前用户读取自己的 SOURCE_IMAGE；不存在、越权、用途不符统一 404。设置真实 Content-Type/Content-Length、`Cache-Control: private, no-store`、`X-Content-Type-Options: nosniff`，不暴露 storageKey 或磁盘路径。previewUrl=null；前端可用选图 Object URL 本地预览，服务器图片用带 Bearer 的 fetch/Axios Blob 读取，不能把 Token 拼进 URL。

## 创建任务和参数

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

`quality` 缺省 STANDARD，显式 null 非法；generateTexture 缺省/null 视为 false。advancedOptions 缺省或 null 正常，removeBackground 缺省/null 视为 true。原始 nullable 高级覆盖值保留在 requestedParameters，不提前填预设；resolvedParameters 为不可变、完整的实际参数，含最终 seed、schemaVersion、profileId、numChunks、format。

解析顺序：能力检查→读取预设→应用非空覆盖→校验组合→生成实际 seed。当前 texture=true 返回 UNSUPPORTED_CAPABILITY。mock 接受去背景等参数用于契约联调，**不实际执行去背景或推理**，capabilities 的 simulationNotice 明示这一点；所有结果均为演示 GLB 的副本，参数不会改变演示模型。removeBackgroundSupported 表示当前模拟 profile 接受这个参数，不表示模型/预处理已部署。

当前 mock profile 的产品限制：seed 为 0～2147483647；steps 为 1～20；guidance 必须有限且 0～10；octree 只允许 128/256；组合还要求 `inferenceSteps × octreeResolution³ <= maximumVoxelSteps`（默认 268435456）。不钳制、不静默忽略参数。这些是 mock 的资源策略，不是官方推荐或 UI 范围。STANDARD=(5,5,128)，HIGH=(10,5,256) 是产品示例预设。

numChunks 仅服务端配置（默认 8000），格式固定 GLB，不向前端开放 face_count、导出格式、多图或任意未知字段。请求/生效快照使用明确 record 类型 JSON，单份最多 4096 字符，不将未知字段透传给 Python。参数 profile 修改后，历史任务读取数据库快照，绝不重算。

## 幂等和容量

必需请求头 `Idempotency-Key: <标准 UUID>`，大小写归一为小写。一次逻辑提交生成一个 UUID，网络重试复用；用户主动重新生成使用新 UUID。

- 事务中先锁当前 ACTIVE 用户行，再查 userId+key 唯一任务并检查容量，保证不同 key 并发不会只 count 后 insert 绕过容量。
- 相同 key/相同规范化请求直接返回已有任务及快照，HTTP 200；配置变化、模式改为 DISABLED 也允许这种查询性重放。不会重新随机 seed 或重新解析参数。
- 相同 key/不同规范化请求为 409 IDEMPOTENCY_CONFLICT；请求指纹包含 sourceFileId、质量、贴图和规范化的高级参数。省略/null advancedOptions 等价，显式高级覆盖与 null 预设意图不同。
- 新请求默认最多一个 PENDING/SUBMITTING/PROCESSING 任务；超限 429 GENERATION_LIMIT_REACHED，Retry-After=3。全局 mock 活跃任务默认 1。
- 默认 DISABLED：新请求 503 INFERENCE_UNAVAILABLE，不插入任务。MOCK 首次插入 PENDING，progress=null，provider=HUNYUAN_LOCAL，simulated=true；providerTaskId 始终留空等待真实接入。

## 任务响应和分页

```json
{
  "code":"OK","message":"success","data":{
    "id":"456","sourceFileId":"123","status":"PENDING","progress":null,"simulated":true,
    "requestedParameters":{"sourceFileId":"123","quality":"STANDARD","generateTexture":false,"advancedOptions":{"removeBackground":true,"seed":null,"inferenceSteps":null,"guidanceScale":null,"octreeResolution":null}},
    "resolvedParameters":{"schemaVersion":1,"profileId":"mock-v1","quality":"STANDARD","generateTexture":false,"removeBackground":true,"seed":12345,"inferenceSteps":5,"guidanceScale":5.0,"octreeResolution":128,"numChunks":8000,"format":"GLB"},
    "assetId":null,"errorCode":null,"errorMessage":null,
    "createdAt":"2026-11-16T08:00:00Z","startedAt":null,"finishedAt":null
  }
}
```

示例 seed 仅示意；以响应的真实 seed 为准。详情越权统一 404；不输出 providerTaskId、内部路径或原始推理异常。分页 data 为 `{records,total,page,size}`，默认 1/20，size≤100，按 createdAt DESC、id DESC。V3 前历史记录没有参数快照时返回 null，不伪造历史参数。

状态 PENDING→SUBMITTING→PROCESSING→SUCCEEDED/FAILED；非终态也可失败。终态由条件更新保护，迟到请求不能回退。无真实进度时 progress 始终 null，包括模拟成功；不要用假百分比。前端每 2～3 秒轮询，终态停止；页面离开不会取消。

## 能力接口完整示例

下面是默认 mock profile 在 **MOCK** 下的完整响应。默认启动实际为 DISABLED：mode 改为 DISABLED，generationAvailable=false、simulated=false，但带 `simulated:true` 的质量预设仍可供预览。modelId 始终 null，没有声称真实模型加载。

```json
{
  "code": "OK",
  "message": "success",
  "data": {
    "mode": "MOCK",
    "generationAvailable": true,
    "simulated": true,
    "modelId": null,
    "profileId": "mock-v1",
    "simulationNotice": "仅模拟参数与任务链路，不执行真实推理、去背景或贴图；参数不会改变演示模型。",
    "supportedInputModes": [
      "SINGLE_IMAGE"
    ],
    "qualityPresets": {
      "STANDARD": {
        "simulated": true,
        "inferenceSteps": 5,
        "guidanceScale": 5.0,
        "octreeResolution": 128
      },
      "HIGH": {
        "simulated": true,
        "inferenceSteps": 10,
        "guidanceScale": 5.0,
        "octreeResolution": 256
      }
    },
    "textureSupported": false,
    "removeBackgroundSupported": true,
    "advancedParameters": {
      "removeBackground": {
        "supported": true,
        "defaultValue": true,
        "minimum": null,
        "maximum": null,
        "allowedValues": null
      },
      "seed": {
        "supported": true,
        "defaultValue": null,
        "minimum": 0,
        "maximum": 2147483647,
        "allowedValues": null
      },
      "inferenceSteps": {
        "supported": true,
        "defaultValue": 5,
        "minimum": 1,
        "maximum": 20,
        "allowedValues": null
      },
      "guidanceScale": {
        "supported": true,
        "defaultValue": 5.0,
        "minimum": 0.0,
        "maximum": 10.0,
        "allowedValues": null
      },
      "octreeResolution": {
        "supported": true,
        "defaultValue": 128,
        "minimum": null,
        "maximum": null,
        "allowedValues": [
          128,
          256
        ]
      }
    },
    "maximumVoxelSteps": 268435456,
    "uploadConstraints": {
      "formats": [
        "JPEG",
        "PNG"
      ],
      "maximumBytes": 10485760,
      "maximumPixels": 16000000
    }
  }
}
```

后续真实模式必须使用经真实模型验证的新 profile，不能自动沿用这些 mock 限制。

## Mock 工作器与真实结果边界

仅 local/dev/test 可启用 MOCK；prod（即使同时 local）或无开发 profile 时启用都拒绝启动。只支持单应用实例，不提供分布式调度。DISABLED 不创建工作器/调度；MOCK 默认全局活跃 1，固定延时串行 tick 从数据库认领/推进。每一步状态与时间在 MySQL，重启后恢复未结束的模拟任务，不依赖内存倒计时。

有配置的本地演示 GLB 必须可读、合法 GLB 2 容器、JSON asset.version=2.0、自包含（不引用任意外部 URI），且大小不超过 MODEL_MAX_BYTES。启动和每次读取均检查。不宣称完成 Three.js 渲染或 glTF 语义的全面验证。缺少演示文件可以启动，任务最终失败 MOCK_RESULT_NOT_CONFIGURED；配置了坏文件则启动失败，不联网下载兜底。

成功前将演示文件流式复制为用户管理的随机文件，再在一个数据库事务中保存 MODEL 文件、唯一作品和 SUCCEEDED。失败清理新副本，终态竞争丢弃副本，不返回假地址。若提交结果不明，先查文件是否已被数据库引用；数据库也无法确认时保留文件并记录 opaque key 供核对，避免删除可能已提交的模型。数据库与文件系统不是分布式事务，进程崩溃可能留下孤立临时/结果文件，需要运维核对清理；不将 mock 描述为生产分布式工作器。

## 作品与 Blob 读取

```json
{"code":"OK","message":"success","data":{"id":"789","generationTaskId":"456","name":"模拟作品 456","format":"GLB","sizeBytes":1024,"simulated":true,"modelUrl":"/api/assets/789/model","thumbnailUrl":null,"createdAt":"2026-11-16T08:00:06Z"}}
```

modelUrl 是受保护的应用路径，不是磁盘地址或公开签名链接。必须带 Authorization 获取 Blob，再交给 Three.js 或下载；Three.js 直接请求该 URL 不会自动携带 JWT。示例：

```ts
const response = await fetch(`/api/assets/${assetId}/model`, {
  headers: { Authorization: `Bearer ${accessToken}` },
});
if (!response.ok) throw new Error('模型读取失败');
const modelBlobUrl = URL.createObjectURL(await response.blob());
// 用 modelBlobUrl 加载模型；页面释放或加载完成后 URL.revokeObjectURL(modelBlobUrl)。
```

下载改请求 `/download`，Blob 后用 Object URL/a.download；响应 Content-Disposition 使用安全的 `model-<id>.glb`。源图片、模型和下载流都用 private,no-store 与 nosniff，支持流式大文件响应，不开放整个存储目录。CORS 允许 Idempotency-Key/Authorization/Content-Type，暴露 Retry-After/Content-Disposition，不启用 Cookie 凭证。

## HTTP 错误

| HTTP | code |
| --- | --- |
| 400 | INVALID_ARGUMENT（请求校验）、INVALID_IMAGE、IMAGE_PIXEL_LIMIT_EXCEEDED、INVALID_GENERATION_PARAMETERS、UNSUPPORTED_CAPABILITY |
| 401 | 现有 UNAUTHORIZED |
| 404 | NOT_FOUND（源图片、任务、作品越权/不存在统一） |
| 409 | IDEMPOTENCY_CONFLICT |
| 413 | FILE_TOO_LARGE |
| 415 | UNSUPPORTED_FILE_TYPE；非 multipart 请求也可能为框架 HTTP_415 |
| 429 | GENERATION_LIMIT_REACHED，带 Retry-After |
| 503 | INFERENCE_UNAVAILABLE、STORAGE_UNAVAILABLE、SERVICE_UNAVAILABLE |

模拟无结果文件体现在任务 status=FAILED、errorCode=MOCK_RESULT_NOT_CONFIGURED，查询本身仍为 200。不向前端返回堆栈、内部绝对路径或供应商原始错误。

## 启动和本地自有 GLB 验证

1. 按 README 配置 MySQL、JWT/HMAC 密钥和已有账号流程；V1/V2 不变，启动自动应用 V3。
2. 默认 `INFERENCE_MODE=DISABLED` 可上传、读取、查询能力和已有任务，但不能新建生成任务。
3. 如需模拟完整成功链路，使用自己有权使用的自包含 GLB，设置：

```sh
export SPRING_PROFILES_ACTIVE=local
export INFERENCE_MODE=MOCK
export FILE_STORAGE_ROOT="$HOME/.formaspace/storage"
export MOCK_DEMO_GLB='/absolute/path/to/your-own-demo.glb'
./mvnw spring-boot:run
```

不提供演示模型时省略 MOCK_DEMO_GLB，验证明确失败路径。不要把用户输入用于环境路径；不要将本地模型/上传目录提交到 Git。

```sh
curl -i -X POST http://127.0.0.1:8080/api/files/upload \
  -H 'Authorization: Bearer <accessToken>' -F 'file=@/absolute/path/to/source.png'
curl -i http://127.0.0.1:8080/api/generation/capabilities -H 'Authorization: Bearer <accessToken>'
curl -i -X POST http://127.0.0.1:8080/api/generation/tasks \
  -H 'Authorization: Bearer <accessToken>' -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: <一次逻辑提交的UUID>' \
  -d '{"sourceFileId":"<上传返回的ID>","quality":"STANDARD","generateTexture":false}'
curl -i http://127.0.0.1:8080/api/generation/tasks/<taskId> -H 'Authorization: Bearer <accessToken>'
curl -o model.glb http://127.0.0.1:8080/api/assets/<assetId>/download -H 'Authorization: Bearer <accessToken>'
```

配置清单与验证结果见 README、.env.example、[generation-checks.md](generation-checks.md)。下一阶段另行选择 mini 模型并在目标 Mac 实测参数/性能、部署 Python HTTP 服务后再接入真实客户端；mock 成功不代表那一步已完成。
