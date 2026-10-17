# 基线（Phase 0 · 可回退）

## 事实
- `F:\Bot` **不是** Git 仓库（无历史）→ 必须靠快照回退。
- `F:\Bot-publish\pixiko` **是** Git 仓库，HEAD = `df7e606`（已推 GitHub），是版本对照基线。
- 当前权威实现 = **Java 17**（`src/main/java` + `src/test/java`）。根目录 Next.js/TypeScript 与 `nextjs-wip` 是**未完成迁移的残留**，缺 `lib/core/runtime.ts` 等后端文件，**不是**实现依据；`docs/MIGRATION.md` 仅作历史参考。

## 快照
- 目录：`F:\Bot-backup\baseline-20261018-021329`
- 压缩包：`F:\Bot-backup\baseline-20261018-021329.zip`
- 大小：4 MB ｜ SHA256：`709d73c7f5de722e322c3b936850da0f9974eb5d72570e2e742b1323d13143fc`
- 内容：`src`、`docs`、`webui`、`webui-extension`、`android/app/src`、启动/构建脚本、`README.md`、`RELEASE.md`、`config.example.json`、`.gitignore`、`maps`（262 个文件 / 9 MB）
- **不含**：`config.json`、`data/`、`logs/`、`work/`、`build/`、`lib/`、模型、生成图、`*.apk`（逐个检查结果为 False；疑似密钥文件 0 个）
- 可恢复性：随机抽 3 个 `.java` 与源树 sha256 逐个一致。

## 回退
```powershell
# 整棵树回退（先停机器人）
powershell -NoProfile -ExecutionPolicy Bypass -File F:\Bot\stop-bot.ps1
Expand-Archive -Path F:\Bot-backup\baseline-20261018-021329.zip -DestinationPath F:\Bot-restore -Force
# 选定要回退的子集（例如 src）再覆盖回去；数据/config/日志/生成图不在快照内，因此不会被回退影响
Copy-Item F:\Bot-restore\src\* F:\Bot\src -Recurse -Force
powershell -NoProfile -ExecutionPolicy Bypass -File F:\Bot\build.ps1
```

## 纪律
1. 每阶段开始前打一次新快照（`F:\Bot-backup\phase<N>-<时间戳>`）。
2. 提交/备份**永不**包含 `config.json`、`data/**`、`logs/**`、`work/**`、模型、生成图、密钥。
3. `config.json`、`data/**`、`maps/**`、`logs/**`、生成图、LoRA、样式、预设、提示词、回执**不删除、不重置、不迁移**。
## ⛔ 生产部署冻结（2026-10-18 用户指令）
- **在用户明确批准之前，8787 必须继续运行备份版本（当前 `build/pixiko.jar`）。**
- 重构期间**禁止**对 8787 执行：重新构建后重启、替换 jar、改端口、热部署新版本。
- 所有验证（Phase 0–6 的测试与探针）必须跑在**隔离端口 + 独立临时目录**（见 `docs/TEST-ISOLATION.md`），**不得**连 8787。
- 需要"预演新版本"时，用**另一个端口**起一份实例（独立 `bot.test.home` 与独立配置），验完即停，**不碰** 8787 的进程与 jar。
- 解冻条件：用户对某个具体版本明确批准 → 才允许一次"停 → 构建 → 起"的部署动作，并在报告里记录前后 jar 时间与 PID。