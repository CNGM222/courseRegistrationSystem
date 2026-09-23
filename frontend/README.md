# 课程注册系统前端

Vue 3 + TypeScript + Vite + Element Plus + Pinia + Vue Router。前端仅调用真实 `/api/v1` 接口，不提供模拟模式、内置演示账号或本地数据后备。接口未实现、未启动或连接失败时会显示错误。

## 启动

安装 Node.js 22.12+ 或 24 LTS（本次使用 Node.js 24.14.0），在本目录执行：

```powershell
npm ci
npm run dev
```

打开 http://localhost:5173 。Vite 将 `/api` 代理到 `http://localhost:8080`，目标配置位于 `vite.config.ts`。前后端应通过同源代理访问，以使用 Cookie 会话；不要直接将浏览器请求改为任意跨域地址。

```powershell
npm run typecheck
npm run build
npm run preview
```

构建结果在 `dist/`。`preview` 仅用于查看构建结果；真实部署需要代理 `/api` 到后端并配置 SPA 路由回退，示例见 `deploy/nginx.conf.example`。

## 当前实现

- 登录、退出、会话恢复、CSRF 令牌、按角色路由和手机导航。
- 课程目录检索、院系筛选、分页、详情、先修课、周次时间与目录校验时间。
- 学生四主选两备选、备选排序、草稿、预检、确认提交、删除课表、名额轮询与版本冲突恢复。
- 当前课表（列表/周课表）、选课历史、独立成绩单。
- 教师授课选择、本人教学班、名单和成绩批量保存；未录入版本为 null，已有版本 0 保留原义。
- 教务学生/教师增改删、字段校验、敏感标识掩码与关联错误提示。
- 注册关闭确认、最终教学班结果和取消原因、财务投递状态筛选/分页/刷新。

完整响应结构与交付边界见 `../课程设计文档/06-前端接口契约与验收说明.md`。后端现有 Entity/Repository 没有 Controller/Service，本次未改写后端；必须按契约实现后才能真实登录与执行业务。

## 安全与草稿

不使用 localStorage、sessionStorage 保存会话、成绩、个人资料或草稿。选课草稿仅在会话过期时保留于当前标签页的 Pinia 内存，重新登录同一账号后提示恢复；其他账号、主动退出和正常离开编辑页会清除它。刷新/关闭标签页会丢失尚未保存的内存草稿。

登录前后重新获取会话关联的 CSRF 令牌；写请求带令牌和 Cookie。UI 权限和字段校验不代替后端授权、事务和输入校验。SSN 可空，填写时接受九位数字（可用 XXX-XX-XXXX 格式），编辑留空保持原值，不将掩码作为新值提交。新增账号密码由后端受控初始化。

## 浏览器测试

```powershell
npm run test:e2e
```

默认使用机器已安装的 Google Chrome 无界面运行。也可安装 Playwright 浏览器并调整 `playwright.config.ts` 的 channel。测试在 `tests/` 使用隔离的 HTTP 响应夹具，不发送真实后端写请求，不进入生产构建。

测试验证前端交互和接口请求，不证明 MySQL 并发事务、跨用户授权、真实财务幂等投递、旧目录接入或 500/2000 用户性能已完成。浏览器测试截图和失败轨迹在 `test-results/`，不进入版本库。
