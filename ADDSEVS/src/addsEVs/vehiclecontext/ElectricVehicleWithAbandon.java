package addsEVs.vehiclecontext;

import java.io.IOException;
import com.vividsolutions.jts.geom.Coordinate;

import addsEVs.ContextCreator;
import addsEVs.GlobalVariables;
import addsEVs.citycontext.ChargingStation;
import addsEVs.citycontext.ChargingStationWithAbandon;
import addsEVs.citycontext.Plan;
import addsEVs.citycontext.Request;
import addsEVs.citycontext.Zone;
import repast.simphony.essentials.RepastEssentials;

/**
 * 
 * @author Xiaowei Chen
 *
 */

public class ElectricVehicleWithAbandon extends ElectricVehicle{
	// Local variables
	public int EV_ID; // the id of generated ev as order
	private boolean Initialshow = true;
	public double batteryRechargeprob = 0.2; // if the random variable is smaller than 0.3, then ev has the prob to recharge
	public int start_zone_id = 0;
	public int sortid_charging_station; // this indicates to find the order of distance sorted charging station near this ev
	public double utility_for_service = 0;
	private double valuationdriver;//ev's valuation of the trip
	
	public void setInitialParams() {
		// added by xiaowei on 09/29
		super.setInitialParams();
		this.EV_ID = GlobalVariables.Random_ID_EV;
		GlobalVariables.Random_ID_EV +=1;
		this.batteryLevel_ =  (0.2 + (1 - 0.3) * ContextCreator.cachedRandomValue.valuationEV.get(this.EV_ID + 10)) * GlobalVariables.EV_BATTERY; // modified by xiaowei on 09/29
		System.out.println("EV_ID" + this.EV_ID + ", Initial_battery:" + this.batteryLevel_);
		//unit:kWh, full capacity times a random variable to give taxi SoC 
		this.valuationdriver =  ContextCreator.cachedRandomValue.randomValue.get(this.EV_ID);
		this.sortid_charging_station = 0; // should start from 0
	}
	
	public ElectricVehicleWithAbandon(){
		super();
		this.setInitialParams();
	}
	public ElectricVehicleWithAbandon(float maximumAcceleration, float maximumDeceleration) {
		super( maximumAcceleration, maximumDeceleration);
		this.setInitialParams();
	}
	
	// Find the closest charging station and update the activity plan
	@Override
	public void goCharging(){
		super.setReachDest();
		int current_dest_zone = this.getDestID();
		Coordinate current_dest_coord = this.getDestCoord();
		// Add a charging activity
		ChargingStation cs = ContextCreator.getCityContext().findNearestChargingStation(this.getCurrentCoord(), 0);
		this.onChargingRoute_ = true;
		this.setState(Vehicle.CHARGING_TRIP);
		this.addPlan(cs.getIntegerID(), cs.getCoord(), (int) RepastEssentials.GetTickCount());
		this.setNextPlan();
		this.addPlan(current_dest_zone, current_dest_coord, (int) RepastEssentials.GetTickCount());
		this.departure();
		ContextCreator.getCityContext().findZoneWithIntegerID(current_dest_zone).removeFutureSupply();
		ContextCreator.logger.info("Vehicle "+ this.EV_ID+" is on route to charging" + ", and its SOC is  "+ this.batteryLevel_);
	}
	
	// re-find the closest charging station and update the activity plan, due to meeting a full charging station
	public void refindCharging(int originID, int sortid_charging_station){ // added by xiaowei on 09/29
		Plan finalone = this.activityplan.get(1);
		int finalone_dest_zone = finalone.getDestID();
		Coordinate finalone_dest_coord = finalone.getLocation();
		this.activityplan.remove(0); //  remove current go charging schedule, now, the current destination schedule is at position 0
		this.onChargingRoute_ = true;
		this.sortid_charging_station +=1;
		// Find a list of sorted charging station based on distance, and find the id = sortid_charging_station one
		// since the ev is in the nearest charging station (sorted id =0), find the next nearest one, so the sortid should be 1.		
		ChargingStation cs = ContextCreator.getCityContext().findNearestChargingStation(this.getCurrentCoord(), 1); 
		this.addPlan(cs.getIntegerID(), cs.getCoord(), (int) RepastEssentials.GetTickCount());
		this.setNextPlan();
		this.addPlan(finalone_dest_zone, finalone_dest_coord, (int) RepastEssentials.GetTickCount());
		this.departure();
		System.out.println("vid:"+this.getVehicleFakeID()+" activityplan size: " + this.activityplan.size());
		//ContextCreator.logger.info("Vehicle "+ this.getId()+" is on route to charging ( road : "+this.road.getLinkid()+")");
	}	

	@Override
	public void setReachDest() {
		// Check if the vehicle was on a charging route
		if (this.onChargingRoute_) {
			super.setReachDest(); // remove from the network
			// Add to the charging station
			// if return_value ==0, the current charging station is full,
			// then if the batteryLevel_ > batteryRechargeLevel, ev stops searching for cs
			// and continue to provide service (which similar to the condition of finish
			// charging)
			// otherwise, the ev quits the entire system
			ChargingStationWithAbandon cs = (ChargingStationWithAbandon) ContextCreator.getCityContext()
					.findNearestChargingStation(this.getCurrentCoord(), 0);
			boolean return_value = cs.tryToReceiveEV(this);
			if (this.sortid_charging_station < 3 && !return_value) { // if ev face with full cs less than 3 times, and
																		// the return_value==0, Do refindCharging
				String formated_msg = RepastEssentials.GetTickCount() + "," + this.getVehicleFakeID() + ","
						+ this.start_zone_id + "," + this.getVehicleID() + ",5," + this.getOriginID() + ","
						+ this.getDestID() + "," + this.getAccummulatedDistance() + "," + this.getDepTime() + ","
						+ this.getTripConsume() + ", charging station id" + cs.getIntegerID() + ","
						+ this.getNumPeople();
				try {
					ContextCreator.ev_logger.write(formated_msg);
					ContextCreator.ev_logger.newLine();
				} catch (IOException e) {
					e.printStackTrace();
				}
				ContextCreator.logger.info("Vehicle" + this.getVehicleFakeID()
						+ " arrives at occupied charging station:" + cs.getIntegerID() + ", we assign next nearby cs");
				this.refindCharging(this.getDestID(), this.sortid_charging_station);
//				cs = ContextCreator.getCityContext().findNearestChargingStation(this.getCurrentCoord(), 1); 
//				return_value = cs.receiveVehicle(this);
				System.out.println("Reassignment CS " + cs.getIntegerID() + " for EV " + this.getVehicleFakeID()
						+ ", and the return_value is " + return_value);
			} else if (this.sortid_charging_station >= 3 && !return_value) {
				if (this.batteryLevel_ > lowerBatteryRechargeLevel_) { // go back and provide services
					System.out.println("Three times for CSs & 0; ID: " + cs.getIntegerID());
					// set 8 = Vehicle wants to recharge, but meet full CSs for three times.
					String formated_msg = RepastEssentials.GetTickCount() + "," + this.getVehicleFakeID() + ","
							+ this.start_zone_id + "," + this.getVehicleID() + ",8," + this.getOriginID() + ","
							+ this.getDestID() + "," + this.getAccummulatedDistance() + "," + this.getDepTime() + ","
							+ this.getTripConsume() + ",-1" + "," + this.getNumPeople();
					try {
						ContextCreator.ev_logger.write(formated_msg);
						ContextCreator.ev_logger.newLine();
					} catch (IOException e) {
						e.printStackTrace();
					}
					this.onChargingRoute_ = false;
					this.sortid_charging_station = 0;
					this.setNextPlan();
					ContextCreator.getCityContext().findZoneWithIntegerID(this.getDestID()).addFutureSupply();
					this.departure();
				} else { // leave the system
					System.out.println("Vehicle leaves the system due to three full CSs.");
					// set 6= Vehicle leaves system due to three full CSs
					String formated_msg = RepastEssentials.GetTickCount() + "," + this.getVehicleFakeID() + ","
							+ this.start_zone_id + "," + this.getVehicleID() + ",6," + this.getOriginID() + ","
							+ this.getDestID() + "," + this.getAccummulatedDistance() + "," + this.getDepTime() + ","
							+ this.getTripConsume() + ",-1" + "," + this.getNumPeople();
					try {
						ContextCreator.ev_logger.write(formated_msg);
						ContextCreator.ev_logger.newLine();
					} catch (IOException e) {
						e.printStackTrace();
					}
				}
			} else if (return_value) {
				String formated_msg = RepastEssentials.GetTickCount() + "," + this.getVehicleFakeID() + ","
						+ this.start_zone_id + "," + this.getVehicleID() + ",4," + this.getOriginID() + ","
						+ this.getDestID() + "," + this.getAccummulatedDistance() + "," + this.getDepTime() + ","
						+ this.getTripConsume() + ",-1" + "," + this.getNumPeople();
				try {
					ContextCreator.ev_logger.write(formated_msg);
					ContextCreator.ev_logger.newLine();
				} catch (IOException e) {
					e.printStackTrace();
				}

				// this.onRoad = true;
				this.tripConsume = 0;

				if (this.sortid_charging_station > 0) {
					System.out.println("vid:" + this.getVehicleFakeID() + "; activityplan size: "
							+ this.activityplan.size() + "; sortid_charging_station:" + this.sortid_charging_station
							+ "; batteryLevel_:" + this.batteryLevel_);
				}
			}
		} else {
			// if ev is not on the charging route, then setNextPlan for ev
			String formated_msg = RepastEssentials.GetTickCount() + "," + this.getVehicleFakeID() + ","
					+ this.start_zone_id + "," + this.getVehicleID() + "," + this.getState() + "," + this.getOriginID()
					+ "," + this.getDestID() + "," + this.getAccummulatedDistance() + "," + this.getDepTime() + ","
					+ this.getTripConsume() + "," + this.getRouteChoice() + "," + this.getNumPeople();
			try {
				ContextCreator.ev_logger.write(formated_msg);
				ContextCreator.ev_logger.newLine();
			} catch (IOException e) {
				e.printStackTrace();
			}
			this.tripConsume = 0;

			if (this.getState() == Vehicle.OCCUPIED_TRIP) {
				this.setNumPeople(this.getNumPeople() - 1); // passenger arrived
				ContextCreator.getCityContext().findZoneWithIntegerID(this.getDestID()).taxiServedRequest += 1;
				// if pass need to take the bus to complete his or her trip
				if (this.passengerWithAdditionalActivityOnTaxi.size() > 0) {
					// generate a pass and add it to the corresponding zone
					Request p = this.passengerWithAdditionalActivityOnTaxi.poll();
					p.moveToNextActivity();
					Zone z = ContextCreator.getCityContext().findZoneWithIntegerID(this.getDestID());
					if (z.busTravelTime.containsKey(p.getDestination())) {// if bus can reach the destination
						z.addBusPass(p);
					} else {
						// this happens when we dynamically update bus schedules
						z.addTaxiPass(p);
					}
				}
			}

			double random = ContextCreator.cachedRandomValue.randomValue.get(this.EV_ID);
			if (!this.onChargingRoute_ && this.getNumPeople() == 0) {
				if (this.Initialshow) { // if ev first show with prob want to charge, then gocharging
					this.Initialshow = false;
					if (random <= batteryRechargeprob) {
						this.goCharging();
					} else {
						ContextCreator.getVehicleContext().getVehiclesByZone(this.getDestID()).add(this);// append this
																											// vehicle
																											// to the
																											// available
																											// vehicle
																											// of given
																											// zones
						ContextCreator.getCityContext().findZoneWithIntegerID(this.getDestID()).addOneVehicle();
						super.setReachDest(); // Call setReachDest in vehicle class.
					}
				} else if (this.batteryLevel_ <= lowerBatteryRechargeLevel_) { // if ev is not first show, then only
																				// gocharging if reaches recharge level
					this.goCharging();
				} else {
					ContextCreator.getVehicleContext().getVehiclesByZone(this.getDestID()).add(this);// append this
																										// vehicle to
																										// the available
																										// vehicle of
																										// given zones
					ContextCreator.getCityContext().findZoneWithIntegerID(this.getDestID()).addOneVehicle();
					super.setReachDest(); // Call setReachDest in vehicle class.
				}
			} else if (this.getNumPeople() > 0) {
				super.setReachDest();
				this.setNextPlan();
				this.departure();
			} else {
				ContextCreator.getVehicleContext().getVehiclesByZone(this.getDestID()).add(this);// append this vehicle
																									// to the available
																									// vehicle of given
																									// zones
				ContextCreator.getCityContext().findZoneWithIntegerID(this.getDestID()).addOneVehicle();
				super.setReachDest(); // Call setReachDest in vehicle class.
			}
		}
	}
	
	@Override
	public void finishCharging(Integer chargerID, String chargerType){
		String formated_msg = RepastEssentials.GetTickCount()+ "," + chargerID + "," + this.getVehicleID() + "," + this.getVehicleClass()
		+ "," + chargerType + "," + this.charging_waiting_time + "," + this.charging_time + ","
		+ this.initial_charging_state + "," + this.sortid_charging_station;
		try {
			ContextCreator.charger_logger.write(formated_msg);
			ContextCreator.charger_logger.newLine();
			this.charging_waiting_time = 0;
			this.charging_time = 0;
		} catch (IOException e) {
			e.printStackTrace();
		}
		
		double C_car = GlobalVariables.EV_BATTERY;
		this.utility_for_service = this.utility_for_service/(C_car - this.initial_charging_state);
		this.onChargingRoute_ = false;
		this.sortid_charging_station =0;
		this.setNextPlan();
		ContextCreator.getCityContext().findZoneWithIntegerID(this.getDestID()).addFutureSupply();
		this.departure();
	}
	
	public int getVehicleFakeID() {
		return this.EV_ID;
	}
	
	public double getValuationDriver(){
		return this.valuationdriver;
	}
	
	public int setStartZoneID(int start_zone_id){
		return this.start_zone_id = start_zone_id;
	}	
}

