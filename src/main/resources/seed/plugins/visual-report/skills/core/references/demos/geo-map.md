# geo-map demo — 世界人口 choropleth + 东亚城市交互地图

geo-map 场景双形态交付小样：cartopy 静态投影地图（离线主交付形态）+ folium 交互地图（联网形态，附实测口径）。

## 产件全表

| 产件 | 绝对路径 | 说明 |
|---|---|---|
| cartopy choropleth PNG | `C:\Users\Kai\.nebflow\plots\vrs2-geoastro\cartopy_world_pop_110m.png` | Robinson 投影，110m 海岸线+国界，177 国人口分级 |
| folium 交互地图 HTML | `C:\Users\Kai\.nebflow\plots\vrs2-geoastro\folium_eastasia_cities.html` | 双底图图层 + 6 城市标记（交互 HTML 落位副本） |
| 复现脚本 | `C:\Users\Kai\.nebflow\tmp\geoastro-probe\demo_cartopy.py` · `demo_folium.py` | viz-venv 直跑可复现 |

![世界人口分布 choropleth](~/.nebflow/plots/vrs2-geoastro/cartopy_world_pop_110m.png)

## 数据来源与投影声明

- **几何与属性数据**：Natural Earth 110m `admin_0_countries`（海岸线 `ne_110m_coastline` 同源），人口取其内置 `POP_EST` 估算字段；数据缓存 `C:\Users\Kai\.local\share\cartopy\shapefiles\natural_earth\`（physical：110m coastline/land、50m coastline；cultural：110m admin_0_countries，180,924 B，2026-10-04 补齐）。
- **投影**：Robinson（伪圆柱、面积视觉均衡），全球范围，经纬网间隔 30°。
- **分级口径**：< 10 万 / 10 万–100 万 / 100 万–1000 万 / 1000 万–1 亿 / > 1 亿；本次数据无 `POP_EST = -99` 无数据国。
- **生成时刻**：2026-10-04 23:08 UTC+8（图内右下角同款标注）。

## 图内数值逐字核对（源自 POP_EST 字段，脚本输出原文）

| 排名 | 国家（NE `NAME` 字段） | POP_EST |
|---|---|---|
| 1 | China | 1397715000 |
| 2 | India | 1366417754 |
| 3 | United States of America | 328239523 |
| 4 | Indonesia | 270625568 |
| 5 | Pakistan | 216565318 |

记录总数 177，无数据计数 0。

## folium 交互页（联网形态）与瓦片实测口径

交互页路径：`C:\Users\Kai\.nebflow\plots\vrs2-geoastro\folium_eastasia_cities.html`（folium 0.20.0，13,499 B，生成 2026-10-04 23:13 UTC+8）。页面含 6 城市标记（北京 / 上海 / 香港 / 东京 / 首尔 / 新加坡，参考坐标以弹层逐字展示）与双底图图层切换。

**⚠ 联网口径（SKILL 降级条款的本次实测）**：底图瓦片与 Leaflet CDN 需联网加载，离线打开仅有标记点、无底图。生成时点实测：主站 `tile.openstreetmap.org` HEAD 与 GET 均超时；镜像 `tile.openstreetmap.de` 经 301 修正为 `/{z}/{x}/{y}.png` 后 GET 出真瓦片（256×256 PNG，30,855 B），已作为可切换图层内建；folium 内建 CartoDB 名触发 API-key 警告（库口径按需 key），未采用；页面壳依赖 CDN（jsdelivr leaflet 1.9.3 js/css、cdnjs awesome-markers、jquery、bootstrap 系六端点）当日全部 200。**静态图（上图）为不受网络影响的主交付**。

## 链接验证记录（2026-10-04，`curl -sIL` 末位状态行）

| 链接 | 状态码 |
|---|---|
| https://www.naturalearthdata.com/ | 200 |
| https://naturalearth.s3.amazonaws.com/110m_cultural/ne_110m_admin_0_countries.zip | 200 |
| https://cartopy.readthedocs.io/ | 200（/en/stable 与 /en/latest 子路径 404，勿引） |
| https://scitools.org.uk/cartopy/docs/latest/ | 200 |
| https://pypi.org/project/cartopy/ | 200 |
| https://python-visualization.github.io/folium/ | 200 |
| https://pypi.org/project/folium/ | 200 |
| https://cdn.jsdelivr.net/npm/leaflet@1.9.3/dist/leaflet.js | 200 |
| https://cdn.jsdelivr.net/npm/leaflet@1.9.3/dist/leaflet.css | 200 |
| https://cdnjs.cloudflare.com/ajax/libs/Leaflet.awesome-markers/2.0.2/leaflet.awesome-markers.js | 200 |
| https://code.jquery.com/jquery-3.7.1.min.js | 200 |
| https://netdna.bootstrapcdn.com/bootstrap/3.0.0/css/bootstrap-glyphicons.css | 200 |
| https://tile.openstreetmap.org/5/25/13.png | HEAD 超时 / GET 超时（当日不可达） |
| https://tile.openstreetmap.de/5/25/13.png | HEAD 200；GET 真瓦片（30,855 B） |
