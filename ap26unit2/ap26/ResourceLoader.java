package ap26;

import java.io.*;
import java.nio.charset.StandardCharsets;

/**
 * 学習済みモデル等の<b>静的リソースファイル</b>を読み込むためのユーティリティ。
 *
 * <p><b>2026 年度プログラミング応用杯のルール</b>では、学生プレイヤーが
 * リソースファイルを利用する場合、本クラス経由でのみ読み込むことが許可される。
 * {@link java.io.FileInputStream} や {@link java.nio.file.Files} 等の
 * ファイル I/O API を学生コードから直接使うことは禁止される。
 *
 * <h2>設計の意図</h2>
 * <ul>
 *   <li>ファイル経由でのプロセス間通信 (カンニング) を構造的に排除するため、
 *       書き込み系 API を完全に塞ぐ。本クラスは {@link InputStream} のみ
 *       提供し、書き込み手段を一切持たない。</li>
 *   <li>学生コードは {@code java.io} / {@code java.nio} のファイル系 API を
 *       直接 import しなくてよいため、提出物の禁止 API 検査が単純になる
 *       (検査対象は本クラス以外の I/O 利用のみで済む)。</li>
 *   <li>リソースの配置場所と検索方法を 1 箇所に集約することで、規約と
 *       実装の整合性を確保する。</li>
 * </ul>
 *
 * <h2>配置規約</h2>
 * リソースファイルは次の場所に置く:
 * <pre>
 *   src/&lt;your-package&gt;/resources/&lt;filename&gt;
 *
 *   例: src/p26x42/resources/model.bin
 *       src/p26x42/resources/eval_weights.csv
 * </pre>
 * 合計サイズは <b>10 MB 以下</b> とする (詳細は document.tex を参照)。
 *
 * <h2>使い方</h2>
 * <pre>
 *   // バイト列として読み込む
 *   byte[] model = ResourceLoader.readAllBytes(getClass(), "model.bin");
 *
 *   // 文字列として読み込む (UTF-8)
 *   String csv = ResourceLoader.readString(getClass(), "eval_weights.csv");
 *
 *   // ストリームとして読み込む (大きなファイル向け)
 *   try (InputStream is = ResourceLoader.open(getClass(), "model.bin")) {
 *       // 逐次処理
 *   }
 * </pre>
 *
 * <h2>検索の仕組み</h2>
 * 呼び出し元クラス {@code owner} の classpath 上の位置を基準として、
 * {@code resources/} サブディレクトリ内の指定ファイルを探す。
 * リーグシステムでは {@code src/} を classpath に含めているため、
 * {@code src/<package>/resources/<filename>} の形で配置すれば自動的に見つかる。
 *
 * <p>本クラスは {@code final} で、コンストラクタは private。インスタンス化不可。
 */
public final class ResourceLoader {

    /** インスタンス化禁止。すべて static メソッド。*/
    private ResourceLoader() {
    }

    /**
     * リソースを {@link InputStream} として開く。
     *
     * <p>呼び出し側は使用後に必ず {@link InputStream#close() close} すること
     * (try-with-resources の使用を強く推奨)。
     *
     * @param owner 呼び出し元クラス (通常 {@code getClass()} を渡す)
     * @param name  リソースファイル名 (例: "model.bin")。
     *              "/" で始まる絶対パスや ".." を含む相対パスは禁止。
     *              サブディレクトリを含むパス ("subdir/file.bin") は許可。
     * @return リソースの InputStream (呼び出し側で close すること)
     * @throws IOException リソースが見つからない場合
     * @throws IllegalArgumentException {@code name} が不正な場合
     */
    public static InputStream open(Class<?> owner, String name) throws IOException {
        if (owner == null) {
            throw new IllegalArgumentException("owner class must not be null");
        }
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Resource name must not be empty");
        }
        // 絶対パスや親ディレクトリ参照を拒否 (パッケージ外への参照を防ぐ)
        if (name.startsWith("/")) {
            throw new IllegalArgumentException(
                "Resource name must not start with '/' (use relative path): " + name);
        }
        if (name.contains("..")) {
            throw new IllegalArgumentException(
                "Resource name must not contain '..': " + name);
        }

        // owner クラスの位置から見て resources/<name> を探す
        // (classpath に src/ が含まれていれば src/<package>/resources/<name> が見つかる)
        InputStream is = owner.getResourceAsStream("resources/" + name);
        if (is == null) {
            String pkgPath = owner.getPackageName().replace('.', '/');
            throw new IOException(
                "Resource not found: " + name + "\n" +
                "  Expected location: src/" + pkgPath + "/resources/" + name + "\n" +
                "  Caller class: " + owner.getName());
        }
        // 規約 §14 で resources/ 配下の合計サイズは 10 MB 上限。
        // 防御層として、単一読み込みも 10 MB を超えると例外を投げる。
        return new LimitedInputStream(is, MAX_BYTES, name);
    }

    /** 規約 §14 の総量上限と一致する単一読み込み上限 (10 MB = 10 × 1024 × 1024)。*/
    public static final long MAX_BYTES = 10L * 1024 * 1024;

    /**
     * 累積読み込み量が {@code limit} を超えると {@link IOException} を投げる
     * 防御用ストリームラッパー。read/readNBytes 等のすべての読み込み API で有効。
     */
    private static final class LimitedInputStream extends FilterInputStream {
        private final long limit;
        private final String resourceName;
        private long readSoFar = 0;

        LimitedInputStream(InputStream in, long limit, String resourceName) {
            super(in);
            this.limit = limit;
            this.resourceName = resourceName;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b != -1) {
                readSoFar++;
                checkLimit();
            }
            return b;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            int n = super.read(buf, off, len);
            if (n > 0) {
                readSoFar += n;
                checkLimit();
            }
            return n;
        }

        private void checkLimit() throws IOException {
            if (readSoFar > limit) {
                throw new IOException(String.format(
                    "Resource '%s' exceeds the regulation limit of %d bytes (%d MB). " +
                    "See document.tex §14 'ファイル I/O とリソース'.",
                    resourceName, limit, limit / 1024 / 1024));
            }
        }
    }

    /**
     * リソースを byte 配列として一括読み込みする。
     * 小〜中サイズ (~ 数 MB まで) のリソース向け。
     *
     * @param owner 呼び出し元クラス
     * @param name  リソースファイル名
     * @return ファイル内容の全バイト
     * @throws IOException 読み込み失敗時
     */
    public static byte[] readAllBytes(Class<?> owner, String name) throws IOException {
        try (InputStream is = open(owner, name)) {
            return is.readAllBytes();
        }
    }

    /**
     * リソースを文字列として一括読み込みする (UTF-8 として解釈)。
     * テキスト形式の重みファイルや設定ファイル向け。
     *
     * @param owner 呼び出し元クラス
     * @param name  リソースファイル名
     * @return ファイル内容の文字列
     * @throws IOException 読み込み失敗時
     */
    public static String readString(Class<?> owner, String name) throws IOException {
        return new String(readAllBytes(owner, name), StandardCharsets.UTF_8);
    }
}
