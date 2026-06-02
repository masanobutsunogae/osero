package workPrograms;

import static ap26.Color.BLACK;
import static ap26.Color.WHITE;

import ap26.*;
import myplayer.*;

public class WorkBenchmark {
  public static void main(String args[]) {
    int depth = 5;
    if (args.length > 0) {
        depth = Integer.parseInt(args[0]);
    }

    System.out.println("Running benchmark: Depth=" + depth + " (Full Game)");
    
    // 探索ノード数をリセット（MyPlayerにLongAdderが注入されている前提）
    MyPlayer.nodeCount.reset();
    
    long startTime = System.currentTimeMillis();
    
    // 黒・白両方のプレイヤーをMyPlayerにして1局対戦させる
    Player blackPlayer = new MyPlayer("Black", BLACK, depth);
    Player whitePlayer = new MyPlayer("White", WHITE, depth);
    
    Board board = new MyBoard();
    MyGame game = new MyGame(board, blackPlayer, whitePlayer);
    game.play();
    
    long endTime = System.currentTimeMillis();
    
    long totalNodes = MyPlayer.nodeCount.sum();
    double duration = (endTime - startTime) / 1000.0;
    double nps = totalNodes / duration;

    System.out.println("Time: " + duration + "s");
    System.out.println("Nodes: " + totalNodes);
    System.out.println("NPS: " + String.format("%.2f", nps));
    System.out.println("Result: " + game.getBoard().score());
  }
}
