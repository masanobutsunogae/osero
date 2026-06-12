# =============================================================================
# Makefile for プログラミング応用杯 (Competition26)
#
# 使い方:
#   make            ... コンパイル (= make compile)
#   make run        ... コンパイル後にリーグを実行
#   make bench      ... コンパイル後にベンチマーク実行
#   make clean      ... bin/ 以下の .class ファイルを削除
#   make rebuild    ... clean してから全コンパイル
#
# 差分コンパイルの仕組み:
#   各 .java ファイルに対応する .class ファイルを bin/ に生成し、
#   .java が更新されたファイルだけを再コンパイルします。
# =============================================================================

JAVAC   = javac
JAVA    = java
BINDIR  = bin
MAIN    = Competition26
BENCH   = BenchmarkMatch

# すべての .java ソースを収集
SRCS    := $(shell find . -name "*.java")

# 対応する .class ファイルのパスを生成
#   例: ./ap26/Board.java -> bin/ap26/Board.class
CLASSES := $(patsubst ./%.java,$(BINDIR)/%.class,$(SRCS))

# =============================================================================
# ターゲット定義
# =============================================================================

.PHONY: all compile run bench clean rebuild

# デフォルトターゲット: 差分コンパイル
all: compile

compile: $(CLASSES)

run: compile
	$(JAVA) -cp "$(BINDIR):." $(MAIN)

bench: compile
	$(JAVA) -cp "$(BINDIR):." $(BENCH)

clean:
	rm -rf $(BINDIR)

rebuild: clean compile

# =============================================================================
# 差分コンパイルルール
#
# bin/X/Y.class が ./X/Y.java より古ければ再コンパイル。
# javac はパッケージ依存を解決するため -sourcepath で全ソースを参照可能にする。
# =============================================================================

$(BINDIR)/%.class: ./%.java
	@mkdir -p $(dir $@)
	$(JAVAC) -d $(BINDIR) -sourcepath . $<
