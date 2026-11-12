# E3-Fix1 安全推送到 GitHub 新分支

目标仓库：`yanjing-chen/openssreader-android`。
新分支：`feature/scan-enhancement-e3-fix1`。

本完整源码包中的 `PUSH_FROM_6800H.sh` 已替换为安全的新分支推送脚本，**不会推送 main**。
该脚本从 GitHub 拉取独立工作树，在 `origin/main` 基础上建立新分支，再导入本包完整源码。
不会覆盖本机已有工作树或 GitHub main，也不会创建 tag / Release。
运行方式（进入源码根目录之后）：`bash ./PUSH_E3_FIX1_NEW_BRANCH_6800H.sh /path/to/本源码ZIP`。
也可以将单独的 `PUSH_OPENSSREADER_E3_FIX1_NEW_BRANCH_6800H.sh` 放在 `~/下载`，再执行 `bash ./PUSH_OPENSSREADER_E3_FIX1_NEW_BRANCH_6800H.sh`。

执行时会检查 `gh` 登录用户是否为 `yanjing-chen`，检查仓库与分支状态，最后要求输入 `yes` 才真正提交和推送。

**注意**：源码中的 CI `android.yml` 对 push 仅监听 main；不需要创建 Release；若希望本功能分支自动跑 CI，可单独配置分支触发。
