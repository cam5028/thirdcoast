package org.strykeforce.thirdcoast.swerve;

import static org.strykeforce.thirdcoast.swerve.SwerveDrive.DriveMode.OPEN_LOOP;

import com.ctre.phoenix.ErrorCode;
import com.ctre.phoenix.motorcontrol.ControlMode;
import com.ctre.phoenix.motorcontrol.can.TalonSRX;
import com.moandjiezana.toml.Toml;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.strykeforce.thirdcoast.swerve.SwerveDrive.DriveMode;
import org.strykeforce.thirdcoast.talon.Errors;
import org.strykeforce.thirdcoast.talon.TalonConfiguration;
import org.strykeforce.thirdcoast.talon.Talons;
import org.strykeforce.thirdcoast.util.Settings;

/**
 * Controls a swerve drive wheel azimuth and drive motors. The azimuth and drive Talons are
 * configured using {@link TalonConfiguration} named "azimuth" and "drive", respectively.
 *
 * <p>The swerve-drive inverse kinematics algorithm will always calculate individual wheel angles as
 * -0.5 to 0.5 rotations, measured clockwise with zero being the straight-ahead position. Wheel
 * speed is calculated as 0 to 1 in the direction of the wheel angle.
 *
 * <p>This class will calculate how to implement this angle and drive direction optimally for the
 * azimuth and drive motors. In some cases it makes sense to reverse wheel direction to avoid
 * rotating the wheel azimuth 180 degrees.
 *
 * <p>Hardware assumed by this class includes a CTRE magnetic encoder on the azimuth motor and no
 * limits on wheel azimuth rotation. Azimuth Talons have an ID in the range 0-3 with corresponding
 * drive Talon IDs in the range 10-13.
 */
public class Wheel {

  private static final String TABLE = "THIRDCOAST.WHEEL";
  private static final Logger logger = LoggerFactory.getLogger(Wheel.class);

  private final double kTicksPerRevolution;
  private final double kDriveSetpointMax;
  private final ControlMode kAzimuthControlMode;
  private final ControlMode kDriveOpenLoopControlMode;
  private final ControlMode kDriveClosedLoopControlMode;

  private final TalonSRX azimuthTalon;
  private final TalonSRX driveTalon;
  private final int[] profileSlotSetpoint = new int[4];
  private double azimuthSetpoint;
  private double driveSetpoint;
  private ControlMode driveControlMode;

  /**
   * This designated constructor constructs a wheel by supplying azimuth and drive talons. They are
   * initialized with Talon configurations named "azimuth" and "drive" respectively.
   *
   * @param settings the settings from TOML config file
   * @param azimuth the configured azimuth TalonSRX
   * @param drive the configured drive TalonSRX
   */
  public Wheel(Settings settings, TalonSRX azimuth, TalonSRX drive) {

    Toml toml = settings.getTable(TABLE);
    kTicksPerRevolution = (double) toml.getLong("ticksPerRevolution");
    kDriveSetpointMax = (double) toml.getLong("driveSetpointMax");
    kAzimuthControlMode = ControlMode.valueOf(toml.getString("azimuthControlMode"));
    kDriveOpenLoopControlMode = ControlMode.valueOf(toml.getString("driveOpenLoopControlMode"));
    kDriveClosedLoopControlMode = ControlMode.valueOf(toml.getString("driveClosedLoopControlMode"));

    azimuthTalon = azimuth;
    driveTalon = drive;

    setDriveMode(OPEN_LOOP);

    logger.debug("azimuth = {} drive = {}", azimuthTalon.getDeviceID(), driveTalon.getDeviceID());
    logger.debug("ticksPerRevolution = {}", kTicksPerRevolution);
    logger.debug("driveSetpointMax = {}", kDriveSetpointMax);
    logger.debug("azimuthControlMode = {}", kAzimuthControlMode);
    logger.debug("driveOpenLoopControlMode = {}", kDriveOpenLoopControlMode);
    logger.debug("driveClosedLoopControlMode = {}", kDriveClosedLoopControlMode);
  }

  /**
   * Convenience constructor for a wheel by specifying the swerve driveTalon wheel number (0-3).
   *
   * @param talons the TalonFactory used to create Talons
   * @param settings the settings from TOML config file
   * @param index the wheel number
   */
  public Wheel(Talons talons, Settings settings, int index) {
    this(settings, talons.getTalon(index), talons.getTalon(index + 10));
  }

  /**
   * This method calculates the optimal driveTalon settings and applies them.
   *
   * <p>The drive setpoint is scaled by the drive Talon {@code setpoint_max} parameter configured in
   * {@link TalonConfiguration}. For instance, with an open-loop {@code setpoint_max = 12.0} volts,
   * a drive setpoint of 1.0 would result in the drive Talon being set to 12.0.
   *
   * @param azimuth -0.5 to 0.5 rotations, measured clockwise with zero being the wheel's zeroed
   *     position
   * @param drive 0 to 1.0 in the direction of the wheel azimuth
   */
  public void set(double azimuth, double drive) {
    if (driveControlMode == kDriveOpenLoopControlMode) {
      driveSetpoint = drive;
    } else {
      driveSetpoint = drive * kDriveSetpointMax;
      selectProfileSlot();
    }
    azimuth = -azimuth; // azimuth hardware configuration dependent

    // don't reset wheel azimuth direction to zero when returning to neutral
    if (driveSetpoint == 0) {
      driveTalon.set(driveControlMode, 0);
      return;
    }

    double azimuthPosition = azimuthTalon.getSelectedSensorPosition(0);
    double azimuthError =
        Math.IEEEremainder(azimuth * kTicksPerRevolution - azimuthPosition, kTicksPerRevolution);
    if (Math.abs(azimuthError) > 0.25 * kTicksPerRevolution) {
      azimuthError -= Math.copySign(0.5 * kTicksPerRevolution, azimuthError);
      driveSetpoint = -driveSetpoint;
    }
    azimuthSetpoint = azimuthPosition + azimuthError;

    azimuthTalon.set(kAzimuthControlMode, azimuthSetpoint);
    driveTalon.set(driveControlMode, driveSetpoint);
  }

  void selectProfileSlot() {}

  /**
   * Set the drive mode
   *
   * @param driveMode the drive mode
   */
  public void setDriveMode(DriveMode driveMode) {
    switch (driveMode) {
      case OPEN_LOOP:
        driveControlMode = kDriveOpenLoopControlMode;
        break;
      case CLOSED_LOOP:
        driveControlMode = kDriveClosedLoopControlMode;
        break;
    }
  }

  /**
   * Stop azimuth and drive movement. This resets the azimuth setpoint and relative encoder to the
   * current position in case the wheel has been manually rotated away from its previous setpoint.
   */
  public void stop() {
    azimuthSetpoint = azimuthTalon.getSelectedSensorPosition(0);
    azimuthTalon.set(kAzimuthControlMode, azimuthSetpoint);
    driveTalon.set(driveControlMode, 0);
  }

  /**
   * Set the azimuthTalon encoder relative to wheel zero alignment position.
   *
   * @param zero encoder position (in ticks) where wheel is zeroed.
   */
  public void setAzimuthZero(int zero) {
    azimuthSetpoint = (double) (getAzimuthAbsolutePosition() - zero);
    ErrorCode e = azimuthTalon.setSelectedSensorPosition((int) azimuthSetpoint, 0, 10);
    Errors.check(e, logger);
    azimuthTalon.set(kAzimuthControlMode, azimuthSetpoint);
  }

  /**
   * Return the azimuth position setpoint. Note this may differ from the actual position if the
   * wheel is still rotating into position.
   *
   * @return azimuth setpoint
   */
  public double getAzimuthSetpoint() {
    return azimuthSetpoint;
  }

  //  /**
  //   * Return the drive speed setpoint. Note this may differ from the actual speed if the wheel is
  //   * accelerating.
  //   *
  //   * @return speed setpoint
  //   */
  //  public double getDriveSetpoint() {
  //    return driveSetpoint;
  //  }

  /**
   * Indicates if the wheel has reversed drive direction to optimize azimuth rotation.
   *
   * @return true if reversed
   */
  public boolean isDriveReversed() {
    return driveSetpoint < 0;
  }

  /**
   * Returns the wheel's azimuth absolute position in encoder ticks.
   *
   * @return 0 - 4095 encoder ticks
   */
  public int getAzimuthAbsolutePosition() {
    return azimuthTalon.getSensorCollection().getPulseWidthPosition() & 0xFFF;
  }

  /**
   * Get the azimuth Talon controller.
   *
   * @return azimuth Talon instance used by wheel
   */
  public TalonSRX getAzimuthTalon() {
    return azimuthTalon;
  }

  /**
   * Get the drive Talon controller.
   *
   * @return drive Talon instance used by wheel
   */
  public TalonSRX getDriveTalon() {
    return driveTalon;
  }

  /**
   * Get encoder ticks per Azimuth revolution.
   *
   * @return number of encoder ticks
   */
  public int getTicksPerRevolution() {
    return (int) kTicksPerRevolution;
  }

  public double getDriveSetpointMax() {
    return kDriveSetpointMax;
  }

  public ControlMode getAzimuthControlMode() {
    return kAzimuthControlMode;
  }

  public ControlMode getDriveOpenLoopControlMode() {
    return kDriveOpenLoopControlMode;
  }

  public ControlMode getDriveClosedLoopControlMode() {
    return kDriveClosedLoopControlMode;
  }

  @Override
  public String toString() {
    return "Wheel{"
        + "azimuthTalon="
        + azimuthTalon
        + ", driveTalon="
        + driveTalon
        + ", kDriveSetpointMax="
        + kDriveSetpointMax
        + '}';
  }
}
