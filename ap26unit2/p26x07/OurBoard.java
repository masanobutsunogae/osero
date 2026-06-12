package p26x07;

import static ap26.Color.*;

import ap26.*;
import java.util.ArrayList;
import java.util.List;

public class OurBoard implements Board, Cloneable {
  private long blackBoard;
  private long whiteBoard;
  private long blockBoard;
  Move move = Move.ofPass(NONE);

  private static final long LEFT_EDGE = 0x041041041L;
  private static final long RIGHT_EDGE = 0x820820820L;
  private static final long SAFE_LEFT = ~LEFT_EDGE;
  private static final long SAFE_RIGHT = ~RIGHT_EDGE;
  private static final long ALL_MASK = (1L << LENGTH) - 1L;

  private static final int[] SHIFTS = {1, 5, 6, 7};
  private static final long[] POS_MASKS = {SAFE_RIGHT, SAFE_LEFT, ~0L, SAFE_RIGHT};
  private static final long[] NEG_MASKS = {SAFE_LEFT, SAFE_RIGHT, ~0L, SAFE_LEFT};

  public OurBoard() {
    blackBoard = 0;
    whiteBoard = 0;
    blockBoard = 0;
    init();
  }

  OurBoard(long blackBoard, long whiteBoard, long blockBoard, Move move) {
    this.blackBoard = blackBoard;
    this.whiteBoard = whiteBoard;
    this.blockBoard = blockBoard;
    this.move = move;
  }

  public OurBoard clone() {
    return new OurBoard(this.blackBoard, this.whiteBoard, this.blockBoard, this.move);
  }

  void init() {
    set(Move.parseIndex("c3"), BLACK);
    set(Move.parseIndex("d4"), BLACK);
    set(Move.parseIndex("d3"), WHITE);
    set(Move.parseIndex("c4"), WHITE);
  }

  public Color get(int k) {
    if (((blackBoard >> k) & 1) == 1) return BLACK;
    else if (((whiteBoard >> k) & 1) == 1) return WHITE;
    else if (((blockBoard >> k) & 1) == 1) return BLOCK;
    else return NONE;
  }

  public Move getMove() {
    return this.move;
  }

  public Color getTurn() {
    return this.move.isNone() ? BLACK : this.move.getColor().flipped();
  }

  public void set(int k, Color color) {
    long mask = 1L << k;
    blackBoard &= ~mask;
    whiteBoard &= ~mask;
    blockBoard &= ~mask;

    if (color == BLACK) blackBoard |= mask;
    else if (color == WHITE) whiteBoard |= mask;
    else if (color == BLOCK) blockBoard |= mask;
  }

  public boolean equals(Object otherObj) {
    if (otherObj instanceof OurBoard) {
      var other = (OurBoard) otherObj;
      return this.blackBoard == other.blackBoard
          && this.whiteBoard == other.whiteBoard
          && this.blockBoard == other.blockBoard;
    }
    return false;
  }

  public String toString() {
    return OurBoardFormatter.format(this);
  }

  public int count(Color color) {
    if (color == BLACK) return Long.bitCount(blackBoard);
    else if (color == WHITE) return Long.bitCount(whiteBoard);
    else if (color == BLOCK) return Long.bitCount(blockBoard);
    else return Long.bitCount(~(blackBoard | whiteBoard | blockBoard) & ALL_MASK);
  }

  public boolean isEnd() {
    var lbs = findNoPassLegalIndexes(BLACK);
    var lws = findNoPassLegalIndexes(WHITE);
    return lbs.size() == 0 && lws.size() == 0;
  }

  public Color winner() {
    var v = score();
    if (isEnd() == false || v == 0) return NONE;
    return v > 0 ? BLACK : WHITE;
  }

  public void foul(Color color) {
    var winner = color.flipped();
    blackBoard = 0L;
    whiteBoard = 0L;
    blockBoard = 0L;

    if (winner == BLACK) blackBoard = ALL_MASK;
    else whiteBoard = ALL_MASK;
  }

  public int score() {
    var bs = Long.bitCount(blackBoard);
    var ws = Long.bitCount(whiteBoard);
    var ns = LENGTH - bs - ws;
    int score = (int) (bs - ws);

    if (bs == 0 || ws == 0) score += Integer.signum(score) * ns;

    return score;
  }

  public List<Move> findLegalMoves(Color color) {
    return findLegalIndexes(color).stream().map(k -> new Move(k, color)).toList();
  }

  List<Integer> findLegalIndexes(Color color) {
    var moves = findNoPassLegalIndexes(color);
    if (moves.size() == 0) moves.add(Move.PASS);
    return moves;
  }

  private long getMatchedPos(long start, long opponent, int shift, long mask) {
    long matched = ((start & mask) << shift) & opponent;
    matched |= ((matched & mask) << shift) & opponent;
    matched |= ((matched & mask) << shift) & opponent;
    matched |= ((matched & mask) << shift) & opponent;
    return matched;
  }

  private long getMatchedNeg(long start, long opponent, int shift, long mask) {
    long matched = ((start & mask) >>> shift) & opponent;
    matched |= ((matched & mask) >>> shift) & opponent;
    matched |= ((matched & mask) >>> shift) & opponent;
    matched |= ((matched & mask) >>> shift) & opponent;
    return matched;
  }

  List<Integer> findNoPassLegalIndexes(Color color) {
    long p = (color == BLACK) ? blackBoard : whiteBoard;
    long o = (color == BLACK) ? whiteBoard : blackBoard;
    long none = ~(this.blackBoard | this.whiteBoard | this.blockBoard) & ALL_MASK;

    long legalMoves = 0L;

    for (int i = 0; i < 4; i++) {
      int s = SHIFTS[i];
      long pm = POS_MASKS[i];
      long nm = NEG_MASKS[i];

      long matched1 = getMatchedPos(p, o, s, pm);
      legalMoves |= ((matched1 & pm) << s) & none;

      long matched2 = getMatchedNeg(p, o, s, nm);
      legalMoves |= ((matched2 & nm) >>> s) & none;
    }

    List<Integer> moves = new ArrayList<>();
    while (legalMoves != 0L) {
      int k = Long.numberOfTrailingZeros(legalMoves);
      moves.add(k);
      legalMoves &= (legalMoves - 1L);
    }

    return moves;
  }

  public OurBoard placed(Move move) {
    var b = clone();
    b.move = move;

    if (move.isPass() || move.isNone()) return b;

    var k = move.getIndex();
    var color = move.getColor();

    long newMove = 1L << k;
    long p = (color == BLACK) ? b.blackBoard : b.whiteBoard;
    long o = (color == BLACK) ? b.whiteBoard : b.blackBoard;

    long flipPattern = 0L;

    for (int i = 0; i < 4; i++) {
      int s = SHIFTS[i];
      long pm = POS_MASKS[i];
      long nm = NEG_MASKS[i];

      long matched1 = getMatchedPos(newMove, o, s, pm);
      if (((matched1 & pm) << s & p) != 0L) {
        flipPattern |= matched1;
      }

      long matched2 = getMatchedNeg(newMove, o, s, nm);
      if (((matched2 & nm) >>> s & p) != 0L) {
        flipPattern |= matched2;
      }
    }

    if (color == BLACK) {
      b.blackBoard |= flipPattern;
      b.blackBoard |= newMove;
      b.whiteBoard &= ~flipPattern;
    } else {
      b.whiteBoard |= flipPattern;
      b.whiteBoard |= newMove;
      b.blackBoard &= ~flipPattern;
    }

    return b;
  }

  public OurBoard flipped() {
    var b = clone();

    long tmp = b.blackBoard;
    b.blackBoard = b.whiteBoard;
    b.whiteBoard = tmp;

    b.move = this.move.flipped();
    return b;
  }
}
