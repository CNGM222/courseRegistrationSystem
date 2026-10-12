# 课程注册系统服务器部署教程

按你的选择，本教程默认部署到 **Ubuntu/Debian 无面板服务器**，使用 **Java 21、MySQL 9.6、Nginx、systemd**，业务数据存入 MySQL，重启后保留。安装命令适用于 Ubuntu 24.04 或 Debian 13；更早的系统须先确认能安装 Java 21。前端是 Vue/Vite 静态文件，后端是 Spring Boot 可执行 JAR。服务器只运行编译产物，不需要 Node.js 或 Maven。

浏览器访问服务器的 80/443 端口，Nginx 提供前端，并将 `/api/v1/...` 原路径转发到本机的 `127.0.0.1:8080`。这让前端与 API 使用同一域名，Cookie 会话和 CSRF 可以正常工作。前端当前构建适合域名根路径，例如 `https://courses.example.com/`。

按第 2～6 节依次执行即可完成 MySQL 版首次部署。无域名时先通过公网 IP 验证，有域名后再配置 HTTPS。

## 1. 本次编译结果

- 后端：`backend/target/backend-0.0.1-SNAPSHOT.jar`，按 Java 21 编译。
- 前端：`frontend/dist/`，包含 `index.html` 和 `assets/`。
- 后端 H2 测试共 39 项，38 项通过，MySQL 专用 NOWAIT 测试跳过。
- 编译后 JAR 使用 `demo` profile 启动，`GET /api/v1/auth/csrf` 返回 HTTP 200。
- 本次没有连接目标服务器，也没有验证服务器上的 MySQL、域名或 HTTPS。

本地 JAR 自检使用了 H2；下面服务器部署使用 MySQL 配置，需在服务器上完成数据库与登录验证。

本次上传包位于 `.local/server-release/course-registration-20261012.zip`。包内有 `backend.jar`、`frontend/`、`database/`、`deploy/`、`backend-README.md` 和本教程，不包含本机数据库备份、真实密码、密钥、`node_modules` 或源码。数据库演示脚本含公开的合成账号。不要上传 `.jar.original`，它不是可独立启动的完整包。

以后修改代码，在本机项目根目录重新构建：

```powershell
Set-Location backend
.\mvnw.cmd clean package
Set-Location ..\frontend
npm.cmd ci
npm.cmd run build
Set-Location ..
```

`npm.cmd` 可以避免 Windows PowerShell 对 `npm.ps1` 的执行策略限制。本次上传包是当前构建的快照，重新构建后要重新打包或直接上传新的 JAR 和 dist。

## 2. 准备服务器并上传

先通过 SSH 登录服务器。可以使用普通 sudo 用户，也可以使用 root（root 用户执行时去掉 sudo）。以下 Linux 命令在服务器终端执行：

```bash
sudo apt update
sudo apt install -y openjdk-21-jre-headless nginx unzip curl openssl
java -version
sudo install -d -m 755 /srv/course-registration
sudo install -d -m 700 /etc/course-registration
```

如果 apt 找不到 `openjdk-21-jre-headless`，先安装 Java 21 并确认 `/usr/bin/java -version` 显示 21 或更高版本，再继续。

在云服务器安全组中允许 TCP 22（SSH）、80（HTTP）、443（HTTPS）。如果已启用 UFW，同时执行 `sudo ufw allow OpenSSH` 和 `sudo ufw allow 'Nginx Full'`。8080 和 3306 由本机访问，不需要对外开放。

在本机项目根目录执行，替换 `ubuntu` 和 `SERVER_IP`：

```powershell
scp .local/server-release/course-registration-20261012.zip ubuntu@SERVER_IP:/tmp/
```

回到服务器：

```bash
sudo unzip /tmp/course-registration-20261012.zip -d /srv/course-registration
sudo useradd --system --user-group --home-dir /srv/course-registration --shell /usr/sbin/nologin courseapp
sudo chown -R root:root /srv/course-registration
sudo chmod 755 /srv/course-registration /srv/course-registration/frontend
sudo chmod 644 /srv/course-registration/backend.jar
```

`useradd` 只需首次执行；若该用户已存在，跳过这一行。

## 3. 安装并初始化 MySQL

仓库数据库脚本和 MySQL 集成记录以 **MySQL 9.6** 为准；本次构建测试使用 H2。如果已经有可用的 MySQL，直接使用它并跳到建表步骤。以下给出在新服务器安装同版本 MySQL 的方式，应用和 Nginx 由 systemd 管理，MySQL 容器配置自动重启和持久卷。以下创建容器和密码文件的命令仅首次执行。

```bash
sudo apt install -y docker.io
sudo systemctl enable --now docker
sudo install -m 600 /dev/null /etc/course-registration/mysql-root-password
sudo nano /etc/course-registration/mysql-root-password
```

在密码文件中写入自己设置的 MySQL root 密码。它与应用登录密码无关。然后首次执行：

```bash
sudo docker volume create course-registration-mysql
sudo docker run -d --name course-registration-mysql \
  --restart unless-stopped \
  -p 127.0.0.1:3306:3306 \
  -v course-registration-mysql:/var/lib/mysql \
  --mount type=bind,src=/etc/course-registration/mysql-root-password,dst=/run/secrets/mysql_root_password,readonly \
  --mount type=bind,src=/srv/course-registration/database,dst=/init,readonly \
  -e MYSQL_ROOT_PASSWORD_FILE=/run/secrets/mysql_root_password \
  mysql:9.6
sudo docker logs --tail 40 course-registration-mysql
```

如果 3306 已被已有数据库占用，使用已有数据库，或修改映射端口并相应修改 `DB_URL`。数据保存在 Docker 命名卷中，重启容器不会删除；不要删除该卷。初次拉取镜像需要服务器能访问镜像仓库。

等日志出现 MySQL 可接受连接的提示，再执行并输入 root 密码：

```bash
sudo docker exec -it course-registration-mysql mysql --default-character-set=utf8mb4 -uroot -p
```

在 MySQL 提示符中执行，替换数据库应用密码：

```sql
SOURCE /init/business-schema.sql;
SOURCE /init/session-schema.sql;
CREATE USER 'courseapp'@'%' IDENTIFIED BY 'REPLACE_WITH_DATABASE_PASSWORD';
GRANT SELECT, INSERT, UPDATE, DELETE ON course_registration_system.* TO 'courseapp'@'%';
SHOW TABLES FROM course_registration_system;
```

Session 脚本只执行一次，表已存在时跳过。应用默认 `ddl-auto=validate`，不会自动建表；业务表与 `SPRING_SESSION`、`SPRING_SESSION_ATTRIBUTES` 都必须存在。非 Docker MySQL 可以用其管理员客户端执行上传包里的同名 SQL 文件。

**用于课程设计演示的新建空库**可继续执行 `SOURCE /init/demo-data.sql;`。该脚本会覆盖全部业务数据，只在专门的演示库中执行；它提供 29 个合成账号，密码为 `demo-only-2026`。有现有业务数据时不要执行它。

完成后执行 `EXIT;` 退出 MySQL 客户端，再继续配置后端。数据库管理员 root 密码、数据库应用账号 courseapp 的密码、网页登录密码是三个不同用途的密码。

## 4. 配置并启动 MySQL 后端

首次部署复制 MySQL 模板并编辑；更新已经运行的服务时不要覆盖已有密钥和密码：

```bash
sudo install -m 600 /srv/course-registration/deploy/backend-mysql.env.example /etc/course-registration/backend.env
openssl rand -hex 32
sudo nano /etc/course-registration/backend.env
```

保持 `SPRING_PROFILES_ACTIVE=default`。填写 `DB_URL`、`DB_USERNAME`、`DB_PASSWORD`，其中 DB_PASSWORD 必须与上一步创建的 courseapp 数据库账号密码一致。把生成的随机字符串填入 `SSN_KEY` 并安全备份；已有加密数据时沿用原来的密钥。

配置文件使用 `KEY=value`，不要写 `$env:` 或 `export`。保留模板中值外面的单引号，这同时适用于 systemd 和下面的 Bash 加载命令。密码可用 `openssl rand -hex 16` 生成，避免引号转义问题。没有配置 HTTPS 时保持 `SESSION_COOKIE_SECURE=false`，第 6 节配置 HTTPS 后改为 `true`。

如果只建了空库，没有导入演示数据，先初始化教务员账户。密码只在终端输入，不放进命令行参数；管理命令会连接同一 MySQL，并在完成后退出：

```bash
sudo bash
set -a
source /etc/course-registration/backend.env
set +a
read -r -s -p '设置教务员密码（至少 12 字符，最多 72 UTF-8 字节）：' APP_PROVISION_PASSWORD
printf '\n'
export APP_PROVISION_PASSWORD
SPRING_MAIN_WEB_APPLICATION_TYPE=none APP_OPERATION=provision-account APP_PROVISION_USERNAME=registrar \
  java -jar /srv/course-registration/backend.jar --debug=false
unset APP_PROVISION_PASSWORD
exit
```

成功后使用 `registrar` 和刚设置的网页登录密码登录。如果已在第 3 节向新建演示库导入 demo-data.sql，则已有 `registrar.demo`、`professor.demo`、`student.demo` 等合成账号，密码为 `demo-only-2026`。这是存放在 MySQL 中的演示数据，后端重启不会清空；这些密码与 DB_PASSWORD 无关。

安装并启动 systemd 服务：

```bash
sudo install -m 644 /srv/course-registration/deploy/course-registration.service /etc/systemd/system/course-registration.service
sudo systemctl daemon-reload
sudo systemctl enable --now course-registration
sudo systemctl status course-registration --no-pager
curl -i http://127.0.0.1:8080/api/v1/auth/csrf
```

首次启动需要十几秒。如果 curl 暂时连接失败，等日志出现 `Started BackendApplication` 后重试。成功时响应为 HTTP 200，且含 `data.token`。日志中应显示默认 profile 和 MySQL 数据源，不应出现 `jdbc:h2:mem:course_demo`。

查看日志、重启和停止：

```bash
sudo journalctl -u course-registration -n 100 --no-pager
sudo systemctl restart course-registration
sudo systemctl stop course-registration
```

空库创建账号后只具备登录和人员维护的基础。要开展选课，还需准备学期、课程、教学班和已发布的目录快照；这些不会因建表或创建教务账号自动产生。合成数据用于课程设计演示，真实目录的同步配置详见上传包中的 `backend-README.md`（仓库源文件为 `backend/README.md`）。

导入课程设计演示数据后，目录快照默认 24 小时过期。只在这种合成演示库中，可通过管理员 MySQL 客户端刷新验证时间并保留业务变更：

```sql
UPDATE course_registration_system.catalog_snapshot
SET verified_at=UTC_TIMESTAMP(3)
WHERE source_kind='MOCK' AND source_version='COURSE-DESIGN-DEMO-V1';
```

真实目录应通过同步验证刷新。需要财务投递时，再配置 BILLING_URL、BILLING_TOKEN，并将 BILLING_DISPATCH_ENABLED=true；模板初始关闭外部投递。

## 5. 配置 Nginx

```bash
sudo install -m 644 /srv/course-registration/deploy/nginx.conf.example /etc/nginx/sites-available/course-registration
sudo nano /etc/nginx/sites-available/course-registration
```

将 `server_name _;` 改为 `server_name SERVER_IP;`；有域名则写 `server_name courses.example.com;`。下面将示例域名替换为你自己的域名。

```bash
sudo ln -s /etc/nginx/sites-available/course-registration /etc/nginx/sites-enabled/course-registration
sudo nginx -t
sudo systemctl reload nginx
```

软链接只需创建一次。如果服务器上已有其他网站，保留原有配置，为本应用设置独立的域名。Nginx 默认站点通常是 default server，写入实际 IP 或域名可以让本应用通过对应的 Host 匹配。

在浏览器打开 `http://SERVER_IP/`（或你的域名）。空库部署使用第 4 节创建的 `registrar` 账号；导入了课程设计演示数据则使用相应的合成账号。

验证前端和代理：

```bash
curl -I http://SERVER_IP/
curl -i http://SERVER_IP/api/v1/auth/csrf
```

两个请求应返回 200；第二个响应应为 JSON。还应在登录后刷新一个内部页面，确认前端路由不会变成 Nginx 404。`proxy_pass` 后不能增加 `/`，否则会把 API 路径中的 `/api/` 去掉。

## 6. 配置域名和 HTTPS

先将域名的 DNS A 记录指向服务器公网 IP，确认 HTTP 访问已成功，且 Nginx `server_name` 已改为该域名。

```bash
sudo apt install -y certbot python3-certbot-nginx
sudo certbot --nginx -d courses.example.com
```

按提示配置证书并启用 HTTP 到 HTTPS 的跳转。之后编辑 `/etc/course-registration/backend.env`，改为：

```dotenv
SESSION_COOKIE_SECURE=true
```

```bash
sudo systemctl restart course-registration
sudo certbot renew --dry-run
curl -i https://courses.example.com/api/v1/auth/csrf
```

通过 HTTPS 重新登录，确认响应的 `Set-Cookie` 中 SESSION 带有 `Secure`、`HttpOnly`、`SameSite=Lax`。纯 HTTP 时设为 `true` 会导致浏览器不发送会话 Cookie。

## 7. 更新与持久化验证

在本机重新编译，上传新的 JAR 和前端 dist。在服务器先停止后端，备份现有 JAR 和前端文件，再替换 `/srv/course-registration/backend.jar` 与整个 `frontend/` 目录，最后：

```bash
sudo systemctl start course-registration
curl -i http://127.0.0.1:8080/api/v1/auth/csrf
```

保留旧产物以便回退。不要覆盖 `/etc/course-registration/backend.env`，其中保存数据库配置和密钥。替换整个前端目录，避免旧 index.html 引用的文件与新版本不匹配。仅更新应用产物时不需要重建数据库。

首次部署后，新增一条用于验证的业务记录，然后执行 `sudo systemctl restart course-registration`，重新登录确认记录仍在。还可以重启服务器，确认 MySQL 容器和 Java 服务自动恢复。MySQL 数据在命名卷 `course-registration-mysql` 中；不要删除卷或重新运行覆盖数据的 demo-data.sql。

定期导出 MySQL 结构和数据，并独立备份 `/etc/course-registration/backend.env` 中稳定的 SSN_KEY。数据库备份与密钥都应存到服务器之外。密码文件和 Docker 卷不会随本机上传包一起备份。

## 8. 迁移当前本机数据库

迁移走 MySQL 配置，不使用 demo profile。服务器上准备同版本 MySQL，先暂停本机应用写入，再通过 MySQL Workbench 的 Data Export 导出 `course_registration_system` 的结构和数据，包含两张 Session 表。上传后通过服务器管理员客户端导入一个新的目标数据库；不要再导入 `demo-data.sql`。

同步迁移原来的 `SSN_KEY`。如果本机由 `start-local.ps1` 启动，密钥通常保存在 `.local/backend.properties` 的 `app.ssn-key` 属性中；若启动时设置了 `SSN_KEY` 环境变量，则以该变量的原值为准。将原值填入服务器 `SSN_KEY`，不要生成新值。不要把密钥加入上传包或 Git。

修改服务器 `DB_URL`、`DB_USERNAME`、`DB_PASSWORD`，启动后检查原有账号、学期、选课、成绩和敏感标识是否可读。定期备份 MySQL 与稳定的 SSN 密钥，并在独立数据库演练恢复。

## 9. 常见问题

| 现象 | 检查方法 |
| --- | --- |
| Nginx 502 | 查看 `journalctl -u course-registration`；先验证本机 8080 的 csrf 接口。 |
| 打开页面但登录失败 | 访问 `/api/v1/auth/csrf` 应返回 JSON；核对代理路径和 Secure Cookie 配置。 |
| 内部页面刷新 404 | 核对 Nginx `try_files $uri $uri/ /index.html`。 |
| Table doesn't exist / schema validation | 初始化业务表和 Session 表，确认连接的数据库名正确。 |
| Access denied | 核对 DB_USERNAME、DB_PASSWORD、账号允许的连接来源和权限。 |
| `CATALOG_UNAVAILABLE` | 缺少已发布快照，或开放学期目录超过 24 小时未验证；配置目录源并同步。 |
| SSN 操作返回 503 | 配置原来的稳定 SSN_KEY。 |
| 重启后数据消失 | 当前使用 demo profile；切换 MySQL 配置才能保留数据。 |
| 浏览器仍显示旧页面 | 检查新的前端目录是否完整替换，并重新加载页面。 |

本教程的默认部署使用 MySQL。包中的 backend-demo.env.example 仅供临时 H2 自检使用，不要将它作为本次服务器配置。
