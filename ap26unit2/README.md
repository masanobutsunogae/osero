# プログラミング応用杯 配布物 (ap26unit2)

**最終更新**: 2026 年度  
**対象**: 第 2 回演習以降の参加チーム

本パッケージは、プログラミング応用杯のための対戦システム一式と、サンプル
プレイヤー実装を含みます。各チームは `p26x_dd_/` パッケージ配下に独自の
プレイヤーを実装し、参加します。

---

## 1. クイックスタート

### コンパイル

```bash
cd ap26unit2
mkdir -p bin
javac -d bin $(find . -name "*.java")
```

### 実行

```bash
java -cp "bin:." Competition26
```

デフォルトでは `p26x00.OurPlayer` と 3 体の `RandomPlayer` が NUM_BOARD=3 の
盤面で総当たり戦を実行します。

実行が長い場合（10〜30 分程度）は、`Competition26.java` の `NUM_BOARD` を
一時的に 1 に変えると短時間で動作確認できます。

### 終了後の確認

```bash
# プレイヤー JVM プロセスがゼロになっているか確認
pgrep -f "ap26.league.proxy.PlayerMain" | wc -l
```

万一残っていたら強制終了：

```bash
pkill -9 -f "ap26.league.proxy.PlayerMain"
```

---

## 2. ディレクトリ構成

```
ap26unit2/
├── Competition26.java           ← エントリポイント (改変対象、参加者を設定)
├── README.md                    ← 本ファイル
├── ap26/                        ← 共通インタフェース (改変禁止)
│   ├── Board.java
│   ├── Color.java
│   ├── Move.java
│   ├── Player.java              ← これを継承して OurPlayer を作る
│   └── ResourceLoader.java      ← 学習済みモデル等の読み込み用 (後述)
├── ap26/league/                 ← 対戦システム (改変禁止)
│   ├── Game.java
│   ├── League.java
│   ├── ProxyGame.java
│   ├── OfficialBoard.java
│   ├── OfficialBoardFormatter.java
│   └── RandomPlayer.java        ← 最弱プレイヤー (ベースライン)
├── ap26/league/proxy/           ← プロセス管理 + Socket 通信 (改変禁止)
│   └── *.java                   ← 学生は触らない
└── p26x00/                      ← サンプル学生プレイヤー (参考)
    ├── OurPlayer.java           ← think() を改良する例
    ├── OurBoard.java
    └── OurBoardFormatter.java
```

**改変禁止**: `ap26` パッケージ全体は、リーグの公平性のため一切変更してはいけません。

---

## 3. 自分のプレイヤーを作る手順

### Step 1: パッケージを作る

チーム番号が `42` の場合：

```bash
mkdir -p p26x42
```

`p26x00/` のサンプルファイルをコピーして編集するのが簡単です：

```bash
cp -r p26x00 p26x42
# パッケージ宣言と参照を p26x42 に書き換える
```

### Step 2: think() を改良する

`p26x42/OurPlayer.java` の `think(Board board)` メソッドを改良します。

最低限の雛形：

```java
package p26x42;

import ap26.*;
import static ap26.Color.*;
import java.util.*;

public class OurPlayer extends Player {
    public OurPlayer(Color color) {
        super("26AB", color);  // プレイヤー名 (ASCII 4 文字、他チームと重複しないこと)
    }

    @Override
    public void setBoard(Board board) {
        // 必要なら内部状態を初期化
    }

    @Override
    public Move think(Board board) {
        // ここに自分の探索ロジックを書く
        List<Move> moves = board.findLegalMoves(getColor());
        if (moves.isEmpty() || moves.get(0).isPass()) {
            return Move.ofPass(getColor());
        }
        return moves.get(0);  // 暫定: 最初の合法手を返す
    }
}
```

### Step 3: Competition26 に追加して試す

`Competition26.java` の `builder` 内に追加：

```java
return new Player[] {
    new p26x42.OurPlayer(color),       // 自チーム
    new p26x00.OurPlayer(color),       // サンプル
    new ap26.league.RandomPlayer(color),
    new ap26.league.RandomPlayer(color),
};
```

再コンパイル＆実行：

```bash
javac -d bin $(find . -name "*.java")
java -cp "bin:." Competition26
```

### Step 4: 改良を続ける

- 評価関数の重みを調整
- 探索深さを増やす
- α-β 枝刈り、move ordering、置換表 等を実装
- 終盤完全読み

詳細な課題は演習指示書を参照してください。

---

## 4. リソースファイル (学習済みモデル等) の使い方

評価関数の重みテーブルや学習済みニューラルネットなど、サイズの大きなデータを
コード外に持ちたい場合は、**`ap26.ResourceLoader` 経由で読み込む**ことが
義務付けられています（規約 §14）。

### 配置場所

```
p26x42/
└── resources/
    ├── weights.csv
    └── model.bin
```

### 読み込み方法

```java
import ap26.ResourceLoader;
import java.io.IOException;
import java.io.InputStream;

public class OurPlayer extends Player {
    private byte[] model;

    public OurPlayer(Color color) {
        super("26AB", color);
        try {
            // バイト配列として一括読み込み
            this.model = ResourceLoader.readAllBytes(getClass(), "model.bin");

            // 文字列 (UTF-8) として一括読み込み
            String csv = ResourceLoader.readString(getClass(), "weights.csv");

            // ストリームとして読み込み (大きなファイル向け)
            try (InputStream is = ResourceLoader.open(getClass(), "model.bin")) {
                // 逐次処理
            }
        } catch (IOException e) {
            throw new RuntimeException("リソース読み込み失敗", e);
        }
    }

    // ... think() 実装 ...
}
```

### 制約

- **合計サイズは 10 MB 以下** (規約 §14.2)
- **`java.io.File*`、`java.nio.file.Files`、`getResourceAsStream` 等の直接利用は禁止**
- **ファイルへの書き込みは一切禁止** (規約 §14.5)

詳細は演習指示書のルール §14「ファイル I/O とリソース」を参照。

---

## 5. デバッグ Tips

### ビルドエラー

`javac -encoding UTF-8 -d bin $(find . -name "*.java")` で UTF-8 を明示。

### 実行時にプレイヤープロセスが残る

```bash
pkill -9 -f "ap26.league.proxy.PlayerMain"
```

### 自分のプレイヤーが負ける

- まず `RandomPlayer` に勝てるか確認
- 次に `p26x00.OurPlayer` (探索深さ 2 の素朴な α-β) に勝てるか
- ログ出力で読み筋を可視化

### 過剰な標準出力

規約 §13.4 で 1 ゲームあたり 100 行以内が目安。デバッグ用 `System.out.println`
を残したまま提出しないこと。

### `static` フィールド

各プレイヤーは独立した JVM プロセスで動作するため、`static` フィールドが
他チームに影響することはありません。安全に使えます（同一プレイヤーが
複数試合をまたいで持つキャッシュ等も可）。ただし、規約上「ファイル経由通信」
は禁じられているため、書き込みを伴う `static` フィールドの永続化（=ファイル
書き出し）は禁止されます。

---

## 6. 規約

参加プレイヤーは演習指示書の「プログラミング応用杯のルール」に従う必要が
あります。主なポイント：

- **継承**: `ap26.Player` を継承
- **名称**: `p26xdd` パッケージ、`OurPlayer` クラス、ASCII 4 文字のプレイヤー名
- **持ち時間**: 1 ゲーム 60 秒（ゲーム単位でリセット）
- **勝ち点**: 勝 = `10 + min(石差, 10)` / 引 = 5 / 負 = 0
- **禁止事項**: 他プログラム利用・通信・`ap26` 改変・過剰な標準出力
- **ファイル I/O**: `ap26.ResourceLoader` 経由でのみ可、書き込み禁止
- **AI ツール**: 利用可、ただし口頭試問で説明できることが必須

詳細は演習指示書を参照。

---

## 7. 提出物

- ファイル名: `p26xdd.zip` (dd は 2 桁チーム番号、1 桁の場合は 0 埋め)
- 内容: `p26xdd/` 配下の全 Java ソース (`resources/` 含む)
- **`ap26` パッケージは含めないこと** (共通実装のため)
- 提出先: Moodle の指定箇所

---

## 8. よくある質問

**Q. `Competition26.java` を改変してもよいか？**  
A. はい。エントリポイントなので、参加プレイヤー設定や盤面数の調整は可です。
ただし、提出物には含めないでください（リーグ運営側で本番設定を使います）。

**Q. `p26x00` のサンプルをそのまま提出してよいか？**  
A. ダメです。`p26xdd` (自チーム番号) パッケージにコピーしてから改変してください。

**Q. 探索が遅くてタイムアウトする**  
A. 探索深さを下げる、α-β 枝刈りを実装する、評価関数を軽くする等を試してください。
1 ゲーム 60 秒のうち、思考時間は累積管理されます。

**Q. 変形盤の BLOCK にどう対応すればよいか？**  
A. `Board.get(k)` が `Color.BLOCK` を返すマスがあります。
そこには石を置けず、裏返しの対象にもなりません。
`board.findLegalMoves(color)` が返す手は BLOCK を考慮済みなので、
リストから選ぶだけなら自動的に対応されています。

---

**Good luck!**
