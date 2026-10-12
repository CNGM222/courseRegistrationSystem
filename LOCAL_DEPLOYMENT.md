# Windows 本机部署

在项目根目录运行：

```powershell
.\start-local.ps1
```

浏览器访问 http://127.0.0.1:5173。后端监听 `127.0.0.1:8080`，前端使用 Vite preview 提供已经构建的 `frontend/dist`，并把 `/api` 转发到后端。这是仅供本机使用的运行方式。两个进程在后台运行，关闭终端后仍可访问。

停止服务：

```powershell
.\stop-local.ps1
```

修改代码后，先停止，再构建并启动：

```powershell
.\stop-local.ps1
.\start-local.ps1 -Build
```

也可以用 `-BackendPort 8081 -FrontendPort 5174` 指定端口。脚本不会自动启用开机启动。

## 数据和配置

使用现有 MySQL 数据库 `course_registration_system`，启动时校验表结构。数据会在停止、重启后保留。默认连接配置见 `backend/src/main/resources/application.properties`；需要更换连接时，在启动前设置 `DB_URL`、`DB_USERNAME`、`DB_PASSWORD`。

本机已有账号 `student.demo`、`professor.demo`、`registrar.demo`。部署时使用的演示密码为 `demo-only-2026`。已有演示学期 `DEMO-CURRENT` 处于 `CLOSED` 状态，部署保留其数据和业务状态。课程目录仍遵循默认的 24 小时有效期；旧快照过期时目录接口会返回 `CATALOG_UNAVAILABLE`。

日志、进程记录和本机配置在 `.local/`，已被 Git 忽略。首次启动会生成稳定的 SSN 加密密钥，保存到 `.local/backend.properties`；应备份该文件，已有加密数据时不要更换密钥。连接其他已有加密标识的数据库时，必须设置其原有的 `SSN_KEY`。

本机配置默认关闭财务投递。需要连接财务服务时，设置 `BILLING_DISPATCH_ENABLED=true`、`BILLING_URL` 和 `BILLING_TOKEN`。正式部署的 HTTPS、财务和目录配置参见 `backend/README.md`。

## 初次构建

要求 Java 21+、Node.js 22.12+ 和已经初始化的 MySQL。前端依赖缺失时执行：

```powershell
Set-Location frontend
npm.cmd ci
Set-Location ..
.\start-local.ps1 -Build
```

若 PowerShell 执行策略阻止脚本，可用 `powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\start-local.ps1`；停止脚本同理。
