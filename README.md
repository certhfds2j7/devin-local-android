# Devin 本地助手（Android，非官方）

面向安卓手机的简体中文编程客户端：Kotlin / Jetpack Compose 界面，离线 CodeMirror 6 编辑器与 xterm.js 终端，通过手机上的 Termux + Ubuntu 24.04 ARM64 运行官方 Devin CLI。

**AI 需要联网和官方账号；文件、Git、Python / Node.js 与命令在手机执行。** 本项目不是完全离线大模型，不包含修改版 Devin，也不是 Cognition 官方产品。官方账号的权限、额度、组织隔离要求继续适用。

## 当前验证状态

一加 Ace 5 竞速版（PLF110，Android 16 / API 36，ARM64，16GB 实体内存）已经完成官方 CLI 3000.11.234 的启动、手动账号登录、ACP 握手、文件修改及本地命令执行验证。

0.3.0 已在该手机覆盖安装，新增项目与对话重命名及项目删除均通过实机验收；0.2.0 的思考强度、文件上传下载、富文本和单条历史删除记录也保留在 [验收记录](docs/acceptance.md)。更广泛的生命周期和升级测试仍有待办。其他设备和账号尚未验证。首版不包含桌面插件商城或完整桌面 IDE。

## 安装与使用

详细步骤见 [中文使用说明](docs/使用说明.md)。

1. 从 [Termux 官方发布页](https://github.com/termux/termux-app/releases) 安装 ARM64 版本，打开一次，完成基础初始化。保留现有 Termux 和 Ubuntu 数据，避免丢失项目及官方登录。
2. 在 Termux 执行下方命令，允许本客户端调用本地环境：

   ```sh
   mkdir -p ~/.termux
   printf '\nallow-external-apps=true\n' >> ~/.termux/termux.properties
   termux-reload-settings
   ```

3. 安装本项目签名 APK，点击“连接环境”，按系统提示授予 Termux 连接权限和通知权限。初始化需要网络、存储空间和一定时间；期间请保持 Termux 运行。
4. 在“设置 → 官方账号登录”中完成官方手动登录，凭据只输入手机的官方终端。已登录的现有环境可直接复用。
5. “项目 → 新建项目”选择 Python、网站或空项目，开始对话，在系统要求时审阅工具权限；随后查看文件、Git 差异，在本地终端运行和提交。

Ubuntu、官方 CLI、Python、Node 24 LTS 和 Git 由初始化流程安装到 Termux 内。Node 安装包使用随客户端固定的官方摘要校验；官方站点不可达时可使用中科大镜像，仍核对相同的官方摘要。客户端源码不重新分发官方 CLI。

## 功能

- 项目：Python / 网站模板、空项目、ZIP 导入导出。
- 管理：重命名项目及对话；输入项目名称确认后删除本机项目及相关客户端记录，运行任务和终端会阻止删除。
- 编辑：本地代码编辑、简体中文输入、内容搜索、草稿恢复、版本冲突检查。
- 对话：官方 ACP、流式 Markdown / LaTeX / 代码高亮、取消、显式权限审批、官方提供的模型 / 模式 / 思考强度与当前状态标识、历史恢复和单条本机记录删除。
- 附件：系统文件选择器多文件上传，按官方能力发送图片、嵌入文本和本地资源链接；代理结果文件可从对话链接或项目文件列表下载到系统选择的位置。
- Git：HTTPS / SSH 克隆、身份设置、暂存、提交、拉取、推送、修改对比。认证由本地终端处理。
- 终端：手机 Ubuntu 的交互式 bash，断开界面后进程继续运行；系统结束进程时显示中断。
- 更新：客户端通过 GitHub Releases 下载或导入 APK，核对摘要、包名、版本和现有签名后交由 Android 确认安装。官方代理独立下载官方发行文件、校验摘要、验证 ACP 后切换，保留原版本及私有配置备份。

## 数据与连接

桥接只监听 `127.0.0.1:8765`，每次请求均需随机凭据，拒绝网页 Origin 请求。凭据留在 Termux 私有目录和 Android Keystore 加密的应用偏好中，不进入 WebView、源码或更新元数据。

项目和会话保存在 Termux 的 `~/.local/share/devin-local-android`，客户端的未保存草稿位于自身私有目录。两个位置都不会随 APK 更新清除；卸载对应应用会清除其私有数据，卸载前请自行导出项目。本项目不上传遥测，发送给 AI 的任务内容仍由官方服务处理。

文件保存使用内容摘要比较；代理运行时拒绝编辑器写入。冲突时保留草稿，先查看差异或重新载入，避免覆盖代理的修改。Termux + proot 不提供完整桌面隔离；如果组织策略要求无法提供的隔离，客户端应报告不兼容，不绕过规则。

## 自行构建

需要 JDK 17、Android SDK 36、build-tools 35.0.0、Python 3.12+、Node.js 24 和 pnpm 11。

```sh
cd web
pnpm install --frozen-lockfile
pnpm run build
cd ..
python3 tools/bundle-runtime.py
python3 -m pip install -r tests/requirements.txt
python3 -m unittest discover -s tests -v
./gradlew :app:assembleDebug :app:lintDebug
```

Windows 使用 `gradlew.bat`。仓库包含构建好的离线网页资源；编辑网页代码后需重新构建。调试签名无法覆盖正式签名版本；请勿使用调试版代替正式升级。

正式构建、签名备份和 GitHub Actions 配置见 [更新与发布说明](docs/更新与发布.md)。签名密钥与密码不得加入仓库。

## 官方接口与许可

- [Devin Local](https://docs.devin.ai/desktop/devin-local/overview)
- [官方 ACP 接入](https://docs.devin.ai/cli/acp/zed)
- [官方 CLI 命令](https://docs.devin.ai/cli/reference/commands)

客户端原创代码采用 MIT 许可，见 [LICENSE](LICENSE)。第三方组件见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) 和 APK 内附许可。官方 Devin 核心受其自身条款约束。
