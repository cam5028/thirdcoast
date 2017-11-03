package org.strykeforce.thirdcoast.telemetry.tct;

import org.jline.terminal.Terminal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main implements Runnable {

  final static Logger logger = LoggerFactory.getLogger(Main.class);
  private final MainComponent component = DaggerMainComponent.builder().build();

  public Main() {
  }

  @Override
  public void run() {
    try {
      Menu menu = component.menu();
      menu.display();
    } catch (Throwable t) {
      logger.error("fatal error", t);
      Terminal terminal = component.terminal();
      terminal.writer().println("fatal error: " + t.getMessage());
      terminal.flush();
      System.exit(-1);
    }
  }
}
