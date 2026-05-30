package workPrograms;

import static ap26.Color.BLACK;
import static ap26.Color.WHITE;

import ap26.*;
import myplayer.*;

public class work1a {
  public static void main(String args[]) {
    for (int depth = 0; depth < 5; depth++) {
      int win = 0, lose = 0, draw = 0;
      for (int i = 0; i < 50; i++) {
        Player winner =
            i < 25
                ? match(new MyPlayer("player" + i, BLACK, depth), new RandomPlayer(WHITE))
                : match(new RandomPlayer(BLACK), new MyPlayer("player" + i, WHITE, depth));

        if (winner instanceof MyPlayer) win++;
        else if (winner instanceof RandomPlayer) lose++;
        else draw++;
      }
      double winrate = (double) win / 50 * 100;
      System.out.println("depth = " + depth + "\nwinrate = " + winrate);
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
