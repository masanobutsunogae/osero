package ap26.league.proxy;

import ap26.*;
import ap26.league.OfficialBoard;
import java.util.*;

/**
 * 対戦管理プロトコル (PROTOCOL.md v1.0) の JSON エンコード／デコード。
 *
 * <p>外部ライブラリに依存しない最小限の JSON パーサ＋シリアライザを内包する。
 * 取り扱うメッセージ型は固定 6 種類:
 * <ul>
 *   <li>{@link InitRequest} ⇔ {@code "init"}</li>
 *   <li>{@link InitResult} ⇔ {@code "init_ok"} / {@code "init_error"}</li>
 *   <li>{@link MoveRequest} ⇔ {@code "move"}</li>
 *   <li>{@link MoveResult} ⇔ {@code "move_ok"} / {@code "move_error"}</li>
 *   <li>{@link GameEndNotification} ⇔ {@code "game_end"}</li>
 *   <li>{@link GameEndResult} ⇔ {@code "ack"} / {@code "nack"}</li>
 * </ul>
 *
 * <h2>使い方</h2>
 * <pre>
 *   // エンコード
 *   String line = JsonCodec.encode(initRequest);
 *
 *   // 受信側
 *   String type = JsonCodec.peekType(line);
 *   if (type.equals("init")) {
 *       InitRequest req = JsonCodec.decodeInitRequest(line);
 *   }
 * </pre>
 */
public final class JsonCodec {

    private JsonCodec() {}

    // ========== Message-level encode (Java object → JSON string) ==========

    public static String encode(InitRequest req) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"type\":\"init\",");
        sb.append("\"playerClass\":").append(encStr(req.getPlayerClass())).append(',');
        sb.append("\"color\":").append(encStr(req.getColor().name())).append(',');
        sb.append("\"totalTimeMs\":").append(req.getTotalTimeMs()).append(',');
        sb.append("\"board\":");
        encodeBoard(sb, req.getInitialBoard());
        sb.append('}');
        return sb.toString();
    }

    public static String encode(InitResult res) {
        StringBuilder sb = new StringBuilder(64);
        if (res.isSuccess()) {
            sb.append("{\"type\":\"init_ok\",\"name\":").append(encStr(res.getPlayerName())).append('}');
        } else {
            sb.append("{\"type\":\"init_error\",\"message\":").append(encStr(nz(res.getErrorMessage()))).append('}');
        }
        return sb.toString();
    }

    public static String encode(MoveRequest req) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"type\":\"move\",\"board\":");
        encodeBoard(sb, req.getBoard());
        sb.append(",\"remainingTimeMs\":").append(req.getRemainingTimeMs());
        sb.append('}');
        return sb.toString();
    }

    public static String encode(MoveResult res) {
        StringBuilder sb = new StringBuilder(64);
        if (res.isSuccess() && res.getMove() != null) {
            sb.append("{\"type\":\"move_ok\",\"index\":").append(res.getMove().getIndex());
            sb.append(",\"thinkTimeMs\":").append(res.getThinkTimeMs()).append('}');
        } else {
            sb.append("{\"type\":\"move_error\",\"message\":").append(encStr(nz(res.getErrorMessage()))).append('}');
        }
        return sb.toString();
    }

    public static String encode(GameEndNotification not) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"type\":\"game_end\",\"finalBoard\":");
        encodeBoard(sb, not.getFinalBoard());
        sb.append(",\"winner\":").append(encStr(not.getWinner() == null ? "NONE" : not.getWinner().name()));
        sb.append(",\"reason\":").append(encStr(nz(not.getReason())));
        sb.append('}');
        return sb.toString();
    }

    public static String encode(GameEndResult res) {
        if (res.isAcknowledged()) {
            return "{\"type\":\"ack\"}";
        } else {
            return "{\"type\":\"nack\",\"message\":" + encStr(nz(res.getErrorMessage())) + "}";
        }
    }

    // ========== Message-level decode (JSON string → Java object) ==========

    public static InitRequest decodeInitRequest(String line) {
        Map<String, Object> obj = asObj(parse(line));
        String playerClass = asStr(obj.get("playerClass"));
        Color color = Color.valueOf(asStr(obj.get("color")));
        long totalTimeMs = asLong(obj.get("totalTimeMs"));
        Board board = decodeBoard(asObj(obj.get("board")));
        return new InitRequest(playerClass, color, totalTimeMs, board);
    }

    public static InitResult decodeInitResult(String line) {
        Map<String, Object> obj = asObj(parse(line));
        String type = asStr(obj.get("type"));
        if ("init_ok".equals(type)) {
            return InitResult.success(asStr(obj.get("name")));
        } else {
            return InitResult.error(asStr(obj.get("message")));
        }
    }

    public static MoveRequest decodeMoveRequest(String line) {
        Map<String, Object> obj = asObj(parse(line));
        Board board = decodeBoard(asObj(obj.get("board")));
        long remainingTimeMs = asLong(obj.get("remainingTimeMs"));
        return new MoveRequest(board, remainingTimeMs);
    }

    public static MoveResult decodeMoveResult(String line, Color color) {
        Map<String, Object> obj = asObj(parse(line));
        String type = asStr(obj.get("type"));
        if ("move_ok".equals(type)) {
            int index = (int) asLong(obj.get("index"));
            long thinkTimeMs = asLong(obj.get("thinkTimeMs"));
            Move move = Move.of(index, color);
            return new MoveResult(move, thinkTimeMs, true, null);
        } else {
            return new MoveResult(null, 0, false, asStr(obj.get("message")));
        }
    }

    public static GameEndNotification decodeGameEndNotification(String line) {
        Map<String, Object> obj = asObj(parse(line));
        Board finalBoard = decodeBoard(asObj(obj.get("finalBoard")));
        String winnerStr = asStr(obj.get("winner"));
        Color winner = (winnerStr == null) ? Color.NONE : Color.valueOf(winnerStr);
        String reason = asStr(obj.get("reason"));
        return new GameEndNotification(finalBoard, winner, reason);
    }

    public static GameEndResult decodeGameEndResult(String line) {
        Map<String, Object> obj = asObj(parse(line));
        String type = asStr(obj.get("type"));
        if ("ack".equals(type)) {
            return new GameEndResult(true, null);
        } else {
            return new GameEndResult(false, asStr(obj.get("message")));
        }
    }

    // ========== Type peek (for dispatch) ==========

    /** メッセージから {@code type} フィールドだけを取り出す (ディスパッチ用)。*/
    public static String peekType(String line) {
        Map<String, Object> obj = asObj(parse(line));
        return asStr(obj.get("type"));
    }

    // ========== Board / Move encoding ==========

    private static void encodeBoard(StringBuilder sb, Board board) {
        sb.append("{\"cells\":[");
        for (int i = 0; i < Board.LENGTH; i++) {
            if (i > 0) sb.append(',');
            sb.append(board.get(i).getValue());
        }
        sb.append(']');
        String boardId = board.getBoardId();
        if (boardId != null) {
            sb.append(",\"boardId\":").append(encStr(boardId));
        }
        Move lastMove = board.getMove();
        sb.append(",\"lastMove\":");
        encodeMove(sb, lastMove);
        sb.append('}');
    }

    private static Board decodeBoard(Map<String, Object> obj) {
        List<Object> cellList = asArr(obj.get("cells"));
        int[] cells = new int[cellList.size()];
        for (int i = 0; i < cellList.size(); i++) {
            cells[i] = (int) asLong(cellList.get(i));
        }
        String boardId = (obj.containsKey("boardId") && obj.get("boardId") != null)
            ? asStr(obj.get("boardId")) : null;
        Move lastMove = null;
        if (obj.containsKey("lastMove") && obj.get("lastMove") != null) {
            lastMove = decodeMove(asObj(obj.get("lastMove")));
        }
        return OfficialBoard.fromCells(cells, lastMove, boardId);
    }

    private static void encodeMove(StringBuilder sb, Move move) {
        if (move == null) {
            sb.append("null");
            return;
        }
        sb.append("{\"index\":").append(move.getIndex());
        sb.append(",\"color\":").append(encStr(move.getColor().name()));
        sb.append('}');
    }

    private static Move decodeMove(Map<String, Object> obj) {
        int index = (int) asLong(obj.get("index"));
        Color color = Color.valueOf(asStr(obj.get("color")));
        return new Move(index, color);
    }

    // ========== String escape ==========

    /** JSON 文字列リテラルを生成 (前後の " 含む)。*/
    private static String encStr(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    /** null を空文字に正規化 (toString のため)。*/
    private static String nz(String s) {
        return s == null ? "" : s;
    }

    // ========== Type coercion helpers ==========

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asObj(Object v) {
        if (!(v instanceof Map)) {
            throw new JsonException("expected object, got: " + v);
        }
        return (Map<String, Object>) v;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asArr(Object v) {
        if (!(v instanceof List)) {
            throw new JsonException("expected array, got: " + v);
        }
        return (List<Object>) v;
    }

    private static String asStr(Object v) {
        if (v == null) return null;
        if (v instanceof String) return (String) v;
        throw new JsonException("expected string, got: " + v);
    }

    private static long asLong(Object v) {
        if (v instanceof Long) return (Long) v;
        if (v instanceof Integer) return (Integer) v;
        if (v instanceof Number) return ((Number) v).longValue();
        throw new JsonException("expected number, got: " + v);
    }

    // ==========================================================
    //   JSON Parser (recursive descent, minimal subset)
    // ==========================================================
    //
    //   value      = object | array | string | number | true | false | null
    //   object     = "{" (string ":" value ("," string ":" value)*)? "}"
    //   array      = "[" (value ("," value)*)? "]"
    //   string     = "..." (with escape sequences: backslash + " \ n r t b f u+hex4)
    //   number     = integer or floating point (long または double)
    //
    // 制約:
    //   - サロゲートペアの unicode エスケープは限定対応 (基本多言語面のみ)
    //   - コメント等の拡張は非対応

    public static Object parse(String s) {
        Parser p = new Parser(s);
        p.skipWs();
        Object v = p.parseValue();
        p.skipWs();
        if (p.pos != s.length()) {
            throw new JsonException("extra characters after value at " + p.pos);
        }
        return v;
    }

    private static final class Parser {
        final String s;
        int pos;

        Parser(String s) {
            this.s = s;
            this.pos = 0;
        }

        void skipWs() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++;
                else break;
            }
        }

        Object parseValue() {
            skipWs();
            if (pos >= s.length()) throw new JsonException("unexpected EOF");
            char c = s.charAt(pos);
            return switch (c) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't', 'f' -> parseBool();
                case 'n' -> parseNull();
                default -> parseNumber();
            };
        }

        Map<String, Object> parseObject() {
            expect('{');
            Map<String, Object> m = new LinkedHashMap<>();
            skipWs();
            if (peek() == '}') { pos++; return m; }
            while (true) {
                skipWs();
                String key = parseString();
                skipWs();
                expect(':');
                Object val = parseValue();
                m.put(key, val);
                skipWs();
                char c = peek();
                if (c == ',') { pos++; continue; }
                if (c == '}') { pos++; return m; }
                throw new JsonException("expected , or } at " + pos);
            }
        }

        List<Object> parseArray() {
            expect('[');
            List<Object> a = new ArrayList<>();
            skipWs();
            if (peek() == ']') { pos++; return a; }
            while (true) {
                a.add(parseValue());
                skipWs();
                char c = peek();
                if (c == ',') { pos++; continue; }
                if (c == ']') { pos++; return a; }
                throw new JsonException("expected , or ] at " + pos);
            }
        }

        String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (pos < s.length()) {
                char c = s.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (pos >= s.length()) throw new JsonException("unterminated escape");
                    char e = s.charAt(pos++);
                    switch (e) {
                        case '"':  sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/':  sb.append('/'); break;
                        case 'n':  sb.append('\n'); break;
                        case 'r':  sb.append('\r'); break;
                        case 't':  sb.append('\t'); break;
                        case 'b':  sb.append('\b'); break;
                        case 'f':  sb.append('\f'); break;
                        case 'u':
                            if (pos + 4 > s.length()) throw new JsonException("bad \\u escape");
                            int code = Integer.parseInt(s.substring(pos, pos + 4), 16);
                            sb.append((char) code);
                            pos += 4;
                            break;
                        default: throw new JsonException("bad escape: \\" + e);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new JsonException("unterminated string");
        }

        Object parseNumber() {
            int start = pos;
            if (peek() == '-') pos++;
            while (pos < s.length() && isDigit(s.charAt(pos))) pos++;
            boolean isFloat = false;
            if (pos < s.length() && s.charAt(pos) == '.') {
                isFloat = true;
                pos++;
                while (pos < s.length() && isDigit(s.charAt(pos))) pos++;
            }
            if (pos < s.length() && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
                isFloat = true;
                pos++;
                if (pos < s.length() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) pos++;
                while (pos < s.length() && isDigit(s.charAt(pos))) pos++;
            }
            String num = s.substring(start, pos);
            if (isFloat) return Double.parseDouble(num);
            return Long.parseLong(num);
        }

        Boolean parseBool() {
            if (s.startsWith("true", pos)) { pos += 4; return Boolean.TRUE; }
            if (s.startsWith("false", pos)) { pos += 5; return Boolean.FALSE; }
            throw new JsonException("expected true/false at " + pos);
        }

        Object parseNull() {
            if (s.startsWith("null", pos)) { pos += 4; return null; }
            throw new JsonException("expected null at " + pos);
        }

        void expect(char c) {
            if (pos >= s.length() || s.charAt(pos) != c) {
                throw new JsonException("expected '" + c + "' at " + pos);
            }
            pos++;
        }

        char peek() {
            return pos < s.length() ? s.charAt(pos) : '\0';
        }

        boolean isDigit(char c) {
            return c >= '0' && c <= '9';
        }
    }

    /** JSON パース／変換エラー。RuntimeException 派生で利便性優先。*/
    public static final class JsonException extends RuntimeException {
        public JsonException(String msg) { super(msg); }
    }
}
