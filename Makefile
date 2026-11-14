.PHONY: build debug release clean test unit-test android-test lint install install-release uninstall deps logcat clear-data run-ios help

# 真机 UDID。不设就用当前连上的那一台；连着多台时必须指定。
IOS_DEVICE ?=

# 默认目标
help: ## 显示帮助信息
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | sort | \
		awk 'BEGIN {FS = ":.*?## "}; {printf "\033[36m%-20s\033[0m %s\n", $$1, $$2}'

# ============ 构建 ============

build: debug ## 默认构建（debug）

debug: ## 构建 debug APK
	./gradlew assembleDebug

release: ## 构建 release APK
	./gradlew assembleRelease

clean: ## 清理构建产物
	./gradlew clean

# ============ 测试 ============

test: unit-test ## 运行所有本地测试

unit-test: ## 运行单元测试
	./gradlew testDebugUnitTest

android-test: ## 运行 instrumentation 测试（需要连接设备/模拟器）
	./gradlew connectedDebugAndroidTest

# ============ 代码质量 ============

lint: ## 运行 Android Lint 检查
	./gradlew lint

# ============ 安装/卸载 ============

install: ## 安装 debug APK 到连接的设备
	./gradlew installDebug

install-release: ## 安装 release APK 到连接的设备（判断动效是否流畅以它为准，debug 包会明显更卡）
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

run-ios: ## 编译当前代码，装到已连接的 iPhone 并打开（保留本机数据）
	@set -euo pipefail; \
	test -d ios/CleanPhotos.xcodeproj || { echo "缺少 ios/CleanPhotos.xcodeproj，先到 ios 目录执行 xcodegen generate"; exit 1; }; \
	test -f ios/Secrets.xcconfig || { echo "缺少 ios/Secrets.xcconfig（真机签名用的 Team）"; exit 1; }; \
	device='$(IOS_DEVICE)'; \
	if [ -z "$$device" ]; then \
	  list=$$(mktemp); \
	  xcrun devicectl list devices --json-output "$$list" >/dev/null; \
	  pick=$$(python3 - "$$list" <<'PY'
	import json, sys
	data = json.load(open(sys.argv[1]))
	def fields(dev):
	    props = dev.get("properties") or {}
	    hw = dev.get("hardwareProperties") or {}
	    conn = props.get("connection") or dev.get("connectionProperties") or {}
	    state = props.get("state") or dev.get("deviceProperties") or {}
	    return {
	        "name": state.get("name") or props.get("name") or "iPhone",
	        "udid": props.get("udid") or hw.get("udid") or "",
	        "reality": props.get("reality") or hw.get("reality") or "",
	        "connected": conn.get("state") == "connected",
	    }
	physical = [fields(d) for d in data["result"]["devices"]]
	physical = [d for d in physical if d["reality"] == "physical" and d["udid"]]
	online = [d for d in physical if d["connected"]]
	if len(online) == 1:
	    chosen = online[0]
	elif len(online) > 1:
	    lines = "\n".join(f"  {d['name']}  {d['udid']}" for d in online)
	    sys.exit("连着多台 iPhone，用 IOS_DEVICE=UDID 指定一台：\n" + lines)
	elif len(physical) == 1:
	    chosen = physical[0]
	elif len(physical) > 1:
	    lines = "\n".join(f"  {d['name']}  {d['udid']}" for d in physical)
	    sys.exit("没有已连接的 iPhone。用 IOS_DEVICE=UDID 指定一台：\n" + lines)
	else:
	    sys.exit("没有已配对的 iPhone。解锁手机，用数据线连接或连上同一 Wi-Fi，并在手机上点信任")
	print(chosen["udid"] + "\t" + chosen["name"])
	PY
	  ); \
	  rm -f "$$list"; \
	  device=$${pick%%$$'\t'*}; \
	  name=$${pick#*$$'\t'}; \
	  echo "装到 $$name"; \
	else \
	  echo "装到 $$device"; \
	fi; \
	xcrun devicectl device info ddiServices --device "$$device" --timeout 30 >/dev/null; \
	( cd ios && xcodebuild -project CleanPhotos.xcodeproj -scheme CleanPhotos \
	  -destination "platform=iOS,id=$$device" \
	  -derivedDataPath build/device \
	  -allowProvisioningUpdates \
	  build ); \
	xcrun devicectl device install app --device "$$device" \
	  ios/build/device/Build/Products/Debug-iphoneos/CleanPhotos.app; \
	xcrun devicectl device process launch --device "$$device" \
	  --terminate-existing com.winters.cleanphotos
