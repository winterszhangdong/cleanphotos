# 1.4.0 宣传片（竖版 1080×1920 @60fps，17.5 s）

实拍 + 动效合成：1.4.0 release 包在专用模拟器 `cleanphotos-promo`（API 36，无窗口启动）上录屏，HTML 舞台逐帧合成，配乐由代码合成。

```bash
# 1. 演示相册（只推进 cleanphotos-promo 模拟器）
cd promo/v1.4/seed && ../work/venv/bin/python gen.py && node shots.mjs 160 && ./push.sh
# 2. 录屏：adb -s emulator-5560 shell screenrecord … /data/local/tmp/<take>.mp4（别录到 /sdcard，会被扫进相册）
#    抽帧：ffmpeg -i work/takes/<take>.mp4 -vf fps=60:start_time=0 -q:v 3 work/frames/<take>/%05d.jpg
# 3. 渲染
cd promo/v1.4/video && node render.mjs            # → out/cleanphotos-1.4.0-promo.mp4
node render.mjs --lang en                          # → out/cleanphotos-1.4.0-promo.en.mp4
node render.mjs --still 4.3,8.6                    # 单帧检查 → work/stills/
node measure.mjs                                   # 逐帧量底部标签外框，出左右 6% 安全边会标 OUT
../work/finish.sh                                  # 中 + 英 + 16:9 + 海报 + 联系表
```

- 剪辑表、触点：`video/timeline.js`；画面：`video/index.html`（`?t=` 停帧，`?lang=en`）。
- 配乐：`music/music.py`，numpy 纯合成，120 BPM，与时间轴同一套节点；无采样、无第三方素材。
- 照片：Unsplash License，见 `seed/CREDITS.md`。`out/`、`work/` 不入库。

## 分镜（v3，17.5 s）

| 时间 | 画面 | 声音 |
|---|---|---|
| 0–2.0 | 照片墙冲击 + 计数 | 冲击 + 计数滴答 |
| 2.0–3.0 | 关灯，琥珀灯条在 2.5 / 2.62 s 闪两下 | 断奏 + 上升 + 咔哒 |
| 3.0–4.0 | 落拍亮灯「理照 1.4」 | 落拍 |
| 4.0–7.0 | 相似照片挑着留：↑留下 / ↓移走 / 放大对比不跑位 | 律动段，切点落在重拍 |
| 7.0–9.0 | 范围行 全部·相似·大文件·截图·聊天·那年今天，「截图 594 MB」 | |
| 9.0–10.0 | 视频也能理 | |
| 10.0–11.5 | 上滑收藏（琥珀残影）/ 下滑进垃圾桶 | |
| 11.5–13.0 | 一键清空溶解 →「已释放 231 MB」 | |
| 13.0 | 手机 0.3 s 内甩出，直接接片尾（原 13–15 s 四下重拍已删） | 12.5 s 短上升 → 13 s 收尾重音 |
| 13.0–14.4 | 拍立得落下、图标、理照 / CleanPhotos、v1.4.0 | 长音 + 琶音 |
| 14.1 | 一行小字「纯本地 · 不联网 · 无广告 · 开源」 | |
| 14.4–15.2 | GitHub 网址打字，光标闪两下后 15.9 s 消失 | |
| 15.2–17.5 | 「ANDROID · 免费下载」，15.6 s 扫光，末 0.45 s 淡出 | 长音自然衰减 |
