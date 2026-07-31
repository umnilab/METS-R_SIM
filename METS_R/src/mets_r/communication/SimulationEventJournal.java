package mets_r.communication;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;

import mets_r.ContextCreator;
import mets_r.mobility.Request;

/** Run-scoped event journal read only at quiescent tick boundaries. */
public final class SimulationEventJournal {
	private static final Object LOCK = new Object();
	private static final ArrayList<Event> EVENTS = new ArrayList<Event>();
	private static long runEpoch = 0L;

	private SimulationEventJournal() {}

	public static void reset(long epoch) {
		synchronized (LOCK) {
			runEpoch = epoch;
			EVENTS.clear();
		}
	}

	public static void record(String type, int taxiID, Request request, int zoneID) {
		int requestID = request == null ? -1 : request.getID();
		int originZoneID = request == null ? -1 : request.getOriginZone();
		int destinationZoneID = request == null ? -1 : request.getDestZone();
		synchronized (LOCK) {
			EVENTS.add(new Event(runEpoch, ContextCreator.getCurrentTick(), type, taxiID,
					requestID, zoneID, originZoneID, destinationZoneID));
		}
	}

	public static Snapshot snapshotAfter(long cursor) {
		ArrayList<Event> ordered;
		long epoch;
		synchronized (LOCK) {
			ordered = new ArrayList<Event>(EVENTS);
			epoch = runEpoch;
		}
		Collections.sort(ordered, EVENT_ORDER);
		int start = (int) Math.max(0L, Math.min(cursor, ordered.size()));
		ArrayList<Object> records = new ArrayList<Object>(ordered.size() - start);
		for (int i = start; i < ordered.size(); i++) {
			Event event = ordered.get(i);
			HashMap<String, Object> record = new HashMap<String, Object>();
			record.put("cursor", i + 1L);
			record.put("tick", event.tick);
			record.put("type", event.type);
			if (event.taxiID >= 0) record.put("taxiID", event.taxiID);
			if (event.requestID >= 0) record.put("requestID", event.requestID);
			if (event.zoneID >= 0) record.put("zoneID", event.zoneID);
			if (event.originZoneID >= 0) record.put("originZoneID", event.originZoneID);
			if (event.destinationZoneID >= 0) record.put("destZoneID", event.destinationZoneID);
			records.add(record);
		}
		return new Snapshot(epoch, ordered.size(), records);
	}

	public static class Snapshot {
		public final long runEpoch;
		public final long nextCursor;
		public final List<Object> events;
		Snapshot(long runEpoch, long nextCursor, List<Object> events) {
			this.runEpoch = runEpoch;
			this.nextCursor = nextCursor;
			this.events = events;
		}
	}

	private static final Comparator<Event> EVENT_ORDER = new Comparator<Event>() {
		public int compare(Event left, Event right) {
			int result = Integer.compare(left.tick, right.tick);
			if (result != 0) return result;
			result = Integer.compare(typeRank(left.type), typeRank(right.type));
			if (result != 0) return result;
			result = Integer.compare(left.requestID, right.requestID);
			if (result != 0) return result;
			result = Integer.compare(left.taxiID, right.taxiID);
			if (result != 0) return result;
			return Integer.compare(left.zoneID, right.zoneID);
		}
	};

	private static int typeRank(String type) {
		if ("match".equals(type)) return 0;
		if ("pickup".equals(type)) return 1;
		if ("dropoff".equals(type)) return 2;
		if ("cancellation".equals(type)) return 3;
		if ("relocationCompletion".equals(type)) return 4;
		return 5;
	}

	private static class Event {
		@SuppressWarnings("unused")
		final long epoch;
		final int tick;
		final String type;
		final int taxiID;
		final int requestID;
		final int zoneID;
		final int originZoneID;
		final int destinationZoneID;
		Event(long epoch, int tick, String type, int taxiID, int requestID, int zoneID,
				int originZoneID, int destinationZoneID) {
			this.epoch = epoch;
			this.tick = tick;
			this.type = type;
			this.taxiID = taxiID;
			this.requestID = requestID;
			this.zoneID = zoneID;
			this.originZoneID = originZoneID;
			this.destinationZoneID = destinationZoneID;
		}
	}
}
