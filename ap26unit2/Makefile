# =============================================================================
# Makefile for プログラミング応用杯 (Competition26)
#
# 使い方:
#   make        ... コンパイル (= make compile)
#   make run    ... コンパイル後にリーグを実行
#   make clean  ... bin/ 以下の .class ファイルを削除
# =============================================================================

JAVAC  = javac
JAVA   = java
BINDIR = bin
SRCS   = $(shell find . -name "*.java")
MAIN   = Competition26

.PHONY: all compile run clean

# デフォルトターゲット: コンパイルのみ
all: compile

# すべての .java を bin/ にコンパイル
compile:
	mkdir -p $(BINDIR)
	$(JAVAC) -d $(BINDIR) $(SRCS)

# コンパイルしてからリーグ実行
run: compile
	$(JAVA) -cp "$(BINDIR):." $(MAIN)

# 生成物を削除
clean:
	rm -rf $(BINDIR)/*.class $(BINDIR)/**/*.class
