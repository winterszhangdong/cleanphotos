# Privacy Policy / 隐私说明

English | [简体中文](#简体中文)

## English

Last updated: 2026-10-13

CleanPhotos works entirely on your device.

- **No network access.** The app does not declare the `INTERNET` permission, so it cannot send or receive data over the network.
- **No analytics, ads, crash reporting or accounts.** The app includes no third-party SDKs for these purposes.
- **Permissions requested:**
  - `READ_MEDIA_IMAGES` and `READ_MEDIA_VIDEO` (Android 13+) or `READ_EXTERNAL_STORAGE` (Android 12L and below) to show your photos and videos.
  - `READ_MEDIA_VISUAL_USER_SELECTED` (Android 14+) so you can grant access to selected photos only.
  - `WRITE_EXTERNAL_STORAGE` (Android 8–10 only), requested when you first empty the trash, so the app can delete the photos you confirmed. Android 11+ shows the system delete confirmation instead.
- **What is stored, on the device only** (Jetpack DataStore, `cleanphotos_preferences`):
  - IDs of photos you favorited, moved to the in-app trash (with the time moved), or have seen;
  - whether the first-use tutorial was shown, and a count of swipe actions used to hide the direction hints;
  - the total size freed by emptying the trash, and when the trash reminder was last shown.
  These records are excluded from Android cloud backup and device transfer.
- **Your photos are never copied or uploaded.** Moving a photo to the trash only records it in the app. Photos are permanently deleted only when you empty the trash and confirm, and on Android 11+ the system shows its own delete prompt.
- Uninstalling the app or clearing its data removes all stored records.

Questions: open an issue at <https://github.com/winterszhangdong/cleanphotos/issues>.

## 简体中文

最后更新：2026-10-13

理照的所有处理都在你的设备上完成。

- **不联网**：清单没有声明 `INTERNET` 权限，App 无法通过网络收发任何数据。
- **没有统计、广告、崩溃上报或账号**：不包含这类第三方 SDK。
- **申请的权限**：
  - `READ_MEDIA_IMAGES` 和 `READ_MEDIA_VIDEO`（Android 13+）或 `READ_EXTERNAL_STORAGE`（Android 12L 及以下），用于显示你的照片和视频；
  - `READ_MEDIA_VISUAL_USER_SELECTED`（Android 14+），可以只授权部分照片；
  - `WRITE_EXTERNAL_STORAGE`（仅 Android 8–10），第一次清空垃圾桶时申请，用于删除你确认过的照片。Android 11+ 改用系统删除确认。
- **只存在本机的数据**（Jetpack DataStore，`cleanphotos_preferences`）：
  - 你收藏、移入 App 内垃圾桶（含移入时间）和看过的照片 ID；
  - 是否看过首次教学，以及用来隐藏方向提示的滑动次数；
  - 清空垃圾桶累计释放的空间大小，以及上次显示垃圾桶提醒的时间。
  这些记录不参与 Android 云备份和换机迁移。
- **照片不会被复制或上传**：移入垃圾桶只是在 App 里做记录；只有清空垃圾桶并确认后才会永久删除，Android 11+ 还会弹出系统删除授权，Android 8–10 需要先授予存储写入权限。
- 卸载 App 或清除应用数据会删除以上所有记录。

有疑问请在 <https://github.com/winterszhangdong/cleanphotos/issues> 提 issue。
