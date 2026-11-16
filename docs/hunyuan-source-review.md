# Hunyuan3D 适配边界检查

2026-11-16 实际读取腾讯官方 Hunyuan3D-2 main 提交 `f8db63096c8282cb27354314d896feba5ba6ff8a`。通过 git ls-remote 记录提交，并下载该提交以下源码到项目外临时目录检查，没有复制模型或源码进仓库：

- [api_server.py](https://github.com/Tencent-Hunyuan/Hunyuan3D-2/blob/f8db63096c8282cb27354314d896feba5ba6ff8a/api_server.py)：示例默认 mini 形状模型；seed 转 torch generator；形状参数包括 num_inference_steps / guidance_scale / octree_resolution。API 在管线前执行 rembg；texture 分支加载独立 PaintPipeline，face_count 用于贴图分支的 FaceReducer，不是几何提取精度。
- [gradio_app.py](https://github.com/Tencent-Hunyuan/Hunyuan3D-2/blob/f8db63096c8282cb27354314d896feba5ba6ff8a/gradio_app.py)：_gen_shape 的 steps、seed、guidance、octree、num_chunks，随机种子开关、可选去背景；展示单图/多图模型模式差异。Slider 不是部署安全资源上限。
- [hy3dgen/shapegen/pipelines.py](https://github.com/Tencent-Hunyuan/Hunyuan3D-2/blob/f8db63096c8282cb27354314d896feba5ba6ff8a/hy3dgen/shapegen/pipelines.py)：检查形状 __call__ 的 generator、num_inference_steps、guidance_scale、octree_resolution、num_chunks，确认这些属于形状阶段；不同 pipeline 默认值并不完全一致。
- [hy3dgen/rembg.py](https://github.com/Tencent-Hunyuan/Hunyuan3D-2/blob/f8db63096c8282cb27354314d896feba5ba6ff8a/hy3dgen/rembg.py)：单独 BackgroundRemover，rembg session 是预处理，不是形状管线的同名生成选项。
- [hy3dgen/texgen/pipelines.py](https://github.com/Tencent-Hunyuan/Hunyuan3D-2/blob/f8db63096c8282cb27354314d896feba5ba6ff8a/hy3dgen/texgen/pipelines.py)：独立贴图管线，不将 texture=true 当作无需额外模型就能生效的形状开关。

## 本轮采取的边界

Java 的 ModelInferenceClient 是内部契约，不宣称官方 HTTP 存在同名 DTO。后续向自部署 Python 服务提供受控图片流/内部接口与完整已解析参数；不传跨机器绝对路径、不访问用户任意 URL。当前没有实现未经核对的真实 HTTP 客户端。

STANDARD/HIGH、seed 范围、上传 10MiB/1600 万像素、steps 1～20、guidance 0～10、octree 128/256 和组合预算都是本项目 mock 产品策略。num_chunks 留在服务端，GLB 固定导出，不开放 face_count、多图或云 API。removeBackground 需要未来预处理层实现，texture 需要未来独立模型和能力声明。

MOCK 的能力与参数快照显式模拟，结果是你提供的 GLB 的副本，不是由上传图片生成。没有下载、部署或实测模型；没有关于 M5 16GB 的性能、参数可用性或质量承诺。下一阶段应先验证选定 mini 模型，再选择实际 profile、封装 Python HTTP 服务并实现真实客户端。
