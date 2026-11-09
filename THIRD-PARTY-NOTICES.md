# 第三方组件说明 / Third-party notices

本仓库整体采用 **GPL-3.0**（见根目录 `LICENSE`）。
下面列出的组件是各自作者的独立作品，**保留其原始授权**；如需使用其中任意组件，**亦需遵守原作者的授权**。

This repository as a whole is licensed under **GPL-3.0** (see the root `LICENSE`).
The components listed below are independent works of their respective authors and **retain their
original licences**; using any of them also requires complying with the original author's licence.

---

## ext-lsposed

| 项 / Item | 内容 / Detail |
|---|---|
| 组件 / Component | MineApp-LSPosed（LSPosed 模块） |
| 版本 / Version | v1.0.3 |
| 作者 / Author | MiaoluoYuanlina |
| 上游 / Upstream | <https://github.com/MiaoluoYuanlina/MineApp-LSPosed> |
| 授权 / Licence | **MIT** |
| 许可全文 / Licence text | `ext-lsposed/LICENSE`（原样保留，未作改动） |
| 版权行 / Copyright | `Copyright (c) 2026 MiaoluoYuanlina` |
| 在本仓库中的用途 / Purpose | 提供 Root / Shizuku 执行通道的参考实现 |
| 与本项目的关系 / Relationship | **只托管源码，不参与构建**；不修改本应用的 APK 与签名 |

### 说明 / Notes

- 该组件以 MIT 协议授权，允许使用、复制、修改、合并、发布、再分发，条件是**保留版权声明与许可声明**。
  本仓库保留其原始 `LICENSE` 文件，未作任何改动。
- 该组件是 **Gradle 工程**，不参与 `appmanager/build.sh` 的构建流程，构建它需要另外的工具链。
- MIT 与 GPL-3.0 是**单向兼容**的：MIT 代码可以并入 GPL-3.0 项目；
  但该组件作为独立作品，其授权不因被本仓库收录而改变。

- Licensed under MIT, which permits use, copy, modify, merge, publish and redistribute provided the
  copyright and permission notices are retained. The original `LICENSE` file is kept unmodified.
- It is a **Gradle project** and is not part of `appmanager/build.sh`; building it needs its own toolchain.
- MIT is **one-way compatible** with GPL-3.0: MIT code may be incorporated into a GPL-3.0 project, but
  the component's own licence is unaffected by being hosted here.

---

## Shizuku API（`appmanager/libs/`）

| 项 / Item | 内容 / Detail |
|---|---|
| 组件 / Component | Shizuku API / Provider / AIDL（`dev.rikka.shizuku:api`、`:provider`、`:aidl`） |
| 版本 / Version | 13.1.5（三个构件：api / provider / aidl） |
| 作者 / Author | RikkaW（RikkaApps） |
| 上游 / Upstream | <https://github.com/RikkaApps/Shizuku> · <https://central.sonatype.com/artifact/dev.rikka.shizuku/api> |
| 授权 / Licence | **Apache-2.0** |
| 许可全文 / Licence text | `appmanager/libs/LICENSE-APACHE-2.0.txt` |
| 在本仓库中的用途 / Purpose | 多通道版编译期依赖：申请 Shizuku 授权、以 Shizuku 身份执行 shell |
| 与本项目的关系 / Relationship | 以 jar 形式随 APK 分发；未修改其源代码 |

### 说明 / Notes

- 仅使用其**客户端 API**；真正的特权服务由用户自行安装的 Shizuku 应用提供，本仓库不包含、不分发该服务。
- 依 Apache-2.0 第 4 条，本仓库**保留许可全文**并在此声明使用了该组件；未对其源码作修改。
- Apache-2.0 与 GPL-3.0 是**单向兼容**的：Apache-2.0 代码可以并入 GPL-3.0 项目。

- Only the **client API** is used; the privileged service is provided by the user-installed Shizuku app
  and is neither bundled nor redistributed here.
- Per Apache-2.0 §4, the licence text is retained in this repository and attribution is given here;
  no source changes were made.
- Apache-2.0 is **one-way compatible** with GPL-3.0: Apache-2.0 code may be incorporated into a
  GPL-3.0 project.
