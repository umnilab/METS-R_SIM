package addsEVs.data;
import java.lang.Math;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.commons.math3.analysis.interpolation.SplineInterpolator;
import org.apache.commons.math3.analysis.polynomials.PolynomialSplineFunction;

import addsEVs.ContextCreator;
import addsEVs.vehiclecontext.ElectricVehicle;
import addsEVs.vehiclecontext.Vehicle;

public class EnergyCalculator{
	private double cumulativeConsumption;     //totalEnergyConsumption
	private double tickConsumption;           //energyConsumptionPerTick
	
	// function 1: construction function
	public EnergyCalculator() {
		this.cumulativeConsumption = 0.0f;
		this.tickConsumption = 0.0f;
	}
	
	//function 2: calculate whole energy
	public void calculateEnergy(){
		this.tickConsumption = 0.0f;
		this.cumulativeConsumption = 0.0f;
		for (int i=0; i<9; i++){
			LinkedBlockingQueue<ElectricVehicle> evList = ContextCreator.getVehicleContext().getVehicles(i);
			for (ElectricVehicle ev : evList) {
				this.tickConsumption += ev.getTickConsume();
				this.cumulativeConsumption += ev.getTotalConsume();
			}	
		}
		
		System.out.println("Current tick consumption is "+ this.tickConsumption);
		System.out.println("Total consumption is " + this.cumulativeConsumption);
	}
}
