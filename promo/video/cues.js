// 字幕：画面里烧录用它，render.mjs 也用它导出 srt / vtt。时间单位秒，和配乐节拍对齐（117 BPM，16.41s 鼓点进入）。
export const CUES = [
  [0.5, 3.6, '你的相册里，躺着 12,847 张照片。', 'There are 12,847 photos in your camera roll.'],
  [3.8, 6.3, '连拍、截图、拍糊的……大多数，再也不会被看第二眼。', 'Bursts, screenshots, blurry shots — most never get a second look.'],
  [6.6, 10.0, '关上灯，一次只看一张。', 'Turn off the lights. Look at one photo at a time.'],
  [10.8, 15.8, '理照 —— 一间装在口袋里的暗房。', 'CleanPhotos — a darkroom in your pocket.'],
  [17.0, 20.6, '喜欢的，往上一推，它就亮起琥珀色。', 'Love it? Push it up, and it glows amber.'],
  [21.0, 24.4, '每一次决定，都会记在顶部的进度条上。', 'Every decision is marked on the progress bar.'],
  [25.0, 28.6, '不想要的，往下一拉，它就退回暗处。', "Don't need it? Pull it down, and it fades into the dark."],
  [29.0, 32.6, '不弹窗，不确认，不打断。', 'No pop-ups. No confirmations. No interruptions.'],
  [33.0, 36.6, '手滑了？4 秒内点一下，原样放回。', 'Slipped? Tap Undo within 4 seconds to put it back.'],
  [37.2, 40.8, '左右翻看，上下决定。', 'Swipe sideways to browse. Up or down to decide.'],
  [41.0, 44.8, '一天的照片，一分钟就能理完。', 'A whole day of photos, sorted in a minute.'],
  [45.4, 49.0, '理完一天，送你一张小结，然后直接进入下一天。', 'Finish a day, get a recap — then jump straight into the next.'],
  [49.4, 53.0, '按日期浏览，看过哪些，它都记得。', "Browse by day. It remembers what you've already seen."],
  [53.6, 57.2, '垃圾桶里随时能恢复，真正删除前只问你一次。', 'Restore anything from the trash. It asks only once — before deleting for good.'],
  [57.6, 61.3, '不申请网络权限，照片从不离开你的手机。', 'No internet permission. Your photos never leave your phone.'],
  [61.8, 65.4, '完全开源，用 Kotlin 与 Jetpack Compose 打造。', 'Fully open source. Built with Kotlin and Jetpack Compose.'],
  [66.2, 70.6, '理照，把相册留给值得留下的照片。', 'CleanPhotos. Make room for the photos worth keeping.'],
];

export const DURATION = 71.8;
