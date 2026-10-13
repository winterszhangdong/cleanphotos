# AGENTS.md

## 项目说明

这是一个安卓相册清理 App。

用户可以在首页查看按日期分组的照片，并在清理页通过上下滑动快速处理照片：

- 向上滑动：收藏照片
- 向下滑动：移动到垃圾桶
- 操作后显示撤回提示
- 操作前不弹确认框

完整产品、设计、架构和实现说明见：

```text
docs/product.md：产品目标和页面定义
docs/ui-design.md：视觉、动效和逐屏 UI 规格（「暗房」）
docs/architecture.md：工程架构
docs/interaction.md：具体交互逻辑
docs/data-model.md：数据结构和接口
docs/test-plan.md: 测试设计说明
```

视觉和动效以 `docs/ui-design.md` 和 `core/design` 的 token 为准（原始设计稿只私下保存，不在仓库里）；业务行为以 `docs/*.md` 为准。

---

## 技术栈

必须使用：

- Kotlin
- Jetpack Compose
- MVVM
- UseCase
- Coroutines
- Flow
- Coil
- MediaStore
- DataStore
- Hilt
- Navigation Compose

---

## 构建与常用命令

项目根目录提供 `Makefile`，封装了日常开发常用操作：

```bash
make help          # 查看所有可用命令
make build         # 构建 debug APK
make test          # 运行单元测试
make lint          # 运行 Lint 检查
make install       # 安装到设备
make logcat        # 查看应用日志
```

完整命令列表见 `Makefile`。

---

## 架构规则

代码必须遵守以下调用链：

```text
Compose
  ↓
ViewModel
  ↓
UseCase
  ↓
Repository
  ↓
MediaStore / DataStore
```

规则：

* Compose 只负责界面渲染和事件回调。
* ViewModel 负责页面状态和业务调度。
* UseCase 负责单一业务动作。
* Repository 负责数据抽象。
* MediaStore 和 DataStore 只能在 data 层访问。
* 禁止在 Composable 中直接访问 Repository。
* 禁止在 Composable 中直接访问 MediaStore。
* 禁止把业务逻辑写进 UI 组件。

## 设计与动效约束

* 颜色语义：琥珀只表示收藏；移到垃圾桶及相关状态只用中性的退影灰；删除红只用于不可逆操作（清空、永久删除确认）。高频操作（左右切换、下滑移到垃圾桶）不能出现红色或警示色。
* 颜色、字体、间距、圆角、motion（时长、曲线、spring 参数）集中定义在 `core/design`，数值与 `docs/ui-design.md` 一致；业务代码里不写魔法数字动画参数。
* 动画值只在 `graphicsLayer {}` lambda、绘制或 layout 阶段读取，不能触发重组。
* 清理页所有手势（上下滑、左右滑、缩放、双击）放在同一个 `pointerInput` 里统一判定；清理页禁止使用 `SubcomposeAsyncImage + crossfade`。
* 遵守系统「移除动画」设置；按 API 等级降级只按动效规格里的 API 分级表来做。

## 代码规则

* 不要写过多兜底代码，预想之外的问题要直接出错，趁早暴露。

## 开始任务前

* 先读 `tasks/lessons.md`，里面记录了清理页闪烁、手势冲突等历史问题。

## 完成任务后

* 检查相关文档（`docs/*.md`）是否需要同步更新，需要就一起改。
* 检查是否误改无关文件，避免提交与当前任务不相关的变更。
* 收到用户纠正后，把对应模式补充到 `tasks/lessons.md`。

## git规则

1. 不要没经过用户允许自行提交git commit
2. commit时必须使用中文，尽可能详细描述本次提交是基于那个 plan或文档，背景，修改内容等
3. 提交说明格式如下：
   ```
   修改内容概要

   - 修改点1
   - 修改点2
   - 修改点3
   ...
   ```
