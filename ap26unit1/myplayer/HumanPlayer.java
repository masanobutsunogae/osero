package myplayer;

import ap26.*;
import java.util.Scanner;

public class HumanPlayer extends Player {
  public HumanPlayer(Color color) {
    super("Human", color);
  }

  public Move think(Board board) {
    Scanner scanner = new Scanner(System.in);

    System.out.println("あなたの番です");
    System.out.println(board);
    var moves = board.findLegalMoves(getColor());
    System.out.println("以下の手から選んでください");
    for (int i = 0; i < moves.size(); i++) System.out.println(i + "... " + moves.get(i));
    int i = scanner.nextInt();
    return moves.get(i);
  }
}
