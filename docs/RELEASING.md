# GitHub 仓库与发布准备

当前构建版本为 `0.1.0-SNAPSHOT`，API 仍属实验阶段，没有公共 Maven 发布。上传源码、创建 GitHub Release 和发布 Maven 是三个独立操作；CI 只构建与验证本地 publication。

## 首次上传

1. 在 GitHub 创建空仓库，名称建议为 dyltun；不要自动初始化 README、gitignore 或 License，以免与已有文件产生独立历史。
2. 检查当前提交范围：源码、构建脚本、Wrapper 和文档应被包含；SDK 路径、缓存、日志、抓包、签名和已构建包应被排除。
3. 首次提交并关联远程。以下 URL 为占位示例，替换成自己的真实仓库地址后执行：

```powershell
git add .
git diff --cached --stat
git diff --cached --name-only
git commit -m "Initial implementation of dyltun"
git branch -M main
git remote add origin "https://github.com/YOUR_USERNAME/dyltun.git"
git push -u origin main
```

已有提交或远程时按实际状态操作，无需重复初始化或覆盖历史。

## GitHub 设置

- 等待首次 CI 实际运行，检查测试、Lint 和构建报告；本地通过不能代替 GitHub 上的执行结果。
- 首次 CI 成功后，为主分支添加规则，按维护方式要求 PR 和 CI 检查；Fork PR 工作流无需仓库写权限。
- 启用 Issues，并验证中英文反馈表单显示正常。
- 在 Security 设置中启用 Private vulnerability reporting，并补充可用的安全联系渠道。
- 添加简短仓库描述和相关 topics，例如 android、kotlin、vpn、tun、http。
- README 如实保留许可证状态：当前未选择项目许可证，不添加 License badge 或授权声明。以后选择时同步更新文档与 Maven POM。

这些设置需要仓库创建后由维护者在 GitHub 配置，配置文件本身不会自动开启分支保护或私密漏洞报告。

## 发布前验证

按 [BUILDING.md](BUILDING.md) 配置工具链，然后执行 CI 的 Gradle 任务，并在适用设备上验证：

- VPN 授权、重复启停、撤销、异常退出和 FD/线程回收。
- TCP/UDP、DNS、HTTP 普通转发、请求/响应修改、本地响应与上游失败回退。
- HTTP 持久连接、chunked/gzip、100-continue、升级流，以及请求完成后不会提前发送 FIN。
- Body 阈值、Session 预算、慢读写、大上传和长连接场景。
- TLS SNI 域名集合、MQTT 重定向与未知协议回退。

在 [IMPLEMENTATION.md](IMPLEMENTATION.md) 中准确记录版本、设备/API、执行结果和未验证项。声明支持 API 21+ 不等于已经验证全部 API/厂商设备。

## 创建版本

- 在版本号、sample 展示版本、API 文档和 CHANGELOG 中使用一致的软件版本；设计文档的 v0.5 是设计基线，不是软件发行版本。
- 首个供试用的发行版可采用 `0.1.0-alpha.1`。实际发布前将库 `build.gradle.kts` 中的 `version` 改为对应值，更新 sample 的 `versionName` 和接入示例。
- 使用明确 commit 的构建产物和校验和创建 tag/Release，写明支持范围、验证结果及已知限制。发布动作由维护者执行，CI 不自动创建 tag 或 Release。
- 发布 AAR 时同时说明 Kotlin/协程依赖；提供 Maven publication 可以保留传递依赖和 sources JAR。

发布到公共 Maven 前还需要配置实际仓库地址、认证、相应签名和完整 POM 信息（真实项目 URL、SCM、开发者及最终许可证状态等）。当前只提供本地 publication，不宣称满足某个远程仓库的发布要求。
