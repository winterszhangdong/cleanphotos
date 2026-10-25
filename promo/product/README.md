# 理照 产品宣传片（竖版 1080×1920 @60fps，26.5 s，不带版本号）

从 `promo/v1.4`（1.4.0 宣传片 v4）复制而来；`work/` 里 venv、media、shots、src、cap、frames 是指向 `../v1.4/work/` 的软链（素材不重复拷贝），v1.4 保持原样。

实拍 + 动效合成：1.4.0 release 包在专用模拟器 `cleanphotos-promo`（API 36，无窗口启动）上录屏，HTML 舞台逐帧合成，配乐由代码合成。

```bash
# 1. 演示相册（只推进 cleanphotos-promo 模拟器）
cd promo/v1.4/seed && ../work/venv/bin/python gen.py && node shots.mjs 160 && ./push.sh   # 素材沿用 v1.4 的（本片未重录）
# 2. 录屏：adb -s emulator-5560 shell screenrecord … /data/local/tmp/<take>.mp4（别录到 /sdcard，会被扫进相册）
#    抽帧：ffmpeg -i work/takes/<take>.mp4 -vf fps=60:start_time=0 -q:v 3 work/frames/<take>/%05d.jpg
# 3. 渲染
cd promo/product/video && node render.mjs         # → out/cleanphotos-promo.mp4
node render.mjs --lang en                          # → out/cleanphotos-promo.en.mp4
node render.mjs --still 4.3,8.6                    # 单帧检查 → work/stills/
node measure.mjs                                   # 逐帧量全部字幕外框，出左右 6% 或进底部 15% 会标 OUT
../work/finish.sh                                  # 中 + 英 + 海报 + 联系表（只出竖版）
node sbcheck.mjs                                   # 状态栏特写 → work/statusbar-check.png
```

- 剪辑表、触点：`video/timeline.js`；画面：`video/index.html`（`?t=` 停帧，`?lang=en`）。
- 配乐：`music/music.py`，numpy 纯合成，120 BPM，与时间轴同一套节点；无采样、无第三方素材。
- 照片：Unsplash License，见 `seed/CREDITS.md`。`out/`、`work/` 不入库。

## 分镜（26.5 s，120 BPM）

| 时间 | 画面 | 声音 |
|---|---|---|
| 0–2.0 | 照片墙冲击 + 计数「12,847 张照片，大多不会再看第二眼」 | 冲击 + 计数滴答 |
| 2.0–3.0 | 关灯：「关上灯 / 一次只看一张」，灯条 2.5 / 2.62 s 闪两下 | 断奏 + 上升 + 咔哒 |
| 3.0–5.0 | 落拍亮灯，手机落下，照片页滑动：「理照 / 口袋里的暗房 · 一张张理」 | 落拍，律动起 |
| 5.0–10.0 | 「相似照片 挑着留」：5.0 ↑留下 / 6.5 ↓移走 / 8.0 双击放大 / 9.0 放大切换不跑位 | 5 s 镲 |
| 10.0–13.0 | 「分类一点就到」：10.0 相似 / 10.75 大文件 / 11.5「截图 594 MB」 | 10 s 镲 |
| 13.0–14.5 | 「照片视频 一起理」：点开 0:12 视频 | 13 s 镲 |
| 14.5–16.0 | 上滑收藏（琥珀余影） | |
| 16.0–17.0 | 下滑进垃圾桶 | |
| 17.0–20.0 | 「一键清空」：17.0 点清空 → 17.5 溶解 → 18.25「已释放 231 MB」 | 17 s 镲，19 s 上升 |
| 20.0–21.5 | 手机甩出；拍立得、图标、理照 / CleanPhotos | 20 s 收尾重音 + 长音 |
| 21.1 | 「纯本地 · 不联网 · 无广告 · 开源」 | |
| 21.4–22.7 | GitHub 网址打字 | |
| 22.7–26.5 | 「ANDROID · 免费下载」，23.3 s 扫光，26.0 s 起淡出 | 长音衰减 |

- 时间：实拍段按真实速度播放（`timeline.js` 的 `CUTS`）；动效层按 17.5 s 版写，经 `TMAP` 分段映射拉长。改节奏只改这两张表和 `music/music.py`。
- 状态栏：录屏原状态栏贴边（被屏幕圆角切掉）且带 3G，合成时素材下移 24 px，顶部换成重画的状态栏（9:41、Wi-Fi、满电，左右内缩 100 px），背景取当帧顶行拉伸。
- 字幕安全区：左右各 6%（x 65–1015），底部 15%（y > 1632）不放字。
