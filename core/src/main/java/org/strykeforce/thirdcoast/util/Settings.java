package org.strykeforce.thirdcoast.util;

import com.moandjiezana.toml.Toml;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
public class Settings {

  private static final Logger logger = LoggerFactory.getLogger(Settings.class);
  private static final String DEFAULTS = "/META-INF/thirdcoast/defaults.toml";

  private final Toml toml;
  private final Toml defaults;

  @Inject
  public Settings(File config) {
    defaults = defaults();
    if (Files.notExists(config.toPath())) {
      logger.warn("{} is missing, using defaults in " + DEFAULTS, config);
      toml = defaults;
      return;
    }

    this.toml = new Toml(defaults).read(config);
    logger.info("reading settings from {}", config);
  }

  public Settings(String tomlString) {
    defaults = defaults();
    toml = new Toml(defaults).read(tomlString);
  }

  public Settings() {
    this("");
  }

  /**
   * Get a table from the settings, with shallow merging.
   *
   * @param key the table key
   * @return the merged table
   */
  public Toml getTable(String key) {
    if (!toml.contains(key)) {
      logger.error("table with key '{}' not present", key);
      return new Toml();
    }
    Toml table = toml.getTable(key);
    Toml defaultTable = defaults.getTable(key);

    return new Toml(defaultTable).read(table);
  }

  public List<Toml> getTables(String key) {
    if (!toml.contains(key)) {
      logger.error("table with key '{}' not present", key);
      return Collections.emptyList();
    }
    return toml.getTables(key);
  }

  private Toml defaults() {
    InputStream in = this.getClass().getResourceAsStream(DEFAULTS);
    return new Toml().read(in);
  }
}
