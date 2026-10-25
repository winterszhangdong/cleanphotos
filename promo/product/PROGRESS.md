# 1.4.0 宣传片进度

- [x] 演示相册：816 项（相机照片 + 连拍组 + 全景大图 + 8 段视频 + 420 张截图 + 90 张聊天存图 + 那年今天），只推进专用模拟器 cleanphotos-promo
- [x] 实拍：1.4.0 release 包录屏 6 段（work/takes：scope / cmp / rv / tr1 / tr2 / fling3），抽帧成 60fps（work/frames）
- [x] 合成：video/index.html + timeline.js；配乐 music/music.py（代码合成，120 BPM）
- [x] 第一次渲染 → 看联系表 → 第二轮改：箭头移到手机两侧、缩放收小、标签加底衬、片尾加浮尘 / 推镜 / 扫光、换掉带录屏缩略图和撤回条的滑动素材、英文版字号
- [x] 最终渲染（12:25 UTC+8）：out/cleanphotos-1.4.0-promo.mp4（中文主版）、.en.mp4、.16x9.mp4、poster.jpg、contact-sheet.png
- [ ] 待用户：听一下配乐、确认是否要改文案（pt115u：宣传文案再抓住重点一些）
- [x] 审片修改（12:35 UTC+8 重渲中/英/16:9、海报、联系表）：重拍段和片尾去掉顶部琥珀灯条；网址打完光标闪两下后消失；「ANDROID · 免费下载」提亮到约 78% 白、28px；2–3 s 灯条和文字随 2.5 / 2.62 s 咔哒声明显闪两下；「已释放」数字 0.32 s 内落定到 231 MB（App 屏幕实值）

## v3（14:13 UTC+8 起）：去掉 13–15 s 四下重拍，总长 17.5 s
- [x] 改 index.html（14:15 UTC+8；备份 work/index.v2.bak.html、music.v2.bak.py） / timeline.js / music.py
- [x] 重渲 中 / 英 / 16:9、海报（16.5 s 帧）、联系表（14:17 UTC+8，均 17.5 s，−14.2 LUFS）
- [x] 自查 12.9–13.4 s 转场：无重拍字、无灯条、闪光压到 0.3；13.0 s 收尾重音对上片尾，17.3 s 前衰减到静音；README 分镜已更新；中文版和联系表已拷到 box（14:27 UTC+8）

## v4（14:40 UTC+8 起）：底部标签「全部 · 相似 · 大文件」出框
- 原因：底部标签 110 px 不换行，这行落定就约 1030 px 宽（屏宽 1080），入场又从 1.25 倍缩回，最宽一帧左右各出框约 100 px；英文版「Screenshots 594 MB」落定也出框
- [x] index.html：标签换字时量宽度，落定宽 > 860 px 就按比例缩字号；入场放大倍数封顶到最宽 950 px（左右各 ≥ 6%）。英文「Screenshots 594 MB」同样缩字；「放大对比 不跑位」/「Zoom stays put」入场第一帧略超 950 px，放大倍数从 1.25 微降到约 1.2；其余标签不变。`video/measure.mjs` 逐帧（1/60 s）量外框，中英全部在 78–1002 px 内（备份 work/index.v3.bak.html）
- [x] 重渲 中 / 英 / 16:9、海报、联系表（14:37 UTC+8，均 17.5 s）
- [x] 抽帧条 work/scope-caption-check-v4.png（work/strip.sh），中文版和抽帧条已拷到 box（14:45 UTC+8）

# 产品宣传片（promo/product，21:13 UTC+8 起；从 v1.4 v4 复制，v1.4 原样保留）
- [x] 复制 video/ music/ seed/ 脚本、README/PROGRESS；work/ 里 venv media shots src cap frames 软链到 v1.4（没有重录，takes/ 为空）
- [x] 去版本号 / 新功能字样（21:14 UTC+8）：3 s 标题「理照」+「口袋里的暗房 · 一张张理」；去掉 NEW 角标；「一点切换范围」→「分类一点就到」；「视频也能理 / NOW WITH VIDEO」→「照片视频 一起理 / PHOTOS & VIDEOS」；片尾去掉 v1.4.0，标语和网址上移
- [x] 状态栏（21:15 UTC+8）：不重录。原录屏状态栏贴顶边和左右边（时间在 x 45–106、y 14–47），被 62 px 屏幕圆角切掉，还带 3G。改为素材下移 24 px，顶部盖一条重画状态栏（背景取素材顶行拉伸，9:41 + Wi-Fi + 满电，左右内缩 100 px）；video/sbcheck.mjs 出特写，各段都正常
- [x] 字幕安全区（21:17 UTC+8）：底部标签上移到 top 1405（遮罩一起上移），片尾去版本号后标语 1320、网址 1440（网址 36 px）；开头计数 220 px；英文标题过宽自动缩字号，英文「SCREENSHOTS · BIG FILES · CHATS」→「SHOTS · BIG FILES · CHATS」。video/measure.mjs 逐帧量全部字幕：中英 0 处出界（x 65–1015，y ≤ 1632）
- [x] 渲染 中 / 英、海报、联系表（21:20 UTC+8，17.5 s，−14.2 LUFS，配乐沿用未改）；拷到 box

## 放慢到 ~26.5 s（21:22 UTC+8 起，用户：节奏稍快，30 s 以内）
- 做法：实拍段按真实速度多播一段（各段都在下一动作之前收住，不慢放）；动效层（镜头、标题、标签、闪光）用分段时间映射 TMAP 跟着拉长；配乐仍 120 BPM，律动段延长到 20 s，片尾 20–26.5 s
- [x] timeline.js / index.html / music.py（21:23 UTC+8；备份 work/*.p1.bak.*）
- [x] 安全区（中英 0 处出界；标签 top 1392）、状态栏特写 8 段正常、抽帧自查（21:25 UTC+8）
- [x] 渲染 中 / 英、海报（25.0 s 帧）、联系表（21:27 UTC+8，26.5 s，−14.4 LUFS）；中文版、联系表、状态栏特写已拷到 box
