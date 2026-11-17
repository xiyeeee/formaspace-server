# 本地 MySQL（OrbStack）

先打开 OrbStack，再在后端仓库运行 Compose。Navicat 是可选客户端，Java 可以直接连接数据库。

## 首次配置

复制 `.env.mysql.example` 为 `.env.mysql`，给 `DB_PASSWORD` 和 `MYSQL_ROOT_PASSWORD` 分别设置独立随机密码。实际 `.env.mysql` 已被 Git 忽略，不要提交。当前机器已生成本地凭证，无需重复复制覆盖。

```sh
docker compose --env-file .env.mysql up -d --wait
docker compose --env-file .env.mysql ps
```

使用官方 `mysql:8.4.10` 镜像，按[官方镜像初始化约定](https://hub.docker.com/_/mysql)创建 `formaspace` 数据库及仅获该库权限的 `formaspace_app` 用户。数据库只监听本机 `127.0.0.1:3307`，避免占用已有 MySQL 的 3306。字符集为 utf8mb4，排序规则 utf8mb4_0900_ai_ci，时区 UTC。健康检查会实际以应用账号执行 SELECT 1。

## Navicat 连接

新建 MySQL 连接，名称可填 FormaSpace 本地：

| 字段 | 值 |
| --- | --- |
| 主机 | 127.0.0.1 |
| 端口 | 3307 |
| 用户名 | formaspace_app |
| 密码 | 本地 `.env.mysql` 的 DB_PASSWORD |
| 数据库 | formaspace |

使用本机 TCP 连接即可，无需 SSH 隧道。不要用 root 作为 Java 应用账号。

## Java 配置

推荐直接运行本地启动脚本：

```sh
bash scripts/run-local.sh
```

脚本加载 `.env.mysql`，启用 local profile，首次生成两把独立密钥保存到 Git 忽略的 `.env.auth`，以后复用。外部已设置的 JWT/HMAC 密钥优先。需要真实生成时运行 `INFERENCE_MODE=HUNYUAN_LOCAL bash scripts/run-local.sh`；邮件配置见 [本地真实邮件](local-mail.md)。

脚本默认使用 `scripts/maven-local-settings.xml` 中的官方 Maven Central，绕开本机全局镜像的解析问题，不修改全局 Maven 配置。如需其他仓库，可通过 `FORMASPACE_MAVEN_SETTINGS` 指定自己的 settings 文件。

在 IDE 的运行环境变量中设置 `.env.mysql` 中的 `DB_URL`、`DB_USERNAME`、`DB_PASSWORD`，并沿用已有的 `SPRING_PROFILES_ACTIVE=local`、`JWT_SECRET` 和 `AUTH_CODE_HMAC_KEY`；Spring Boot 不自动读取该文件。不要把 `MYSQL_ROOT_PASSWORD` 配给 Java。

如果日志显示登录用户为字面量 `${DB_USERNAME}`，表示没有注入环境变量。把 IDE 环境变量设为实际值，不要填写 `${DB_USERNAME}`；DB_URL 应指向 3307，避免默认回退到已有的 3306 服务。使用脚本启动不需要在 IDE 配置这些变量。

本机已生成 IDEA 运行配置 `.run/FormaSpace.local.run.xml`，名称为 **FormaSpace 本地**，包含实际数据库变量、local profile 和已有持久化密钥。选择这份配置运行，原有 FormaspaceServerApplication 配置不会自动继承终端脚本的环境变量。该文件含本地凭证，被 Git 忽略；其他机器需单独创建。

local profile 自动允许 HTTP/HTTPS 的 `localhost` 与 `127.0.0.1` 任意端口前端来源，Vite 改端口不需要改后端。`CORS_ALLOWED_ORIGINS` 可以补充其他明确来源。prod（包括同时启用 local/prod）不启用本地端口通配，仍只允许显式配置的来源。修改后重启 Java。真实验证码邮件已接入仓库外本地配置，参见 [本地真实邮件](local-mail.md)；其他机器未配置邮件时仍返回 `503 MAIL_UNAVAILABLE`。

终端也可以仅加载本文件的数据库变量（本示例 JDBC URL 已加引号）：

```sh
set -a
source .env.mysql
set +a
# 另外配置 local profile、JWT/HMAC 等现有必填变量，再启动：
./mvnw spring-boot:run
```

容器只负责建库和应用账号；业务表由 Java 启动时通过 Flyway V1～V4 自动创建，不手工执行迁移 SQL。刚启动数据库时 Navicat 看到空库属于正常现象。

## 日常操作

```sh
# 停止，保留容器与数据
docker compose --env-file .env.mysql stop
# 再启动
docker compose --env-file .env.mysql up -d --wait
# 查看日志
docker compose --env-file .env.mysql logs --tail=80 mysql
```

数据保存在独立命名卷 `formaspace-local_mysql-data`。普通 `down` 保留数据，不要添加 `-v`，该参数会删除数据库数据。镜像初始化变量只在空数据卷首次启动时生效；已有数据时编辑密码文件不会修改数据库密码。

## 实际启动验证

2026-11-17 已使用本地数据库凭证与持久化 JWT/HMAC 密钥完整启动 Java，Flyway 在空库成功应用 V1～V4，七张表（六张业务表及迁移记录表）已创建，`/api/health` 返回 UP。验证采用 DISABLED 推理模式，不代表真实生成或 SMTP 已验证。全局 Maven 镜像出现校验问题，验证使用临时官方 Central 镜像配置及已缓存依赖完成，未修改全局 Maven 配置。
