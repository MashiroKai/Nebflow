---
name: geo-map
description: 地理报告（cartopy 静态+folium 交互）；地图与空间分布任务使用。
---

# geo-map — 地理·空间分布场景 skill

> 上游公共规范：`../core/SKILL.md`（铁律 / 嵌入路径 / 暗色 / 渲染器兼容 / 缺装降级总则 / 自检模板）。本文件只写 geo-map 场景增量，通用规则一律引用 core，不复制漂移。

**独立成 skill 论证（一句话）**：地理数据的表达语汇是「投影 + choropleth」——同一组数值在不同投影下面积/形状/方位关系完全不同，区划底色分级承载「区域对比」语义，通用图表库无法表达这些 GIS 语义，必须由 cartopy/folium 专业工具链承接。

## 触发条件

命中任一即用本场景骨架交付：

- **区域对比**：按国家/省州等行政区着色的分级统计图（choropleth），如人口/密度/销量分布；
- **位置分布**：点位地图（城市/站点/事件标记、坐标散布）；
- **地理关系**：范围示意、邻接格局、有数据源的路径/航线（无坐标数据的「示意线」不走本场景）；
- **投影议题本身**：投影对比、坐标参考（CRS）说明、经纬网图。

不命中即换场景：纯数值表格对比用 office；时序数据用 timing/office；无空间语义的数据不得硬上地图。

## 报告骨架模板（五段，顺序固定）

**需求定图 → 数据与坐标参考 → 地图图解 → 解读标注 → 数据来源与投影声明**

| 段 | 必含内容 | 硬约束 |
|---|---|---|
| 需求定图 | 图型判定：定位图 / choropleth / 密度图 / 流线，并给一句选择理由 | choropleth 只用于「行政区划 + 区划口径统计值」；无区划属性数据不得硬上 choropleth |
| 数据与坐标参考 | 几何数据源与分辨率、属性字段口径、CRS/投影 | 数据缓存失败改用离线形态并如实写明，不空转、不硬上（core §5） |
| 地图图解 | 底图 + 图层 + 图例 + 经纬网/比例尺，单文档 ≤3 图（core §1.3） | 全部专业工具真实渲染落盘；交互页与静态页分工按下述工具链执行 |
| 解读标注 | 区域对比结论，引用图内图例口径 | 数值逐字（core §1.2），结论必须能从图中读出 |
| 数据来源与投影声明 | NE 版本/分辨率、投影名、生成时刻、瓦片联网口径 | 缺本段不算 geo-map 交付 |

骨架即验收单：缺任一段或段序颠倒，不算 geo-map 交付。

## 工具链与自检

### 工具选择

- **cartopy（静态投影地图，离线主交付形态）**：Robinson/PlateCarree 等投影、choropleth、海岸线/国界图层、经纬网全覆盖，matplotlib 后端渲染 PNG/SVG；
- **folium（交互地图）**：缩放/标记弹层/图层切换的网页 HTML；**瓦片与 Leaflet CDN 需联网加载，离线场景受限**——离线打开仅有标记点、无底图（页面壳 JS/CSS 走 jsdelivr 等 CDN）；
- matplotlib / numpy 为底座（已装，core §7）；装包只进 viz-venv（core §5）。

### 工具自检（实测 2026-10-04）

| 工具 | 用途 | 本机检测命令（实测） | 官方下载链接 | 链接验证记录 | 缺装降级策略 |
|---|---|---|---|---|---|
| cartopy | 静态投影地图 / choropleth | `<venv>\python.exe -c "import cartopy; print(cartopy.__version__)"` → 0.26.0 | https://cartopy.readthedocs.io/ · https://scitools.org.uk/cartopy/docs/latest/ | 200 · 200（2026-10-04 实测；readthedocs 的 /en/stable、/en/latest 子路径实测 404，引用只走根路径） | folium 顶交互位；再缺则文字表格（经纬度+数值），禁止手画 |
| folium | 交互网页地图 | `<venv>\python.exe -c "import folium; print(folium.__version__)"` → 0.20.0 | https://python-visualization.github.io/folium/ · https://pypi.org/project/folium/ | 200 · 200（2026-10-04 实测） | **cartopy 静态 PNG 顶交付位**（见降级链） |
| Natural Earth 数据 | 海岸线/国界/行政区几何 | `ls ~\.local\share\cartopy\shapefiles\natural_earth\` → physical + cultural 在盘（见下） | https://www.naturalearthdata.com/ · 下载端点 https://naturalearth.s3.amazonaws.com/110m_cultural/ne_110m_admin_0_countries.zip | 200 · 200（2026-10-04 实测） | 用已缓存图层降级；全缺则文字表格 |
| matplotlib | cartopy 底座渲染 | 已装 3.11.2 | 见套件 README 链接验证表 | 200（2026-10-04，README 表） | plotly（已装）或数值表 |

本机数据缓存现状（实测 2026-10-04）：`C:\Users\Kai\.local\share\cartopy\shapefiles\natural_earth\` 下 physical（110m coastline/land、50m coastline）+ cultural（110m admin_0_countries，180,924 B，本日补齐）。瓦片实测（同日）：tile.openstreetmap.org HEAD 与 GET 均超时；镜像 `https://tile.openstreetmap.de/{z}/{x}/{y}.png` 修正 301 后 GET 出真瓦片（256×256 PNG）；CartoDB 直连 `basemaps.cartocdn.com` GET 出真瓦片但 folium 0.20 内建名触发 API-key 警告（库口径按需 key，不依赖）；folium 页面依赖 CDN（jsdelivr/cdnjs/jquery 六端点）全部 200。

### 降级链

- **folium 交互页不可用（无网络 / CDN 失效 / 瓦片超时）⇒ cartopy 静态 PNG 为交付口径**：交互 HTML 可附带，但必须显著注明「底图需联网加载」，不得以交互页替代静态图作为唯一交付；
- cartopy 数据某图层缓存失败 ⇒ 用已缓存图层降级（如无国界则海岸线+点位）并注明缺失层；全部缺失 ⇒ 文字表格（经纬度+数值），不空转；
- cartopy 与 folium 均缺装 ⇒ 文字表格（core §5），禁止手画 SVG；
- 投影拿不准 ⇒ 用 PlateCarree（等经纬）并声明，不用未声明的投影。

## 质量红线

1. **投影必声明**：图题或声明段写投影名与理由；choropleth 慎用墨卡托（高纬面积失真放大视觉对比），默认 Robinson / PlateCarree；
2. **choropleth 必带图例**：分级边界数值逐字（core §1.2），无数据区灰色并在图例标注（如 `POP_EST = -99`）；
3. **方向与范围**：经纬网或指北针至少其一；地图范围适配主题（全球议题不出区域特写）；
4. **交互页联网口径必注明**：folium HTML 首屏显著声明「瓦片/CDN 需联网，离线仅标记无底图」，并写明生成时点瓦片实测结论；
5. **数据缓存失败如实声明**：改离线形态交付，不空转、不硬上（core §5）；
6. **边界正确性由数据源承担**：行政边界一律来自 Natural Earth 等权威数据，不手绘、不改边界（敏感区域以数据源口径引用并注明版本）；
7. 禁手画 SVG、单文档 ≤3 图、marked v12 兼容（core §1.3/§4：`## ` 带空格、表分隔行列数==表头列数、段间空行）。

## 示例

- `../demos/geo-map.md` —— 双形态交付小样：cartopy 世界人口 choropleth（Robinson 投影，110m 海岸线+国界+分级图例，TOP5 数值逐字）+ folium 东亚城市交互页（双底图图层、联网口径横幅、生成时点瓦片实测注明）。
