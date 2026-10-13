# 贡献

报告问题或提交改动之前先看完本文。

## 开始之前

- 先搜索已有 issue，相同的问题补在原 issue 下。
- 一个 issue 只写一件事。
- 改代码之前先在 issue 里说明要做什么，等维护者回复后再动手。

## 报告问题

用对应的 issue 表单。

缺陷写清：机型、Android 版本、复现步骤、期望结果、实际结果。

功能建议先写使用场景：谁、在什么情况下、需要什么。

## 提交改动

- 同一时间只开一个还没合并的 pull request。一个 pull request 只做一件事。
- 说明为什么改。
- 在真机或模拟器上验证，并写明机型、系统版本和操作步骤。改了界面就附上前后截图。
- 代码改动跑过 `make test`。
- 提交说明用中文：

  ```text
  修改内容概要

  - 修改点
  ```

- 调用链保持为 Compose → ViewModel → UseCase → Repository → MediaStore / DataStore。Compose 不访问 Repository 或 MediaStore。细节见 `docs/architecture.md`。

## 使用 AI

可以用 AI 写代码和改文档。

- 每一处改动你都要自己看懂，并且在设备上跑过。回答审阅时不能只说「是模型写的」。
- issue 和 pull request 的描述自己写，不要把模型输出原样贴上来。
- pull request 里注明用了哪个工具、用在哪一步。
- 不要把模型写成 `Co-authored-by`。
- 你保证这些改动可以按 Apache License 2.0 提交和再分发。

编造的问题，或一次丢来一堆 pull request，会直接关掉。

维护者在自己的改动里使用 AI，不按上面的披露格式要求自己。外部贡献从这些规则开始。

## 许可证

提交即表示你有权提交这些改动，并同意它们以 Apache License 2.0 再分发。见 [LICENSE](LICENSE)。
