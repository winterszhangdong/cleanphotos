.PHONY: build debug release clean test unit-test android-test lint install install-release uninstall deps logcat clear-data run-ios run-ios-release android-debug android-release android-install android-install-release ios-install ios-install-release ios-device verify-similar help

# 真机 UDID。不设就用当前连上的那一台；连着多台时必须指定。
IOS_DEVICE ?=

# 默认目标
help: ## 显示帮助信息
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | sort | \
		awk 'BEGIN {FS = ":.*?## "}; {printf "\033[36m%-26s\033[0m %s\n", $$1, $$2}'

# ============ 构建 ============

build: android-debug ## 同 android-debug
debug: android-debug ## 同 android-debug

android-debug: ## 构建 debug APK
	./gradlew assembleDebug

release: android-release ## 同 android-release

android-release: ## 构建 release APK
	./gradlew assembleRelease

clean: ## 清理构建产物
	./gradlew clean

# ============ 测试 ============

test: unit-test ## 运行所有本地测试

unit-test: ## 运行单元测试
	./gradlew testDebugUnitTest

android-test: ## 运行 instrumentation 测试（需要连接设备/模拟器）
	./gradlew connectedDebugAndroidTest

verify-similar: ## 相似对比下滑：单测后在 emulator-5554 上走完（不碰 5564）
	./gradlew :app:testDebugUnitTest --tests com.winters.cleanphotos.feature.similar.SimilarViewModelTest --tests com.winters.cleanphotos.feature.similar.SimilarCompareSessionTest :app:assembleDebug
	adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
	scripts/verify-similar-compare.sh

# ============ 代码质量 ============

lint: ## 运行 Android Lint 检查
	./gradlew lint

# ============ 安装/卸载 ============

install: android-install ## 同 android-install
install-release: android-install-release ## 同 android-install-release

android-install: ## 安装 debug APK 到连接的设备
	./gradlew installDebug

android-install-release: ## 安装 release APK 到连接的设备（判断动效是否流畅以它为准，debug 包会明显更卡）
	./gradlew installRelease

uninstall: ## 从设备卸载应用
	adb uninstall com.winters.cleanphotos

# ============ 依赖 ============

deps: ## 查看项目依赖树
	./gradlew :app:dependencies --configuration debugRuntimeClasspath

# ============ 实用工具 ============

logcat: ## 查看应用日志
	adb logcat --pid=$$(adb shell pidof -s com.winters.cleanphotos)

clear-data: ## 清除应用数据（含 DataStore）
	adb shell pm clear com.winters.cleanphotos

# ============ iOS 实机 ============

# Debug 或 Release。ios-install / ios-install-release 各自指定，不要在命令行改这个。
IOS_CONFIG ?= Debug

run-ios: ios-install ## 同 ios-install
run-ios-release: ios-install-release ## 同 ios-install-release

ios-install: IOS_CONFIG = Debug
ios-install: ios-device ## 编译 debug，装到已连接的 iPhone 并打开（保留本机数据）

ios-install-release: IOS_CONFIG = Release
ios-install-release: ios-device ## 编译 release，装到已连接的 iPhone 并打开（保留本机数据；判断动效以它为准）

ios-device:
	@set -euo pipefail; \
	command -v xcodegen >/dev/null || { echo "缺少 xcodegen，先 brew install xcodegen"; exit 1; }; \
	( cd ios && xcodegen generate ); \
	test -f ios/Secrets.xcconfig || { echo "缺少 ios/Secrets.xcconfig（真机签名用的 Team）"; exit 1; }; \
	device='$(IOS_DEVICE)'; \
	if [ -z "$$device" ]; then \
	  list=$$(mktemp); \
	  xcrun devicectl list devices --json-output "$$list" >/dev/null; \
	  pick=$$(python3 ios/scripts/pick-iphone.py "$$list") || exit 1; \
	  rm -f "$$list"; \
	  device=$${pick%%$$'\t'*}; \
	  name=$${pick#*$$'\t'}; \
	  echo "装到 $${name}（$(IOS_CONFIG)）"; \
	else \
	  echo "装到 $${device}（$(IOS_CONFIG)）"; \
	fi; \
	xcrun devicectl device info ddiServices --device "$$device" --timeout 30 >/dev/null; \
	( cd ios && xcodebuild -project CleanPhotos.xcodeproj -scheme CleanPhotos \
	  -configuration $(IOS_CONFIG) \
	  -destination "platform=iOS,id=$$device" \
	  -derivedDataPath build/device \
	  -allowProvisioningUpdates \
	  build ); \
	xcrun devicectl device install app --device "$$device" \
	  ios/build/device/Build/Products/$(IOS_CONFIG)-iphoneos/CleanPhotos.app; \
	xcrun devicectl device process launch --device "$$device" \
	  --terminate-existing com.winters.cleanphotos
