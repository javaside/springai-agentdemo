.PHONY: all build test run clean package

# 默认目标：构建并运行测试
all: test

# 构建（从根项目编译本模块）
build:
	mvn -pl springai-code-tui -am compile

# 运行测试
test:
	mvn -pl springai-code-tui -am test

# 打包为可执行 JAR
package:
	mvn -pl springai-code-tui -am package -DskipTests

# 运行（需要先 export DEEPSEEK_API_KEY）
run: package
	java -jar target/springai-code-tui.jar

# 清理本地构建产物
clean:
	mvn -pl springai-code-tui clean
	rm -rf target/
