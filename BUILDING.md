# Android 构建与发布

## 工具与本地构建

版本入口为 `scripts/toolchain.json`、`gradle/libs.versions.toml` 和 Gradle Wrapper。当前固定 Python 3.12.10、JDK 21.0.10、Go 1.26.5、Gradle 8.14.3、Android SDK 36、Build Tools 36.0.0、NDK 27.2.12479018；最低 Android API 26，仅 ARM64 和 x86_64。

设置 `JAVA_HOME`、`ANDROID_HOME`，把 Python、Go 与 JDK 工具加入当前终端 PATH。构建只依赖本仓库；首次需要下载 Gradle、Maven 与 Go 模块。Windows 使用 `python`，Linux 可使用 `python3`。

```text
python scripts/dev.py doctor
python scripts/dev.py check
python scripts/dev.py build-debug
python scripts/dev.py build-release
```

`check` 执行维护脚本测试、双语/UI 约束、Go 与 Kotlin 测试、Android Lint。构建校验两种 ABI、核心和 GEO 哈希、ELF 与 APK 16 KB 对齐。检查通过不等于实机兼容性认证；这些命令不会安装应用或启动 VPN。

Debug 和未签名 Release 输出至 `Bin/Jeemi-Android/Android-{arm64,amd64}/{debug,release}/`，每包附 SHA-256。`build-release` 是 R8 构建，随后才执行签名。不要并发运行共享 `engine/build` 的构建任务。

`core-verify`、`geo-verify` 校验锁定资源；`core-build` 从源码归档及补丁重建核心，`geo-fetch` 恢复固定 GEO。普通构建仅校验现有资源。被资源清单引用的 JSON 按原始字节保留，不能批量转换换行后绕过哈希校验。

## 签名 Secrets

在本公开仓库 **Settings → Secrets and variables → Actions → New repository secret** 添加下列五项；均使用 Secrets，无需配置 Variables：

| Secret | 值 |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | JKS 文件完整内容的单行 Base64 |
| `ANDROID_KEYSTORE_PASSWORD` | JKS 密码 |
| `ANDROID_KEY_ALIAS` | 密钥别名，项目约定为 `jeemi-android-release` |
| `ANDROID_KEY_PASSWORD` | 该别名对应的私钥密码 |
| `ANDROID_SIGNING_CERT_SHA256` | 公钥证书 DER 的 SHA-256，64 位十六进制；用于校验证书身份 |

维护者从自己的私有签名材料填写这些值。密钥文件、密码和 Base64 不属于公开源码或发布资产。指纹是公开校验信息；这里也采用 Secret，便于在同一位置配置。

Jeemi Android 当前发布证书 SHA-256：`808fb3325fd433be89fe5ec0e0da61991d956c7f8b6e025c779252e867517970`。

独立构建者可运行 `python scripts/dev.py signing-init` 生成自己的 RSA 4096 / SHA256withRSA 证书，有效期 10000 天。生成器拒绝覆盖非空签名目录；材料默认保存在已忽略的 `private/android-signing/`。本地 `sign-release` 自动读取其中的 `github-secrets.json`，也可使用以上五个环境变量。CI 必须提供完整 Secrets，不会回退读取本地文件。

```text
python scripts/dev.py build-signed-release
```

这一个命令完成本地 Release 构建、签名及归档。已有签名材料无需重新生成，本地也不依赖 GitHub Secrets；`build-release` 和 `sign-release` 仍可用于分步操作。构建失败会停止，不使用旧归档继续签名。

脚本对两份 APK 使用同一证书，启用 APK Signature Scheme v2/v3，核对签名、包名、版本、ABI、核心、GEO 和对齐。签名密码仅经环境传给工具，解码的临时密钥会清理。完整六件发布资产位于 `Bin/github/v版本号/`，签名 APK 与单包哈希也复制到各架构的 `release/`。后续升级沿用原密钥，并增加 `versionCode`；不同证书不能直接覆盖安装原应用。请另行备份签名材料。

Release 禁用 APK 内嵌 Git 元数据，源码提交由 CI 记录在 `release.json`；本地构建的该字段为空。

## GitHub 工作流

- **Android 源码检查**：main 推送、PR 和手动运行时检查源码，并构建两份 Debug APK；不使用签名 Secrets。
- **发布 Android APK**：推送匹配的 `vX.Y.Z` 标签，或在 main 手动运行。先检查版本与说明，固定公开提交，完成全部检查和双架构 Release，再签名及上传。

应用版本取自 `app/build.gradle.kts` 顶部 `plugins` 块后的 `appVersionName` 与 `appVersionCode`。只需在顶部更新三段版本名称并递增内部编号；下方 `defaultConfig` 的 `versionName` 与 `versionCode` 保持引用它们。本地构建、签名和 GitHub 发布共用这两项。发布前在 `CHANGELOG.md` 添加 `## X.Y.Z` 及非空更新说明。手动运行如果尚无标签，会在产物全部验证后创建标签；标签已指向其他提交则停止。已公开的同版本 Release 直接跳过，不覆盖资产。

发布任务再次核对下载的六件资产、两种 APK 的证书与包信息，创建或续传草稿，确认 GitHub 返回的各资产大小和 SHA-256 后才公开。中途失败保留草稿，可重新运行失败工作流；不要移动既有版本标签。正式发布仅允许在 `bluevava/jeemi-android` 执行，fork 自行发布时需要明确调整仓库常量及工作流限制。

草稿通过已认证的 Releases 列表查找，上传后按固定 Release ID 重新校验；按标签查询的 API 只返回已发布版本。历史工作流若在签名成功后报 `Release state changed while uploading`，需检查是否仍在使用旧的草稿查询脚本。重新运行旧任务仍使用旧提交；脚本更新后，后续版本按正常流程递增版本号并发布，已有标签保持不变。

可用 `apksigner verify --verbose --print-certs <APK>` 查看证书指纹，并与 `release.json` 的 `certificateSha256` 对比；文件哈希用 `sha256sum -c SHA256SUMS` 或 PowerShell `Get-FileHash -Algorithm SHA256` 校验。

参考：[Android 应用签名](https://developer.android.com/studio/publish/app-signing)、[apksigner](https://developer.android.com/tools/apksigner)、[GitHub Actions Secrets](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets)。
