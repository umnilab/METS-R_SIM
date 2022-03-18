package addsEVs.vehiclecontext;

import java.io.IOException;
import java.util.List;
import org.apache.commons.math3.analysis.interpolation.SplineInterpolator;
import org.apache.commons.math3.analysis.polynomials.PolynomialSplineFunction;

import com.vividsolutions.jts.geom.Coordinate;

import addsEVs.ContextCreator;
import addsEVs.GlobalVariables;
import addsEVs.citycontext.ChargingStation;
import addsEVs.citycontext.Passenger;
import addsEVs.citycontext.Road;
import addsEVs.data.DataCollector;
import repast.simphony.essentials.RepastEssentials;

public class ElectricVehicle extends Vehicle{
	// local variables
	private int numPeople_; // no of people inside the vehicle
	private double avgPersonMass_; // average mass of a person in lbs
	private double batteryLevel_; // current battery level
	private double batteryRechargeLevel_ = 20;
	private double mass; // mass of the vehicle in kg
	private boolean onChargingRoute_ = false;
	private Coordinate tripOrigin; // Because setNextPlan will alter the origin, we need this variable to retain the true origin of a trip.
	
	// parameters for storing energy consumptions
	private double tickConsume;
	private double totalConsume;
	private double linkConsume; // LZ: For UCB eco-routing, energy spent for passing current link, will be reset to zero once this ev entering a new road.
	private double tripConsume; // LZ: For UCB testing
	
    // private PolynomialSplineFunction splinef;   // spline result
	private int originID = -1;
	private int destinationID = -1;
	
	public int served_pass = 0;
	public int charging_time = 0;
	public int charging_waiting_time = 0;
	public double initial_charging_state = 0;
	
	public static double gravity = 9.8; // the gravity is 9.80N/kg for NYC
    public static double batteryCapacity = GlobalVariables.EV_BATTERY; // the storedEnergy is 50 kWh.
	
	// parameter to show which route has been chosen in eco-routing.
	private int routeChoice = -1;
	
	// parameters for Fiori (2016) model
	public static double p0 = 1.2256;
	public static double A = 2.33;
	public static double cd = 0.28;
	public static double cr = 1.75;
	public static double c1 = 0.0328;
	public static double c2 = 4.575;
	public static double etaM = 0.92;
	public static double etaG = 0.91;
	public static double cp = 70000;
	public static double Pconst = 700;
	
	// Parameters for Maia (2012) model
	//	public static double urr = 0.005; // 1996_1998_General Motor Model
	//	public static double densityAir = 1.25; // air density = 1.25kg/m3
	//	public static double A = 1.89; // A = 1.89m2
	//	public static double Cd = 0.19; // Cv
		// Parameter for Fhc, Fla calculation
	//	public static double etaM = 0.95; // etaM
	//	public static double etaG = 0.95; // etaG
	//	public static double Pconst = 1300.0; // pConst
	//	public static double Voc = 325.0; // nominal system voltage
	//	public static double rIn = 0.0; // 
	//	public static double T = 20.0; // assume the temperature is 20 Celsius degree;
	//	public static double k = 1.03; // k is the Peukert coefficient, k = 1.03;
	//	public static double cp = 77.0; // cp = 77.0; ///nominal capacity: 77 AH
	//	public static double c = 20.0;
	
	public ElectricVehicle(){
		super(Vehicle.ETAXI);
		this.setInitialParams();
	}
	public ElectricVehicle(float maximumAcceleration, float maximumDeceleration) {
		super( maximumAcceleration, maximumDeceleration, 1);
		this.setInitialParams();
	}
	
	// LZ: find the closest charging station and update the activity plan
	public void goCharging(int originID){
		this.leaveNetwork();
		int current_dest_zone = this.destinationZone;
		Coordinate current_dest_coord = this.destCoord;
		// Restore current plan
		// this.addPlan(current_dest_zone, current_dep_time);
		// add a charging activity
		ChargingStation cs = ContextCreator.getCityContext().findNearestChargingStation(this.getCurrentCoord());
		this.onChargingRoute_ = true;
		this.setState(Vehicle.CHARGING_TRIP);
		this.addPlan(cs.getIntegerID(), cs.getCoord(), (int) RepastEssentials.GetTickCount());
		//System.out.print("Previous Plan: " + this.getPlan());
		this.setNextPlan();
		this.addPlan(current_dest_zone, current_dest_coord, (int) RepastEssentials.GetTickCount());
		Coordinate currentCoord = this.getCurrentCoord();
		this.tripOrigin = currentCoord;
		this.originID = originID;
		this.destinationID = cs.getIntegerID();
		Road road = ContextCreator.getCityContext().findRoadAtCoordinates(currentCoord, false);
		road.addVehicleToNewQueue(this);
		//System.out.print("After Plan: " + this.getPlan());
		//ContextCreator.logger.info("Vehicle "+ this.getId()+" is on route to charging ( road : "+this.road.getLinkid()+")");
		//this.setNextPlan();
	}
	
    @Override
	public void updateBatteryLevel() {
		//double usage = (mass_ + numPeople_*avgPersonMass_)*this.usageRate_;
    	// Xue, Juan 20191212
    	double tickEnergy = calculateEnergy(); // the energy consumption(kWh) for this tick, LZ: for display, I enlarge this by 5 times 
    	tickConsume = tickEnergy;
    	linkConsume += tickEnergy; // update energy consumption on current link
    	totalConsume += tickEnergy;
    	tripConsume += tickEnergy;
//    	if(Math.random()>0.99){ //Sample 1% data to debug the energy model
//    		//LZ: Log the trip consume here
//			String formated_msg = RepastEssentials.GetTickCount() + "," + this.getVehicleID() + ","+ this.getVehicleClass()+","+ Double.toString(this.currentSpeed()) + ',' + Double.toString(tickConsume);
//			try{
//				ContextCreator.bw2.write(formated_msg);
//				ContextCreator.bw2.newLine();
//			} catch(IOException e){
//				e.printStackTrace();
//			}
//    	}
    	batteryLevel_ -= tickEnergy;
		// if battery level go below some the minlevel go to charge
	}
	
	// relocate vehicle
	/**
	 * @param p
	 */
	public void relocation(int orginID, int destinationID) {
		this.originID = orginID;
		this.destinationID = destinationID;
		this.addPlan(destinationID,
				ContextCreator.getCityContext().findHouseWithDestID(destinationID).getCoord() , (int) RepastEssentials.GetTickCount());
		this.setNextPlan();
		//Add vehicle to newqueue of corresponding road
		Coordinate currentCoord = this.getOriginalCoord();
		this.tripOrigin = currentCoord;
		Road road = ContextCreator.getCityContext().findRoadAtCoordinates(currentCoord, false);
		road.addVehicleToNewQueue(this);
		this.tripConsume = 0; // Start recording the tripConsume
		this.setState(Vehicle.RELOCATION_TRIP);
	}
	
	// serve passenger from the airport
	// TODO: make it can handle multiple destinations
	/**
	 * @param p
	 */
	public void servePassenger(List<Passenger> plist) {
		if(!plist.isEmpty()) {
			this.originID = plist.get(0).getOrigin();
			this.destinationID = plist.get(0).getDestination();
			for(Passenger p: plist) {
				this.addPlan(p.getDestination(),
						ContextCreator.getCityContext().findHouseWithDestID(p.getDestination()).getCoord(), (int) RepastEssentials.GetTickCount());
				this.served_pass += 1;
				this.setNumPeople(this.getNumPeople()+1);
			}
			this.setNextPlan();
			// Add vehicle to new queue of corresponding road
			Coordinate currentCoord = this.getOriginalCoord();
			this.tripOrigin = currentCoord;
			Road road = ContextCreator.getCityContext().findRoadAtCoordinates(currentCoord, false);
			road.addVehicleToNewQueue(this);
			this.tripConsume = 0; // Start recording the tripConsume
			this.setState(Vehicle.OCCUPIED_TRIP);
		}
	}

	@Override
	public void setReachDest() {
		// check if the vehicle was on a charging route 
		if(this.onChargingRoute_) {
			String formated_msg = RepastEssentials.GetTickCount() + "," + 
			this.getVehicleID() + ",4,"+ this.getOriginID()+","+
					this.getDestinationID()+"," + this.accummulatedDistance_ +"," +this.getDepTime()+","+this.getTripConsume()+",-1" + "," + this.getNumPeople();
			try{
				ContextCreator.ev_logger.write(formated_msg);
				ContextCreator.ev_logger.newLine();
				this.accummulatedDistance_=0;
			} catch(IOException e){
				e.printStackTrace();
			}
			this.leaveNetwork(); // remove from the network
			//Add to the charging station
			ContextCreator.logger.info("Vehicle arriving at charging station:"+this.getId());
			ChargingStation cs = ContextCreator.getCityContext().findChargingStationWithID(this.getDestinationZoneID());
			//System.out.println(cs);
			cs.receiveVehicle(this);
			this.endTime = (int) RepastEssentials.GetTickCount();
			this.reachActLocation = true;
		} else {
			//LZ: Log the trip consume here
			String formated_msg = RepastEssentials.GetTickCount() + "," + 
			this.getVehicleID() + ","+this.getState()+","+ this.getOriginID()+","+
					this.getDestinationID()+"," + this.accummulatedDistance_ + "," + this.getDepTime()+","+this.getTripConsume()+","+this.getRouteChoice()+ "," + this.getNumPeople();
			try{
				ContextCreator.ev_logger.write(formated_msg);
				ContextCreator.ev_logger.newLine();
				this.accummulatedDistance_=0;
			} catch(IOException e){
				e.printStackTrace();
			}
			
			this.originID = this.destinationID; // Next trip starts from the previous destination
			if(this.getState() == Vehicle.OCCUPIED_TRIP) {
				this.setNumPeople(this.getNumPeople() - 1); // Passenger arrived
			}
			if(!onChargingRoute_ && batteryLevel_ <= batteryRechargeLevel_ && this.getNumPeople() == 0) {
				this.setState(Vehicle.CHARGING_TRIP);
				this.goCharging(this.destinationID);
			}
			else if(this.getNumPeople()>0){
				this.setNextPlan();
				if(this.originID == this.destinationID) { // next passenger has the same destination as the previous one's
					this.setReachDest();
				}
			}
			else{
				ContextCreator.getVehicleContext().getVehicles(this.getDestinationID()).add(this);//append this vehicle to the available vehicle of given zones
				ContextCreator.getCityContext().findHouseWithDestID(this.getDestinationID()).addVehicleStock(1);
				super.setReachDest(); // Call setReachDest in vehicle class.
			}
		}
	}
	
	public Coordinate getTripOrigin(){
		return this.tripOrigin;
	}
	

	public void setInitialParams() {
		this.numPeople_ = 0;
		this.batteryLevel_ =  GlobalVariables.EV_BATTERY; //unit:kWh, times a large number to disable charging
		this.mass = 1521; // 4000
		this.avgPersonMass_ = 60; //180
		this.tripOrigin = null;
		
		// Parameters for energy calculation
		this.tickConsume = 0.0;  //kWh
		this.totalConsume = 0.0;  //kWh
		//For Maia's model
//		double soc[] = {0.00, 0.10, 0.20, 0.40, 0.60, 0.80, 1.00};             
//		double r[] = {0.0419, 0.0288, 0.0221, 0.014, 0.0145, 0.0145, 0.0162}; 
		//For Fiori's model
//		double soc[] = {0.00, 0.10, 0.20, 0.40, 0.60, 0.80, 1.00};  
//		double r[] =  {7.961, 5.472, 4.199, 2.66 , 2.755, 2.755, 3.078};
//		PolynomialSplineFunction f = splineFit(soc,r);                         
//		this.splinef = f; 
		
		//Parameters for UCB calculation
		this.linkConsume = 0;
	}
	
	
	public double getBatteryLevel() {
		return batteryLevel_;
	}
	
	public int getNumPeople() {
		return numPeople_;
	}
	
	public void setNumPeople(int numPeople){
		numPeople_ = numPeople;
	}
	
	public double getMass() {
		return 1.05*(mass+numPeople_*avgPersonMass_);
	}
	
	@Override
	public boolean onChargingRoute(){
		return this.onChargingRoute_;
	}
	
	// xue: charge the battery.
	public void chargeItself(double batteryValue) {
		charging_time += GlobalVariables.SIMULATION_CHARGING_STATION_REFRESH_INTERVAL;
		batteryLevel_ += batteryValue;
	}
	
	// xue: vehicle arrive at the charging station
	public void vehicleArrive(ChargingStation chargingStation){
		chargingStation.receiveVehicle(this);
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
		double f2 = getMass() * gravity + Math.cos(slope)*cr/1000*(c1*velocity+c2);
		double f3 = 1/2*p0*A*cd*velocity*velocity;
		double f4 = getMass() * gravity * Math.sin(slope);
		double F = Math.abs(f1+f2+f3+f4);
		double Pte = F*velocity;
		double Pbat;
		if(acceleration>=0){
			Pbat = (Pte + Pconst)/(etaM*etaG);
		}
		else{
			double nrb = 1/Math.exp(0.0411/Math.abs(acceleration));
			Pbat = (Pte + Pconst)*nrb;
		}
		double energyConsumption = Pbat*dt/(3600*cp);
		return energyConsumption;
	}
	
	// Old EV energy consumption model:
	/*R. Maia, M. Silva, R. Arajo, and U. Nunes, 
	Electric vehicle simulator for energy consumption studies in electric mobility systems, 
	 in 2011 IEEE Forum on Integrated and Sustainable Transportation Systems, 2011, pp.232.
	*/
	// Xue, Juan 20191212: calculate the energy for each vehicle per tick. return: kWh.
//	public double calculateEnergy(){		
//		double velocity = currentSpeed();   // obtain the speed
//		double acceleration = currentAcc(); // obtain the acceleration
//		if(!this.movingFlag){
//			return 0;
//		}
//		double slope = 0.0f;          //positive: uphill; negative: downhill
//		double dt = GlobalVariables.SIMULATION_STEP_SIZE;   // this time interval. the length of one tick. 0.3
//		double currentSOC = Math.max(getBatteryLevel()/(this.batteryCapacity + 0.001), 0.001);     // currentSOC
//		// System.out.println("vehicle energy :"+splinef.value(currentSOC)+" "+currentSOC + " " + (getBatteryLevel()));
//		// System.out.println("currentSOC: " + currentSOC + " Battery Level: "+getBatteryLevel());	
//		//step 1: use the model: Fte = Frr + Fad + Fhc + Fla + Fwa. And Fwa = 0.
//		//mass_ = mass_ * 1.05;
//		double Frr = ElectricVehicle.urr * mass_ * ElectricVehicle.gravity;
//		//System.out.println("VID: "+ this.vehicleID_ + " urr: "+ GlobalVariables.urr + "mass_: " + mass_ + " gravity: " + GlobalVariables.gravity);
//		double Fad = 0.5 * ElectricVehicle.densityAir * ElectricVehicle.A * ElectricVehicle.Cd * velocity * velocity;
//		double Fhc = mass_ * ElectricVehicle.gravity * Math.sin(slope); //can be positive, can be negative  // mass loss // m = 1.05
//		double Fla = mass_ * acceleration;       //can be positive, can be negative
//		double Fte = Frr + Fad + Fhc + Fla;     //can be positive, can be negative
//		double Pte = Math.abs(Fte) * velocity;  //positive unit: W
//		
//		//step 2: two cases
//		double Pbat = 0.0f;
//		if (Fte >= 0){      //driven case
//			Pbat = (Pte+ElectricVehicle.Pconst)/ElectricVehicle.etaM/ElectricVehicle.etaG;		   //positive	
//		}else{              //regenerative case
//			Pbat = (Pte+ElectricVehicle.Pconst)*ElectricVehicle.etaM*ElectricVehicle.etaG;         //positive
//		}	
//		double rIn = splinef.value(currentSOC)*ElectricVehicle.c;          //c	
//		double Pmax = ElectricVehicle.Voc*ElectricVehicle.Voc/4.0/rIn;               //rIn
//		
//		//step 3: energy calculation
//		double kt = ElectricVehicle.k/1.03*(1.027 - 0.001122*ElectricVehicle.T + 1.586*Math.pow(10, -5*ElectricVehicle.T*ElectricVehicle.T));  //kt depends on T also.
//		double cpt = ElectricVehicle.cp/2.482*(2.482 + 0.0373*ElectricVehicle.T - 1.65*Math.pow(10, -4*ElectricVehicle.T*ElectricVehicle.T));  //cpt depends on T also. //real capacity: not 77 AH
//		
//		double CR = 0.0f;
//		double I = 0.0f;
//		if (Pbat > Pmax){ 
////			System.out.println("Error process, output error, need to recalculate vi"+Pbat+"," + Pmax+","+Fte+","+velocity+","+acceleration);
//			Pbat = Pmax;
//		}
//		
//        if(Pbat >= 0){  //driven case
//			I = (ElectricVehicle.Voc - Math.sqrt(ElectricVehicle.Voc*ElectricVehicle.Voc -4 * rIn * Pbat + 1e-6))/(2*rIn); // Prevent negative value by adding a tiny error
//			CR = Math.pow(I, kt)*dt;     //unit: AS                                       // I_kt??
//			//System.out.println("VID: "+ this.vehicleID_ + " Pte: "+ Pte + "Frr: " + Frr + " Fad: " + Fad + "Fhc: " + Fhc + "Fla: " + Fla +"V:" + velocity + "a: "+acceleration);
//			//System.out.println("VID: "+ this.vehicleID_ + " Pte: "+ Pte+ " batter level" + this.batteryLevel_ + " within sqrt:"+ Double.toString(GlobalVariables.Voc*GlobalVariables.Voc -4 * rIn * Pbat)); //LZX: Negative power!
//		}else{              //regenerative case
//			I = (0.0f - ElectricVehicle.Voc + Math.sqrt(ElectricVehicle.Voc*ElectricVehicle.Voc + 4 * rIn * Pbat + 1e-6))/(2*rIn);    // Prevent negative value by adding a tiny error
//			CR = -I * dt;    //unit: AS  //?
//		}
//		
//		double capacityConsumption = CR/(cpt*3600);   //unit: AH 
//		double energyConsumption = capacityConsumption * this.batteryCapacity;//ElectricVehicle.storedEnergy;  //unit: kWh
//		//System.out.println("ev energy :"+splinef.value(currentSOC)+" "+currentSOC);
////		if(Double.isNaN(energyConsumption)){
//////			System.out.println("v: "+ velocity + " acc: "+acceleration + " currentSOC: " + currentSOC);
//////			System.out.println("Frr: "+ Frr + " Fad: "+Fad + " Fhc: " + Fhc + " Fla: " + Fla);
//////			System.out.println("Fte: "+ Fte + " Pte: "+Pte + " Pbat: " + Pbat);
//////			System.out.println("rIn:"+ rIn + " Pmax: "+Pmax + " kt: " + kt);
//////			System.out.println("cpt:"+ cpt + " CR: "+CR + " I: " + I);
////			return -0.01;
////		}
//		if (energyConsumption > 0.1) {
//			System.out.println("v: " + velocity + " acc: " + acceleration + " currentSOC: " + currentSOC);
//			System.out.println("Frr: " + Frr + " Fad: " + Fad + " Fhc: " + Fhc + " Fla: " + Fla);
//			System.out.println("Fte: " + Fte + " Pte: " + Pte + " Pbat: " + Pbat);
//			System.out.println("rIn:" + rIn + " Pmax: " + Pmax + " kt: " + kt);
//			System.out.println("cpt:" + cpt + " CR: " + CR + " I: " + I);
//		}
//		return energyConsumption;
//	}
	
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
		String formated_msg = RepastEssentials.GetTickCount()+ "," + chargerID + "," + this.getVehicleID() + "," + this.getVehicleClass()
				+ "," + chargerType + "," + this.charging_waiting_time + "," + this.charging_time + ","
				+ this.initial_charging_state;
		try {
			ContextCreator.charger_logger.write(formated_msg);
			ContextCreator.charger_logger.newLine();
			this.charging_waiting_time = 0;
			this.charging_time = 0;
		} catch (IOException e) {
			e.printStackTrace();
		}
				
		this.onChargingRoute_ = false;
		this.setNextPlan();
		this.originID = this.destinationID;
		this.destinationID = this.getDestinationZoneID();
		Coordinate currentCoord = this.getOriginalCoord();
		this.tripOrigin = currentCoord;
		Road road = ContextCreator.getCityContext().findRoadAtCoordinates(currentCoord, false);
		road.addVehicleToNewQueue(this);
	}
	
	public double getLinkConsume(){
		return linkConsume;
	}
	
	// Reset link consume once a ev has passed a link
	public void resetLinkConsume(){ 
		this.linkConsume = 0;
	}
	public void recLinkSnaphotForUCB() {
//		System.out.println("Recording UCB data!");
		//System.out.println("Record data for UCB!");
		DataCollector.getInstance().recordLinkSnapshot(this.getRoad().getLinkid(),this.getLinkConsume());
//		this.resetLinkConsume(); // Reset link consumption to 0
	}
	
	//July, 2020, Jiawei Xue
	public void recSpeedVehicle(){
		DataCollector.getInstance().recordSpeedVehilce(this.getRoad().getLinkid(),this.currentSpeed());
	}
	
	public int getOriginID(){
		return this.originID;
	}
	
	public int getDestinationID(){
		return this.destinationID;
	}
	
	public double getTripConsume(){
		return tripConsume;
	}
	
	public void setRouteChoice(int i) {
		this.routeChoice = i;
	}
	
	public int getRouteChoice() {
		return this.routeChoice;
	}
}

