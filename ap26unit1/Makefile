# Java Makefile

# コンパイラと実行コマンド
JAVAC = javac
JAVA = java

# ソースファイルとメインクラス
SOURCES := $(shell find . -name "*.java")
MAIN_CLASS = myplayer.MyGame

# デフォルトターゲット
all: compile

# コンパイル
compile:
	$(JAVAC) $(SOURCES)

# 実行
run: compile
	$(JAVA) $(MAIN_CLASS)

# クラスファイルの削除
clean:
	find . -name "*.class" -delete

.PHONY: all compile run clean
