# XNotes AI 阅读笔记（派生预览版）

基于 [shardulvs/xnotes-android](https://github.com/shardulvs/xnotes-android) 二次开发，增加当前页、截图框选和 PDF 选中文字的 AI 阅读助手。支持提问、总结、生成复习题，以及把回答保存为可编辑、可撤销的笔记新页。

- 使用方式、验收步骤和限制：[AI_PREVIEW.md](AI_PREVIEW.md)。
- 构建与 APK：[本仓库 Actions](https://github.com/gky051224/xnotes-android/actions/workflows/ai-preview.yml)。
- 本派生版增加联网权限；只有点击 AI 助手的“发送”才会请求所配置的模型服务。API Key 在本机加密保存。调用模型需要用户自己的服务商账号、接口地址和 Key。
- 应用 ID 为 `io.github.gky051224.xnotes`（调试版带 `.debug`），可与上游安装包共存。

下方为上游项目介绍；其中商店和上游 Releases 链接对应原版安装包。

<p align="center">
  <img src="docs/readme-banner.png" width="100%" alt="xnotes: a handwriting-first notebook for Android" />
</p>

<p align="center">
  <a href="https://github.com/shardulvs/xnotes-android/releases/latest"><img src="https://img.shields.io/github/v/release/shardulvs/xnotes-android?style=flat-square&label=release&color=blue" alt="Release" /></a>
  <a href="https://f-droid.org/en/packages/com.xnotes"><img src="https://img.shields.io/f-droid/v/com.xnotes?style=flat-square" alt="F-Droid" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-orange?style=flat-square" alt="License" /></a>
  <img src="https://img.shields.io/badge/Android-8.0%2B-3ddc84?style=flat-square&logo=android&logoColor=white" alt="Android 8.0+" />
  <a href="https://github.com/sponsors/shardulvs"><img src="https://img.shields.io/badge/sponsor-%E2%9D%A4-ec6cb9?style=flat-square&logo=githubsponsors&logoColor=white" alt="Sponsor" /></a>
</p>

<p align="center">
  <a href="https://f-droid.org/en/packages/com.xnotes"><img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png" height="70" alt="Get it on F-Droid" /></a>
  <a href="https://play.google.com/store/apps/details?id=com.xnotes"><img src="https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png" height="70" alt="Get it on Google Play" /></a>
</p>

<p align="center">
  <img src="docs/screenshots/editor.png" width="90%" alt="The xnotes editor with a PDF open in dark mode, annotated by hand with a note, an arrow, a highlight and an underline, and page thumbnails down the left" />
  <br />
  <em>Annotating a PDF in dark mode, with the pages inverted</em>
</p>

<p align="center">
  <img src="docs/screenshots/explorer.png" width="90%" alt="The xnotes file explorer in gallery view, showing a grid of imported book covers with page counts" />
  <br />
  <em>Browsing imported PDFs in the gallery view</em>
</p>

---

## Features

- **Ink that feels like a pen**: pressure-sensitive and low latency, with pens and highlighters.
- **Neon glow**: make any pen glow.
- **Disappearing ink**: ink that fades away on its own, great for teaching and presenting.
- **Shape snapping**: draw a rough shape, hold, and it snaps clean.
- **Sharp at any zoom**: ink and PDFs stay crisp however far you zoom in.
- **Notebooks and an infinite canvas**: pages when you want structure, endless space when you don't.
- **Book layouts**: single, double or cover pages, scrolling or page flips.
- **PDF import**: bring in any PDF and write all over it.
- **Room for side notes**: widen a PDF's margins on any edge and take notes on the side.
- **PDF text markups**: highlight, underline and strike out text, add comments, search it.
- **PDF colour filters**: invert, sepia, contrast, brightness, multiply and screen.
- **Images stay untouched**: filter a PDF and its photos and images keep their true colours.
- **Dark and OLED modes**: easy on the eyes at night, true black on OLED screens.
- **Real PDF export**: sharp vector output with selectable text, links and bookmarks.
- **Markdown**: write in markdown and it formats as you type.
- **Typed notes**: tables, LaTeX equations and code highlighting, right on the page.
- **20+ page templates**: Cornell, planners, music staves, isometric and more.
- **Endless themes**: any accent colour, eight styles for each, dual tone and Material You.
- **Make it yours**: rearrange the toolbar, float it or dock it on any edge, and pick its size.
- **Split view**: two notes side by side.
- **A real file explorer**: grid, gallery, list, column and timeline views, with plenty to customise.
- **Your notes, your folders**: notes are saved as files in a folder you choose, not locked away in an app's database, so you stay in full control of them.
- **Works with stylus pens**: S Pen and many others.
- **Always editable**: nothing gets flattened, so every stroke can be moved, restyled or erased later.
- **Private**: open source, no app account, no ads or tracking. This fork adds optional, user-triggered requests to your configured AI provider.

## Install

| Channel | |
|---|---|
| [GitHub Releases](https://github.com/shardulvs/xnotes-android/releases/latest) | Signed APK |
| [F-Droid](https://f-droid.org/en/packages/com.xnotes) | Built reproducibly from source |
| [Google Play](https://play.google.com/store/apps/details?id=com.xnotes) | Automatic updates |

GitHub and F-Droid ship the same signed APK, so you can switch between them without reinstalling.

## Build from source

Needs JDK 17, NDK 27.0.12077973 and CMake 3.22.1.

```bash
git clone https://github.com/shardulvs/xnotes-android.git
cd xnotes-android
JAVA_HOME=/path/to/jdk-17 ./gradlew assembleDebug
```
