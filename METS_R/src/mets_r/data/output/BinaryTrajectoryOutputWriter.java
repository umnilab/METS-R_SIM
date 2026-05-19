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

import com.vividsolutions.jts.geom.Coordinate;

import mets_r.ContextCreator;
import mets_r.GlobalVariables;
import mets_r.communication.DataConsumer;
import mets_r.facility.ChargingStation;
import mets_r.facility.Road;
import mets_r.facility.Zone;
import mets_r.mobility.ElectricBus;
import mets_r.mobility.ElectricTaxi;
import mets_r.mobility.ElectricVehicle;
import mets_r.mobility.Vehicle;

/**
 * Writes trajectory snapshots as compact binary chunks for browser playback.
 *
 * The companion manifest.json describes the byte order, schema, chunk list, and
 * road-id dictionary. Each .bin chunk contains a small header followed by
 * variable-length tick frames. Numeric fields are fixed width, so the frontend
 * can decode chunks into typed arrays without first materializing large JSON
 * object graphs. Zone and charging-station frames are sparse deltas: the first
 * frame in each chunk writes the full state, then later frames write changed
 * records plus removed IDs only.
 */
public class BinaryTrajectoryOutputWriter implements DataConsumer {
	private static final String FORMAT_NAME = "metsr-trajectory-binary";
	private static final int FORMAT_VERSION = 6;
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
	private ArrayList<HashMap<String, Object>> zoneDictionary;
	private ArrayList<HashMap<String, Object>> chargingStationDictionary;
	private ArrayList<HashMap<String, Object>> busRouteDictionary;
	private HashMap<Integer, BinaryZoneRecord> previousZoneRecords;
	private HashMap<Integer, BinaryChargingStationRecord> previousChargingStationRecords;

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
		this.zoneDictionary = new ArrayList<HashMap<String, Object>>();
		this.chargingStationDictionary = new ArrayList<HashMap<String, Object>>();
		this.busRouteDictionary = new ArrayList<HashMap<String, Object>>();
		this.previousZoneRecords = new HashMap<Integer, BinaryZoneRecord>();
		this.previousChargingStationRecords = new HashMap<Integer, BinaryChargingStationRecord>();
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
		this.initializeZoneDictionary();
		this.initializeChargingStationDictionary();
		this.initializeBusRouteDictionary();

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
				try {
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
						} catch (Throwable t) {
							ContextCreator.logger.error("Binary trajectory writer failed at tick "
									+ BinaryTrajectoryOutputWriter.this.currentTick, t);
							break;
						}

						try {
							Thread.sleep(5);
						} catch (InterruptedException ie) {
							break;
						}
					}
				} catch (Throwable t) {
					ContextCreator.logger.error("Binary trajectory writer failed at tick "
							+ BinaryTrajectoryOutputWriter.this.currentTick, t);
				} finally {
					try {
						BinaryTrajectoryOutputWriter.this.closeOutputFileWriter();
					} catch (IOException ioe) {
						ContextCreator.logger.error("Failed to close binary trajectory writer", ioe);
					} catch (Throwable t) {
						ContextCreator.logger.error("Failed to close binary trajectory writer", t);
					}

					BinaryTrajectoryOutputWriter.this.paused = false;
					BinaryTrajectoryOutputWriter.this.consuming = false;
				}
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
		this.previousZoneRecords.clear();
		this.previousChargingStationRecords.clear();
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
		manifest.put("zoneDictionary", this.zoneDictionary);
		manifest.put("chargingStationDictionary", this.chargingStationDictionary);
		this.initializeBusRouteDictionary();
		manifest.put("busRouteDictionary", this.busRouteDictionary);
		manifest.put("vehicleTypes", BinaryTrajectoryOutputWriter.createVehicleTypes());
		manifest.put("frameGroups", schema("ev_private", "ev_occupied", "ev_relocation",
				"ev_charging", "bus", "link", "zone", "chargingStation"));
		manifest.put("sparseFrameGroups", schema("zone", "chargingStation"));
		manifest.put("sparseFrameGroupMode", "initialFullFrameThenChangedRecordsAndRemovedIds");
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
		BinaryZoneGroup zones = this.getChangedZoneRecords();
		BinaryChargingStationGroup chargingStations = this.getChangedChargingStationRecords();

		this.writer.writeInt(tick.getTickNumber());
		this.writer.writeInt(summary.matchedRequests);
		this.writer.writeInt(summary.matchedPassengers);
		this.writer.writeInt(summary.pickupRequests);
		this.writer.writeInt(summary.pickupPassengers);
		this.writer.writeInt(summary.dropoffRequests);
		this.writer.writeInt(summary.dropoffPassengers);
		this.writer.writeInt(summary.leftRequests);
		this.writer.writeInt(summary.leftPassengers);
		this.writer.writeFloat(summary.energyConsumption);
		this.writer.writeInt(summary.vehicleCount);
		this.writer.writeFloat(summary.meanSpeed);
		this.writer.writeFloat(summary.privateEVEnergy);
		this.writer.writeFloat(summary.eTaxiEnergy);
		this.writer.writeFloat(summary.eBusEnergy);

		this.writePrivateEVGroup(privateEvs);
		this.writeETaxiGroup(occupiedTaxis);
		this.writeETaxiGroup(relocationTaxis);
		this.writeETaxiGroup(chargingTaxis);
		this.writeBusGroup(buses);
		this.writeLinkGroup(summary.links);
		this.writeZoneGroup(zones);
		this.writeChargingStationGroup(chargingStations);
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
		for (ElectricVehicle ev : ContextCreator.getVehicleContext().getPrivateEVs()) {
			summary.privateEVEnergy += (float) ev.getTotalConsume();
		}
		for (ElectricTaxi ev : ContextCreator.getVehicleContext().getTaxis()) {
			summary.eTaxiEnergy += (float) ev.getTotalConsume();
			summary.matchedRequests += ev.getMatchedRequests();
			summary.matchedPassengers += ev.getMatchedPassengers();
			summary.pickupRequests += ev.getPickupRequests();
			summary.pickupPassengers += ev.getPickupPassengers();
			summary.dropoffRequests += ev.getDropoffRequests();
			summary.dropoffPassengers += ev.getDropoffPassengers();
		}
		for (ElectricBus bus : ContextCreator.getVehicleContext().getBuses()) {
			summary.eBusEnergy += (float) bus.getTotalConsume();
			summary.matchedRequests += bus.getMatchedRequests();
			summary.matchedPassengers += bus.getMatchedPassengers();
			summary.pickupRequests += bus.getPickupRequests();
			summary.pickupPassengers += bus.getPickupPassengers();
			summary.dropoffRequests += bus.getDropoffRequests();
			summary.dropoffPassengers += bus.getDropoffPassengers();
		}
		summary.energyConsumption = summary.privateEVEnergy + summary.eTaxiEnergy + summary.eBusEnergy;

		for (Zone zone : ContextCreator.getZoneContext().getAll()) {
			summary.leftRequests += zone.numberOfLeavedTaxiRequest + zone.numberOfLeavedBusRequest;
			summary.leftPassengers += zone.numberOfLeavedTaxiPassengers + zone.numberOfLeavedBusPassengers;
		}

		if (currentTick % GlobalVariables.JSON_FREQ_RECORD_LINK_SNAPSHOT != 0) {
			return summary;
		}

		float weightedSpeed = 0;
		for (Road road : ContextCreator.getRoadContext().getAll()) {
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

	private void initializeZoneDictionary() {
		this.zoneDictionary = new ArrayList<HashMap<String, Object>>();
		for (Zone zone : this.getZoneRecords()) {
			HashMap<String, Object> record = new HashMap<String, Object>();
			record.put("id", zone.getID());
			record.put("type", zone.getZoneType());
			record.put("capacity", zone.getCapacity());
			if (zone.getCoord() != null) {
				record.put("x", scaledX(zone.getCoord().x));
				record.put("y", scaledY(zone.getCoord().y));
			}
			this.zoneDictionary.add(record);
		}
	}

	private void initializeChargingStationDictionary() {
		this.chargingStationDictionary = new ArrayList<HashMap<String, Object>>();
		for (ChargingStation station : this.getChargingStationRecords()) {
			HashMap<String, Object> record = new HashMap<String, Object>();
			record.put("id", station.getID());
			record.put("numL2", station.numCharger(ChargingStation.L2));
			record.put("numL3", station.numCharger(ChargingStation.L3));
			record.put("numBus", station.numBusCharger());
			record.put("priceL2", station.getPriceL2());
			record.put("priceL3", station.getPriceL3());
			if (station.getCoord() != null) {
				record.put("x", scaledX(station.getCoord().x));
				record.put("y", scaledY(station.getCoord().y));
			}
			this.chargingStationDictionary.add(record);
		}
	}

	private void initializeBusRouteDictionary() {
		this.busRouteDictionary = new ArrayList<HashMap<String, Object>>();
		if (ContextCreator.bus_schedule == null) {
			return;
		}
		ArrayList<Integer> routeIDs = ContextCreator.bus_schedule.getRouteIDs();
		Collections.sort(routeIDs);
		for (Integer routeID : routeIDs) {
			HashMap<String, Object> record = new HashMap<String, Object>();
			record.put("routeId", routeID);
			record.put("routeName", ContextCreator.bus_schedule.getRouteName(routeID));
			record.put("stopZones", ContextCreator.bus_schedule.getStopZones(routeID));
			this.busRouteDictionary.add(record);
		}
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

	private ArrayList<Zone> getZoneRecords() {
		ArrayList<Zone> records = new ArrayList<Zone>();
		records.addAll(ContextCreator.getZoneContext().getAll());
		records.sort((a, b) -> Integer.compare(a.getID(), b.getID()));
		return records;
	}

	private ArrayList<ChargingStation> getChargingStationRecords() {
		ArrayList<ChargingStation> records = new ArrayList<ChargingStation>();
		records.addAll(ContextCreator.getChargingStationContext().getAll());
		records.sort((a, b) -> Integer.compare(a.getID(), b.getID()));
		return records;
	}

	private BinaryZoneGroup getChangedZoneRecords() {
		BinaryZoneGroup changedGroup = new BinaryZoneGroup();
		HashMap<Integer, BinaryZoneRecord> currentRecords = new HashMap<Integer, BinaryZoneRecord>();
		for (Zone zone : this.getZoneRecords()) {
			BinaryZoneRecord record = new BinaryZoneRecord(zone);
			currentRecords.put(record.id, record);
			BinaryZoneRecord previousRecord = this.previousZoneRecords.get(record.id);
			if (previousRecord == null || !record.hasSameValues(previousRecord)) {
				changedGroup.records.add(record);
			}
		}
		for (Integer previousID : sortedIds(this.previousZoneRecords.keySet())) {
			if (!currentRecords.containsKey(previousID)) {
				changedGroup.removedIds.add(previousID);
			}
		}
		this.previousZoneRecords = currentRecords;
		return changedGroup;
	}

	private BinaryChargingStationGroup getChangedChargingStationRecords() {
		BinaryChargingStationGroup changedGroup = new BinaryChargingStationGroup();
		HashMap<Integer, BinaryChargingStationRecord> currentRecords =
				new HashMap<Integer, BinaryChargingStationRecord>();
		for (ChargingStation station : this.getChargingStationRecords()) {
			BinaryChargingStationRecord record = new BinaryChargingStationRecord(station);
			currentRecords.put(record.id, record);
			BinaryChargingStationRecord previousRecord = this.previousChargingStationRecords.get(record.id);
			if (previousRecord == null || !record.hasSameValues(previousRecord)) {
				changedGroup.records.add(record);
			}
		}
		for (Integer previousID : sortedIds(this.previousChargingStationRecords.keySet())) {
			if (!currentRecords.containsKey(previousID)) {
				changedGroup.removedIds.add(previousID);
			}
		}
		this.previousChargingStationRecords = currentRecords;
		return changedGroup;
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
			this.writer.writeInt(ev.getMatchedRequests());
			this.writer.writeInt(ev.getMatchedPassengers());
			this.writer.writeInt(ev.getPickupRequests());
			this.writer.writeInt(ev.getPickupPassengers());
			this.writer.writeInt(ev.getDropoffRequests());
			this.writer.writeInt(ev.getDropoffPassengers());
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
			this.writer.writeInt(bus.getMatchedRequests());
			this.writer.writeInt(bus.getMatchedPassengers());
			this.writer.writeInt(bus.getPickupRequests());
			this.writer.writeInt(bus.getPickupPassengers());
			this.writer.writeInt(bus.getDropoffRequests());
			this.writer.writeInt(bus.getDropoffPassengers());
			ArrayList<Integer> stopZones = bus.getStopZones();
			this.writer.writeInt(stopZones.size());
			for (Integer stopZone : stopZones) {
				this.writer.writeInt(stopZone == null ? -1 : stopZone.intValue());
			}
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

	private void writeZoneGroup(BinaryZoneGroup group) throws IOException {
		this.writer.writeInt(group.records.size());
		for (BinaryZoneRecord zone : group.records) {
			this.writer.writeInt(zone.id);
			this.writer.writeInt(zone.x);
			this.writer.writeInt(zone.y);
			this.writer.writeInt(zone.zoneType);
			this.writer.writeInt(zone.capacity);
			this.writer.writeInt(zone.vehicleStock);
			this.writer.writeInt(zone.taxiRequest);
			this.writer.writeInt(zone.busRequest);
			this.writer.writeInt(zone.generatedTaxi);
			this.writer.writeInt(zone.generatedBus);
			this.writer.writeInt(zone.generatedPrivateEV);
			this.writer.writeInt(zone.generatedPrivateGV);
			this.writer.writeInt(zone.arrivedPrivateEV);
			this.writer.writeInt(zone.arrivedPrivateGV);
			this.writer.writeInt(zone.taxiMatchedRequests);
			this.writer.writeInt(zone.busMatchedRequests);
			this.writer.writeInt(zone.taxiDropoffRequests);
			this.writer.writeInt(zone.busDropoffRequests);
			this.writer.writeInt(zone.leftTaxiRequests);
			this.writer.writeInt(zone.leftBusRequests);
			this.writer.writeInt(zone.leftTaxiPassengers);
			this.writer.writeInt(zone.leftBusPassengers);
			this.writer.writeInt(zone.relocatedVehicles);
			this.writer.writeInt(zone.futureSupply);
			this.writer.writeFloat(zone.futureDemand);
			this.writer.writeFloat(zone.vehicleSurplus);
			this.writer.writeFloat(zone.vehicleDeficiency);
			this.writer.writeInt(zone.taxiDropoffWait);
			this.writer.writeInt(zone.busDropoffWait);
			this.writer.writeInt(zone.taxiLeftWait);
			this.writer.writeInt(zone.busLeftWait);
			this.writer.writeInt(zone.taxiParkingTime);
			this.writer.writeInt(zone.taxiCruisingTime);
		}
		this.writer.writeInt(group.removedIds.size());
		for (Integer id : group.removedIds) {
			this.writer.writeInt(id == null ? -1 : id.intValue());
		}
	}

	private void writeChargingStationGroup(BinaryChargingStationGroup group) throws IOException {
		this.writer.writeInt(group.records.size());
		for (BinaryChargingStationRecord station : group.records) {
			this.writer.writeInt(station.id);
			this.writer.writeInt(station.x);
			this.writer.writeInt(station.y);
			this.writer.writeInt(station.queueL2);
			this.writer.writeInt(station.queueL3);
			this.writer.writeInt(station.queueBus);
			this.writer.writeInt(station.chargingL2);
			this.writer.writeInt(station.chargingL3);
			this.writer.writeInt(station.chargingBus);
			this.writer.writeInt(station.freeL2);
			this.writer.writeInt(station.freeL3);
			this.writer.writeInt(station.freeBus);
			this.writer.writeInt(station.numL2);
			this.writer.writeInt(station.numL3);
			this.writer.writeInt(station.numBus);
			this.writer.writeInt(station.chargedCar);
			this.writer.writeInt(station.chargedBus);
			this.writer.writeFloat(station.priceL2);
			this.writer.writeFloat(station.priceL3);
			this.writer.writeFloat(station.waitingTimeL2);
			this.writer.writeFloat(station.waitingTimeL3);
			this.writer.writeInt(station.active);
		}
		this.writer.writeInt(group.removedIds.size());
		for (Integer id : group.removedIds) {
			this.writer.writeInt(id == null ? -1 : id.intValue());
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
		schemas.put("frameHeader", schema("tick:int32", "matchedRequests:int32",
				"matchedPassengers:int32", "pickupRequests:int32", "pickupPassengers:int32",
				"dropoffRequests:int32", "dropoffPassengers:int32", "leftRequests:int32",
				"leftPassengers:int32", "energy:float32", "numVeh:int32", "meanSpeed:float32",
				"energyPrivateEV:float32", "energyETaxi:float32", "energyEBus:float32"));
		schemas.put("ev_private", schema("id:int32", "x0:int32", "y0:int32", "x1:int32",
				"y1:int32", "bearing:float32", "speed:float32", "originId:int32",
				"destId:int32", "battery:float32", "energy:float32", "roadId:int32",
				"tripNumber:int32"));
		schemas.put("ev_taxi", schema("id:int32", "x0:int32", "y0:int32", "x1:int32",
				"y1:int32", "bearing:float32", "speed:float32", "originId:int32",
				"destId:int32", "battery:float32", "energy:float32", "roadId:int32",
				"matchedRequests:int32", "matchedPassengers:int32", "pickupRequests:int32",
				"pickupPassengers:int32", "dropoffRequests:int32", "dropoffPassengers:int32"));
		schemas.put("bus", schema("id:int32", "routeId:int32", "x0:int32", "y0:int32",
				"x1:int32", "y1:int32", "bearing:float32", "speed:float32",
				"battery:float32", "energy:float32", "roadId:int32", "matchedRequests:int32",
				"matchedPassengers:int32", "pickupRequests:int32", "pickupPassengers:int32",
				"dropoffRequests:int32", "dropoffPassengers:int32", "stopZoneCount:int32",
				"stopZones:int32[stopZoneCount]"));
		schemas.put("link", schema("roadIndex:int32", "nVehicles:int32", "speed:float32",
				"flow:int32", "energy:float32"));
		schemas.put("zone", schema("updateCount:int32", "updates:zoneRecord[updateCount]",
				"removedCount:int32", "removedIds:int32[removedCount]"));
		schemas.put("zoneRecord", schema("id:int32", "x:int32", "y:int32", "zoneType:int32",
				"capacity:int32", "vehicleStock:int32", "taxiRequest:int32", "busRequest:int32",
				"generatedTaxi:int32", "generatedBus:int32", "generatedPrivateEV:int32",
				"generatedPrivateGV:int32", "arrivedPrivateEV:int32", "arrivedPrivateGV:int32",
				"taxiMatchedRequests:int32", "busMatchedRequests:int32",
				"taxiDropoffRequests:int32", "busDropoffRequests:int32",
				"leftTaxiRequests:int32", "leftBusRequests:int32", "leftTaxiPassengers:int32",
				"leftBusPassengers:int32", "relocatedVehicles:int32", "futureSupply:int32",
				"futureDemand:float32", "vehicleSurplus:float32", "vehicleDeficiency:float32",
				"taxiDropoffWait:int32", "busDropoffWait:int32", "taxiLeftWait:int32",
				"busLeftWait:int32", "taxiParkingTime:int32", "taxiCruisingTime:int32"));
		schemas.put("chargingStation", schema("updateCount:int32",
				"updates:chargingStationRecord[updateCount]", "removedCount:int32",
				"removedIds:int32[removedCount]"));
		schemas.put("chargingStationRecord", schema("id:int32", "x:int32", "y:int32",
				"queueL2:int32", "queueL3:int32", "queueBus:int32", "chargingL2:int32",
				"chargingL3:int32", "chargingBus:int32", "freeL2:int32", "freeL3:int32",
				"freeBus:int32", "numL2:int32", "numL3:int32", "numBus:int32",
				"chargedCar:int32", "chargedBus:int32", "priceL2:float32", "priceL3:float32",
				"waitingTimeL2:float32", "waitingTimeL3:float32", "active:int32"));
		schemas.put("busRouteDictionary", schema("routeId:int32", "routeName:string",
				"stopZones:int32[]"));
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
		int matchedRequests = 0;
		int matchedPassengers = 0;
		int pickupRequests = 0;
		int pickupPassengers = 0;
		int dropoffRequests = 0;
		int dropoffPassengers = 0;
		int leftRequests = 0;
		int leftPassengers = 0;
		float energyConsumption = 0;
		float privateEVEnergy = 0;
		float eTaxiEnergy = 0;
		float eBusEnergy = 0;
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

	private static class BinaryZoneGroup {
		final ArrayList<BinaryZoneRecord> records = new ArrayList<BinaryZoneRecord>();
		final ArrayList<Integer> removedIds = new ArrayList<Integer>();
	}

	private static class BinaryChargingStationGroup {
		final ArrayList<BinaryChargingStationRecord> records =
				new ArrayList<BinaryChargingStationRecord>();
		final ArrayList<Integer> removedIds = new ArrayList<Integer>();
	}

	private static class BinaryZoneRecord {
		final int id;
		final int x;
		final int y;
		final int zoneType;
		final int capacity;
		final int vehicleStock;
		final int taxiRequest;
		final int busRequest;
		final int generatedTaxi;
		final int generatedBus;
		final int generatedPrivateEV;
		final int generatedPrivateGV;
		final int arrivedPrivateEV;
		final int arrivedPrivateGV;
		final int taxiMatchedRequests;
		final int busMatchedRequests;
		final int taxiDropoffRequests;
		final int busDropoffRequests;
		final int leftTaxiRequests;
		final int leftBusRequests;
		final int leftTaxiPassengers;
		final int leftBusPassengers;
		final int relocatedVehicles;
		final int futureSupply;
		final float futureDemand;
		final float vehicleSurplus;
		final float vehicleDeficiency;
		final int taxiDropoffWait;
		final int busDropoffWait;
		final int taxiLeftWait;
		final int busLeftWait;
		final int taxiParkingTime;
		final int taxiCruisingTime;

		BinaryZoneRecord(Zone zone) {
			Coordinate coord = zone.getCoord();
			this.id = zone.getID();
			this.x = coord == null ? 0 : scaledX(coord.x);
			this.y = coord == null ? 0 : scaledY(coord.y);
			this.zoneType = zone.getZoneType();
			this.capacity = zone.getCapacity();
			this.vehicleStock = zone.getVehicleStock();
			this.taxiRequest = zone.getTaxiRequestNum();
			this.busRequest = zone.getBusRequestNum();
			this.generatedTaxi = zone.numberOfGeneratedTaxiRequest;
			this.generatedBus = zone.numberOfGeneratedBusRequest;
			this.generatedPrivateEV = zone.numberOfGeneratedPrivateEVTrip;
			this.generatedPrivateGV = zone.numberOfGeneratedPrivateGVTrip;
			this.arrivedPrivateEV = zone.arrivedPrivateEVTrip;
			this.arrivedPrivateGV = zone.arrivedPrivateGVTrip;
			this.taxiMatchedRequests = zone.taxiPickupRequest;
			this.busMatchedRequests = zone.busPickupRequest;
			this.taxiDropoffRequests = zone.taxiServedRequest;
			this.busDropoffRequests = zone.busServedRequest;
			this.leftTaxiRequests = zone.numberOfLeavedTaxiRequest;
			this.leftBusRequests = zone.numberOfLeavedBusRequest;
			this.leftTaxiPassengers = zone.numberOfLeavedTaxiPassengers;
			this.leftBusPassengers = zone.numberOfLeavedBusPassengers;
			this.relocatedVehicles = zone.numberOfRelocatedVehicles;
			this.futureSupply = zone.getFutureSupply();
			this.futureDemand = (float) zone.getFutureDemand();
			this.vehicleSurplus = (float) zone.getVehicleSurplus();
			this.vehicleDeficiency = (float) zone.getVehicleDeficiency();
			this.taxiDropoffWait = zone.taxiServedPassWaitingTime;
			this.busDropoffWait = zone.busServedPassWaitingTime;
			this.taxiLeftWait = zone.taxiLeavedPassWaitingTime;
			this.busLeftWait = zone.busLeavedPassWaitingTime;
			this.taxiParkingTime = zone.taxiParkingTime;
			this.taxiCruisingTime = zone.taxiCruisingTime;
		}

		boolean hasSameValues(BinaryZoneRecord other) {
			return other != null
					&& this.id == other.id
					&& this.x == other.x
					&& this.y == other.y
					&& this.zoneType == other.zoneType
					&& this.capacity == other.capacity
					&& this.vehicleStock == other.vehicleStock
					&& this.taxiRequest == other.taxiRequest
					&& this.busRequest == other.busRequest
					&& this.generatedTaxi == other.generatedTaxi
					&& this.generatedBus == other.generatedBus
					&& this.generatedPrivateEV == other.generatedPrivateEV
					&& this.generatedPrivateGV == other.generatedPrivateGV
					&& this.arrivedPrivateEV == other.arrivedPrivateEV
					&& this.arrivedPrivateGV == other.arrivedPrivateGV
					&& this.taxiMatchedRequests == other.taxiMatchedRequests
					&& this.busMatchedRequests == other.busMatchedRequests
					&& this.taxiDropoffRequests == other.taxiDropoffRequests
					&& this.busDropoffRequests == other.busDropoffRequests
					&& this.leftTaxiRequests == other.leftTaxiRequests
					&& this.leftBusRequests == other.leftBusRequests
					&& this.leftTaxiPassengers == other.leftTaxiPassengers
					&& this.leftBusPassengers == other.leftBusPassengers
					&& this.relocatedVehicles == other.relocatedVehicles
					&& this.futureSupply == other.futureSupply
					&& sameFloat(this.futureDemand, other.futureDemand)
					&& sameFloat(this.vehicleSurplus, other.vehicleSurplus)
					&& sameFloat(this.vehicleDeficiency, other.vehicleDeficiency)
					&& this.taxiDropoffWait == other.taxiDropoffWait
					&& this.busDropoffWait == other.busDropoffWait
					&& this.taxiLeftWait == other.taxiLeftWait
					&& this.busLeftWait == other.busLeftWait
					&& this.taxiParkingTime == other.taxiParkingTime
					&& this.taxiCruisingTime == other.taxiCruisingTime;
		}
	}

	private static class BinaryChargingStationRecord {
		final int id;
		final int x;
		final int y;
		final int queueL2;
		final int queueL3;
		final int queueBus;
		final int chargingL2;
		final int chargingL3;
		final int chargingBus;
		final int freeL2;
		final int freeL3;
		final int freeBus;
		final int numL2;
		final int numL3;
		final int numBus;
		final int chargedCar;
		final int chargedBus;
		final float priceL2;
		final float priceL3;
		final float waitingTimeL2;
		final float waitingTimeL3;
		final int active;

		BinaryChargingStationRecord(ChargingStation station) {
			Coordinate coord = station.getCoord();
			this.id = station.getID();
			this.x = coord == null ? 0 : scaledX(coord.x);
			this.y = coord == null ? 0 : scaledY(coord.y);
			this.queueL2 = station.getQueueL2().size();
			this.queueL3 = station.getQueueL3().size();
			this.queueBus = station.getQueueBus().size();
			this.chargingL2 = station.getChargingL2().size();
			this.chargingL3 = station.getChargingL3().size();
			this.chargingBus = station.getChargingBus().size();
			this.freeL2 = station.capacity(ChargingStation.L2);
			this.freeL3 = station.capacity(ChargingStation.L3);
			this.freeBus = station.capacityBus();
			this.numL2 = station.numCharger(ChargingStation.L2);
			this.numL3 = station.numCharger(ChargingStation.L3);
			this.numBus = station.numBusCharger();
			this.chargedCar = station.numChargedCar.get();
			this.chargedBus = station.numChargedBus.get();
			this.priceL2 = (float) station.getPriceL2();
			this.priceL3 = (float) station.getPriceL3();
			this.waitingTimeL2 = (float) station.waitingTimeL2();
			this.waitingTimeL3 = (float) station.waitingTimeL3();
			this.active = station.hasChargingVehicles() ? 1 : 0;
		}

		boolean hasSameValues(BinaryChargingStationRecord other) {
			return other != null
					&& this.id == other.id
					&& this.x == other.x
					&& this.y == other.y
					&& this.queueL2 == other.queueL2
					&& this.queueL3 == other.queueL3
					&& this.queueBus == other.queueBus
					&& this.chargingL2 == other.chargingL2
					&& this.chargingL3 == other.chargingL3
					&& this.chargingBus == other.chargingBus
					&& this.freeL2 == other.freeL2
					&& this.freeL3 == other.freeL3
					&& this.freeBus == other.freeBus
					&& this.numL2 == other.numL2
					&& this.numL3 == other.numL3
					&& this.numBus == other.numBus
					&& this.chargedCar == other.chargedCar
					&& this.chargedBus == other.chargedBus
					&& sameFloat(this.priceL2, other.priceL2)
					&& sameFloat(this.priceL3, other.priceL3)
					&& sameFloat(this.waitingTimeL2, other.waitingTimeL2)
					&& sameFloat(this.waitingTimeL3, other.waitingTimeL3)
					&& this.active == other.active;
		}
	}

	private static boolean sameFloat(float left, float right) {
		return Float.floatToIntBits(left) == Float.floatToIntBits(right);
	}
}
