package workPrograms;

import static ap26.Color.BLACK;
import static ap26.Color.WHITE;

import ap26.*;
import myplayer.*;

public class calNps {
  public static void main(String args[]) {
    int depth = 2;
    while (true) {
      MyPlayer mp = new MyPlayer("myplayer", BLACK, depth);
      match(mp, new RandomPlayer(WHITE));

      long nodeCount = mp.getNodeCount();
      double seconds = mp.getSeconds();
      double nps = nodeCount / seconds;
      System.out.printf(
          "depth: %d, count: %d, second: %f, nps: %f\n", depth, nodeCount, seconds, nps);

      if (seconds > 60) break;

      depth++;
    }
  }

  static Player match(Player player1, Player player2) {
    Board board = new MyBoard();
    MyGame game = new MyGame(board, player1, player2);
    game.play();
    return game.getWinner(game.getBoard());
  }
}

/*
Player player1 = new myplayer.RandomPlayer(BLACK);
Player player2 = new myplayer.MyPlayer("player" + i, WHITE, depth);
Board board = new MyBoard();
MyGame game = new MyGame(board, player1, player2);
        Player player1 = new myplayer.MyPlayer("player" + i, BLACK, depth);
        Player player2 = new myplayer.RandomPlayer(WHITE);
*/
