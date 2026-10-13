# 视频保存助手（Android）

单功能安卓 App：粘贴抖音/快手等平台的分享口令，解析出**无水印视频直链**，下载保存到相册。支持图集保存。无需服务器、无需备案域名，解析逻辑全部内置在 App 内。

> 素材仅供个人学习使用，请尊重原作者版权。

## 获取安装包（APK）—— 三选一

### 方式一：GitHub 云端打包（推荐，本机零安装）

1. 注册/登录 [github.com](https://github.com)；
2. 右上角「+」→ New repository → 起个名字（如 `videosaver`）→ Public → Create；
3. 在仓库页点 **uploading an existing file**，把本项目的全部文件和文件夹拖进去 → Commit changes；
4. 顶部 **Actions** 标签 → 选中「Build APK」运行记录 → 页面下方 **Artifacts** 下载 `VideoSaver-debug-apk`；
5. 解压得到 `app-debug.apk`，传到手机安装（首次安装需允许「安装未知应用」）。

以后想改功能，把改好的文件重新上传覆盖，Actions 会自动重新出包。

### 方式二：Android Studio 本机打包

1. 安装 [Android Studio](https://developer.android.com/studio)；
2. Open 打开本项目目录，等 Gradle 同步完成；
3. 菜单 Build → Build App Bundle(s)/APK(s) → Build APK(s)；
4. 产物在 `app/build/outputs/apk/debug/`。

### 方式三：让我帮你出包

把项目交给我配置的其他云端构建渠道（需要你提供 GitHub 账号协助）。

## 项目结构

```
app/src/main/java/com/videotoolbox/saver/
├── MainActivity.kt   界面与流程（粘贴 → 解析 → 保存）
├── Parsers.kt        解析核心：抖音（_ROUTER_DATA）/ 快手（photoUrl）/ 通用兜底（og 标签）
└── MediaSaver.kt     OkHttp 下载 + MediaStore 写入相册（API 29+ 免存储权限）
```

## 已支持平台

| 平台 | 方式 | 说明 |
|------|------|------|
| 抖音 | 页面内嵌 `_ROUTER_DATA` JSON 提取 | 视频与图集均支持，直链即无水印 |
| 快手 | `photoUrl` 字段提取 | 视频为主 |
| 其他站点 | og:video 标签与常见字段兜底 | 覆盖有限，失败会明确提示 |

## 维护提示

- 抖音/快手接口若变动，优先调整 `Parsers.kt` 中的正则与 JSON 字段路径；
- 在抖音 App 内点「分享」→ 选择本 App（Manifest 已注册 ACTION_SEND），可跳过粘贴步骤自动解析；
- `minSdk 29`（Android 10+），覆盖近年绝大多数设备；保存目录为 相册/Movies/VideoSaver 与 Pictures/VideoSaver。
