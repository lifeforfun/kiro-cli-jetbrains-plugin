# Kiro CLI Terminal

JetBrains IDE 插件，在底部工具窗内集成 [kiro-cli](https://kiro.dev) 的 `chat` TUI，把对话搬进 IDE 终端，并保留原生终端的输入与滚动行为。

参考实现：`cursor-cli-terminal-plugin`（Cursor Agent 同构工具窗）。

## 功能

- 打开 **Kiro CLI Terminal** 工具窗后，自动在项目目录下启动 `kiro-cli chat --tui`。
- **恢复上次对话**：
  - 有绑定 / 可解析的 session：`kiro-cli chat --tui --resume-id <SESSION_ID>`
  - 否则：`kiro-cli chat --tui --resume`（本目录最近一次）
- 工具栏：
  - **开启会话**：无活跃会话时恢复；有会话时确认后开启全新 chat（不带 `--resume`）
  - **注入路径**：向终端注入当前编辑器文件的 `@` 路径
  - **滚到底部**
- 剪贴板为图片时，`Cmd/Ctrl+V` 发送 `0x16`（SYN）尝试触发图片粘贴
- **Shift+Enter** 换行；继承用户 shell 环境（含 `EDITOR` / `VISUAL`）；`NO_HYPERLINK=1`

## 使用

1. 安装本插件（见下方构建），或从 `build/distributions/kiro-cli-jetbrains-plugin-*.zip` 安装。
2. 本机已安装 `kiro-cli`，或指定：

   ```bash
   export KIRO_CLI_PATH=/path/to/kiro-cli
   ```

3. 在 IDE 底部打开 **Kiro CLI Terminal** 工具窗。

**注意**：不要用 `kiro-cli-term`（figterm）作为 chat 入口；本插件只启动 `kiro-cli chat`。

## 构建

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew buildPlugin
# build/distributions/kiro-cli-jetbrains-plugin-<version>.zip
```

## 会话解析

优先：

```bash
kiro-cli chat --list-sessions -f json
# [{"cwd":"...","sessions":[{"sessionId":"...","updatedAt":"..."}, ...]}]
```

回退：扫描 `~/.kiro/sessions/cli/<id>.json`（字段 `session_id` / `cwd` / `updated_at`）。

插件绑定：`~/.kiro/plugin-bound/<md5(project)>/.plugin-bound-session`

## 结构

```
src/main/kotlin/com/github/kiroterm/
├── KiroTerminalToolWindowFactory.kt
├── KiroCliTerminalController.kt
├── TerminalLauncher.kt              # kiro-cli chat 启动参数
├── KiroCliSessionStore.kt           # 会话 ID 解析与持久化
└── feature/                         # 会话 / 路径注入 / 图片 / 键位
```
