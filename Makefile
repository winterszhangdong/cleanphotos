.PHONY: build debug release clean test unit-test android-test lint install install-release uninstall deps logcat clear-data help

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
