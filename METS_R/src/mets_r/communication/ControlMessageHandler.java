package mets_r.communication;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;

import org.geotools.geometry.jts.JTS;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.opengis.referencing.operation.TransformException;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.vividsolutions.jts.geom.Coordinate;

import mets_r.ContextCreator;
import mets_r.GlobalVariables;
import mets_r.communication.MessageClass.VehIDVehType;
import mets_r.communication.MessageClass.VehIDVehTypeAcc;
import mets_r.communication.MessageClass.VehIDVehTypeOrigDest;
import mets_r.communication.MessageClass.VehIDVehTypeOrigRoadDestRoad;
import mets_r.communication.MessageClass.VehIDVehTypeSensorType;
import mets_r.communication.MessageClass.VehIDVehTypeTranRoadIDXYDist;
import mets_r.data.input.SumoXML;
import mets_r.facility.Lane;
import mets_r.facility.Road;
import mets_r.facility.Zone;
import mets_r.mobility.ElectricVehicle;
import mets_r.mobility.Vehicle;

public class ControlMessageHandler extends MessageHandler {
	
	public ControlMessageHandler() {
		messageHandlers.put("end", this::endSim);
		messageHandlers.put("reset", this::resetSim);
		messageHandlers.put("teleportVeh", this::teleportVeh);
		messageHandlers.put("controlVeh", this::controlVeh);
		messageHandlers.put("enterNextRoad", this::enterNextRoad);
		messageHandlers.put("routingTaxi", this::routingTaxi);
		messageHandlers.put("routingBus", this::routingBus);
        messageHandlers.put("scheduleBus", this::scheduleBus);
        messageHandlers.put("scheduleTaxi", this::sheduleTaxi);
        messageHandlers.put("generateTrip", this::generateTrip);
        messageHandlers.put("genTripBwRoads", this::generateTripBwRoads);
        messageHandlers.put("setCoSimRoad", this::setCoSimRoad);
        messageHandlers.put("releaseCosimRoad", this::releaseCosimRoad);
        messageHandlers.put("updateVehicleSensorType", this::updateVehicleSensorType);
        messageHandlers.put("updateSignal", this::updateSignal);
        
	}
	
	public String handleMessage(String msgType, JSONObject jsonMsg) {
		CustomizableHandler handler = messageHandlers.get(msgType);
    	HashMap<String, Object> jsonAns = (handler != null) ? handler.handle(jsonMsg) : null;
    	jsonAns.put("TYPE", "CTRL_" + msgType);
    	count++;		
		return JSONObject.toJSONString(jsonAns);
	}
	
	private HashMap<String, Object> resetSim(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		// Get data
		String property_file = jsonMsg.get("propertyFile").toString();
		
		try {
			// Call the reset function
			ContextCreator.reset(property_file);
			jsonAns.put("CODE", "OK");
		}
		catch (Exception e) {
			ContextCreator.logger.error("Error processing control" + e.toString());
			jsonAns.put("CODE", "KO");
		}
		
		return jsonAns;
	}
	
	private HashMap<String, Object> endSim(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		
		ContextCreator.connection.sendStopMessage();
		
		// Call the end function, cannot fail
		ContextCreator.end();
		jsonAns.put("CODE", "OK");
		
		return jsonAns;
	}
	
	private HashMap<String, Object> setCoSimRoad(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("DATA")) {
			jsonAns.put("WARN", "No DATA field found in the control message");
			jsonAns.put("CODE", "KO");
		}
		else{
			try {
				Gson gson = new Gson();
				TypeToken<Collection<String>> collectionType = new TypeToken<Collection<String>>() {};
			    Collection<String> IDs = gson.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
			    ArrayList<Object> jsonData = new ArrayList<Object>();
			    
			    for(String roadID: IDs) {
			    	Road r = ContextCreator.getCityContext().findRoadWithOrigID(roadID);
			    	if(r != null) {
			    		r.setControlType(Road.CoSim);
						// Add road to coSim HashMap in the road context
						ContextCreator.coSimRoads.put(roadID, r);
						// Also output the lane information for computing the co-simulation area
						HashMap<String, Object> record2 = new HashMap<String, Object>();
						record2.put("ID", roadID);
						record2.put("STATUS", "OK");
						ArrayList<Object> centerLines = new ArrayList<Object>();
						for(Lane l: r.getLanes()) {
							centerLines.add(l.getXYList());
						}
						
						record2.put("center_lines", centerLines);
						jsonData.add(record2);
			    	}
			    	else {
			    		ContextCreator.logger.warn("Cannot find the road, road ID: " + roadID);
			    		HashMap<String, Object> record2 = new HashMap<String, Object>();
			    		record2.put("ID", roadID);
			    		record2.put("STATUS", "KO");
						jsonData.add(record2);
			    	}
					
			    }
			    jsonAns.put("DATA", jsonData);
			    jsonAns.put("CODE", "OK");
			}
			catch (Exception e) {
			    // Log error and return KO in case of exception
			    ContextCreator.logger.error("Error processing control: " + e.toString());
			    jsonAns.put("CODE", "KO");
			}
		}
		return jsonAns;
	}
	
	private HashMap<String, Object> releaseCosimRoad(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("DATA")) {
			jsonAns.put("WARN", "No DATA field found in the control message");
			jsonAns.put("CODE", "KO");
		}
		else {
			try {
				Gson gson = new Gson();
				TypeToken<Collection<String>> collectionType = new TypeToken<Collection<String>>() {};
			    Collection<String> IDs = gson.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
			    ArrayList<Object> jsonData = new ArrayList<Object>();
			    
			    for(String roadID: IDs) {
			    	Road r = ContextCreator.getCityContext().findRoadWithOrigID(roadID);
			    	if(r != null) {
			    		r.setControlType(Road.NONE_OF_THE_ABOVE);
						// Remove road from coSim HashMap in the ContextCreator
						ContextCreator.coSimRoads.remove(roadID);
						HashMap<String, Object> record2 = new HashMap<String, Object>();
			    		record2.put("ID", roadID);
			    		record2.put("STATUS", "OK");
						jsonData.add(record2);
			    	}
			    	else {
			    		ContextCreator.logger.warn("Cannot find the road, road ID: " + roadID);
			    		HashMap<String, Object> record2 = new HashMap<String, Object>();
			    		record2.put("ID", roadID);
			    		record2.put("STATUS", "KO");
						jsonData.add(record2);
			    	}
			    }
				jsonAns.put("DATA", jsonData);
				jsonAns.put("CODE", "OK");
			}
			catch (Exception e) {
			    // Log error and return KO in case of exception
			    ContextCreator.logger.error("Error processing control: " + e.toString());
			    jsonAns.put("CODE", "KO");;
			}
		}
		return jsonAns;
	}
	
    private HashMap<String, Object> generateTrip(JSONObject jsonMsg) {
    	HashMap<String, Object> jsonAns = new HashMap<String, Object>();
    	if(!jsonMsg.containsKey("DATA")) {
			jsonAns.put("WARN", "No DATA field found in the control message");
			jsonAns.put("CODE", "KO");
		}
    	else {
	    	try {
				Gson gson = new Gson();
				TypeToken<Collection<VehIDVehTypeOrigDest>> collectionType = new TypeToken<Collection<VehIDVehTypeOrigDest>>() {};
			    Collection<VehIDVehTypeOrigDest> vehIDVehTypeOrigDests = gson.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
			    ArrayList<Object> jsonData = new ArrayList<Object>();
			    for(VehIDVehTypeOrigDest vehIDVehTypeOrigDest:  vehIDVehTypeOrigDests) {
			    	// Get data
			    	int vehID = vehIDVehTypeOrigDest.vehID;
			    	ElectricVehicle v = ContextCreator.getVehicleContext().getPrivateEV(vehID);
			    	if(v != null) {
						if (v.getState() != Vehicle.NONE_OF_THE_ABOVE) {
			    			ContextCreator.logger.warn("The private EV: " + vehID + " is currently on the road, maybe there are two trips for the same vehicle that are too close?");
			    			HashMap<String, Object> record2 = new HashMap<String, Object>();
				    		record2.put("ID", vehID);
				    		record2.put("STATUS", "KO");
							jsonData.add(record2);
			    			continue;
						}
			    	}
					else { // If vehicle does not exists, create vehicle
						v = new ElectricVehicle(Vehicle.EV, Vehicle.NONE_OF_THE_ABOVE);
						ContextCreator.getVehicleContext().registerPrivateEV(vehID, v);
					}
					
					// Find the origin and dest zones
					int originID = vehIDVehTypeOrigDest.orig;
					int destID = vehIDVehTypeOrigDest.dest;
					Zone originZone = null;
					Zone destZone = null;
					
					if(originID >= 0) {
						originZone = ContextCreator.getZoneContext().get(originID);
						if(originZone == null) {
							ContextCreator.logger.warn("Cannot find the origin with ID: " + originID);
							HashMap<String, Object> record2 = new HashMap<String, Object>();
				    		record2.put("ID", vehID);
				    		record2.put("STATUS", "KO");
							jsonData.add(record2);
			    			continue;
						}
					}
					else {
						// randomly select a zone as origin
						originID = GlobalVariables.RandomGenerator.nextInt(ContextCreator.getZoneContext().ZONE_NUM);
						originZone = ContextCreator.getZoneContext().get(originID);
					}
					
					if(destID >= 0) {
						destZone = ContextCreator.getZoneContext().get(destID);
						if(destZone == null) {
							ContextCreator.logger.warn("Cannot find the dest with ID: " + destID);
							HashMap<String, Object> record2 = new HashMap<String, Object>();
				    		record2.put("ID", vehID);
				    		record2.put("STATUS", "KO");
							jsonData.add(record2);
			    			continue;
						}
					}
					else {
						// randomly select a zone as destination
						destID = GlobalVariables.RandomGenerator.nextInt(ContextCreator.getZoneContext().ZONE_NUM);
						destZone = ContextCreator.getZoneContext().get(destID);
					}
			    			
					// Assign trips
					v.initializePlan(originID, originZone.getClosestRoad(false), (int) ContextCreator.getCurrentTick());
					v.addPlan(destID, destZone.getClosestRoad(true), (int) ContextCreator.getNextTick());
					v.setNextPlan();
					v.setState(Vehicle.PRIVATE_TRIP);
					v.departure();
					HashMap<String, Object> record2 = new HashMap<String, Object>();
					record2.put("ID", vehID); // Note this vehID will be different from that obtained by veh.getID() which is generated by ContextCreator.generateAgentID();
					record2.put("STATUS", "OK");
					record2.put("origin", originID);
					record2.put("destination",destID);
					jsonData.add(record2);
			    }
			    jsonAns.put("DATA", jsonData);
			    jsonAns.put("CODE", "OK");
			}
			catch (Exception e) {
			    // Log error and return KO in case of exception
			    ContextCreator.logger.error("Error processing control: " + e.toString());
			    jsonAns.put("CODE", "KO");
			}
    	}
    	return jsonAns;
    }
    
    private HashMap<String, Object> generateTripBwRoads(JSONObject jsonMsg) {
    	HashMap<String, Object> jsonAns = new HashMap<String, Object>();
    	if(!jsonMsg.containsKey("DATA")) {
			jsonAns.put("WARN", "No DATA field found in the control message");
			jsonAns.put("CODE", "KO");
		}
    	else {
	    	try {
				Gson gson = new Gson();
				TypeToken<Collection<VehIDVehTypeOrigRoadDestRoad>> collectionType = new TypeToken<Collection<VehIDVehTypeOrigRoadDestRoad>>() {};
			    Collection<VehIDVehTypeOrigRoadDestRoad> vehIDVehTypeOrigDests = gson.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
			    ArrayList<Object> jsonData = new ArrayList<Object>();
			    for(VehIDVehTypeOrigRoadDestRoad vehIDVehTypeOrigDest:  vehIDVehTypeOrigDests) {
			    	// Get data
			    	int vehID = vehIDVehTypeOrigDest.vehID;
			    	ElectricVehicle v = ContextCreator.getVehicleContext().getPrivateEV(vehID);
			    	if(v != null) {
						if (v.getState() != Vehicle.NONE_OF_THE_ABOVE) {
			    			ContextCreator.logger.warn("The private EV: " + vehID + " is currently on the road, maybe there are two trips for the same vehicle that are too close?");
			    			HashMap<String, Object> record2 = new HashMap<String, Object>();
				    		record2.put("ID", vehID);
				    		record2.put("STATUS", "KO");
							jsonData.add(record2);
			    			continue;
						}
			    	}
					else { // If vehicle does not exists, create vehicle
						v = new ElectricVehicle(Vehicle.EV, Vehicle.NONE_OF_THE_ABOVE);
						ContextCreator.getVehicleContext().registerPrivateEV(vehID, v);
					}
					
					// Find the origin and dest zones
					String originID = vehIDVehTypeOrigDest.orig;
					String destID = vehIDVehTypeOrigDest.dest;
					Road originRoad = null;
					Road destRoad = null;
					
					originRoad = ContextCreator.getCityContext().findRoadWithOrigID(originID);
					if(originRoad == null) {
						ContextCreator.logger.warn("Cannot find the origin road with ID: " + originID);
						HashMap<String, Object> record2 = new HashMap<String, Object>();
			    		record2.put("ID", vehID);
			    		record2.put("STATUS", "KO");
						jsonData.add(record2);
		    			continue;
					}
					
					
					destRoad = ContextCreator.getCityContext().findRoadWithOrigID(destID);
					if(destRoad == null) {
						ContextCreator.logger.warn("Cannot find the dest road with ID: " + destID);
						HashMap<String, Object> record2 = new HashMap<String, Object>();
			    		record2.put("ID", vehID);
			    		record2.put("STATUS", "KO");
						jsonData.add(record2);
		    			continue;
					}
		    			
		    		int originZoneID = originRoad.getNeighboringZone(false);
		    		int destZoneID = destRoad.getNeighboringZone(true);
			    			
					// Assign trips
					v.initializePlan(originZoneID, originRoad.getID(), (int) ContextCreator.getCurrentTick());
					v.addPlan(destZoneID, destRoad.getID(), (int) ContextCreator.getNextTick());
					v.setNextPlan();
					v.setState(Vehicle.PRIVATE_TRIP);
					v.departure();
					HashMap<String, Object> record2 = new HashMap<String, Object>();
					record2.put("vehID", vehID); // Note this vehID will be different from that obtained by veh.getID() which is generated by ContextCreator.generateAgentID();
		    		record2.put("STATUS", "OK");
		    		record2.put("origin", originID);
					record2.put("destination",destID);
					jsonData.add(record2);
			    }
			    jsonAns.put("DATA", jsonData);
			    jsonAns.put("CODE", "OK");
			}
			catch (Exception e) {
			    // Log error and return KO in case of exception
			    ContextCreator.logger.error("Error processing control: " + e.toString());
			    jsonAns.put("CODE", "KO");
			}
    	}
    	return jsonAns;
    }
	
	// This can be done at t or t+1, directly change the road properties
	// When perform at t+1 (wait == false), append takes effect immediately
	private HashMap<String, Object> teleportVeh(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("DATA")) { 
			jsonAns.put("WARN", "No DATA field found in the control message");
			jsonAns.put("CODE", "KO");
		}
		else {
	    	try {
				Gson gson = new Gson();
				TypeToken<Collection<VehIDVehTypeTranRoadIDXYDist>> collectionType = new TypeToken<Collection<VehIDVehTypeTranRoadIDXYDist>>() {};
			    Collection<VehIDVehTypeTranRoadIDXYDist> vehIDVehTypeTranRoadIDXYDists = gson.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
			    ArrayList<Object> jsonData = new ArrayList<Object>();
			    
			    for(VehIDVehTypeTranRoadIDXYDist vehIDVehTypeTranRoadIDXYDist: vehIDVehTypeTranRoadIDXYDists) {
			    	// Get data
					Vehicle veh = null;
					if(vehIDVehTypeTranRoadIDXYDist.vehType) {
						veh = ContextCreator.getVehicleContext().getPrivateVehicle(vehIDVehTypeTranRoadIDXYDist.vehID);
					}
					else {
						veh = ContextCreator.getVehicleContext().getPublicVehicle(vehIDVehTypeTranRoadIDXYDist.vehID);
					}
					
					if(veh != null) {
						Road road = ContextCreator.getRoadContext().get(vehIDVehTypeTranRoadIDXYDist.roadID);
						Lane lane = ContextCreator.getLaneContext().get(vehIDVehTypeTranRoadIDXYDist.laneID);
						double dist = vehIDVehTypeTranRoadIDXYDist.dist;
						double x = vehIDVehTypeTranRoadIDXYDist.x;
						double y = vehIDVehTypeTranRoadIDXYDist.y;
						if(road != null && veh != null && lane != null) {
							// Integrity check
							if (lane.getRoad() == road.getID()) {
								// Transform coordinates if needed 
								if(vehIDVehTypeTranRoadIDXYDist.transformCoord) {
									Coordinate coord = new Coordinate();
									coord.x = x;
									coord.y = y;
									coord.z = 0;
									try {
										JTS.transform(coord, coord, SumoXML.getData(GlobalVariables.NETWORK_FILE).transform);
										x = coord.x;
										y = coord.y;
									} catch (TransformException e) {
										ContextCreator.logger.error("Coordinates transformation failed, input x: " + x + " y:" + y);
										e.printStackTrace();
									}
								}
							
								// Update its location in the target link and target lane
								if(road.insertVehicle(veh, lane, dist, x, y)) {
									HashMap<String, Object> record2 = new HashMap<String, Object>();
						    		record2.put("ID", vehIDVehTypeTranRoadIDXYDist.vehID);
						    		record2.put("STATUS", "OK");
									jsonData.add(record2);
									continue;
								}
							}
						}
					}
					HashMap<String, Object> record2 = new HashMap<String, Object>();
		    		record2.put("ID", vehIDVehTypeTranRoadIDXYDist.vehID);
		    		record2.put("STATUS", "KO");
					jsonData.add(record2);
			    }
				jsonAns.put("DATA", jsonData);
				jsonAns.put("CODE", "OK");
			}
			catch (Exception e) {
			    // Log error and return KO in case of exception
			    ContextCreator.logger.error("Error processing control: " + e.toString());
			    jsonAns.put("CODE", "KO");
			}
		}
		return jsonAns;
	}
	
	// This need to be made at t, and takes effect during t to t+1
	// This essentially alternate the acceleration decisions
	private HashMap<String, Object> controlVeh(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("DATA")) {
			jsonAns.put("WARN", "No DATA field found in the control message");
			jsonAns.put("CODE", "KO");
		}
		else {
	    	try {
				Gson gson = new Gson();
				TypeToken<Collection<VehIDVehTypeAcc>> collectionType = new TypeToken<Collection<VehIDVehTypeAcc>>() {};
			    Collection<VehIDVehTypeAcc> vehIDVehTypeAccs = gson.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
			    ArrayList<Object> jsonData = new ArrayList<Object>();
			    
			    for (VehIDVehTypeAcc vehIDVehTypeAcc: vehIDVehTypeAccs) {
			    	// Get vehicle
					Vehicle veh = null;
					if(vehIDVehTypeAcc.vehType) {
						veh = ContextCreator.getVehicleContext().getPrivateVehicle(vehIDVehTypeAcc.vehID);
					}
					else {
						veh = ContextCreator.getVehicleContext().getPublicVehicle(vehIDVehTypeAcc.vehID);
					}
					double acc = vehIDVehTypeAcc.acc;
					// Register its acceleration
					if(veh.controlVehicleAcc(acc)) {
						HashMap<String, Object> record2 = new HashMap<String, Object>();
			    		record2.put("ID", vehIDVehTypeAcc.vehID);
			    		record2.put("STATUS", "OK");
						jsonData.add(record2);
					}
					else{
						HashMap<String, Object> record2 = new HashMap<String, Object>();
			    		record2.put("ID", vehIDVehTypeAcc.vehID);
			    		record2.put("STATUS", "KO");
						jsonData.add(record2);
					}
			    }
			    jsonAns.put("DATA", jsonData);
			    jsonAns.put("CODE", "OK");
			}
			catch (Exception e) {
			    // Log error and return KO in case of exception
			    ContextCreator.logger.error("Error processing control: " + e.toString());
			    jsonAns.put("CODE", "KO");
			}
		}
		return jsonAns;
	}

	private HashMap<String, Object> routingTaxi(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		HashMap<String, Integer> res = new HashMap<String, Integer>();
		ContextCreator.logger.info("Received route result!");
		JSONArray list_OD = (JSONArray) jsonMsg.get("OD");
		JSONArray list_result = (JSONArray) jsonMsg.get("result");
		for (int index = 0; index < list_OD.size(); index++) {
			Long result = (Long) list_result.get(index);
			int result_int = result.intValue();
			String OD = (String) list_OD.get(index);
			res.put(OD, result_int);
		}
		ContextCreator.routeResult_received = res;
		jsonAns.put("CODE", "OK");
		return jsonAns;
	}
	
	private HashMap<String, Object> routingBus(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		HashMap<String, Integer> res = new HashMap<String, Integer>();
		ContextCreator.logger.info("Received bus route result!");
		JSONArray list_OD = (JSONArray) jsonMsg.get("BOD");
		JSONArray list_result = (JSONArray) jsonMsg.get("result");
		for (int index = 0; index < list_OD.size(); index++) {
			Long result = (Long) list_result.get(index);
			int result_int = result.intValue();
			String OD = (String) list_OD.get(index);
			res.put(OD, result_int);
		}
		ContextCreator.routeResult_received_bus = res;
		jsonAns.put("CODE", "OK");
		return jsonAns;
	}
	
	private HashMap<String, Object> scheduleBus(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		ContextCreator.logger.info("Received bus schedules!");
		JSONArray list_routename = (JSONArray) jsonMsg.get("Bus_routename");
		JSONArray list_route = (JSONArray) jsonMsg.get("Bus_route");
		JSONArray list_gap = (JSONArray) jsonMsg.get("Bus_gap");
		JSONArray list_num = (JSONArray) jsonMsg.get("Bus_num");
		Long hour = Long.valueOf((String) jsonMsg.get("Bus_currenthour"));
		int newhour = hour.intValue();

		if (Connection.prevHour < newhour) { // New schedules
			Connection.prevHour = newhour;
			int array_size = list_num.size();
			ArrayList<Integer> newRouteName = new ArrayList<Integer>(array_size);
			ArrayList<Integer> newBusNum = new ArrayList<Integer>(array_size);
			ArrayList<Integer> newBusGap = new ArrayList<Integer>(array_size);
			ArrayList<ArrayList<Integer>> newRoutes = new ArrayList<ArrayList<Integer>>(array_size);
			for (int index = 0; index < list_num.size(); index++) {
				int bus_num_int = 0;
				if (list_num.get(index) instanceof Number) {
					bus_num_int = ((Number) list_num.get(index)).intValue();
				}
				if (bus_num_int > 0) {
					// Verify the data, the last stop ID should be the same as the first one
					@SuppressWarnings("unchecked")
					ArrayList<Long> route = (ArrayList<Long>) list_route.get(index);
					if (route.get(0).intValue() == route.get(route.size() - 1).intValue()) {
						newBusNum.add(bus_num_int);
						Double gap = (Double) list_gap.get(index);
						int gap_int = gap.intValue(); // in minutes
						newBusGap.add(gap_int);
						Long routename = (Long) list_routename.get(index);
						int list_routename_int = routename.intValue();
						newRouteName.add(list_routename_int);
						int route_size = route.size() - 1;
						ArrayList<Integer> route_int = new ArrayList<Integer>(route_size);
						for (int index_route = 0; index_route < route_size; index_route++) {
							int route_int_i = route.get(index_route).intValue();
							route_int.add(route_int_i);
						}
						newRoutes.add(route_int);
					}
				}
			}
			ContextCreator.bus_schedule.updateEvent(newhour, newRouteName, newRoutes, newBusNum, newBusGap);
			ContextCreator.receivedNewBusSchedule = true;
			jsonAns.put("CODE", "OK");
		}
		else{
			jsonAns.put("CODE", "KO");
		}
		
		return jsonAns;
	}
	
	private HashMap<String, Object> enterNextRoad(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("DATA")) {
			jsonAns.put("WARN", "No DATA field found in the control message");
			jsonAns.put("CODE", "KO");
		}
		else {
	    	try {
				Gson gson = new Gson();
				TypeToken<Collection<VehIDVehType>> collectionType = new TypeToken<Collection<VehIDVehType>>() {};
			    Collection<VehIDVehType> vehIDVehTypes = gson.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
			    ArrayList<Object> jsonData = new ArrayList<Object>();
			    
			    for(VehIDVehType vehIDVehType: vehIDVehTypes) {
			    	Vehicle veh = null;
					if(vehIDVehType.vehType) { // True: private vehicles
						veh = ContextCreator.getVehicleContext().getPrivateVehicle(vehIDVehType.vehID);
					}
					else {
						veh = ContextCreator.getVehicleContext().getPublicVehicle(vehIDVehType.vehID);
					}
					if(veh != null) {
						if(veh.changeRoad()) {
							HashMap<String, Object> record2 = new HashMap<String, Object>();
				    		record2.put("ID", vehIDVehType.vehID);
				    		record2.put("STATUS", "OK");
							jsonData.add(record2);
							continue;
						}
					}
					HashMap<String, Object> record2 = new HashMap<String, Object>();
		    		record2.put("ID", vehIDVehType.vehID);
		    		record2.put("STATUS", "KO");
					jsonData.add(record2);
			    }
				jsonAns.put("DATA", jsonData);
				jsonAns.put("CODE", "OK");
			}
			catch (Exception e) {
			    // Log error and return KO in case of exception
			    ContextCreator.logger.error("Error processing query: " + e.toString());
			    jsonAns.put("CODE", "KO");
			}
		}
		return jsonAns;
	}
	
	// Update sensorType of a vehicle
	private HashMap<String, Object> updateVehicleSensorType(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("DATA")) {
			jsonAns.put("WARN", "No DATA field found in the control message");
			jsonAns.put("CODE", "KO");
		}
		else {
			try {
				Gson gson = new Gson();
				TypeToken<Collection<VehIDVehTypeSensorType>> collectionType = new TypeToken<Collection<VehIDVehTypeSensorType>>() {};
			    Collection<VehIDVehTypeSensorType> vehIDVehTypeSensorTypes = gson.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
			    ArrayList<Object> jsonData = new ArrayList<Object>();
			    
			    for(VehIDVehTypeSensorType vehIDVehTypeSensorType: vehIDVehTypeSensorTypes) {
			    	Vehicle veh = null;
			    	if(vehIDVehTypeSensorType.vehType) { // True: private vehicles
						veh = ContextCreator.getVehicleContext().getPrivateVehicle(vehIDVehTypeSensorType.vehID);
					}
					else {
						veh = ContextCreator.getVehicleContext().getPublicVehicle(vehIDVehTypeSensorType.vehID);
					}
			    	if(veh != null) {
			    		veh.setVehicleSensorType(vehIDVehTypeSensorType.sensorType);
			    		HashMap<String, Object> record2 = new HashMap<String, Object>();
			    		record2.put("ID", vehIDVehTypeSensorType.vehID);
			    		record2.put("STATUS", "OK");
						jsonData.add(record2);
			    	}
			    	else {
			    		HashMap<String, Object> record2 = new HashMap<String, Object>();
			    		record2.put("ID", vehIDVehTypeSensorType.vehID);
			    		record2.put("STATUS", "KO");
						jsonData.add(record2);
			    	}
			    }
			    jsonAns.put("DATA", jsonData);
			    jsonAns.put("CODE", "OK");
			    
			}
			catch (Exception e) {
			    // Log error and return KO in case of exception
			    ContextCreator.logger.error("Error processing query: " + e.toString());
			    jsonAns.put("CODE", "KO");
			}
		}
		return jsonAns;
	}
	
	// Taxi dispatching
	private HashMap<String, Object> sheduleTaxi(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		jsonAns.put("CODE", "OK");
		return jsonAns;
	}
	
	// Bus
	
	
	// Traffic signal
	private HashMap<String, Object> updateSignal(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		jsonAns.put("CODE", "OK");
		return jsonAns;
	}
	
}
