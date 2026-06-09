package mets_r.facility;

import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.GeometryFactory;

import mets_r.ContextCreator;
import mets_r.GlobalVariables;
import mets_r.data.input.SumoXML;
import mets_r.mobility.Vehicle;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;

import repast.simphony.context.space.gis.GeographyFactoryFinder;
import repast.simphony.space.gis.Geography;
import repast.simphony.space.gis.GeographyParameters;
import repast.simphony.space.gis.ShapefileLoader;

/**
 * Inherit from A-RESCUE
 **/

public class RoadContext extends FacilityContext<Road> {
	private ConcurrentHashMap<Integer, Long> activeRoadIDs;
	private ConcurrentHashMap<Integer, ConcurrentHashMap<Integer, Boolean>> enteringVehicleRoadIDs;
	private AtomicLong activeRoadMarkVersion;
	
	public RoadContext() {
		super("RoadContext");
		this.activeRoadIDs = new ConcurrentHashMap<Integer, Long>();
		this.enteringVehicleRoadIDs = new ConcurrentHashMap<Integer, ConcurrentHashMap<Integer, Boolean>>();
		this.activeRoadMarkVersion = new AtomicLong(0);
		ContextCreator.logger.info("RoadContext creation");
		/*
		 * GIS projection for spatial information about Roads. This is used to then
		 * create junctions and finally the road network.
		 */
		GeographyParameters<Road> geoParams = new GeographyParameters<Road>();
		Geography<Road> roadGeography = GeographyFactoryFinder.createGeographyFactory(null)
				.createGeography("RoadGeography", this, geoParams);

		/* Read in the data and add to the context and geography */
		File roadFile = null;
		ShapefileLoader<Road> roadLoader = null;

		/* CSV or xodr file for data attribute */
		String fileName = GlobalVariables.ROADS_CSV;
		if(GlobalVariables.NETWORK_FILE.length() > 0){
			fileName = GlobalVariables.NETWORK_FILE;
		}
		
		
		if(fileName.endsWith(".csv")) {
			// File class needed to turn stringName to actual file
			try {
				roadFile = new File(GlobalVariables.ROADS_SHAPEFILE);
				URI uri = roadFile.toURI();
				roadLoader = new ShapefileLoader<Road>(Road.class, uri.toURL(), roadGeography, this);
				BufferedReader br = new BufferedReader(new FileReader(fileName));
				String line = br.readLine();
				String[] result = line.split(",");
				if(result.length < 19) {
					ContextCreator.logger.error("Missing fields in Road configuration, a proper one should contain (LinkID, (unused) LaneNum, TLinkID, FnJunction, TNJunction, Left, Through, Right, (optional) Lane1 - Lane 9), length");
				}
				while (roadLoader.hasNext()) {
					line = br.readLine();
					result = line.split(",");
					Road road = roadLoader.nextWithArgs(Integer.parseInt(result[0]));
					road = setAttribute(road, result);
					road.setCoords(roadGeography.getGeometry(road).getCoordinates());
					this.put(road.getID(), road);
				}
				br.close();
			} catch (java.net.MalformedURLException e) {
				ContextCreator.logger.info(
						"ContextCreator: malformed URL exception when reading roadshapefile. Check the 'roadLoc' parameter is correct");
				e.printStackTrace();
			} catch (FileNotFoundException e) {
				ContextCreator.logger.info("ContextCreator: No road csv file found");
				e.printStackTrace();
			} catch (IOException e) {
				e.printStackTrace();
			} 
		}
		else {
			SumoXML sxml = SumoXML.getData(fileName);
			GeometryFactory geomFac = new GeometryFactory();
			for (Road r : sxml.getRoad().values()) {
				this.put(r.getID(), r);
				roadGeography.move(r, geomFac.createLineString(r.getCoords().toArray(new Coordinate[r.getCoords().size()])));
			}
		}
	}

	public Road setAttribute(Road r, String[] att) {
		if(Integer.parseInt(att[6])!=0)
			r.addDownStreamRoad(Integer.parseInt(att[6]));
		if(Integer.parseInt(att[7])!=0)
			r.addDownStreamRoad(Integer.parseInt(att[7]));
		if(Integer.parseInt(att[8])!=0)
			r.addDownStreamRoad(Integer.parseInt(att[8]));
		if(Integer.parseInt(att[3])!=0)
			r.addDownStreamRoad(Integer.parseInt(att[3]));
		r.setRoadType((int)Double.parseDouble(att[2]));
		r.setUpStreamJunction(Integer.parseInt(att[4]));
		r.setDownStreamJunction(Integer.parseInt(att[5]));
		r.setLength(Double.parseDouble(att[18]));
		return r;
	}
	
	public List<String> getOrigIDList(){
		List<String> facilityIDList = new ArrayList<String>();
		for(Road r: this.getAll()) {
			facilityIDList.add(r.getOrigID());
		}	
		return facilityIDList;
	}

	public void markRoadActive(Road road) {
		if (road != null) {
			markRoadActive(road.getID());
		}
	}

	public void markRoadActive(int roadID) {
		this.activeRoadIDs.put(roadID, this.activeRoadMarkVersion.incrementAndGet());
	}

	public void registerEnteringVehicle(Road road, Vehicle vehicle) {
		if (road == null || vehicle == null) return;
		ConcurrentHashMap<Integer, Boolean> roadIDs = this.enteringVehicleRoadIDs.get(vehicle.getID());
		if (roadIDs == null) {
			ConcurrentHashMap<Integer, Boolean> newRoadIDs = new ConcurrentHashMap<Integer, Boolean>();
			roadIDs = this.enteringVehicleRoadIDs.putIfAbsent(vehicle.getID(), newRoadIDs);
			if (roadIDs == null) {
				roadIDs = newRoadIDs;
			}
		}
		roadIDs.put(road.getID(), Boolean.TRUE);
	}

	public void unregisterEnteringVehicle(Road road, Vehicle vehicle) {
		if (road == null || vehicle == null) return;
		ConcurrentHashMap<Integer, Boolean> roadIDs = this.enteringVehicleRoadIDs.get(vehicle.getID());
		if (roadIDs == null) return;
		roadIDs.remove(road.getID());
		if (roadIDs.isEmpty()) {
			this.enteringVehicleRoadIDs.remove(vehicle.getID(), roadIDs);
		}
	}

	public void removeVehicleFromEnteringQueues(Vehicle vehicle) {
		if (vehicle == null) return;
		ConcurrentHashMap<Integer, Boolean> roadIDMap = this.enteringVehicleRoadIDs.get(vehicle.getID());
		if (roadIDMap != null && !roadIDMap.isEmpty()) {
			ArrayList<Integer> roadIDs = new ArrayList<Integer>(roadIDMap.keySet());
			for (Integer roadID : roadIDs) {
				Road road = roadID == null ? null : this.get(roadID);
				if (road != null) {
					road.removeVehicleFromEnteringQueue(vehicle);
				} else {
					roadIDMap.remove(roadID);
				}
			}
			if (roadIDMap.isEmpty()) {
				this.enteringVehicleRoadIDs.remove(vehicle.getID(), roadIDMap);
			}
			return;
		}

		// Fallback for queues restored or created before the membership index existed.
		boolean removed = false;
		ArrayList<Road> fallbackRoads = getLikelyEnteringQueueRoads(vehicle);
		for (Road road : fallbackRoads) {
			if (road != null) {
				removed = road.removeVehicleFromEnteringQueue(vehicle) || removed;
			}
		}
		if (removed) return;
		for (Road road : getActiveRoadsSnapshot()) {
			if (road != null && !fallbackRoads.contains(road)) {
				road.removeVehicleFromEnteringQueue(vehicle);
			}
		}
	}

	private ArrayList<Road> getLikelyEnteringQueueRoads(Vehicle vehicle) {
		ArrayList<Road> roads = new ArrayList<Road>();
		addEnteringQueueCandidate(roads, vehicle.getRoad());
		addEnteringQueueCandidate(roads, vehicle.getOriginRoad());
		addEnteringQueueCandidate(roads, vehicle.getLastDeparturableRoad());
		if (roads.isEmpty()) {
			for (Road road : getActiveRoadsSnapshot()) {
				addEnteringQueueCandidate(roads, road);
			}
		}
		return roads;
	}

	private void addEnteringQueueCandidate(ArrayList<Road> roads, int roadID) {
		if (roadID >= 0) {
			addEnteringQueueCandidate(roads, this.get(roadID));
		}
	}

	private void addEnteringQueueCandidate(ArrayList<Road> roads, Road road) {
		if (road == null || roads.contains(road)) return;
		roads.add(road);
	}

	public List<Road> getActiveRoadsSnapshot() {
		ArrayList<Road> activeRoads = new ArrayList<Road>(this.activeRoadIDs.size());
		for (Integer roadID : this.activeRoadIDs.keySet()) {
			Road road = this.get(roadID);
			if (road != null) {
				activeRoads.add(road);
			} else {
				this.activeRoadIDs.remove(roadID);
			}
		}
		return activeRoads;
	}

	public void refreshActiveRoads(Collection<Road> roads) {
		if (roads == null) {
			return;
		}
		for (Road road : roads) {
			if (road == null) {
				continue;
			}
			if (road.hasActiveVehicles()) {
				markRoadActive(road);
			} else {
				Long activeMark = this.activeRoadIDs.get(road.getID());
				if (activeMark != null && !road.hasActiveVehicles()) {
					this.activeRoadIDs.remove(road.getID(), activeMark);
				}
			}
		}
	}

	public void rebuildActiveRoadsFromState() {
		this.activeRoadIDs.clear();
		this.enteringVehicleRoadIDs.clear();
		for (Road road : this.getAll()) {
			if (road.hasActiveVehicles()) {
				markRoadActive(road);
			}
			for (Vehicle vehicle : road.getEnteringVehicleQueueSnapshot()) {
				registerEnteringVehicle(road, vehicle);
			}
		}
	}

	public int getActiveRoadCount() {
		return this.activeRoadIDs.size();
	}

	public boolean isRoadActive(int roadID) {
		return this.activeRoadIDs.containsKey(roadID);
	}

	@Override
	public void remove(int ID) {
		this.activeRoadIDs.remove(ID);
		for (ConcurrentHashMap<Integer, Boolean> roadIDs : this.enteringVehicleRoadIDs.values()) {
			roadIDs.remove(ID);
		}
		super.remove(ID);
	}
}
