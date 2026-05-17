package mets_r.data.output;

import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;

import org.json.simple.JSONObject;

import mets_r.ContextCreator;
import mets_r.GlobalVariables;
import mets_r.communication.DataConsumer;
import mets_r.facility.Road;
import mets_r.facility.Zone;
import mets_r.mobility.Vehicle;

/**
 * Writes trajectory snapshots as compact binary chunks for browser playback.
 *
 * The companion manifest.json describes the byte order, schema, chunk list, and
 * road-id dictionary. Each .bin chunk contains a small header followed by
 * variable-length tick frames. Numeric fields are fixed width, so the frontend
 * can decode chunks into typed arrays without first materializing large JSON
 * object graphs.
 */
public class BinaryTrajectoryOutputWriter implements DataConsumer {
	private static final String FORMAT_NAME = "metsr-trajectory-binary";
	private static final int FORMAT_VERSION = 1;
	private static final String CHUNK_MAGIC = "MRTB";

	private boolean append;
	private boolean defaultDirectory;
	private File outputDirectory;
	private DataOutputStream writer;
	private Thread writingThread;
	protected int currentTick;
	private boolean consuming;
	private boolean paused;
	private int ticksWritten;
	private int fileSeriesNumber;
	private String currentChunkFilename;
	private int currentChunkFirstTick;
	private int currentChunkLastTick;
	private int currentChunkTickCount;
	private int currentChunkVehicleCount;
	private int currentChunkLinkCount;
	private ArrayList<HashMap<String, Object>> chunks;
	private ArrayList<String> roadIdDictionary;
	private HashMap<String, Integer> roadIndexByOrigID;

	public BinaryTrajectoryOutputWriter() {
		this(null, false);
	}

	public BinaryTrajectoryOutputWriter(File outputDirectory, boolean append) {
		this.append = append;
		this.outputDirectory = outputDirectory;
		this.defaultDirectory = outputDirectory == null;
		this.writer = null;
		this.writingThread = null;
		this.currentTick = -1;
		this.consuming = false;
		this.paused = false;
		this.ticksWritten = 0;
		this.fileSeriesNumber = 1;
		this.currentChunkFilename = null;
		this.currentChunkFirstTick = -1;
		this.currentChunkLastTick = -1;
		this.currentChunkTickCount = 0;
		this.currentChunkVehicleCount = 0;
		this.currentChunkLinkCount = 0;
		this.chunks = new ArrayList<HashMap<String, Object>>();
		this.roadIdDictionary = new ArrayList<String>();
		this.roadIndexByOrigID = new HashMap<String, Integer>();
	}

	@Override
	public void startConsumer() throws Throwable {
		if (this.consuming) {
			if (this.paused) {
				this.paused = false;
			}
			return;
		}

		this.consuming = true;
		this.paused = false;
		this.fileSeriesNumber = 1;
		this.ticksWritten = 0;
		this.currentChunkFirstTick = -1;
		this.currentChunkLastTick = -1;
		this.currentChunkTickCount = 0;
		this.currentChunkVehicleCount = 0;
		this.currentChunkLinkCount = 0;
		this.chunks = new ArrayList<HashMap<String, Object>>();
		this.initializeRoadDictionary();

		if (this.defaultDirectory || this.outputDirectory == null) {
			this.outputDirectory = BinaryTrajectoryOutputWriter.createDefaultOutputDirectory();
		}
		if (this.outputDirectory == null) {
			throw new IOException("No binary trajectory output directory could be created.");
		}
		Files.createDirectories(Paths.get(this.outputDirectory.getAbsolutePath()));
		this.openCurrentChunkWriter();
		this.writeManifest();

		Runnable writingRunnable = new Runnable() {
			@Override
			public void run() {
				int totalCount = 0;
				int writeCount = 0;
				BinaryTrajectoryOutputWriter.this.currentTick = 0;
				while (true) {
					if (!BinaryTrajectoryOutputWriter.this.consuming) {
						DataCollector.printDebug("BIN", "NOT CONSUMING");
						break;
					}

					if (BinaryTrajectoryOutputWriter.this.paused) {
						DataCollector.printDebug("BIN", "PAUSED");
						try {
							Thread.sleep(GlobalVariables.JSON_BUFFER_REFRESH);
							continue;
						} catch (InterruptedException ie) {
							break;
						}
					}

					int nextTick = BinaryTrajectoryOutputWriter.this.currentTick
							+ GlobalVariables.JSON_TICKS_BETWEEN_TWO_RECORDS;
					TickSnapshot snapshot = ContextCreator.dataCollector.getNextTick(nextTick);
					if (snapshot == null) {
						if (writeCount > 0) {
							String report = "Wrote " + writeCount + " binary trajectory ticks to disk ("
									+ totalCount + " total)";
							DataCollector.printDebug("BIN", report);
							writeCount = 0;
						}

						if (!ContextCreator.dataCollector.isCollecting() && !ContextCreator.dataCollector.isPaused()) {
							break;
						}

						try {
							Thread.sleep(GlobalVariables.JSON_BUFFER_REFRESH);
							continue;
						} catch (InterruptedException ie) {
							break;
						}
					}

					BinaryTrajectoryOutputWriter.this.currentTick += GlobalVariables.JSON_TICKS_BETWEEN_TWO_RECORDS;
					try {
						BinaryTrajectoryOutputWriter.this.writeTickSnapshot(
								snapshot, BinaryTrajectoryOutputWriter.this.currentTick);
						totalCount++;
						writeCount++;
					} catch (IOException ioe) {
						DataCollector.printDebug("BIN", "WRITE ERROR: " + ioe.getMessage());
					}

					try {
						Thread.sleep(5);
					} catch (InterruptedException ie) {
						break;
					}
				}

				try {
					BinaryTrajectoryOutputWriter.this.closeOutputFileWriter();
				} catch (IOException ioe) {
					DataCollector.printDebug("BIN", "CLOSE ERROR: " + ioe.getMessage());
				}

				BinaryTrajectoryOutputWriter.this.paused = false;
				BinaryTrajectoryOutputWriter.this.consuming = false;
			}
		};
		this.writingThread = new Thread(writingRunnable);
		this.writingThread.start();
	}

	@Override
	public void stopConsumer() throws Throwable {
		if (!this.consuming) {
			return;
		}

		this.paused = false;
		this.consuming = false;
		this.writingThread.interrupt();
		this.writingThread.join();
		this.writingThread = null;
		this.currentTick = -1;
		this.closeOutputFileWriter();
	}

	public void awaitCompletion() throws InterruptedException {
		Thread thread = this.writingThread;
		if (thread != null) {
			thread.join();
		}
	}

	@Override
	public void pauseConsumer() throws Throwable {
		if (!this.consuming) {
			return;
		}
		this.paused = true;
		this.consuming = true;
	}

	@Override
	public void resetConsumer() throws Throwable {
		this.stopConsumer();
		this.paused = false;
		this.consuming = false;
		this.currentTick = -1;
	}

	@Override
	public int getTick() {
		return this.currentTick;
	}

	@Override
	public void setTick(int tick) throws Throwable {
		this.currentTick = tick;
	}

	private void initializeRoadDictionary() {
		this.roadIdDictionary = new ArrayList<String>();
		this.roadIndexByOrigID = new HashMap<String, Integer>();
		for (Road road : ContextCreator.getRoadContext().getAll()) {
			if (road == null || road.getOrigID() == null) {
				continue;
			}
			this.roadIdDictionary.add(road.getOrigID());
		}
		Collections.sort(this.roadIdDictionary);
		for (int i = 0; i < this.roadIdDictionary.size(); i++) {
			this.roadIndexByOrigID.put(this.roadIdDictionary.get(i), i);
		}
	}

	private void openCurrentChunkWriter() throws IOException {
		this.currentChunkFilename = GlobalVariables.TRAJECTORY_BINARY_DEFAULT_FILENAME + "."
				+ this.fileSeriesNumber + "." + GlobalVariables.TRAJECTORY_BINARY_DEFAULT_EXTENSION;
		File file = new File(this.outputDirectory, this.currentChunkFilename);
		this.writer = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file, this.append)));
		this.writeChunkHeader();
	}

	private void writeChunkHeader() throws IOException {
		this.writer.writeBytes(CHUNK_MAGIC);
		this.writer.writeInt(FORMAT_VERSION);
		this.writer.writeInt(GlobalVariables.TRAJECTORY_BINARY_COORD_SCALE);
		this.writer.writeDouble(GlobalVariables.INITIAL_X);
		this.writer.writeDouble(GlobalVariables.INITIAL_Y);
		this.writer.writeInt(GlobalVariables.JSON_TICKS_BETWEEN_TWO_RECORDS);
		this.writer.writeInt(GlobalVariables.JSON_FREQ_RECORD_LINK_SNAPSHOT);
	}

	private void startNextOutputFile() throws IOException {
		this.closeCurrentChunk();
		this.fileSeriesNumber++;
		this.openCurrentChunkWriter();
		this.ticksWritten = 0;
		this.writeManifest();
	}

	private void closeCurrentChunk() throws IOException {
		if (this.writer == null) {
			return;
		}
		this.writer.flush();
		this.writer.close();
		this.writer = null;
		this.finalizeCurrentChunkManifest();
		this.writeManifest();
	}

	private void closeOutputFileWriter() throws IOException {
		this.closeCurrentChunk();
		this.writeManifest();
		if (this.defaultDirectory) {
			this.outputDirectory = null;
		}
	}

	private void finalizeCurrentChunkManifest() {
		if (this.currentChunkFilename == null || this.currentChunkTickCount == 0) {
			this.resetCurrentChunkCounters();
			return;
		}
		HashMap<String, Object> chunk = new HashMap<String, Object>();
		chunk.put("file", this.currentChunkFilename);
		chunk.put("firstTick", this.currentChunkFirstTick);
		chunk.put("lastTick", this.currentChunkLastTick);
		chunk.put("tickCount", this.currentChunkTickCount);
		chunk.put("vehicleCount", this.currentChunkVehicleCount);
		chunk.put("linkCount", this.currentChunkLinkCount);
		chunk.put("closed", true);
		this.chunks.add(chunk);
		this.resetCurrentChunkCounters();
	}

	private void resetCurrentChunkCounters() {
		this.currentChunkFirstTick = -1;
		this.currentChunkLastTick = -1;
		this.currentChunkTickCount = 0;
		this.currentChunkVehicleCount = 0;
		this.currentChunkLinkCount = 0;
	}

	@SuppressWarnings("unchecked")
	private void writeManifest() throws IOException {
		if (this.outputDirectory == null) {
			return;
		}

		JSONObject manifest = new JSONObject();
		manifest.put("format", FORMAT_NAME);
		manifest.put("version", FORMAT_VERSION);
		manifest.put("byteOrder", "bigEndian");
		manifest.put("chunkMagic", CHUNK_MAGIC);
		manifest.put("coordScale", GlobalVariables.TRAJECTORY_BINARY_COORD_SCALE);
		manifest.put("initialX", GlobalVariables.INITIAL_X);
		manifest.put("initialY", GlobalVariables.INITIAL_Y);
		manifest.put("tickInterval", GlobalVariables.JSON_TICKS_BETWEEN_TWO_RECORDS);
		manifest.put("linkSnapshotInterval", GlobalVariables.JSON_FREQ_RECORD_LINK_SNAPSHOT);
		manifest.put("chunkTickLimit", GlobalVariables.TRAJECTORY_BINARY_TICK_LIMIT_PER_FILE);
		manifest.put("chunks", this.chunks);
		manifest.put("activeChunk", this.createActiveChunkManifest());
		manifest.put("roadIdDictionary", this.roadIdDictionary);
		manifest.put("vehicleTypes", BinaryTrajectoryOutputWriter.createVehicleTypes());
		manifest.put("schemas", BinaryTrajectoryOutputWriter.createSchemas());

		File manifestFile = new File(this.outputDirectory, "manifest.json");
		BufferedWriter manifestWriter = new BufferedWriter(new FileWriter(manifestFile));
		try {
			manifestWriter.write(JSONObject.toJSONString(manifest));
		} finally {
			manifestWriter.close();
		}
	}

	private HashMap<String, Object> createActiveChunkManifest() {
		HashMap<String, Object> activeChunk = new HashMap<String, Object>();
		if (this.writer == null || this.currentChunkFilename == null) {
			return activeChunk;
		}
		activeChunk.put("file", this.currentChunkFilename);
		activeChunk.put("firstTick", this.currentChunkFirstTick);
		activeChunk.put("lastTick", this.currentChunkLastTick);
		activeChunk.put("tickCount", this.currentChunkTickCount);
		activeChunk.put("vehicleCount", this.currentChunkVehicleCount);
		activeChunk.put("linkCount", this.currentChunkLinkCount);
		activeChunk.put("closed", false);
		return activeChunk;
	}

	private void writeTickSnapshot(TickSnapshot tick, int currentTick) throws IOException {
		if (this.writer == null) {
			throw new IOException("The binary trajectory file is not open for writing.");
		}
		if (this.ticksWritten >= GlobalVariables.TRAJECTORY_BINARY_TICK_LIMIT_PER_FILE) {
			this.startNextOutputFile();
		}
		if (this.currentChunkTickCount == 0) {
			this.currentChunkFirstTick = tick.getTickNumber();
		}

		FrameSummary summary = this.createFrameSummary(currentTick);
		ArrayList<EVSnapshot> privateEvs = this.getPrivateEVRecords(tick);
		ArrayList<ETaxiSnapshot> occupiedTaxis = this.getETaxiRecords(tick, Vehicle.OCCUPIED_TRIP);
		ArrayList<ETaxiSnapshot> relocationTaxis = this.getETaxiRecords(tick, Vehicle.INACCESSIBLE_RELOCATION_TRIP);
		ArrayList<ETaxiSnapshot> chargingTaxis = this.getETaxiRecords(tick, Vehicle.CHARGING_TRIP);
		ArrayList<BusSnapshot> buses = this.getBusRecords(tick);

		this.writer.writeInt(tick.getTickNumber());
		this.writer.writeInt(summary.servedPass);
		this.writer.writeInt(summary.leftPass);
		this.writer.writeFloat(summary.energyConsumption);
		this.writer.writeInt(summary.vehicleCount);
		this.writer.writeFloat(summary.meanSpeed);

		this.writePrivateEVGroup(privateEvs);
		this.writeETaxiGroup(occupiedTaxis);
		this.writeETaxiGroup(relocationTaxis);
		this.writeETaxiGroup(chargingTaxis);
		this.writeBusGroup(buses);
		this.writeLinkGroup(summary.links);
		this.writer.flush();

		int vehicleRecords = privateEvs.size() + occupiedTaxis.size() + relocationTaxis.size()
				+ chargingTaxis.size() + buses.size();
		this.currentChunkLastTick = tick.getTickNumber();
		this.currentChunkTickCount++;
		this.currentChunkVehicleCount += vehicleRecords;
		this.currentChunkLinkCount += summary.links.size();
		this.ticksWritten++;
	}

	private FrameSummary createFrameSummary(int currentTick) {
		FrameSummary summary = new FrameSummary();
		if (currentTick % GlobalVariables.JSON_FREQ_RECORD_LINK_SNAPSHOT != 0) {
			return summary;
		}

		for (Zone zone : ContextCreator.getZoneContext().getAll()) {
			summary.servedPass += zone.taxiPickupRequest + zone.busPickupRequest;
			summary.leftPass += zone.numberOfLeavedTaxiRequest + zone.numberOfLeavedBusRequest;
		}

		float weightedSpeed = 0;
		for (Road road : ContextCreator.getRoadContext().getAll()) {
			summary.energyConsumption += (float) road.getTotalEnergy();
			if (!road.stateHasChanged()) {
				continue;
			}
			double speed = road.calcSpeed();
			int nVehicles = road.getVehicleNum();
			double energy = road.getTotalEnergy();
			int flow = road.getTotalFlow();
			weightedSpeed += (float) speed * nVehicles;
			summary.vehicleCount += nVehicles;
			summary.links.add(new BinaryLinkRecord(this.getRoadDictionaryIndex(road.getOrigID()),
					nVehicles, (float) speed, flow, (float) energy));
		}
		summary.meanSpeed = weightedSpeed / Math.max(summary.vehicleCount, 1);
		return summary;
	}

	private int getRoadDictionaryIndex(String origID) {
		Integer index = this.roadIndexByOrigID.get(origID);
		return index == null ? -1 : index.intValue();
	}

	private ArrayList<EVSnapshot> getPrivateEVRecords(TickSnapshot tick) {
		ArrayList<EVSnapshot> records = new ArrayList<EVSnapshot>();
		for (Integer id : sortedIds(tick.getPrivateEVList())) {
			EVSnapshot snapshot = tick.getPrivateEVSnapshot(id);
			if (snapshot != null) {
				records.add(snapshot);
			}
		}
		return records;
	}

	private ArrayList<ETaxiSnapshot> getETaxiRecords(TickSnapshot tick, int vehicleState) {
		ArrayList<ETaxiSnapshot> records = new ArrayList<ETaxiSnapshot>();
		for (Integer id : sortedIds(tick.getETaxiList(vehicleState))) {
			ETaxiSnapshot snapshot = tick.getETaxiSnapshot(id, vehicleState);
			if (snapshot != null) {
				records.add(snapshot);
			}
		}
		return records;
	}

	private ArrayList<BusSnapshot> getBusRecords(TickSnapshot tick) {
		ArrayList<BusSnapshot> records = new ArrayList<BusSnapshot>();
		for (Integer id : sortedIds(tick.getBusList())) {
			BusSnapshot snapshot = tick.getBusSnapshot(id);
			if (snapshot != null) {
				records.add(snapshot);
			}
		}
		return records;
	}

	private void writePrivateEVGroup(ArrayList<EVSnapshot> records) throws IOException {
		this.writer.writeInt(records.size());
		for (EVSnapshot ev : records) {
			this.writeEVFields(ev.getId(), ev.getPrevX(), ev.getPrevY(), ev.getX(), ev.getY(),
					ev.getBearing(), ev.getSpeed(), ev.getOriginID(), ev.getDestID(),
					ev.getBatteryLevel(), ev.getTotalEnergyConsumption(), ev.getRoadID());
			this.writer.writeInt(ev.getTripNumber());
		}
	}

	private void writeETaxiGroup(ArrayList<ETaxiSnapshot> records) throws IOException {
		this.writer.writeInt(records.size());
		for (ETaxiSnapshot ev : records) {
			this.writeEVFields(ev.getId(), ev.getPrevX(), ev.getPrevY(), ev.getX(), ev.getY(),
					ev.getBearing(), ev.getSpeed(), ev.getOriginID(), ev.getDestID(),
					ev.getBatteryLevel(), ev.getTotalEnergyConsumption(), ev.getRoadID());
			this.writer.writeInt(ev.getServedPass());
		}
	}

	private void writeBusGroup(ArrayList<BusSnapshot> records) throws IOException {
		this.writer.writeInt(records.size());
		for (BusSnapshot bus : records) {
			this.writer.writeInt(bus.getId());
			this.writer.writeInt(parseIntOrDefault(bus.getRouteID(), -1));
			this.writer.writeInt(scaledX(bus.getPrevX()));
			this.writer.writeInt(scaledY(bus.getPrevY()));
			this.writer.writeInt(scaledX(bus.getX()));
			this.writer.writeInt(scaledY(bus.getY()));
			this.writer.writeFloat((float) bus.getBearing());
			this.writer.writeFloat((float) bus.getSpeed());
			this.writer.writeFloat((float) bus.getBatteryLevel());
			this.writer.writeFloat((float) bus.getTotalEnergyConsumption());
			this.writer.writeInt(bus.getRoadID());
			this.writer.writeInt(bus.getServedPass());
		}
	}

	private void writeLinkGroup(ArrayList<BinaryLinkRecord> records) throws IOException {
		this.writer.writeInt(records.size());
		for (BinaryLinkRecord link : records) {
			this.writer.writeInt(link.roadIndex);
			this.writer.writeInt(link.nVehicles);
			this.writer.writeFloat(link.speed);
			this.writer.writeInt(link.flow);
			this.writer.writeFloat(link.energy);
		}
	}

	private void writeEVFields(int id, double prevX, double prevY, double x, double y,
			double bearing, double speed, int originID, int destID, double batteryLevel,
			double energyConsumption, int roadID) throws IOException {
		this.writer.writeInt(id);
		this.writer.writeInt(scaledX(prevX));
		this.writer.writeInt(scaledY(prevY));
		this.writer.writeInt(scaledX(x));
		this.writer.writeInt(scaledY(y));
		this.writer.writeFloat((float) bearing);
		this.writer.writeFloat((float) speed);
		this.writer.writeInt(originID);
		this.writer.writeInt(destID);
		this.writer.writeFloat((float) batteryLevel);
		this.writer.writeFloat((float) energyConsumption);
		this.writer.writeInt(roadID);
	}

	private static ArrayList<Integer> sortedIds(Collection<Integer> ids) {
		ArrayList<Integer> sorted = new ArrayList<Integer>();
		if (ids == null || ids.isEmpty()) {
			return sorted;
		}
		sorted.addAll(ids);
		Collections.sort(sorted);
		return sorted;
	}

	private static int scaledX(double x) {
		return (int) ((x - GlobalVariables.INITIAL_X) * GlobalVariables.TRAJECTORY_BINARY_COORD_SCALE);
	}

	private static int scaledY(double y) {
		return (int) ((y - GlobalVariables.INITIAL_Y) * GlobalVariables.TRAJECTORY_BINARY_COORD_SCALE);
	}

	private static int parseIntOrDefault(String value, int defaultValue) {
		try {
			return Integer.parseInt(value);
		} catch (NumberFormatException nfe) {
			return defaultValue;
		}
	}

	public static File createDefaultOutputDirectory() {
		String defaultDir = GlobalVariables.TRAJECTORY_BINARY_DEFAULT_PATH;
		if (defaultDir == null || defaultDir.trim().length() < 1) {
			defaultDir = System.getProperty("user.dir");
		}

		SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd-HHmm-ss");
		String timestamp = formatter.format(new Date());
		File directory = new File(defaultDir, timestamp);
		if (directory.exists()) {
			directory = new File(defaultDir, timestamp + "_" + System.identityHashCode(timestamp));
		}
		try {
			Files.createDirectories(Paths.get(directory.getAbsolutePath()));
		} catch (IOException ioe) {
			ioe.printStackTrace();
			directory = new File(System.getProperty("java.io.tmpdir"),
					GlobalVariables.TRAJECTORY_BINARY_DEFAULT_FILENAME + "_" + timestamp);
			try {
				Files.createDirectories(Paths.get(directory.getAbsolutePath()));
			} catch (IOException ignored) {
				return null;
			}
		}
		return directory;
	}

	private static HashMap<String, Object> createVehicleTypes() {
		HashMap<String, Object> vehicleTypes = new HashMap<String, Object>();
		vehicleTypes.put("ev_private", 1);
		vehicleTypes.put("ev_occupied", 2);
		vehicleTypes.put("ev_relocation", 3);
		vehicleTypes.put("ev_charging", 4);
		vehicleTypes.put("bus", 5);
		return vehicleTypes;
	}

	private static HashMap<String, Object> createSchemas() {
		HashMap<String, Object> schemas = new HashMap<String, Object>();
		schemas.put("chunkHeader", schema("magic:char[4]", "version:int32", "coordScale:int32",
				"initialX:float64", "initialY:float64", "tickInterval:int32",
				"linkSnapshotInterval:int32"));
		schemas.put("frameHeader", schema("tick:int32", "served:int32", "left:int32",
				"energy:float32", "numVeh:int32", "meanSpeed:float32"));
		schemas.put("ev_private", schema("id:int32", "x0:int32", "y0:int32", "x1:int32",
				"y1:int32", "bearing:float32", "speed:float32", "originId:int32",
				"destId:int32", "battery:float32", "energy:float32", "roadId:int32",
				"tripNumber:int32"));
		schemas.put("ev_taxi", schema("id:int32", "x0:int32", "y0:int32", "x1:int32",
				"y1:int32", "bearing:float32", "speed:float32", "originId:int32",
				"destId:int32", "battery:float32", "energy:float32", "roadId:int32",
				"servedPass:int32"));
		schemas.put("bus", schema("id:int32", "routeId:int32", "x0:int32", "y0:int32",
				"x1:int32", "y1:int32", "bearing:float32", "speed:float32",
				"battery:float32", "energy:float32", "roadId:int32", "servedPass:int32"));
		schemas.put("link", schema("roadIndex:int32", "nVehicles:int32", "speed:float32",
				"flow:int32", "energy:float32"));
		return schemas;
	}

	private static ArrayList<String> schema(String... fields) {
		ArrayList<String> schema = new ArrayList<String>();
		for (String field : fields) {
			schema.add(field);
		}
		return schema;
	}

	private static class FrameSummary {
		int servedPass = 0;
		int leftPass = 0;
		float energyConsumption = 0;
		int vehicleCount = 0;
		float meanSpeed = 0;
		ArrayList<BinaryLinkRecord> links = new ArrayList<BinaryLinkRecord>();
	}

	private static class BinaryLinkRecord {
		final int roadIndex;
		final int nVehicles;
		final float speed;
		final int flow;
		final float energy;

		BinaryLinkRecord(int roadIndex, int nVehicles, float speed, int flow, float energy) {
			this.roadIndex = roadIndex;
			this.nVehicles = nVehicles;
			this.speed = speed;
			this.flow = flow;
			this.energy = energy;
		}
	}
}
