package addsEVs.vehiclecontext;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Dictionary;
import java.util.Hashtable;
import org.apache.commons.math3.analysis.interpolation.SplineInterpolator;
import org.apache.commons.math3.analysis.polynomials.PolynomialSplineFunction;
import com.vividsolutions.jts.geom.Coordinate;
import addsEVs.ContextCreator;
import addsEVs.GlobalVariables;
import addsEVs.citycontext.ChargingStation;
import addsEVs.citycontext.Passenger;
import addsEVs.citycontext.Road;
import addsEVs.citycontext.Zone;
import addsEVs.data.DataCollector;
import repast.simphony.essentials.RepastEssentials;

/**
 * 
 * @author Zengxiang Lei, Jiawei Xue
 *
 */

public class Bus extends Vehicle{
	private int routeID;
	private int passNum;
	private int numSeat;                        // Capacity of the bus.
	private int nextStop;                       // nextStop =i  means the i-th entry of ArrayList<Integer> busStop. i=0,1,2,...,17.
	private ArrayList<Integer> busStop;         
	// Each entry represents a bus stop (zone) along the bus route.
	// For instance, it is [0,1,2,3,4,5,6,7,8,9,8,7,6,5,4,3,2,1];
	private Dictionary<Integer, Integer> stopBus; // LZ: reverse table for track the zone in the busStop;
	
	// Timetable variable here, the next departure time
	private int nextDepartureTime;
	
	private ArrayList<Integer> destinationDemandOnBus;  
	// The destination distribution of passengers on the bus now.  
	// [x0,x1,x2,x3,x4,x5,x6,x7,x8,x9]. xi means that there are xi passengers having the destination of zone i.
	
	private boolean onChargingRoute_ = false;
	private double batteryLevel_; // current battery level
	private double avgPersonMass_; // average mass of a person in lbs
	private double batteryRechargeLevel_ = 50.0;  //the bus recharges itself when the battery is 50kWh.
	private double mass_; // mass of the vehicle (consider passengers' weight) in kg for energy calcuation
	private double mass; // mass of the vehicle in kg
	
	public int charging_time = 0; // total time of charging
	public int served_pass = 0;
	public int charging_waiting_time = 0;
	public double initial_charging_state = 0;
	
	// Parameters for storing energy consumptions
	private double tickConsume;
	private double totalConsume;
	
	private double linkConsume; // for UCB eco-routing, energy spent for passing current link, will be reset to zero once this ev entering a new road.
	private double tripConsume; // for UCB testing
	
	// Parameters for Fiori (2016) model, 
	// Modified frontal area and draft coefficient according to Bus 2 in "Aerodynamic Exterior Body Design of Bus"
	public static double p0 = 1.2256;
	public static double A = 8; // frontal area of the vehicle // we used 6.93 to run the experiment
	public static double cd = 0.68; // draft coefficient
	public static double cr = 1.75;
	public static double c1 = 0.0328;
	public static double c2 = 4.575;
	public static double etaM = 0.92;
	public static double etaG = 0.91;
	public static double Pconst = 5500; // energy consumption by auxiliary accessories
	public static double gravity = 9.8; // the gravity is 9.80N/kg for NYC
	
	public static double batteryCapacity = GlobalVariables.BUS_BATTERY; // the storedEnergy is 250 kWh.
	
	private int originID = -1;
	private int destinationID = -1;
	// Parameter to show which route has been chosen in eco-routing.
	private int routeChoice = -1;
	
	// Function 1: constructors
	public Bus(int routeID, ArrayList<Integer> route, int nextDepartureTime){
		super(Vehicle.EBUS); // Class 2 means bus
		this.routeID = routeID;
		this.busStop = route;  
		this.originID = route.get(0);
		this.stopBus = new Hashtable<Integer, Integer>();
		for(int i=0;i<route.size();i++){
			stopBus.put(route.get(i), i);
		}
		this.nextDepartureTime = nextDepartureTime;
		this.destinationDemandOnBus = new ArrayList<Integer>(Collections.nCopies(this.busStop.size(), 0));
		this.setInitialParams();
	}
	
	// Function 2: setInitialParams
	public void setInitialParams() {
		this.passNum = 0;
		this.nextStop = Math.min(1, this.busStop.size()-1);
		this.numSeat = 40;
		this.batteryLevel_ =  GlobalVariables.BUS_BATTERY;    // the battery capacity for the bus is 250.0 kWh.
		this.mass = 18000.0; // the weight of bus is 18t.
		this.mass_ = mass * 1.05;           
		this.avgPersonMass_ = 180.0;
		
		this.tickConsume = 0.0; //kWh
		this.totalConsume = 0.0; //kWh
	}
	
	// Function 4: updateBatteryLevel
	@Override
	public void updateBatteryLevel() {
		if(this.routeID>=0) {
			double tickEnergy = calculateEnergy(); // the energy consumption(kWh) for this tick
			tickConsume = tickEnergy;
			totalConsume += tickEnergy;
			linkConsume += tickEnergy;
			tripConsume += tickEnergy;
			 ContextCreator.logger.debug( Double.toString(totalConsume) +','+
			 Double.toString(this.accummulatedDistance_));
			 ContextCreator.logger.debug( Double.toString(tickConsume) +','+
			 Double.toString(this.currentSpeed()));
			batteryLevel_ -= tickEnergy;
		}
	}
	 
	public double getTripConsume(){
			return tripConsume;
	}

	// Function 5: busDeparture from current stop
	public void departure(int destinationID){
		this.destinationID = destinationID;
		Coordinate currentCoord = this.getOriginalCoord();
		Road road = ContextCreator.getCityContext().findRoadAtCoordinates(currentCoord, false);
		road.addVehicleToNewQueue(this);
		ContextCreator.logger.debug("Bus "+this.vehicleID_+" is re-entering the Road " + road.getID());
	}
	
	//The setReachDest() function applies for three cases: 
	//Case 1: arrive at the charging station.
	//Case 2: arrive at the start bus stop, and then go the charging station
	//Case 3: (arrive at the other bus stop), or (arrive at the start bus stop and continue to move)
	public void setReachDest() {
		// Case 1: the bus arrives at the charging station
		if (onChargingRoute_) {
			String formated_msg = RepastEssentials.GetTickCount() + "," + 
			this.getVehicleID() + ","+ this.getRouteID()+",4,"+ this.getOriginID()+","+
							this.getDestinationID()+"," + this.accummulatedDistance_ +"," +this.getDepTime()+","+this.getTripConsume()+",-1"+ "," + this.getPassNum();
			try{
				ContextCreator.bus_logger.write(formated_msg);
				ContextCreator.bus_logger.newLine();
				this.accummulatedDistance_=0;
			} catch(IOException e){
				e.printStackTrace();
			}
			this.leaveNetwork();                // remove the bus from the network
			ContextCreator.logger.debug("Bus arriving at charging station:"+this.getId());
			ChargingStation cs = ContextCreator.getCityContext().findChargingStationWithID(this.getDestinationZoneID());
			cs.receiveBus(this);
			this.originID = cs.getIntegerID();
			this.endTime = (int) RepastEssentials.GetTickCount();
			this.reachActLocation = true;
			this.tripConsume=0;
			this.accummulatedDistance_=0;
		// Case 2: the bus arrives at the start bus stop
		}else if(nextStop ==0 && batteryLevel_ <= batteryRechargeLevel_){
			if(this.originID!=this.destinationID) {
				String formated_msg = RepastEssentials.GetTickCount() + "," + 
						this.getVehicleID() + "," + this.getRouteID() + ",3,"
						+ this.getOriginID() + "," + this.getDestinationID() + "," + this.accummulatedDistance_ + ","
						+ this.getDepTime() + "," + this.getTripConsume() + "," + this.routeChoice+ "," + this.getPassNum();
				try {
					ContextCreator.bus_logger.write(formated_msg);
					ContextCreator.bus_logger.newLine();
					ContextCreator.bus_logger.flush();
					this.accummulatedDistance_ = 0;
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
			
			this.originID = this.destinationID;
			// Store the current information
			int current_dest_zone = this.destinationZone;
			Coordinate current_dest_coord = this.destCoord;
			
			// Drop off passengers.
			this.leaveNetwork();
			ContextCreator.logger.debug("Bus arriving at origin stop: " + nextStop);
			this.setPassNum(this.getPassNum()-this.destinationDemandOnBus.get(nextStop));
			this.destinationDemandOnBus.set(nextStop,0);
			
			// Add the plan to the charging station.
			ChargingStation cs = ContextCreator.getCityContext().findNearestBusChargingStation(this.getCurrentCoord());
			this.addPlan(cs.getIntegerID(), cs.getCoord(), (int) RepastEssentials.GetTickCount()); // instantly go to charging station
			ContextCreator.logger.debug("Bus "+ this.getId()+" is on route to charging ( road : "+this.road.getLinkid()+")");
			this.onChargingRoute_ = true;
			this.setState(Vehicle.CHARGING_TRIP);
			this.setNextPlan();	
			this.addPlan(current_dest_zone, current_dest_coord, Math.max((int) RepastEssentials.GetTickCount(), nextDepartureTime)); // Follow the old schedule
			this.tripConsume=0;
			this.accummulatedDistance_=0;
			this.departure(cs.getIntegerID());
		}else{
			// Case 3: (arrive at the other bus stop), or (arrive at the start bus stop and continue to move)
			// drop off passengers at the stop 
			String formated_msg = RepastEssentials.GetTickCount() + "," + 
					this.getVehicleID() + "," + this.getRouteID() + ",3,"
					+ this.getOriginID() + "," + this.getDestinationID() + "," + this.accummulatedDistance_ + ","
					+ this.getDepTime() + "," + this.getTripConsume() + "," + this.routeChoice+ "," + this.getPassNum();
			try {
				ContextCreator.bus_logger.write(formated_msg);
				ContextCreator.bus_logger.newLine();
				this.accummulatedDistance_ = 0;
			} catch (IOException e) {
				e.printStackTrace();
			}
			
			this.originID = this.destinationID;
			this.leaveNetwork();                           
			ContextCreator.logger.debug("Bus arriving at bus stop: " + nextStop);
			this.setPassNum(this.getPassNum()-this.destinationDemandOnBus.get(nextStop));
			this.destinationDemandOnBus.set(nextStop,0);
			
			// Serve passengers
			this.servePassenger();
			
			// Start the bus again
			if (nextStop == busStop.size()-1) { // arrive at the last stop
				nextStop = 0;
				ContextCreator.busSchedule.popSchedule(this.busStop.get(0), this);
			}
			else{
				nextStop = Math.min(nextStop + 1, this.busStop.size()-1);        //arrive at the other bus stop
			}
			
			this.addPlan(busStop.get(nextStop), 
					ContextCreator.getCityContext().findHouseWithDestID(busStop.get(nextStop)).getCoord(), 
					Math.max((int) RepastEssentials.GetTickCount(), nextDepartureTime));
			this.setNextPlan();
			ContextCreator.logger.debug("Bus "+this.id+" has arrive the next station: " +nextStop);
			this.tripConsume=0;
			this.accummulatedDistance_=0;
			this.departure(this.busStop.get(nextStop));
		}
	}

	private void servePassenger() {
		// ServePassengerByBus
		Zone arrivedZone = ContextCreator.getCityContext().findHouseWithDestID(busStop.get(nextStop));
		ArrayList<Passenger> passOnBoard = arrivedZone.servePassengerByBus(this.numSeat-this.passNum, busStop);
		for(Passenger p: passOnBoard){
			this.destinationDemandOnBus.set(this.stopBus.get(p.getDestination()),destinationDemandOnBus.get(this.stopBus.get(p.getDestination()))+1);
		}
		this.served_pass+=passOnBoard.size();
		this.setPassNum(this.getPassNum()+passOnBoard.size());
	}

	// Charge the battery.
	public void chargeItself(double batteryValue) {
		charging_time += GlobalVariables.SIMULATION_CHARGING_STATION_REFRESH_INTERVAL;
		batteryLevel_ += batteryValue;
	}
	
	@Override
	public boolean onChargingRoute(){
		return this.onChargingRoute_;
	}
	
	public double getBatteryLevel() {
		return batteryLevel_;
	}
	
	public int getPassNum(){
		return this.passNum;
	}
	
	public void setPassNum(int numPeople){
		this.passNum = numPeople;
	}
	
    public int getNextStop(){
    	return busStop.get(nextStop);
    }
    
    public int getRouteID(){
    	return this.routeID;
    }
    
    public double getMass() {
    	return this.mass_ + this.avgPersonMass_* passNum;
    }
    
    // New EV energy consumption model
 	// Fiori, C., Ahn, K., & Rakha, H. A. (2016). Power-based electric vehicle energy consumption model: Model development and validation. Applied Energy, 168, 257�268.
 	// P = (ma + mgcos(\theta)\frac{C_r}{1000)(c_1v+c_2) + 1/2 \rho_0 AC_Dv^2+mgsin(\theta))v
 	public double calculateEnergy(){
 		double velocity = currentSpeed();   // obtain the speed
 		double acceleration = currentAcc(); // obtain the acceleration
 		if(!this.movingFlag){
 			velocity = 0;
 			acceleration = 0;
 		}
 		double slope = 0.0f;          //positive: uphill; negative: downhill, this is always 0, change this if the slope data is available
 		double dt = GlobalVariables.SIMULATION_STEP_SIZE;   // this time interval, the length of one tick. 0.3
 		double f1 = getMass() * acceleration;
 		double f2 = getMass() * gravity * Math.cos(slope)*cr/1000*(c1*velocity+c2);
 		double f3 = 1/2*p0*A*cd*velocity*velocity;
 		double f4 = getMass() * gravity * Math.sin(slope);
 		double F = f1+f2+f3+f4;
 		double Pte = F*velocity;
 		double Pbat;
 		if(acceleration>=0){
 			Pbat = (Pte + Pconst)/(etaM*etaG);
 		}
 		else{
 			double nrb = 1/Math.exp(0.0411/Math.abs(acceleration));
 			Pbat = (Pte + Pconst)*nrb;
 		}
 		double energyConsumption = Pbat*dt/(3600*1000);
 		return energyConsumption;
 	}
    
 	public PolynomialSplineFunction splineFit(double[] x, double[] y) {
 		SplineInterpolator splineInt = new SplineInterpolator();
 		PolynomialSplineFunction polynomialSpl = splineInt.interpolate(x, y);
 		return polynomialSpl;
 	}

 	public double getTickConsume() {
 		return tickConsume;
 	}

 	public double getTotalConsume() {
 		return totalConsume;
 	}
 	
 	public void finishCharging(Integer chargerID, String chargerType){
		String formated_msg = RepastEssentials.GetTickCount() + "," + chargerID + "," + this.getVehicleID() + ","
				+ this.getVehicleClass() + "," + chargerType + "," + this.charging_waiting_time + ","
				+ this.charging_time + "," + this.initial_charging_state;
		try {
			ContextCreator.charger_logger.write(formated_msg);
			ContextCreator.charger_logger.newLine();
//			ContextCreator.charger_logger.flush();
			this.charging_waiting_time = 0;
			this.charging_time = 0;
		} catch (IOException e) {
			e.printStackTrace();
		}
 		this.onChargingRoute_ = false;
 	}
 	
	public int getOriginID(){
		return this.originID;
	}
	
	public int getDestinationID(){
		return this.destinationID;
	}
	
	public void setRouteChoice(int i) {
		this.routeChoice = i;
	}
	
	public double getLinkConsume(){
		return linkConsume;
	}
	
	// Reset link consume once a EV has passed a link
	public void resetLinkConsume(){ 
		this.linkConsume = 0;
	}
	
	public void recLinkSnaphotForUCBBus() {
		//System.out.println("Record data for UCB!");
		DataCollector.getInstance().recordLinkSnapshotBus(this.getRoad().getLinkid(),this.getLinkConsume());
		this.resetLinkConsume(); // Reset link consumption to 0
	}
	
	public void updateSchedule(int newID, ArrayList<Integer> newRoute, int departureTime) {
		if(newID==-1) {
			this.routeID = -1;
			this.busStop = new ArrayList<Integer>(Arrays.asList(this.busStop.get(0)));
			this.nextDepartureTime = (int) (RepastEssentials.GetTickCount()+600/GlobalVariables.SIMULATION_STEP_SIZE); // Wait for 10 min
		}
		else {
			this.routeID = newID;
			this.busStop = newRoute;  
			this.stopBus = new Hashtable<Integer, Integer>();
			for(int i=0;i<this.busStop.size();i++){
				this.stopBus.put(this.busStop.get(i), i);
			}
			this.nextDepartureTime = departureTime;
		}
		this.originID = busStop.get(0);
		this.destinationDemandOnBus = new ArrayList<Integer>(Collections.nCopies(this.busStop.size(), 0));
		this.setPassNum(0);
	}
	
	
}
