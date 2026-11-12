# OpenSSReader Android 1.0.6 — 下载入库与书架 JSON 持久化修复

## 基线

直接以先前交付的 `OpenSSReader-Android-1.0.5-Compat12-Complete-Source.zip` 为基线。保留其中的 12 项 PDZ 兼容性修复、GeckoView 书城、UI、更新器、签名策略与 GitHub Actions。不含历史修复包、不含个人图书、账户凭据或密钥。

## 已确认根因

旧实现 `OsrViewModel.addBook()` 先更新 `_books` 并调用 `ShelfStore.save()`；此时下载的 PDZ 尚未通过 `mirror()` 发布，`publicPdzName` 为空。`BookshelfIndex.encode()` 不会为无公共文件的记录生成 JSON。之后 `mirror()` 修改公共文件引用，但索引更新依赖 `scheduleIndexSync()` 异步执行，且可能因 `scanComplete=false` 被跳过。于是界面已有书，`bookshelf` 却没有对应 JSON；重启重新扫描后会消失。既有 `PublicBookStorage` 又只识别 UUID.json，即使改成书名 JSON 也无法恢复。

## 本次修复

| 项目 | 处理方式 |
|---|---|
| 新下载入库 | 下载并确认外层 PDZ、检查图书、重命名后先将 PDZ 发布到用户选定的 PDZ 目录；随后同步写入并读回单书 JSON，**只有成功后才更新界面**，再开始转换 |
| PDF 转换完成 | 生成的 PDF 先通过原有页数/渲染校验，再发布到 PDF 目录、把 `publicPdfName` 写回**同一个 JSON**，成功后任务才标记“已完成” |
| 文件名一致 | `示例书名_作者_出版社_年份_ssid.pdz` ↔ `示例书名_作者_出版社_年份_ssid.json`；对 PDF-only 图书则使用 PDF 文件名去扩展名；JSON 里的稳定 UUID **内容字段**保留，用于标签和书签关联 |
| 启动与重扫 | 可见书架由 `bookshelf` JSON 和已明确成功提交的新书决定；**不自动扫描 PDZ/PDF 目录并虚构书架记录**；不把旧 SharedPreferences 书直接显示在界面 |
| 老索引迁移 | 读取 `index.json`、老的 UUID.json；迁移为同名 JSON，在新 JSON 成功落盘后才移除旧 JSON；仅首轮补救旧 preferences 中确有公共文件链接的记录，包含 `labels.json` 已存在而图书 JSON 缺失的情况 |
| 断开存储 | 已有 JSON 指向的公共文件若暂不可用，当前不显示该书，但对其他书做增删、收藏或标签调整时保留这类旧 JSON，不据此删除记录 |
| 重名与异常 | 两书同名 JSON 会显式报错，拒绝覆盖；失败时保留已下载的 PDZ，不声称已安全入库；仅删除索引时不删除原始图书，除非用户另选文件删除 |
| 未知书目 | 对外层封装有效但书目暂无法解析的下载，若已知 SSID，用 `机构图书_ssid.pdz` 作为后备实际文件名，JSON 同名 |
| 既有安全保障 | 不改写原始 PDZ、不过度放宽页数和授权检查、不改变签名源与 GitHub Release 规则 |

关键变更：`OsrViewModel.kt`、`PublicBookStorage.kt`、`BookshelfIndex.kt`、`ShelfStore.kt`、`BookEngine.kt`。新增 `BookshelfPersistenceInstrumentedTest.kt`，扩展 `BookshelfIndexInstrumentedTest.kt` 和 `check_bookshelf_persistence.py`，CI 构建脚本已包含新静态门禁。

## 本环境已执行的验证

- 全套 `android/verification/check_*.py` 静态检查通过，包括新增的书架持久化执行顺序检查。
- `verify_compatibility_12.sh` 通过，确认此前 12 项 PDZ 格式兼容性合成回归没有因本轮变更失效。
- `verify_import_storage_fix.sh` 通过，覆盖 XML 与 book:// 路由及本地导入存储静态/离线回归。
- `BookshelfIndex.kt` 与 `PublicBookStorage.kt` 使用本地 Kotlin 编译器及隔离 Android/JSON 编译桩做过语法/类型编译检查。
- **没有本地 Android SDK / emulator**，因此**没有**声称整包 Android Gradle 编译、真实 Android JSON 文件读写、真正机构下载或设备重启已通过。新增 Android Instrumented tests 需要运行 `connectedDebugAndroidTest` 才能得到真实结果。

## TB138FC / 其他 Android 设备必须验证的流程

1. 先备份 `OpenSSReader/pdz`、`OpenSSReader/pdf`、`OpenSSReader/bookshelf`，不要卸载原 APK；使用**相同正式签名**升级测试版。
2. 从书城下载一本已授权 PDZ，观察它入书架时是否已经存在与实际 PDZ 同名的 `bookshelf/*.json`。下载/转换结束后，检查 PDF 文件及 JSON 的 `publicPdfName` 关联。
3. 强制结束应用、重新启动，再次扫描 `bookshelf`；验证仍有这本书，能打开，进度和标签可保留。
4. 测试第二册及连续下载、重复下载、无网络/中断保存、书架文件夹改用 SAF、故意撤销写权限（不得出现假成功）。
5. 测试旧 UUID.json 迁移；旧 JSON 需要在新文件成功写入后才删除。移除书架项时仅按用户选择删除物理 PDZ/PDF。
6. 重新执行 `bash ./BUILD_ANDROID_6800H.sh`（JDK 17、Android SDK Platform 37.0），有测试设备时运行 `cd android && ./gradlew :app:connectedDebugAndroidTest`。Android 实机通过后再考虑创建正式 tag。

**注意**：对于用户已经在旧版中遭遇、且私有 preferences 已被旧启动扫描清空的“无 JSON 孤儿 PDZ”，本次不会违反“仅按 JSON 入书架”的要求自动扫描整个 PDZ 目录；可以在应用内通过文件选择器**手动导入该 PDZ 一次**，新版会建立相应 JSON。
