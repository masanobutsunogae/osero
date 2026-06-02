#!/bin/bash
TARGETS=("65ac692" "bitboard" "multithread")
LABELS=("Base (65ac692)" "Bitboard" "Multithread")

DEPTH=5

echo "Performance Comparison (NPS Reporting - Full Game)"
echo "Parameters: Depth=$DEPTH"
echo "------------------------------------------------"

for i in "${!TARGETS[@]}"; do
  TARGET="${TARGETS[$i]}"
  LABEL="${LABELS[$i]}"
  
  echo -n "Testing $LABEL ... "
  
  # サーバー上のGitリポジトリでブランチ切り替え
  git checkout "$TARGET" --quiet
  
  # Python script to patch MyPlayer.java (NPS計測用のコード注入)
  cat > patch.py << 'EOF'
import sys
import re

content = sys.stdin.read()

# Add import
if "import java.util.concurrent.atomic.LongAdder;" not in content:
    content = content.replace("import java.util.List;", "import java.util.List;\nimport java.util.concurrent.atomic.LongAdder;")

# Add nodeCount field
if "public static LongAdder nodeCount" not in content:
    field_code = "\n  public static LongAdder nodeCount = new LongAdder();\n"
    content = re.sub(r"(public class MyPlayer extends ap26\.Player \{)", r"\1" + field_code, content)

# Add increment calls
content = re.sub(r"(float (max|min)Search\(Board currentBoard, float alpha, float beta, int depth\) \{)", r"\1\n    nodeCount.increment();", content)

sys.stdout.write(content)
EOF

  python3 patch.py < ap26unit1/myplayer/MyPlayer.java > ap26unit1/myplayer/MyPlayer.java.tmp
  mv ap26unit1/myplayer/MyPlayer.java.tmp ap26unit1/myplayer/MyPlayer.java
  rm patch.py

  # コンパイル
  cd ap26unit1
  make clean > /dev/null 2>&1
  javac ap26/*.java
  javac myplayer/*.java workPrograms/WorkBenchmark.java
  
  if [ $? -eq 0 ]; then
    # 実行 (引数はDepthのみ)
    RESULT=$(java workPrograms.WorkBenchmark $DEPTH)
    TIME=$(echo "$RESULT" | grep "Time:" | awk '{print $2}')
    NODES=$(echo "$RESULT" | grep "Nodes:" | awk '{print $2}')
    NPS=$(echo "$RESULT" | grep "NPS:" | awk '{print $2}')
    
    echo "Time: $TIME, Nodes: $NODES, NPS: $NPS"
  else
    echo "Compilation Failed"
  fi
  
  cd ..
  # 切り替えたファイルを元に戻す
  git checkout ap26unit1/myplayer/MyPlayer.java --quiet
done

# 最後に元のブランチに戻る
git checkout bitboard --quiet
echo "------------------------------------------------"
echo "Benchmark Finished."
