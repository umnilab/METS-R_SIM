package mets_r.communication;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;

import org.geotools.geometry.jts.JTS;
import org.json.simple.JSONObject;
import org.opengis.referencing.operation.TransformException;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.vividsolutions.jts.geom.Coordinate;

import mets_r.ContextCreator;
import mets_r.GlobalVariables;
import mets_r.communication.MessageClass.BusIDRouteNameStopIndex;
import mets_r.communication.MessageClass.BusIDRouteNameZoneRoadStopIndex;
import mets_r.communication.MessageClass.ChargerIDChargerTypeWeight;
import mets_r.communication.MessageClass.SignalIDPhase;
import mets_r.communication.MessageClass.SignalIDPhaseTiming;
import mets_r.communication.MessageClass.SignalPhasePlan;
import mets_r.communication.MessageClass.SignalPhasePlanTicks;
import mets_r.communication.MessageClass.OrigRoadDestRoadNum;
import mets_r.communication.MessageClass.RoadIDWeight;
import mets_r.communication.MessageClass.RouteNameDepartTime;
import mets_r.communication.MessageClass.RouteNameZonesRoads;
import mets_r.communication.MessageClass.RouteNameZonesRoadsPath;
import mets_r.communication.MessageClass.VehIDOrigDestNum;
import mets_r.communication.MessageClass.VehIDOrigRoadDestRoadNum;
import mets_r.communication.MessageClass.VehIDVehType;
import mets_r.communication.MessageClass.VehIDVehTypeAcc;
import mets_r.communication.MessageClass.VehIDVehTypeRoad;
import mets_r.communication.MessageClass.VehIDVehTypeRoadLaneDist;
import mets_r.communication.MessageClass.VehIDVehTypeRoute;
import mets_r.communication.MessageClass.VehIDVehTypeSensorType;
import mets_r.communication.MessageClass.VehIDVehTypeTranBearingXY;
import mets_r.communication.MessageClass.ZoneIDOrigDestRouteNameNum;
import mets_r.data.input.SumoXML;
import mets_r.facility.ChargingStation;
import mets_r.facility.Lane;
import mets_r.facility.Node;
import mets_r.facility.Road;
import mets_r.facility.Signal;
import mets_r.facility.Zone;
import mets_r.mobility.ElectricBus;
import mets_r.mobility.ElectricTaxi;
import mets_r.mobility.ElectricVehicle;
import mets_r.mobility.Request;
import mets_r.mobility.Vehicle;

public class ControlMessageHandler extends MessageHandler {
	
	public ControlMessageHandler() {
		messageHandlers.put("end", this::endSim);
		messageHandlers.put("reset", this::resetSim);
		messageHandlers.put("save", this::saveSim);
		messageHandlers.put("load", this::loadSim);
		messageHandlers.put("teleportCoSimVeh", this::teleportCoSimVeh);
		messageHandlers.put("teleportTraceReplayVeh", this::teleportTraceReplayVeh);
		messageHandlers.put("controlVeh", this::controlVeh);
		messageHandlers.put("enterNextRoad", this::enterNextRoad);
		messageHandlers.put("addBusRoute", this::addBusRoute);
		messageHandlers.put("addBusRouteWithPath", this::addBusRouteWithPath);
		messageHandlers.put("addBusRun", this::addBusRun);
		messageHandlers.put("dispatchTaxi", this::dispatchTaxi);
		messageHandlers.put("dispTaxiBwRoads", this::dispTaxiBwRoads);
		messageHandlers.put("assignRequestToBus", this::assignRequestToBus);
		messageHandlers.put("addBusRequests", this::addBusRequests);
		messageHandlers.put("addTaxiRequests", this::addTaxiRequests);
		messageHandlers.put("addTaxiReqBwRoads", this::addTaxiReqBwRoads);
		messageHandlers.put("generateTrip", this::generateTrip);
		messageHandlers.put("genTripBwRoads", this::generateTripBwRoads);
		messageHandlers.put("setCoSimRoad", this::setCoSimRoad);
		messageHandlers.put("releaseCosimRoad", this::releaseCosimRoad);
		messageHandlers.put("updateVehicleSensorType", this::updateVehicleSensorType);
		messageHandlers.put("reachDest", this::reachDest);
		messageHandlers.put("updateVehicleRoute", this::updateVehicleRoute);
		messageHandlers.put("updateEdgeWeight", this::updateEdgeWeight);
		messageHandlers.put("insertStopToRoute", this::insertStopToRoute);
		messageHandlers.put("removeStopFromRoute", this::removeStopFromRoute);
		messageHandlers.put("updateChargingPrice", this::updateChargingPrice);
		messageHandlers.put("updateSignal", this::updateSignal);
		messageHandlers.put("updateSignalTiming", this::updateSignalTiming);
		messageHandlers.put("setSignalPhasePlan", this::setSignalPhasePlan);
		messageHandlers.put("setSignalPhasePlanTicks", this::setSignalPhasePlanTicks);
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
		
		try {
			// Call the reset function
			ContextCreator.reset();
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
	
	private HashMap<String, Object> saveSim(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if (!jsonMsg.containsKey("DATA")) {
			jsonAns.put("WARN", "No DATA field found. Expected: {\"path\": \"<zip file path>\"}");
			jsonAns.put("CODE", "KO");
		} else {
			try {
				Gson gson = new Gson();
				HashMap<String, String> data = gson.fromJson(
						jsonMsg.get("DATA").toString(),
						new com.google.gson.reflect.TypeToken<HashMap<String, String>>() {}.getType());
				String zipPath = data.get("path");
				if (zipPath == null || zipPath.isEmpty()) {
					jsonAns.put("WARN", "Missing 'path' in DATA");
					jsonAns.put("CODE", "KO");
				} else {
					ContextCreator.save(zipPath);
					jsonAns.put("CODE", "OK");
					jsonAns.put("path", zipPath);
				}
			} catch (Exception e) {
				ContextCreator.logger.error("Error saving simulation: " + e.toString());
				jsonAns.put("CODE", "KO");
			}
		}
		return jsonAns;
	}
	
	private HashMap<String, Object> loadSim(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if (!jsonMsg.containsKey("DATA")) {
			jsonAns.put("WARN", "No DATA field found. Expected: {\"path\": \"<zip file path>\"}");
			jsonAns.put("CODE", "KO");
		} else {
			try {
				Gson gson = new Gson();
				HashMap<String, String> data = gson.fromJson(
						jsonMsg.get("DATA").toString(),
						new com.google.gson.reflect.TypeToken<HashMap<String, String>>() {}.getType());
				String zipPath = data.get("path");
				if (zipPath == null || zipPath.isEmpty()) {
					jsonAns.put("WARN", "Missing 'path' in DATA");
					jsonAns.put("CODE", "KO");
				} else {
					ContextCreator.load(zipPath);
					jsonAns.put("CODE", "OK");
					jsonAns.put("path", zipPath);
					jsonAns.put("tick", ContextCreator.getCurrentTick());
				}
			} catch (Exception e) {
				ContextCreator.logger.error("Error loading simulation: " + e.toString());
				jsonAns.put("CODE", "KO");
			}
		}
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
			    		r.setControlType(Road.COSIM);
						// Add road to coSim HashMap in the road context
						ContextCreator.coSimRoads.put(roadID, r);
						HashMap<String, Object> record2 = new HashMap<String, Object>();
						record2.put("ID", roadID);
						record2.put("STATUS", "OK");
						
						// Also output the lane information for computing the co-simulation area
//						ArrayList<Object> centerLines = new ArrayList<Object>();
//						for(Lane l: r.getLanes()) {
//							centerLines.add(l.getXYList());
//						}
//						
//						record2.put("center_lines", centerLines);
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
	
	// Generate trip for private vehicles
    private HashMap<String, Object> generateTrip(JSONObject jsonMsg) {
    	HashMap<String, Object> jsonAns = new HashMap<String, Object>();
    	if(!jsonMsg.containsKey("DATA")) {
			jsonAns.put("WARN", "No DATA field found in the control message");
			jsonAns.put("CODE", "KO");
		}
    	else {
	    	try {
				Gson gson = new Gson();
				TypeToken<Collection<VehIDOrigDestNum>> collectionType = new TypeToken<Collection<VehIDOrigDestNum>>() {};
			    Collection<VehIDOrigDestNum> vehIDVehTypeOrigDests = gson.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
			    ArrayList<Object> jsonData = new ArrayList<Object>();
			    for(VehIDOrigDestNum vehIDVehTypeOrigDest:  vehIDVehTypeOrigDests) {
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
						
					}
					else {
						// randomly select a zone as origin
						originID = GlobalVariables.RandomGenerator.nextInt(ContextCreator.getZoneContext().ZONE_NUM);
						originZone = ContextCreator.getZoneContext().get(originID);
					}
					if(originZone == null) {
						ContextCreator.logger.warn("Cannot find the origin with ID: " + originID);
						HashMap<String, Object> record2 = new HashMap<String, Object>();
			    		record2.put("ID", vehID);
			    		record2.put("STATUS", "KO");
						jsonData.add(record2);
		    			continue;
					}
					
					if(destID >= 0) {
						destZone = ContextCreator.getZoneContext().get(destID);
						
					}
					else {
						// randomly select a zone as destination
						destID = GlobalVariables.RandomGenerator.nextInt(ContextCreator.getZoneContext().ZONE_NUM);
						destZone = ContextCreator.getZoneContext().get(destID);
					} 
					if(destZone == null) {
						ContextCreator.logger.warn("Cannot find the dest with ID: " + destID);
						HashMap<String, Object> record2 = new HashMap<String, Object>();
			    		record2.put("ID", vehID);
			    		record2.put("STATUS", "KO");
						jsonData.add(record2);
		    			continue;
					}
			    			
					// Assign trips
					int origRoad = originZone.sampleRoad(false);
					v.initializePlan(originID, origRoad, (int) ContextCreator.getCurrentTick());
					v.addPlan(destID, destZone.sampleRoad(true), (int) ContextCreator.getNextTick());
					v.setNextPlan();
					v.setState(Vehicle.PRIVATE_TRIP);
					v.departure(origRoad);
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
    
    // Generate trip between roads for private vehicles
    private HashMap<String, Object> generateTripBwRoads(JSONObject jsonMsg) {
    	HashMap<String, Object> jsonAns = new HashMap<String, Object>();
    	if(!jsonMsg.containsKey("DATA")) {
			jsonAns.put("WARN", "No DATA field found in the control message");
			jsonAns.put("CODE", "KO");
		}
    	else {
	    	try {
				Gson gson = new Gson();
				TypeToken<Collection<VehIDOrigRoadDestRoadNum>> collectionType = new TypeToken<Collection<VehIDOrigRoadDestRoadNum>>() {};
			    Collection<VehIDOrigRoadDestRoadNum> vehIDVehTypeOrigDests = gson.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
			    ArrayList<Object> jsonData = new ArrayList<Object>();
			    for(VehIDOrigRoadDestRoadNum vehIDVehTypeOrigDest:  vehIDVehTypeOrigDests) {
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
					v.departure(originRoad);
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
	
    private HashMap<String, Object> teleportTraceReplayVeh(JSONObject jsonMsg){
    	HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("DATA")) { 
			jsonAns.put("WARN", "No DATA field found in the control message");
			jsonAns.put("CODE", "KO");
		}
		else {
	    	try {
				Gson gson = new Gson();
				TypeToken<Collection<VehIDVehTypeRoadLaneDist>> collectionType = new TypeToken<Collection<VehIDVehTypeRoadLaneDist>>() {
				};
				Collection<VehIDVehTypeRoadLaneDist> vehIDVehTypeRoadLaneDists = gson
						.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
				ArrayList<Object> jsonData = new ArrayList<Object>();

				for (VehIDVehTypeRoadLaneDist vehIDVehTypeRoadLaneDist : vehIDVehTypeRoadLaneDists) {
					// Get data
					Vehicle veh = null;
					if (vehIDVehTypeRoadLaneDist.vehType) {
						veh = ContextCreator.getVehicleContext().getPrivateVehicle(vehIDVehTypeRoadLaneDist.vehID);
					} else {
						veh = ContextCreator.getVehicleContext().getPublicVehicle(vehIDVehTypeRoadLaneDist.vehID);
					}
					
					if(veh == null) {
						ContextCreator.logger.error("Vehicle not found for ID: " + vehIDVehTypeRoadLaneDist.vehID);
					}
					else{
						Road road = ContextCreator.getCityContext().findRoadWithOrigID(vehIDVehTypeRoadLaneDist.roadID);
						if (road == null) {
			                ContextCreator.logger.error("Road not found for ID: " + vehIDVehTypeRoadLaneDist.roadID);
						}
		                else {
		                	if (vehIDVehTypeRoadLaneDist.laneID < 0 || vehIDVehTypeRoadLaneDist.laneID >= road.getNumberOfLanes()) {
		                		 ContextCreator.logger.error(
		                                 String.format("Invalid lane index %d for road %s (lanes: %d)",
		                                		 vehIDVehTypeRoadLaneDist.laneID, vehIDVehTypeRoadLaneDist.roadID, road.getNumberOfLanes()));
		                	}
		                	else{
		                		try {
									Lane lane = road.getLane(vehIDVehTypeRoadLaneDist.laneID);
									// Update its location in the target link and target lane
									if (veh.getRoad() == road) {
										veh.removeFromCurrentLane(); // Just remove the vehicle from the current lane
									}
									else {
										veh.removeFromCurrentLane();
										veh.removeFromCurrentRoad();
									}
									
									road.teleportVehicle(veh, lane, vehIDVehTypeRoadLaneDist.dist);
									HashMap<String, Object> record2 = new HashMap<String, Object>();
									record2.put("ID", vehIDVehTypeRoadLaneDist.vehID);
									record2.put("STATUS", "OK");
									jsonData.add(record2);
									continue;
		                		} catch (Exception innerEx) {
		                            ContextCreator.logger.error("Teleport failure for vehicle " + vehIDVehTypeRoadLaneDist.vehID
		                                + " on road " + vehIDVehTypeRoadLaneDist.roadID + ": " + innerEx.getMessage(), innerEx);
		                        }

							}
						}
					}
					HashMap<String, Object> record2 = new HashMap<String, Object>();
					record2.put("ID", vehIDVehTypeRoadLaneDist.vehID);
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
    
	private HashMap<String, Object> teleportCoSimVeh(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("DATA")) { 
			jsonAns.put("WARN", "No DATA field found in the control message");
			jsonAns.put("CODE", "KO");
		}
		else {
	    	try {
				Gson gson = new Gson();
				TypeToken<Collection<VehIDVehTypeTranBearingXY>> collectionType = new TypeToken<Collection<VehIDVehTypeTranBearingXY>>() {
				};
				Collection<VehIDVehTypeTranBearingXY> vehIDVehTypeTranBearingXYs = gson
						.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
				ArrayList<Object> jsonData = new ArrayList<Object>();

				for (VehIDVehTypeTranBearingXY vehIDVehTypeTranBearingXY : vehIDVehTypeTranBearingXYs) {
					// Get data
					Vehicle veh = null;
					if (vehIDVehTypeTranBearingXY.vehType) {
						veh = ContextCreator.getVehicleContext().getPrivateVehicle(vehIDVehTypeTranBearingXY.vehID);
					} else {
						veh = ContextCreator.getVehicleContext().getPublicVehicle(vehIDVehTypeTranBearingXY.vehID);
					}

					if (veh != null) {
						double x = vehIDVehTypeTranBearingXY.x;
						double y = vehIDVehTypeTranBearingXY.y;
						// Transform coordinates if needed
						if (vehIDVehTypeTranBearingXY.transformCoord) {
							Coordinate coord = new Coordinate(x, y);
							try {
								JTS.transform(coord, coord,
										SumoXML.getData(GlobalVariables.NETWORK_FILE).transform);
								x = coord.x;
								y = coord.y;
							} catch (TransformException e) {
								ContextCreator.logger
										.error("Coordinates transformation failed, input x: " + x + " y:" + y);
								e.printStackTrace();
							}
						}
						
						veh.setCurrentCoord(new Coordinate(x, y));
						veh.setBearing(vehIDVehTypeTranBearingXY.bearing);
						veh.getAndSetLastMoveTick(ContextCreator.getCurrentTick());

						HashMap<String, Object> record2 = new HashMap<String, Object>();
						record2.put("ID", vehIDVehTypeTranBearingXY.vehID);
						record2.put("STATUS", "OK");
						jsonData.add(record2);
						continue;
					}
					HashMap<String, Object> record2 = new HashMap<String, Object>();
					record2.put("ID", vehIDVehTypeTranBearingXY.vehID);
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
	
	// Take a road as input, check whether the road
	private HashMap<String, Object> enterNextRoad(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("DATA")) {
			jsonAns.put("WARN", "No DATA field found in the control message");
			jsonAns.put("CODE", "KO");
		}
		else {
	    	try {
				Gson gson = new Gson();
				TypeToken<Collection<VehIDVehTypeRoad>> collectionType = new TypeToken<Collection<VehIDVehTypeRoad>>() {};
			    Collection<VehIDVehTypeRoad> vehIDVehTypeRoads = gson.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
			    ArrayList<Object> jsonData = new ArrayList<Object>();
			    
			    for(VehIDVehTypeRoad vehIDVehTypeRoad: vehIDVehTypeRoads) {
			    	Vehicle veh = null;
					if(vehIDVehTypeRoad.vehType) { // True: private vehicles
						veh = ContextCreator.getVehicleContext().getPrivateVehicle(vehIDVehTypeRoad.vehID);
					}
					else {
						veh = ContextCreator.getVehicleContext().getPublicVehicle(vehIDVehTypeRoad.vehID);
					}
					if(veh != null) {
						if(vehIDVehTypeRoad.roadID != "") {
							Road r = ContextCreator.getCityContext().findRoadWithOrigID(vehIDVehTypeRoad.roadID);
							if(r != null) {
								if(veh.getNextRoad() != r) {
									veh.rerouteWithSpecifiedNextRoad(r);
								}
							}
						}

						if(veh.changeRoad()) {
							HashMap<String, Object> record2 = new HashMap<String, Object>();
				    		record2.put("ID", vehIDVehTypeRoad.vehID);
				    		record2.put("STATUS", "OK");
							jsonData.add(record2);
							continue;
						}
						
					}
					HashMap<String, Object> record2 = new HashMap<String, Object>();
		    		record2.put("ID", vehIDVehTypeRoad.vehID);
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
	
//	// Find the closest lane end coords in coSim Road, teleport the vehicle to the lane in METS-R SIM
//	// Trigger enterNextRoad and check whether it succeed or not
//	private HashMap<String, Object> exitCoSimRegion(JSONObject jsonMsg){
//		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
//		if(!jsonMsg.containsKey("DATA")) { 
//			jsonAns.put("WARN", "No DATA field found in the control message");
//			jsonAns.put("CODE", "KO");
//		}
//		else {
//	    	try {
//				Gson gson = new Gson();
//				TypeToken<Collection<VehIDVehTypeTranXY>> collectionType = new TypeToken<Collection<VehIDVehTypeTranXY>>() {
//				};
//				Collection<VehIDVehTypeTranXY> vehIDVehTypeTranXYs = gson
//						.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
//				ArrayList<Object> jsonData = new ArrayList<Object>();
//
//				for (VehIDVehTypeTranXY vehIDVehTypeTranXY : vehIDVehTypeTranXYs) {
//					// Get data
//					Vehicle veh = null;
//					if (vehIDVehTypeTranXY.vehType) {
//						veh = ContextCreator.getVehicleContext().getPrivateVehicle(vehIDVehTypeTranXY.vehID);
//					} else {
//						veh = ContextCreator.getVehicleContext().getPublicVehicle(vehIDVehTypeTranXY.vehID);
//					}
//
//					if (veh != null) {
//						double x = vehIDVehTypeTranXY.x;
//						double y = vehIDVehTypeTranXY.y;
//						// Transform coordinates if needed
//						if (vehIDVehTypeTranXY.transformCoord) {
//							Coordinate coord = new Coordinate(x, y);
//							try {
//								JTS.transform(coord, coord,
//										SumoXML.getData(GlobalVariables.NETWORK_FILE).transform);
//								x = coord.x;
//								y = coord.y;
//							} catch (TransformException e) {
//								ContextCreator.logger
//										.error("Coordinates transformation failed, input x: " + x + " y:" + y);
//								e.printStackTrace();
//							}
//						}
//						
//						// Find the closest road
//						Coordinate coord2 = new Coordinate();
//						coord2.x = x;
//						coord2.y = y;
//						Road road = ContextCreator.getCityContext().findRoadAtCoordinates(coord2, true);
//						Lane lane = null;
//						if(road != null) {
//							// Find the current lane
//							double minDist = Double.MAX_VALUE;
//							for(Lane l: road.getLanes()) {
//								double currentDist = ContextCreator.getCityContext().getDistance(l.getEndCoord(), coord2);
//								if( currentDist < minDist) {
//									minDist = currentDist;
//									lane = l;
//								}
//							}
//							if(lane != null) {
//								// Insert vehicle to the end of lane
//								veh.removeFromCurrentLane();
//								veh.removeFromCurrentRoad();
//								veh.appendToRoad(road);
//								veh.teleportToLane(lane, 0);
//							
//								// Enter next road
//								if(veh.changeRoad()) {
//									HashMap<String, Object> record2 = new HashMap<String, Object>();
//						    		record2.put("ID", vehIDVehTypeTranXY.vehID);
//						    		record2.put("STATUS", "OK");
//									jsonData.add(record2);
//									continue;
//								}
//							}
//						}
//					}
//					HashMap<String, Object> record2 = new HashMap<String, Object>();
//					record2.put("ID", vehIDVehTypeTranXY.vehID);
//					record2.put("STATUS", "KO");
//					jsonData.add(record2);
//				}
//				jsonAns.put("DATA", jsonData);
//				jsonAns.put("CODE", "OK");
//			}
//			catch (Exception e) {
//			    // Log error and return KO in case of exception
//			    ContextCreator.logger.error("Error processing control: " + e.toString());
//			    jsonAns.put("CODE", "KO");
//			}
//		}
//		return jsonAns;	
//	}
//	
	// Reach dest, teleport the vehicle to the destination, used when 
	// the destination road belongs to the co-simulation road
	private HashMap<String, Object> reachDest(JSONObject jsonMsg){
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
			    		HashMap<String, Object> record2 = new HashMap<String, Object>();
			    		veh.reachDest(); 
			    		record2.put("STATUS", "OK");
						jsonData.add(record2);
			    	}
			    	else {
			    		HashMap<String, Object> record2 = new HashMap<String, Object>();
			    		record2.put("ID", vehIDVehType.vehID);
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
			    ContextCreator.logger.error("Error processing control: " + e.toString());
			    jsonAns.put("CODE", "KO");
			}
		}
		return jsonAns;
	}
	
	// Taxi dispatching
	// Find a taxi, add a pickup trip and a request
	// If not departure, make it departure
	private HashMap<String, Object> dispatchTaxi(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("DATA")) {
			jsonAns.put("WARN", "No DATA field found in the control message");
			jsonAns.put("CODE", "KO");
		}
		else {
			try {
				Gson gson = new Gson();
				TypeToken<Collection<VehIDOrigDestNum>> collectionType = new TypeToken<Collection<VehIDOrigDestNum>>() {};
				Collection<VehIDOrigDestNum> vehIDOrigDestNums = gson.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
				ArrayList<Object> jsonData = new ArrayList<Object>();
				
				for(VehIDOrigDestNum vehIDOrigDestNum: vehIDOrigDestNums) {
					ElectricTaxi veh = (ElectricTaxi) ContextCreator.getVehicleContext().getPublicVehicle(vehIDOrigDestNum.vehID);
					Zone z1 = ContextCreator.getZoneContext().get(vehIDOrigDestNum.orig);
					Zone z2 = ContextCreator.getZoneContext().get(vehIDOrigDestNum.dest);
					if(veh != null && z1 != null && z2 != null) {
						// generate request
						ArrayList<Request> plist = new ArrayList<Request>();
						Request p = new Request(z1.getID(), z2.getID(), z1.sampleRoad(false), z1.sampleRoad(true), vehIDOrigDestNum.num);
						if(veh.getState() == Vehicle.PARKING) {
							ContextCreator.getZoneContext().get(veh.getCurrentZone()).removeOneParkingVehicle();
						}
						p.matchedTime = ContextCreator.getCurrentTick();
						z1.taxiPickupRequest += 1;
						z2.addFutureSupply();
						plist.add(p);
						
						veh.servePassenger(plist);
						
						HashMap<String, Object> record2 = new HashMap<String, Object>();
			    		record2.put("ID", vehIDOrigDestNum.vehID);
			    		record2.put("STATUS", "OK");
						jsonData.add(record2);
					}
					else {
			    		HashMap<String, Object> record2 = new HashMap<String, Object>();
			    		record2.put("ID", vehIDOrigDestNum.vehID);
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
	
	private HashMap<String, Object> dispTaxiBwRoads(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("DATA")) {
			jsonAns.put("WARN", "No DATA field found in the control message");
			jsonAns.put("CODE", "KO");
		}
		else {
			try {
				Gson gson = new Gson();
				TypeToken<Collection<VehIDOrigRoadDestRoadNum>> collectionType = new TypeToken<Collection<VehIDOrigRoadDestRoadNum>>() {};
				Collection<VehIDOrigRoadDestRoadNum> vehIDOrigRoadDestRoadNums = gson.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
				ArrayList<Object> jsonData = new ArrayList<Object>();
				
				for(VehIDOrigRoadDestRoadNum vehIDOrigRoadDestRoadNum: vehIDOrigRoadDestRoadNums) {
					ElectricTaxi veh = (ElectricTaxi) ContextCreator.getVehicleContext().getPublicVehicle(vehIDOrigRoadDestRoadNum.vehID);
					Road r1 = ContextCreator.getCityContext().findRoadWithOrigID(vehIDOrigRoadDestRoadNum.orig);
					Road r2 = ContextCreator.getCityContext().findRoadWithOrigID(vehIDOrigRoadDestRoadNum.dest);
					if(veh != null && r1 != null && r2 != null) {
						// generate request
						ArrayList<Request> plist = new ArrayList<Request>();
						Zone z1 = ContextCreator.getZoneContext().get(r1.getNeighboringZone(false));
						Zone z2 = ContextCreator.getZoneContext().get(r2.getNeighboringZone(true));
						
						Request p = new Request(z1.getID(),z2.getID(), r1.getID(), r2.getID(), vehIDOrigRoadDestRoadNum.num);
						
						if(veh.getState() == Vehicle.PARKING) {
							ContextCreator.getZoneContext().get(veh.getCurrentZone()).removeOneParkingVehicle();
						}
						
						p.matchedTime = ContextCreator.getCurrentTick();
						z1.taxiPickupRequest += 1;
						z2.addFutureSupply();
						
						plist.add(p);
						
						ContextCreator.getVehicleContext().removeAvailableTaxi(veh, z1.getID());
						veh.servePassenger(plist);
						
						HashMap<String, Object> record2 = new HashMap<String, Object>();
			    		record2.put("ID", vehIDOrigRoadDestRoadNum.vehID);
			    		record2.put("STATUS", "OK");
						jsonData.add(record2);
					}
					else {
			    		HashMap<String, Object> record2 = new HashMap<String, Object>();
			    		record2.put("ID", vehIDOrigRoadDestRoadNum.vehID);
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
	
	// Taxi dispatching
	// Find a zone, add a request
	private HashMap<String, Object> addTaxiRequests(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("DATA")) {
			jsonAns.put("WARN", "No DATA field found in the control message");
			jsonAns.put("CODE", "KO");
		}
		else {
			try {
				Gson gson = new Gson();
				TypeToken<Collection<ZoneIDOrigDestRouteNameNum>> collectionType = new TypeToken<Collection<ZoneIDOrigDestRouteNameNum>>() {};
				Collection<ZoneIDOrigDestRouteNameNum> zoneIDOrigDestNums = gson.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
				ArrayList<Object> jsonData = new ArrayList<Object>();
				
				for(ZoneIDOrigDestRouteNameNum zoneIDOrigDestNum: zoneIDOrigDestNums) {
					Zone z1 = ContextCreator.getZoneContext().get(zoneIDOrigDestNum.zoneID);
					Zone z2 = ContextCreator.getZoneContext().get(zoneIDOrigDestNum.dest);
					if(z1 != null && z2 != null) {
						// generate request
						Request p = new Request(z1.getID(), z2.getID(), z1.sampleRoad(false), z1.sampleRoad(true), zoneIDOrigDestNum.num);
						z1.insertTaxiPass(p);
						
						HashMap<String, Object> record2 = new HashMap<String, Object>();
			    		record2.put("ID", zoneIDOrigDestNum.zoneID);
			    		record2.put("STATUS", "OK");
						jsonData.add(record2);
					}
					else {
			    		HashMap<String, Object> record2 = new HashMap<String, Object>();
			    		record2.put("ID", zoneIDOrigDestNum.zoneID);
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
	
	private HashMap<String, Object> addTaxiReqBwRoads(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("DATA")) {
			jsonAns.put("WARN", "No DATA field found in the control message");
			jsonAns.put("CODE", "KO");
		}
		else {
			try {
				Gson gson = new Gson();
				TypeToken<Collection<OrigRoadDestRoadNum>> collectionType = new TypeToken<Collection<OrigRoadDestRoadNum>>() {};
				Collection<OrigRoadDestRoadNum> origRoadDestRoadNums = gson.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
				ArrayList<Object> jsonData = new ArrayList<Object>();
				
				for(OrigRoadDestRoadNum origRoadDestRoadNum: origRoadDestRoadNums) {
					Road r1 = ContextCreator.getCityContext().findRoadWithOrigID(origRoadDestRoadNum.orig);
					Road r2 = ContextCreator.getCityContext().findRoadWithOrigID(origRoadDestRoadNum.dest);
					if(r1 != null && r2 != null) {
						Zone z1 = ContextCreator.getZoneContext().get(r1.getNeighboringZone(false));
						// generate request
						Request p = new Request(z1.getID(), r2.getNeighboringZone(true), r1.getID(), r2.getID(), origRoadDestRoadNum.num);
						z1.insertTaxiPass(p);
						
						HashMap<String, Object> record2 = new HashMap<String, Object>();
			    		record2.put("ID", origRoadDestRoadNum.orig);
			    		record2.put("STATUS", "OK");
						jsonData.add(record2);
					}
					else {
			    		HashMap<String, Object> record2 = new HashMap<String, Object>();
			    		record2.put("ID", origRoadDestRoadNum.orig);
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
	
	// Assign request to a specific bus
	// TODO: revise to consider bus routes
	private HashMap<String, Object> assignRequestToBus(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("DATA")) {
			jsonAns.put("WARN", "No DATA field found in the control message");
			jsonAns.put("CODE", "KO");
		}
		else {
			try {
				Gson gson = new Gson();
				TypeToken<Collection<VehIDOrigDestNum>> collectionType = new TypeToken<Collection<VehIDOrigDestNum>>() {};
				Collection<VehIDOrigDestNum> vehIDOrigDestNums = gson.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
				ArrayList<Object> jsonData = new ArrayList<Object>();
				
				for(VehIDOrigDestNum vehIDOrigDestNum: vehIDOrigDestNums) {
					ElectricBus veh = (ElectricBus) ContextCreator.getVehicleContext().getPublicVehicle(vehIDOrigDestNum.vehID);
					int rID = veh.getRouteID();
					ArrayList<Integer> stops = ContextCreator.bus_schedule.getStopZones(rID);
					int idx0 = -1;
					int idx1 = -1;
					for (int i = veh.getCurrentStop(); i < stops.size(); i++) {
				        if (!stops.get(i).equals(vehIDOrigDestNum.orig)) continue;
				        for (int j = i+1; j < stops.size(); j++) {
				        	if(!stops.get(j).equals(vehIDOrigDestNum.dest)) continue;
				        	idx0 = i;
				        	idx1 = j;
				        }
					}
					
					if(veh != null && idx0 >= 0 && idx1 > idx0 && rID != -1) {
						// generate request
						Zone z1 = ContextCreator.getZoneContext().get(vehIDOrigDestNum.orig);
						Request p = new Request(vehIDOrigDestNum.orig, vehIDOrigDestNum.dest, ContextCreator.bus_schedule.getStopRoad(rID, idx0).getID(),  ContextCreator.bus_schedule.getStopRoad(rID, idx1).getID(), vehIDOrigDestNum.num);
						p.matchedTime = ContextCreator.getCurrentTick();
						
						if(veh.addToBoardPass(p)) {
							z1.busPickupRequest += 1;
							HashMap<String, Object> record2 = new HashMap<String, Object>();
				    		record2.put("ID", vehIDOrigDestNum.vehID);
				    		record2.put("STATUS", "OK");
							jsonData.add(record2);
							continue;
						}
					}
		    		HashMap<String, Object> record2 = new HashMap<String, Object>();
		    		record2.put("ID", vehIDOrigDestNum.vehID);
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
	
	// Add a bus request in a zone
	private HashMap<String, Object> addBusRequests(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("DATA")) {
			jsonAns.put("WARN", "No DATA field found in the control message");
			jsonAns.put("CODE", "KO");
		}
		else {
			try {
				Gson gson = new Gson();
				TypeToken<Collection<ZoneIDOrigDestRouteNameNum>> collectionType = new TypeToken<Collection<ZoneIDOrigDestRouteNameNum>>() {};
				Collection<ZoneIDOrigDestRouteNameNum> zoneIDOrigDestRouteNameNums = gson.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
				ArrayList<Object> jsonData = new ArrayList<Object>();
				
				for(ZoneIDOrigDestRouteNameNum zoneIDOrigDestRouteNameNum: zoneIDOrigDestRouteNameNums) {
					Zone z1 = ContextCreator.getZoneContext().get(zoneIDOrigDestRouteNameNum.zoneID);
					Zone z2 = ContextCreator.getZoneContext().get(zoneIDOrigDestRouteNameNum.dest);
					
					int rID = ContextCreator.bus_schedule.getRouteID(zoneIDOrigDestRouteNameNum.routeName);
					int idx0 = -1;
					int idx1 = -1;
					
					if(rID != -1) {
						ArrayList<Integer> stops = ContextCreator.bus_schedule.getStopZones(rID);
						
						for (int i = 0; i < stops.size(); i++) {
					        if (!stops.get(i).equals(zoneIDOrigDestRouteNameNum.zoneID)) continue;
					        for (int j = i+1; j < stops.size(); j++) {
					        	if(!stops.get(j).equals(zoneIDOrigDestRouteNameNum.dest)) continue;
					        	idx0 = i;
					        	idx1 = j;
					        }
						}
					}
					
					if(idx0 >= 0 && idx1 > idx0 && rID != -1) {
						// generate request
						Request p = new Request(z1.getID(), z2.getID(), ContextCreator.bus_schedule.getStopRoad(rID, idx0).getID(), ContextCreator.bus_schedule.getStopRoad(rID, idx1).getID(), zoneIDOrigDestRouteNameNum.num);
						z1.insertBusPass(p);
						HashMap<String, Object> record2 = new HashMap<String, Object>();
			    		record2.put("ID", zoneIDOrigDestRouteNameNum.zoneID);
			    		record2.put("STATUS", "OK");
						jsonData.add(record2);
					}
					else {
			    		HashMap<String, Object> record2 = new HashMap<String, Object>();
			    		record2.put("ID", zoneIDOrigDestRouteNameNum.zoneID);
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
	 
	private HashMap<String, Object> addBusRoute(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("DATA")) {
			jsonAns.put("WARN", "No DATA field found in the control message");
			jsonAns.put("CODE", "KO");
		}
		else {
			try {
				Gson gson = new Gson();
				TypeToken<Collection<RouteNameZonesRoads>> collectionType = new TypeToken<Collection<RouteNameZonesRoads>>() {};
				Collection<RouteNameZonesRoads> routeNameZonesRoads = gson.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
				ArrayList<Object> jsonData = new ArrayList<Object>();
				
				for(RouteNameZonesRoads routeNameZonesRoad: routeNameZonesRoads) {
					if(ContextCreator.bus_schedule.insertNewRouteByRoadNames(routeNameZonesRoad.routeName, routeNameZonesRoad.zones, routeNameZonesRoad.roads)) {
						HashMap<String, Object> record2 = new HashMap<String, Object>();
			    		record2.put("routeName", routeNameZonesRoad.routeName);
			    		record2.put("STATUS", "OK");
						jsonData.add(record2);
					}
					else {
						HashMap<String, Object> record2 = new HashMap<String, Object>();
			    		record2.put("routeName", routeNameZonesRoad.routeName);
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
	
	private HashMap<String, Object> addBusRouteWithPath(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("DATA")) {
			jsonAns.put("WARN", "No DATA field found in the control message");
			jsonAns.put("CODE", "KO");
		}
		else {
			try {
				Gson gson = new Gson();
				TypeToken<Collection<RouteNameZonesRoadsPath>> collectionType = new TypeToken<Collection<RouteNameZonesRoadsPath>>() {};
				Collection<RouteNameZonesRoadsPath> routeNameZonesRoadsPaths = gson.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
				ArrayList<Object> jsonData = new ArrayList<Object>();
				
				for(RouteNameZonesRoadsPath routeNameZonesRoadsPath: routeNameZonesRoadsPaths) {
					if(ContextCreator.bus_schedule.insertNewRouteByRoadNames(routeNameZonesRoadsPath.routeName, routeNameZonesRoadsPath.zones, routeNameZonesRoadsPath.roads, routeNameZonesRoadsPath.paths)) {
						HashMap<String, Object> record2 = new HashMap<String, Object>();
			    		record2.put("routeName", routeNameZonesRoadsPath.routeName);
			    		record2.put("STATUS", "OK");
						jsonData.add(record2);
					}
					else {
						HashMap<String, Object> record2 = new HashMap<String, Object>();
			    		record2.put("routeName", routeNameZonesRoadsPath.routeName);
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
	
	private HashMap<String, Object> addBusRun(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("DATA")) {
			jsonAns.put("WARN", "No DATA field found in the control message");
			jsonAns.put("CODE", "KO");
		}
		else {
			try {
				Gson gson = new Gson();
				TypeToken<Collection<RouteNameDepartTime>> collectionType = new TypeToken<Collection<RouteNameDepartTime>>() {};
				Collection<RouteNameDepartTime> routeNameDepartTimes = gson.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
				ArrayList<Object> jsonData = new ArrayList<Object>();
				
				for(RouteNameDepartTime routeNameDepartTime: routeNameDepartTimes) {
					if(ContextCreator.bus_schedule.insertBusRun(routeNameDepartTime.routeName, routeNameDepartTime.departTime)) {
						HashMap<String, Object> record2 = new HashMap<String, Object>();
						record2.put("routeName", routeNameDepartTime.routeName);
			    		record2.put("STATUS", "OK");
						jsonData.add(record2);
					}
					else {
						HashMap<String, Object> record2 = new HashMap<String, Object>();
						record2.put("routeName", routeNameDepartTime.routeName);
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
	
	// Traffic signal phase control
	// Update the signal phase given signal ID and target phase (optionally with phase time offset)
	// If only phase is provided, starts from the beginning of that phase (phaseTime = 0)
	private HashMap<String, Object> updateSignal(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("DATA")) {
			jsonAns.put("WARN", "No DATA field found in the control message. Expected: [{signalID, targetPhase, phaseTime(optional)}, ...]");
			jsonAns.put("CODE", "KO");
		}
		else {
			try {
				Gson gson = new Gson();
				TypeToken<Collection<SignalIDPhase>> collectionType = new TypeToken<Collection<SignalIDPhase>>() {};
				Collection<SignalIDPhase> signalIDPhases = gson.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
				ArrayList<Object> jsonData = new ArrayList<Object>();
				
				for(SignalIDPhase signalIDPhase: signalIDPhases) {
					Signal signal = ContextCreator.getSignalContext().get(signalIDPhase.signalID);
					if(signal != null) {
						// Set the phase (phaseTime defaults to 0 if not provided)
						boolean success = signal.setPhase(signalIDPhase.targetPhase, signalIDPhase.phaseTime);
						
						HashMap<String, Object> record2 = new HashMap<String, Object>();
						record2.put("ID", signalIDPhase.signalID);
						if(success) {
							record2.put("STATUS", "OK");
							record2.put("new_state", signal.getState());
							record2.put("next_update_tick", signal.getNextUpdateTick());
						}
						else {
							record2.put("STATUS", "KO");
							record2.put("REASON", "Invalid target phase (must be 0=Green, 1=Yellow, 2=Red)");
						}
						jsonData.add(record2);
					}
					else {
						ContextCreator.logger.warn("Cannot find the signal, signal ID: " + signalIDPhase.signalID);
						HashMap<String, Object> record2 = new HashMap<String, Object>();
						record2.put("ID", signalIDPhase.signalID);
						record2.put("STATUS", "KO");
						record2.put("REASON", "Signal not found");
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
	
	// Update signal phase timing (green, yellow, red durations)
	private HashMap<String, Object> updateSignalTiming(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("DATA")) {
			jsonAns.put("WARN", "No DATA field found in the control message. Expected: [{signalID, greenTime, yellowTime, redTime}, ...]");
			jsonAns.put("CODE", "KO");
		}
		else {
			try {
				Gson gson = new Gson();
				TypeToken<Collection<SignalIDPhaseTiming>> collectionType = new TypeToken<Collection<SignalIDPhaseTiming>>() {};
				Collection<SignalIDPhaseTiming> signalIDPhaseTimings = gson.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
				ArrayList<Object> jsonData = new ArrayList<Object>();
				
				for(SignalIDPhaseTiming signalIDPhaseTiming: signalIDPhaseTimings) {
					Signal signal = ContextCreator.getSignalContext().get(signalIDPhaseTiming.signalID);
					if(signal != null) {
						ArrayList<Integer> phaseTime = new ArrayList<Integer>();
						phaseTime.add(signalIDPhaseTiming.greenTime);
						phaseTime.add(signalIDPhaseTiming.yellowTime);
						phaseTime.add(signalIDPhaseTiming.redTime);
						
						boolean success = signal.updatePhaseTiming(phaseTime);
						
						HashMap<String, Object> record2 = new HashMap<String, Object>();
						record2.put("ID", signalIDPhaseTiming.signalID);
						if(success) {
							record2.put("STATUS", "OK");
							record2.put("phase_ticks", signal.getPhaseTick());
						}
						else {
							record2.put("STATUS", "KO");
							record2.put("REASON", "Invalid phase timing (all durations must be positive)");
						}
						jsonData.add(record2);
					}
					else {
						ContextCreator.logger.warn("Cannot find the signal, signal ID: " + signalIDPhaseTiming.signalID);
						HashMap<String, Object> record2 = new HashMap<String, Object>();
						record2.put("ID", signalIDPhaseTiming.signalID);
						record2.put("STATUS", "KO");
						record2.put("REASON", "Signal not found");
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
	
	// Set a complete new phase plan for a signal (phase timing + starting state + offset)
	// Time values are in seconds
	private HashMap<String, Object> setSignalPhasePlan(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("DATA")) {
			jsonAns.put("WARN", "No DATA field found. Expected: [{signalID, greenTime, yellowTime, redTime, startPhase, phaseOffset(optional)}, ...]");
			jsonAns.put("CODE", "KO");
		}
		else {
			try {
				Gson gson = new Gson();
				TypeToken<Collection<SignalPhasePlan>> collectionType = new TypeToken<Collection<SignalPhasePlan>>() {};
				Collection<SignalPhasePlan> signalPhasePlans = gson.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
				ArrayList<Object> jsonData = new ArrayList<Object>();
				
				for(SignalPhasePlan plan: signalPhasePlans) {
					Signal signal = ContextCreator.getSignalContext().get(plan.signalID);
					if(signal != null) {
						ArrayList<Integer> phaseTime = new ArrayList<Integer>();
						phaseTime.add(plan.greenTime);
						phaseTime.add(plan.yellowTime);
						phaseTime.add(plan.redTime);
						
						boolean success = signal.setPhasePlan(phaseTime, plan.startPhase, plan.phaseOffset);
						
						HashMap<String, Object> record2 = new HashMap<String, Object>();
						record2.put("ID", plan.signalID);
						if(success) {
							record2.put("STATUS", "OK");
							record2.put("phase_ticks", signal.getPhaseTick());
							record2.put("current_state", signal.getState());
							record2.put("next_update_tick", signal.getNextUpdateTick());
						}
						else {
							record2.put("STATUS", "KO");
							record2.put("REASON", "Invalid phase plan (check phase durations and startPhase)");
						}
						jsonData.add(record2);
					}
					else {
						ContextCreator.logger.warn("Cannot find the signal, signal ID: " + plan.signalID);
						HashMap<String, Object> record2 = new HashMap<String, Object>();
						record2.put("ID", plan.signalID);
						record2.put("STATUS", "KO");
						record2.put("REASON", "Signal not found");
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
	
	// Set a complete new phase plan with tick-level precision
	// Time values are in simulation ticks for more precise control
	private HashMap<String, Object> setSignalPhasePlanTicks(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("DATA")) {
			jsonAns.put("WARN", "No DATA field found. Expected: [{signalID, greenTicks, yellowTicks, redTicks, startPhase, tickOffset(optional)}, ...]");
			jsonAns.put("CODE", "KO");
		}
		else {
			try {
				Gson gson = new Gson();
				TypeToken<Collection<SignalPhasePlanTicks>> collectionType = new TypeToken<Collection<SignalPhasePlanTicks>>() {};
				Collection<SignalPhasePlanTicks> signalPhasePlansTicks = gson.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
				ArrayList<Object> jsonData = new ArrayList<Object>();
				
				for(SignalPhasePlanTicks plan: signalPhasePlansTicks) {
					Signal signal = ContextCreator.getSignalContext().get(plan.signalID);
					if(signal != null) {
						ArrayList<Integer> phaseTicks = new ArrayList<Integer>();
						phaseTicks.add(plan.greenTicks);
						phaseTicks.add(plan.yellowTicks);
						phaseTicks.add(plan.redTicks);
						
						boolean success = signal.setPhasePlanInTicks(phaseTicks, plan.startPhase, plan.tickOffset);
						
						HashMap<String, Object> record2 = new HashMap<String, Object>();
						record2.put("ID", plan.signalID);
						if(success) {
							record2.put("STATUS", "OK");
							record2.put("phase_ticks", signal.getPhaseTick());
							record2.put("current_state", signal.getState());
							record2.put("next_update_tick", signal.getNextUpdateTick());
						}
						else {
							record2.put("STATUS", "KO");
							record2.put("REASON", "Invalid phase plan (check phase tick durations and startPhase)");
						}
						jsonData.add(record2);
					}
					else {
						ContextCreator.logger.warn("Cannot find the signal, signal ID: " + plan.signalID);
						HashMap<String, Object> record2 = new HashMap<String, Object>();
						record2.put("ID", plan.signalID);
						record2.put("STATUS", "KO");
						record2.put("REASON", "Signal not found");
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
	
	// Route for one vehicle trip
	private HashMap<String, Object> updateVehicleRoute(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("DATA")) {
			jsonAns.put("WARN", "No DATA field found in the control message");
			jsonAns.put("CODE", "KO");
		}
		else {
			try {
				Gson gson = new Gson();
				TypeToken<Collection<VehIDVehTypeRoute>> collectionType = new TypeToken<Collection<VehIDVehTypeRoute>>() {};
				Collection<VehIDVehTypeRoute> vehIDVehTypeRoutes = gson.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
				ArrayList<Object> jsonData = new ArrayList<Object>();
				
				for(VehIDVehTypeRoute vehIDVehTypeRoute: vehIDVehTypeRoutes) {
					Vehicle veh = null;
			    	if(vehIDVehTypeRoute.vehType) { // True: private vehicles
						veh = ContextCreator.getVehicleContext().getPrivateVehicle(vehIDVehTypeRoute.vehID);
					}
					else {
						veh = ContextCreator.getVehicleContext().getPublicVehicle(vehIDVehTypeRoute.vehID);
					}
			    	if(veh != null) {
			    		HashMap<String, Object> record2 = new HashMap<String, Object>();
			    		if(veh.updateRouteByRoadName(vehIDVehTypeRoute.route)){
			    			record2.put("ID", vehIDVehTypeRoute.vehID);
				    		record2.put("STATUS", "OK");
			    		}
			    		else {
			    			record2.put("ID", vehIDVehTypeRoute.vehID);
				    		record2.put("STATUS", "KO");
			    		}
			    		jsonData.add(record2);
			    	}
			    	else {
			    		HashMap<String, Object> record2 = new HashMap<String, Object>();
			    		record2.put("ID", vehIDVehTypeRoute.vehID);
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
	
	// Route for one bus
	private HashMap<String, Object> insertStopToRoute(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("DATA")) {
			jsonAns.put("WARN", "No DATA field found in the control message");
			jsonAns.put("CODE", "KO");
		}
		else {
			try {
				Gson gson = new Gson();
				TypeToken<Collection<BusIDRouteNameZoneRoadStopIndex>> collectionType = new TypeToken<Collection<BusIDRouteNameZoneRoadStopIndex>>() {};
				Collection<BusIDRouteNameZoneRoadStopIndex> busIDRouteNameZoneRoadStopIndexes = gson.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
				ArrayList<Object> jsonData = new ArrayList<Object>();
				
				for(BusIDRouteNameZoneRoadStopIndex busIDRouteNameZoneRoadStopIndex: busIDRouteNameZoneRoadStopIndexes) {
					ElectricBus veh = (ElectricBus) ContextCreator.getVehicleContext().getPublicVehicle(busIDRouteNameZoneRoadStopIndex.busID);
					
					if(veh!= null) {
						int rID = veh.getRouteID();
						Road r = ContextCreator.getCityContext().findRoadWithOrigID(busIDRouteNameZoneRoadStopIndex.road);
						if(ContextCreator.bus_schedule.getRouteName(rID).equals(busIDRouteNameZoneRoadStopIndex.routeName) && r != null) {
							if (veh.insertStop(busIDRouteNameZoneRoadStopIndex.zone, r, busIDRouteNameZoneRoadStopIndex.stopIndex)) {
								HashMap<String, Object> record2 = new HashMap<String, Object>();
					    		record2.put("ID", busIDRouteNameZoneRoadStopIndex.busID);
					    		record2.put("STATUS", "OK");
								jsonData.add(record2);
							}
							else {
								HashMap<String, Object> record2 = new HashMap<String, Object>();
					    		record2.put("ID", busIDRouteNameZoneRoadStopIndex.busID);
					    		record2.put("STATUS", "KO");
								jsonData.add(record2);
							}
						}
						else {
							ContextCreator.logger.info("insertStopToRoute: bus route or road name incorrect.");
							HashMap<String, Object> record2 = new HashMap<String, Object>();
				    		record2.put("ID", busIDRouteNameZoneRoadStopIndex.busID);
				    		record2.put("STATUS", "KO");
							jsonData.add(record2);
						}
					}
					else {
						ContextCreator.logger.info("insertStopToRoute: cannot find bus with ID: " +  busIDRouteNameZoneRoadStopIndex.busID);
						HashMap<String, Object> record2 = new HashMap<String, Object>();
			    		record2.put("ID", busIDRouteNameZoneRoadStopIndex.busID);
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
	
	private HashMap<String, Object> removeStopFromRoute(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("DATA")) {
			jsonAns.put("WARN", "No DATA field found in the control message");
			jsonAns.put("CODE", "KO");
		}
		else {
			try {
				Gson gson = new Gson();
				TypeToken<Collection<BusIDRouteNameStopIndex>> collectionType = new TypeToken<Collection<BusIDRouteNameStopIndex>>() {};
				Collection<BusIDRouteNameStopIndex> busIDRouteNameStopIndexes = gson.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
				ArrayList<Object> jsonData = new ArrayList<Object>();
				
				for(BusIDRouteNameStopIndex busIDRouteNameStopIndex: busIDRouteNameStopIndexes) {
					ElectricBus veh = (ElectricBus) ContextCreator.getVehicleContext().getPublicVehicle(busIDRouteNameStopIndex.busID);
					
					if(veh!= null) {
						int rID = veh.getRouteID();
						if(ContextCreator.bus_schedule.getRouteName(rID).equals(busIDRouteNameStopIndex.routeName)) {
							if (veh.removeStop(busIDRouteNameStopIndex.stopIndex)) {
								HashMap<String, Object> record2 = new HashMap<String, Object>();
					    		record2.put("ID", busIDRouteNameStopIndex.busID);
					    		record2.put("STATUS", "OK");
								jsonData.add(record2);
							}
							else {
								HashMap<String, Object> record2 = new HashMap<String, Object>();
					    		record2.put("ID", busIDRouteNameStopIndex.busID);
					    		record2.put("STATUS", "KO");
								jsonData.add(record2);
							}
						}
						else {
							ContextCreator.logger.info("removeStopFromRoute: bus route or road name incorrect.");
							HashMap<String, Object> record2 = new HashMap<String, Object>();
				    		record2.put("ID", busIDRouteNameStopIndex.busID);
				    		record2.put("STATUS", "KO");
							jsonData.add(record2);
						}
					}
					else {
						ContextCreator.logger.info("insertStopToRoute: cannot find bus with ID: " +  busIDRouteNameStopIndex.busID);
						HashMap<String, Object> record2 = new HashMap<String, Object>();
			    		record2.put("ID", busIDRouteNameStopIndex.busID);
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
	
	
	
	// Weight for online shortest path
	private HashMap<String, Object> updateEdgeWeight(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("DATA")) {
			jsonAns.put("WARN", "No DATA field found in the control message");
			jsonAns.put("CODE", "KO");
		}
		else {
			try {
				Gson gson = new Gson();
				TypeToken<Collection<RoadIDWeight>> collectionType = new TypeToken<Collection<RoadIDWeight>>() {};
			    Collection<RoadIDWeight> roadIDWeights = gson.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
			    ArrayList<Object> jsonData = new ArrayList<Object>();
			    
			    for(RoadIDWeight roadIDWeight: roadIDWeights) {
			    	Road r = ContextCreator.getCityContext().findRoadWithOrigID(roadIDWeight.roadID);
			    	if(r != null) {
			    		Node node1 = r.getUpStreamNode();
				    	Node node2 = r.getDownStreamNode();
				    	ContextCreator.getRoadNetwork().getEdge(node1, node2).setWeight(Math.max(roadIDWeight.weight, 1e-3)); // weight cannot be negative 
						HashMap<String, Object> record2 = new HashMap<String, Object>();
			    		record2.put("ID", roadIDWeight.roadID);
			    		record2.put("STATUS", "OK");
						jsonData.add(record2);
			    	}
			    	else {
			    		ContextCreator.logger.warn("Cannot find the road, road ID: " + roadIDWeight.roadID);
			    		HashMap<String, Object> record2 = new HashMap<String, Object>();
			    		record2.put("ID", roadIDWeight.roadID);
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
	
	// Price for searching charging stations
	private HashMap<String, Object> updateChargingPrice(JSONObject jsonMsg) {
		HashMap<String, Object> jsonAns = new HashMap<String, Object>();
		if(!jsonMsg.containsKey("DATA")) {
			jsonAns.put("WARN", "No DATA field found in the control message");
			jsonAns.put("CODE", "KO");
		}
		else {
			try {
				Gson gson = new Gson();
				TypeToken<Collection<ChargerIDChargerTypeWeight>> collectionType = new TypeToken<Collection<ChargerIDChargerTypeWeight>>() {};
			    Collection<ChargerIDChargerTypeWeight> chargerIDChargerTypeWeights = gson.fromJson(jsonMsg.get("DATA").toString(), collectionType.getType());
			    ArrayList<Object> jsonData = new ArrayList<Object>();
			    
			    for(ChargerIDChargerTypeWeight chargerIDChargerTypeWeight: chargerIDChargerTypeWeights) {
			    	ChargingStation cs = ContextCreator.getChargingStationContext().get(chargerIDChargerTypeWeight.chargerID);
			    	if(cs != null) {
			    		boolean success = cs.setPrice(chargerIDChargerTypeWeight.chargerType, chargerIDChargerTypeWeight.weight);
			    		HashMap<String, Object> record2 = new HashMap<String, Object>();
			    		record2.put("ID", chargerIDChargerTypeWeight.chargerID);
			    		if(success) {
			    			record2.put("STATUS", "OK");
			    		}
			    		else {
			    			record2.put("STATUS", "KO");
			    		}
						jsonData.add(record2);
			    	}
			    	else {
			    		ContextCreator.logger.warn("Cannot find the charging station, ID: " + chargerIDChargerTypeWeight.chargerID);
			    		HashMap<String, Object> record2 = new HashMap<String, Object>();
			    		record2.put("ID", chargerIDChargerTypeWeight.chargerID);
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
}
