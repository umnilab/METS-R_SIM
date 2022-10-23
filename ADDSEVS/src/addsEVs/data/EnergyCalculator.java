package addsEVs.data;
import java.util.concurrent.ConcurrentLinkedQueue;
import addsEVs.ContextCreator;
import addsEVs.vehiclecontext.ElectricVehicle;

/**
 * An energy calculator class for debugging
 * 
 * @author Zengxiang Lei
 *
 */
public class EnergyCalculator{
	private double cumulativeConsumption;     // TotalEnergyConsumption
	private double tickConsumption;           // EnergyConsumptionPerTick
	
	// Function 1: construction function
	public EnergyCalculator() {
		this.cumulativeConsumption = 0.0f;
		this.tickConsumption = 0.0f;
	}
	
	// Function 2: calculate whole energy
	public void calculateEnergy(){
		this.tickConsumption = 0.0f;
		this.cumulativeConsumption = 0.0f;
		for (int i=0; i<9; i++){
			ConcurrentLinkedQueue<ElectricVehicle> evList = ContextCreator.getVehicleContext().getVehiclesByZone(i);
			for (ElectricVehicle ev : evList) {
				this.tickConsumption += ev.getTickConsume();
				this.cumulativeConsumption += ev.getTotalConsume();
			}	
		}
		
		ContextCreator.logger.debug("Current tick consumption is "+ this.tickConsumption);
		ContextCreator.logger.debug("Total consumption is " + this.cumulativeConsumption);
	}
}
