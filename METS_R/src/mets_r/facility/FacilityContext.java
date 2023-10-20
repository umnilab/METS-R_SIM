package mets_r.facility;

import java.util.Collection;
import java.util.HashMap;

import mets_r.ContextCreator;
import repast.simphony.context.DefaultContext;

public class FacilityContext<T> extends DefaultContext<T>{
	private HashMap<Integer, T> facilityDictionary;
	
	public FacilityContext(String facilityName) {
		super(facilityName);
		facilityDictionary = new HashMap<Integer, T>();
	}
	
	public void put(int ID, T newFacility) {
		if(facilityDictionary.containsKey(ID)) {
			ContextCreator.logger.warn("Facility with ID: " + ID + " already exists.");
		}
		this.facilityDictionary.put(ID, newFacility);
		this.add(newFacility);
	}
	
	public T get(int ID) {
		if (this.facilityDictionary.containsKey(ID)) {
			return this.facilityDictionary.get(ID);
		} else {
			return null;
		}
	}
	
	public boolean contains(int ID) {
		if(this.facilityDictionary.containsKey(ID)) {
			return true;
		}
		else {
			return false;
		}
	}
	
	public Collection<T> getAll(){
		return this.facilityDictionary.values();
	}
}
