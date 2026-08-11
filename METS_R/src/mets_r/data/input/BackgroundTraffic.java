package mets_r.data.input;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Objects;
import java.util.TreeMap;

import au.com.bytecode.opencsv.CSVReader;
import mets_r.ContextCreator;
import mets_r.GlobalVariables;

/**
 * Reads hourly background-traffic mean/std profiles keyed by original road ID.
 * Profile values are interpreted as mph by Road, preserving the existing input
 * contract.
 */
public class BackgroundTraffic {
	public static final class BackgroundSpeedSample {
		private final int profileHour;
		private final double speed;

		private BackgroundSpeedSample(int profileHour, double speed) {
			this.profileHour = profileHour;
			this.speed = speed;
		}

		public int getProfileHour() {
			return this.profileHour;
		}

		public double getSpeed() {
			return this.speed;
		}
	}

	public static final class ProfileStateSnapshot {
		private final String eventFilePath;
		private final String stdFilePath;
		private final int profileHourOffset;
		private final int currentProfileHour;

		private ProfileStateSnapshot(String eventFilePath, String stdFilePath,
				int profileHourOffset, int currentProfileHour) {
			this.eventFilePath = eventFilePath;
			this.stdFilePath = stdFilePath;
			this.profileHourOffset = profileHourOffset;
			this.currentProfileHour = currentProfileHour;
		}

		public String getEventFilePath() {
			return this.eventFilePath;
		}

		public String getStdFilePath() {
			return this.stdFilePath;
		}

		public int getProfileHourOffset() {
			return this.profileHourOffset;
		}

		public int getCurrentProfileHour() {
			return this.currentProfileHour;
		}
	}

	private final TreeMap<String, ArrayList<Double>> backgroundSpeed =
			new TreeMap<String, ArrayList<Double>>();
	private final TreeMap<String, ArrayList<Double>> backgroundSpeedStd =
			new TreeMap<String, ArrayList<Double>>();

	private String eventFilePath;
	private String stdFilePath;
	private volatile int profileHourOffset;
	private int eventHourCount;
	private int stdHourCount;

	public BackgroundTraffic() {
		this(GlobalVariables.BT_EVENT_FILE, GlobalVariables.BT_STD_FILE,
				GlobalVariables.BT_START_HOUR);
	}

	public BackgroundTraffic(String eventFilePath, String stdFilePath, int startHour) {
		restoreProfileState(eventFilePath, stdFilePath, startHour);
	}

	public synchronized void restoreProfileState(String eventFilePath,
			String stdFilePath, int startHour) {
		if (startHour < 0) {
			throw new IllegalArgumentException("Background speed profile start hour must be non-negative: "
					+ startHour);
		}
		boolean sameFiles = Objects.equals(this.eventFilePath, eventFilePath)
				&& Objects.equals(this.stdFilePath, stdFilePath);
		this.profileHourOffset = startHour;
		if (sameFiles) {
			warnIfProfileWindowIsShort("mean", this.eventFilePath, this.eventHourCount);
			warnIfProfileWindowIsShort("standard-deviation", this.stdFilePath, this.stdHourCount);
			return;
		}

		this.eventFilePath = eventFilePath;
		this.stdFilePath = stdFilePath;
		this.eventHourCount = readProfileFile(eventFilePath, this.backgroundSpeed, "mean");
		this.stdHourCount = readProfileFile(stdFilePath, this.backgroundSpeedStd,
				"standard-deviation");
		warnIfProfileWindowIsShort("mean", eventFilePath, this.eventHourCount);
		warnIfProfileWindowIsShort("standard-deviation", stdFilePath, this.stdHourCount);
	}

	/** Re-read the configured mean profile. Retained for API compatibility. */
	public synchronized void readEventFile() {
		this.eventHourCount = readProfileFile(this.eventFilePath, this.backgroundSpeed, "mean");
		warnIfProfileWindowIsShort("mean", this.eventFilePath, this.eventHourCount);
	}

	/** Re-read the configured standard-deviation profile. */
	public synchronized void readStdFile() {
		this.stdHourCount = readProfileFile(this.stdFilePath, this.backgroundSpeedStd,
				"standard-deviation");
		warnIfProfileWindowIsShort("standard-deviation", this.stdFilePath, this.stdHourCount);
	}

	private int readProfileFile(String path,
			TreeMap<String, ArrayList<Double>> destination, String label) {
		destination.clear();
		if (path == null || path.trim().isEmpty()) return 0;
		File profileFile = new File(path);
		if (!profileFile.isFile()) {
			ContextCreator.logger.warn("Background speed " + label + " profile not found: " + path
					+ "; original lane speeds will be retained.");
			return 0;
		}

		CSVReader reader = null;
		try {
			reader = new CSVReader(new FileReader(profileFile));
			String[] header = reader.readNext();
			if (header == null || header.length <= 1) return 0;
			int hourCount = header.length - 1;
			String[] row;
			int lineNumber = 1;
			while ((row = reader.readNext()) != null) {
				lineNumber++;
				if (row.length == 0 || row[0] == null || row[0].trim().isEmpty()) continue;
				if (row.length < hourCount + 1) {
					throw new IllegalArgumentException("Background speed " + label + " profile "
							+ path + " line " + lineNumber + " has " + Math.max(0, row.length - 1)
							+ " hourly values; header declares " + hourCount + ".");
				}
				ArrayList<Double> values = new ArrayList<Double>(hourCount);
				for (int hour = 0; hour < hourCount; hour++) {
					values.add(Double.parseDouble(row[hour + 1].trim()));
				}
				destination.put(row[0].trim(), values);
			}
			return hourCount;
		} catch (FileNotFoundException e) {
			ContextCreator.logger.warn("Background speed " + label + " profile not found: " + path, e);
			return 0;
		} catch (IOException e) {
			throw new IllegalStateException("Could not read background speed " + label
					+ " profile " + path, e);
		} finally {
			if (reader != null) {
				try {
					reader.close();
				} catch (IOException e) {
					ContextCreator.logger.warn("Could not close background speed profile " + path, e);
				}
			}
		}
	}

	private void warnIfProfileWindowIsShort(String label, String path, int availableHours) {
		if (availableHours <= 0) return; // Empty files intentionally disable the input.
		long requiredExclusiveHour = (long) this.profileHourOffset + GlobalVariables.HOUR_OF_SPEED;
		if (requiredExclusiveHour > availableHours) {
			ContextCreator.logger.warn("Background speed " + label + " profile " + path + " has "
					+ availableHours + " hourly columns, but BT_START_HOUR=" + this.profileHourOffset
					+ " and this run requests " + GlobalVariables.HOUR_OF_SPEED
					+ " intervals. After column " + (availableHours - 1)
					+ ", the last applied lane speed will be retained.");
		}
	}

	private int profileHourForSimulationTick(int simulationTick) {
		int interval = Math.max(1, GlobalVariables.SIMULATION_SPEED_REFRESH_INTERVAL);
		int elapsedProfileHours = Math.floorDiv(Math.max(0, simulationTick), interval);
		return Math.addExact(this.profileHourOffset, elapsedProfileHours);
	}

	public synchronized int getProfileHourForSimulationTick(int simulationTick) {
		return profileHourForSimulationTick(simulationTick);
	}

	public synchronized BackgroundSpeedSample getBackgroundTrafficForSimulationTick(
			String ID, int simulationTick) {
		int profileHour = profileHourForSimulationTick(simulationTick);
		ArrayList<Double> values = this.backgroundSpeed.get(ID);
		double speed = values != null && profileHour >= 0 && profileHour < values.size()
				? values.get(profileHour) : -1;
		return new BackgroundSpeedSample(profileHour, speed);
	}

	public synchronized ProfileStateSnapshot snapshotProfileState(int simulationTick) {
		return new ProfileStateSnapshot(this.eventFilePath, this.stdFilePath,
				this.profileHourOffset, profileHourForSimulationTick(simulationTick));
	}

	public synchronized int getProfileHourOffset() {
		return this.profileHourOffset;
	}

	public synchronized String getEventFilePath() {
		return this.eventFilePath;
	}

	public synchronized String getStdFilePath() {
		return this.stdFilePath;
	}

	public synchronized double getBackgroundTraffic(String ID, int profileHour) {
		ArrayList<Double> values = this.backgroundSpeed.get(ID);
		return values != null && profileHour >= 0 && profileHour < values.size()
				? values.get(profileHour) : -1;
	}

	public synchronized double getBackgroundTrafficStd(String ID, int profileHour) {
		ArrayList<Double> values = this.backgroundSpeedStd.get(ID);
		if (values != null && profileHour >= 0 && profileHour < values.size()) {
			return values.get(profileHour);
		}
		ContextCreator.logger.error("Could not find the background speed std for link "
				+ ID + " at profile hour " + profileHour + ", using the default value (30).");
		return 30;
	}
}
