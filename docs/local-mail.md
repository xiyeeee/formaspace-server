# 本地真实邮件

local profile 自动读取 `~/.config/formaspace/mail.properties`。该文件在仓库外，仅当前用户可读写，保存本机 SMTP 凭证；不提交到 Git。终端脚本和 IDEA 使用 local profile 都会加载，无需重复把授权码填进运行配置。

本机经用户授权复用 focusgarden 已有 QQ SMTP 发件配置，独立保存到上述文件，没有修改 focusgarden 配置。

配置项为 `EMAIL_CODE_ENABLED=true`、`SMTP_HOST`、`SMTP_PORT`、`SMTP_USERNAME`、`SMTP_PASSWORD`、`SMTP_FROM`、`SMTP_AUTH`、`SMTP_STARTTLS`、`SMTP_SSL`。QQ SMTP 本机配置使用 465/SSL，关闭 STARTTLS；SMTP_PASSWORD 是 SMTP 授权码。

修改配置后重启 Java，在注册页获取验证码并查看真实邮箱。IDE 或 shell 显式设置的环境变量优先于文件；如仍提示邮件未开启，检查是否残留 `EMAIL_CODE_ENABLED=false`。

此文件只由 local 配置导入；正常仅启用 prod 的部署仍通过部署环境注入邮件配置。未配置此文件时保留原有默认行为，邮件关闭。

## 验证记录

2026-11-17，QQ SMTP 的 TLS 连接与账号认证成功。使用独立临时端口启动完整 Java 应用，POST 注册验证码接口向发件邮箱自身发送一封真实测试邮件，接口返回 HTTP 200 / OK，数据库对应记录 send_status=SENT。表示 SMTP 接受投递；未自动验证邮箱收件箱或垃圾箱。验证码和授权码未写入日志、文档或 Git。临时验证服务随后停止，现有 IDEA 服务需重启才能加载配置。
