# 橙子课表 / OrangeOrganize — 图标资源

官网图标统一使用 **SVG**（矢量，任意缩放不糊），本目录只存 SVG。

## logo.svg
- 位置：`D:\Dannr\OrangeKeBiao\assets\icons\logo.svg`
- 规格：512×512 画布，橙色圆角底 `#9A4800` + 三条白色圆角横线（象征课表行列）
- 对应 App 内图标源文件：`app/src/main/res/drawable/ic_launcher_foreground.xml`（应用启动图标前景）+ `mipmap-anydpi-v26/ic_launcher.xml`（自适应图标容器）
- 官网引用示例：`<img src="assets/icons/logo.svg" alt="橙子课表 Logo">`（尺寸用 CSS 控制即可，无需放大/导出 PNG）

## 说明
- 图标未来若改版，改 SVG 一处即可，网站各处引用同一文件。
- 若浏览器标签页 favicon 也需图标：现代 Chrome/Edge/Firefox 支持 `favicon.svg`，可把本文件复制为 `favicon.svg` 并 `&lt;link rel="icon" type="image/svg+xml" href="...favicon.svg"&gt;`；老浏览器需 ico/png 时再另行生成。
