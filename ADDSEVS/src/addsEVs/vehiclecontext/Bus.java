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

/* For test stage, we have 10 zones: 0,1,2,3,4,5,6,7,8,9.
 * We assume the bus route is a cycle. 
 * For instance, the bus route is [0,1,2,3,4,5,6,7,8,9,8,7,6,5,4,3,2,1]. It starts at zone 0 and comes back to zone 0.
 */

public class Bus extends Vehicle{
	private int routeID;
	private int passNum;
	private int numSeat;                        //capacity of the bus.
	private int nextStop;                       //nextStop =i  means the i-th entry of ArrayList<Integer> busStop. i=0,1,2,...,17.
	private ArrayList<Integer> busStop;         
	// Each entry represents a bus stop (zone) along the bus route.
	// For instance, it is [0,1,2,3,4,5,6,7,8,9,8,7,6,5,4,3,2,1];
	private Dictionary<Integer, Integer> stopBus; // LZ: reverse table for track the zone in the busStop;
	
	// Timetable variable here, the next departure time
	private int nextDepartureTime;
	private int roundTripTime;
	
	private ArrayList<Integer> destinationDemandOnBus;  
	// the destination distribution of passengers on the bus now.  
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
	
	//July,2020,JiaweiXue
	private double linkConsume; // LZ: For UCB eco-routing, energy spent for passing current link, will be reset to zero once this ev entering a new road.
	private double tripConsume; // LZ: For UCB testing
	
	private PolynomialSplineFunction splinef;   // spline result
	
	//Parameter for Frr calculation
	public static double urr = 0.005; // 1996_1998_General Motor Model
	public static double gravity = 9.8; // the gravity is 9.80N/kg for NYC
	// Parameters for Fad calculation
	public static double densityAir = 1.25; // air density = 1.25kg/m3
	public static double A = 8; // A = 1.89m2
	public static double Cd = 0.4; // Cv
	// Parameter for Fhc, Fla calculation
	public static double etaM = 0.95; // etaM
	public static double etaG = 0.95; // etaG
	public static double Pconst = 10000.0; // pConst
	public static double Voc = 600.0; // nominal system voltage ///???
										// 325?350?343?
	public static double rIn = 0.0; // ///??? c= 6.0?
	public static double T = 20.0; // assume the temperature is 20 Celsius
									// degree;
	public static double k = 1.03; // k is the Peukert coefficient, k = 1.03;
	public static double cp = 216.0; // cp = 216; ///nominal capacity: 216 AH
	public static double c = 45;
	public static double batteryCapacity = GlobalVariables.BUS_BATTERY; // the storedEnergy is 250 kWh.
	
	private int originID = -1;
	private int destinationID = -1;
	// Parameter to show which route has been chosen in eco-routing.
	private int routeChoice = -1;
	
	// Function 1: constructors
	public Bus(int routeID, ArrayList<Integer> route, int nextDepartureTime){
		super(Vehicle.EBUS); // Class 2 means bus
//		System.out.println("Bus id:"+this.vehicleID_); // See if this id appear in the output file
		this.routeID = routeID;
		this.busStop = route;  
		this.originID = route.get(0);
		this.stopBus = new Hashtable<Integer, Integer>();
		for(int i=0;i<route.size();i++){
			stopBus.put(route.get(i), i);
		}
		this.nextDepartureTime = nextDepartureTime;
//		this.roundTripTime = roundTripTime;
//		System.out.println("Bus route "+ this.busStop+"  Route bus "+ this.stopBus + " depature time " + this.nextDepartureTime+ " round trip time " + this.roundTripTime);
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
		double soc[] = {0.00, 0.10, 0.20, 0.40, 0.60, 0.80, 1.00};             
		double r[] = {0.0419, 0.0288, 0.0221, 0.014, 0.0145, 0.0145, 0.0162}; 
		PolynomialSplineFunction f = splineFit(soc,r);                         
		this.splinef = f; 
	}
	
	// Function 4: updateBatteryLevel
	 @Override
	public void updateBatteryLevel() {
		double tickEnergy = calculateEnergy(); // the energy consumption(kWh) for this tick
		tickConsume = tickEnergy;
		totalConsume += tickEnergy;

		// July,2020,JiaweiXue
		linkConsume += tickEnergy;
		tripConsume += tickEnergy;
//		if (Math.random() > 0.99) { // Sample 1% data
//			// LZ: Log the trip consume here
//			String formated_msg = RepastEssentials.GetTickCount() + "," + this.getVehicleID() + ","
//					+ this.getVehicleClass() + "," + Double.toString(this.currentSpeed()) + ','
//					+ Double.toString(tickConsume);
//			try {
//				ContextCreator.bw2.write(formated_msg);
//				ContextCreator.bw2.newLine();
//			} catch (IOException e) {
//				e.printStackTrace();
//			}
//		}

		// System.out.println( Double.toString(totalConsume) +','+
		// Double.toString(this.accummulatedDistance_));
		// System.out.println( Double.toString(tickConsume) +','+
		// Double.toString(this.currentSpeed()));
		batteryLevel_ -= tickEnergy;
	}
	 
	public double getTripConsume(){
			return tripConsume;
	}

	// function 5: busDeparture from current stop
	public void departure(int destinationID){
		this.destinationID = destinationID;
		Coordinate currentCoord = this.getOriginalCoord();
		Road road = ContextCreator.getCityContext().findRoadAtCoordinates(currentCoord, false);
		road.addVehicleToNewQueue(this);
//		System.out.println("Bus "+this.vehicleID_+" is re-entering the Road " + road.getID());
	}
	
	//The setReachDest() function applies for three cases: 
	//Case 1: arrive at the charging station.
	//Case 2: arrive at the start bus stop, and then go the charging station
	//Case 3: (arrive at the other bus stop), or (arrive at the start bus stop and continue to move)
	
	public void setReachDest() {
		// int tickcount = (int) RepastEssentials.GetTickCount(); // current time tick
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
			ContextCreator.logger.info("Bus arriving at charging station:"+this.getId());
			ChargingStation cs = ContextCreator.getCityContext().findChargingStationWithID(this.getDestinationZoneID());
//			System.out.println(cs);
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
					this.accummulatedDistance_ = 0;
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
			
			this.originID = this.destinationID;
			// store the current information
			int current_dest_zone = this.destinationZone;
			Coordinate current_dest_coord = this.destCoord;
			
			// drop off passengers.
			this.leaveNetwork();
			ContextCreator.logger.info("Bus arriving at origin stop: " + nextStop);
			this.setPassNum(this.getPassNum()-this.destinationDemandOnBus.get(nextStop));
			this.destinationDemandOnBus.set(nextStop,0);
			
			// add the plan to the charging station.
			ChargingStation cs = ContextCreator.getCityContext().findNearestBusChargingStation(this.getCurrentCoord());
			this.addPlan(cs.getIntegerID(), cs.getCoord(), (int) RepastEssentials.GetTickCount()); // instantly go to charging station
			ContextCreator.logger.info("Bus "+ this.getId()+" is on route to charging ( road : "+this.road.getLinkid()+")");
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
			ContextCreator.logger.info("Bus arriving at bus stop: " + nextStop);
			
			this.setPassNum(this.getPassNum()-this.destinationDemandOnBus.get(nextStop));
			this.destinationDemandOnBus.set(nextStop,0);
			
			// Serve passengers
			this.servePassenger();
			
			// start the bus again
			if (nextStop == busStop.size()-1) { // arrive at the last stop
				nextStop = 0;
				ContextCreator.busSchedule.popSchedule(this.busStop.get(0), this);
				// System.out.println("Bus "+this.id+" finished one loop!");
				// Update the schedule if reaching the next_update_time
				// System.out.println(this.activityplan.get(0).getDestID());
			}
			else{
				nextStop = Math.min(nextStop + 1, this.busStop.size()-1);        //arrive at the other bus stop
			}
			
			this.addPlan(busStop.get(nextStop), 
					ContextCreator.getCityContext().findHouseWithDestID(busStop.get(nextStop)).getCoord(), 
					Math.max((int) RepastEssentials.GetTickCount(), nextDepartureTime));
			this.setNextPlan();
//			System.out.println("Bus "+this.id+" has arrive the next station: " +nextStop);
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
    
    public double calculateEnergy(){		
		double velocity = currentSpeed();   // Obtain the speed
		double acceleration = currentAcc(); // Obtain the acceleration
		if(!this.movingFlag){
			velocity = 0;
			acceleration = 0;
		}
		double slope = 0.0f;          // Positive: uphill; negative: downhill
		double dt = GlobalVariables.SIMULATION_STEP_SIZE;   // this time interval. the length of one tick. 0.3
		double currentSOC = Math.min(Math.max(getBatteryLevel()/(Bus.batteryCapacity+0.001), 0.001),0.99);     // currentSOC
		// Step 1: use the model: Fte = Frr + Fad + Fhc + Fla + Fwa. And Fwa = 0.
		double Frr = Bus.urr * (mass_+ avgPersonMass_* passNum) * Bus.gravity;
		double Fad = 0.5 * Bus.densityAir * Bus.A * Bus.Cd * velocity * velocity;
		double Fhc = (mass_+ avgPersonMass_* passNum) * Bus.gravity * Math.sin(slope); //can be positive, can be negative  // mass loss // m = 1.05
		double Fla = (mass_+ avgPersonMass_* passNum) * acceleration;       //can be positive, can be negative
		double Fte = Frr + Fad + Fhc + Fla;     //can be positive, can be negative
		double Pte = Math.abs(Fte) * velocity;  //positive unit: W
		
		// Step 2: two cases
		double Pbat = 0.0f;
		if (Fte >= 0){      //driven case
			Pbat = (Pte+Bus.Pconst)/Bus.etaM/Bus.etaG;		   //positive	
		}else{              //regenerative case
			Pbat = (Pte+Bus.Pconst)*Bus.etaM*Bus.etaG;         //positive
		}	
		double rIn = splinef.value(currentSOC)*Bus.c;          //c?		
		double Pmax = Bus.Voc*Bus.Voc/4.0/rIn;               //rIn
		
		// Step 3: energy calculation
		double kt = Bus.k/1.03*(1.027 - 0.001122*Bus.T + 1.586*Math.pow(10, -5*Bus.T*Bus.T));  //kt depends on T also.
		double cpt = Bus.cp/2.482*(2.482 + 0.0373*Bus.T - 1.65*Math.pow(10, -4*Bus.T*Bus.T));  //cpt depends on T also. //real capacity: not 77 AH
		
		double CR = 0.0f;
		double I = 0.0f;
		if (Pbat > Pmax){ 
			Pbat = Pmax;
		}
		
		if(Pbat >= 0){  //driven case
			I = (Bus.Voc - Math.sqrt(Bus.Voc*Bus.Voc -4 * rIn * Pbat + 1e-6))/(2*rIn);// Prevent negative value by adding a tiny error
			CR = Math.pow(I, kt)*dt;     //unit: AS                                       // I_kt??
		}else{              //regenerative case
			I = (0.0f - Bus.Voc + Math.sqrt(Bus.Voc*Bus.Voc + 4 * rIn * Pbat + 1e-6))/(2*rIn);     // Prevent negative value by adding a tiny error
			CR = -I * dt;    //unit: AS  //?
		}
		
		double capacityConsumption = CR/(cpt*3600);   //unit: AH 
		double energyConsumption = capacityConsumption * Bus.batteryCapacity;  //unit: kWh
//		if(Double.isNaN(energyConsumption)){
//			System.out.println("v: "+ velocity + " acc: "+acceleration + " currentSOC: " + currentSOC);
//			System.out.println("Frr: "+ Frr + " Fad: "+Fad + " Fhc: " + Fhc + " Fla: " + Fla);
//			System.out.println("Fte: "+ Fte + " Pte: "+Pte + " Pbat: " + Pbat);
//			System.out.println("rIn:"+ rIn + " Pmax: "+Pmax + " kt: " + kt);
//			System.out.println("cpt:"+ cpt + " CR: "+CR + " I: " + I);
//			System.out.println("energyConsumption: "+ energyConsumption);
//			System.out.println("LLLL: "+  (Bus.Voc*Bus.Voc -4 * rIn * Pbat));
//			System.out.println("LLLLL: "+  Math.sqrt(Bus.Voc*Bus.Voc -4 * rIn * Pbat));
//			return -1;
//		}
		return energyConsumption;
	}
    // Xue, Juan 20191212: spline interpolation 
 	public PolynomialSplineFunction splineFit(double[] x, double[] y) {
 		SplineInterpolator splineInt = new SplineInterpolator();
 		PolynomialSplineFunction polynomialSpl = splineInt.interpolate(x, y);
 		return polynomialSpl;
 	}

 	// Xue, Juan 20191212
 	public double getTickConsume() {
 		return tickConsume;
 	}

 	// Xue, Juan 20191212
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
			this.charging_waiting_time = 0;
			this.charging_time = 0;
		} catch (IOException e) {
			e.printStackTrace();
		}
 		this.onChargingRoute_ = false;
 	}
 	
 	//July,2020,JiaweiXue
	public int getOriginID(){
		return this.originID;
	}
	
	//July,2020,JiaweiXue
	public int getDestinationID(){
		return this.destinationID;
	}
	
	//July,2020,JiaweiXue
	public void setRouteChoice(int i) {
		this.routeChoice = i;
	}
	
	//July,2020,JiaweiXue
	public double getLinkConsume(){
		return linkConsume;
	}
	
	//July,2020,JiaweiXue
	// Reset link consume once a ev has passed a link
	public void resetLinkConsume(){ 
		this.linkConsume = 0;
	}
	
	//July,2020,JiaweiXue
//	public void recLinkSnaphotForUCBBus() {
//		//System.out.println("Record data for UCB!");
//		DataCollector.getInstance().recordLinkSnapshotBus(this.getRoad().getLinkid(),this.getLinkConsume());
//		this.resetLinkConsume(); // Reset link consumption to 0
//	}
	
	public void updateSchedule(int newID, ArrayList<Integer> newRoute, int departureTime) {
		if(newID==-1) {
			this.routeID = -1;
			this.busStop = new ArrayList<Integer>(Arrays.asList(this.busStop.get(0)));
			this.nextDepartureTime = (int) (RepastEssentials.GetTickCount()+3600/GlobalVariables.SIMULATION_STEP_SIZE);
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
	}
	
	
}
