package addsEVs.data;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;

import addsEVs.ContextCreator;
import addsEVs.GlobalVariables;
import au.com.bytecode.opencsv.CSVReader;
import java.util.*;

/**
 * @author: Xiaowei
 * Read random values for EV and request valuation.
 * 
 **/


public class CachedRandomValues{
	public ArrayList<Double> valuatioRequest;
	public ArrayList<Double> valuationEV;
	public ArrayList<Double> randomValue;
	
	public CachedRandomValues(){
		ContextCreator.logger.info("Read random values.");
		readEventFile();
//		validPassNum(); // For debugging the demand generation process
	}
	
	// Read and parse the CSV data
	public void readEventFile() {
		File valueFile = new File(GlobalVariables.VALUATION_FILE_PASS);
		CSVReader valueReader = null;
		String[] nextLinevalue;
		//System.out.println("Load Valuation File");
		valuatioRequest = new ArrayList<Double>();
		try {
			valueReader = new CSVReader(new FileReader(valueFile),',');
		    while ((nextLinevalue = valueReader.readNext()) != null) {
		    	//System.out.println("The nextLinevalue "+nextLinevalue);
		    	double theValue = Double.parseDouble(nextLinevalue[0]);
		    	valuatioRequest.add(theValue);
		    }} catch (FileNotFoundException e) {
				e.printStackTrace();
			} catch (IOException e) {
				e.printStackTrace();
			} catch (Exception e) {
				e.printStackTrace();
			}

		File valueFileEV = new File(GlobalVariables.VALUATION_FILE_EV); // added by xiaowei on 09/29
		CSVReader valuereaderEV = null;
		String[] nextLinevalueEV;
		//System.out.println("Load Valuation File");
		valuationEV = new ArrayList<Double>();
		try {
			valuereaderEV = new CSVReader(new FileReader(valueFileEV),',');
		    while ((nextLinevalueEV = valuereaderEV.readNext()) != null) {
		    	//System.out.println("The nextLinevalue "+nextLinevalue);
		    	double theValue = Double.parseDouble(nextLinevalueEV[0]);
		    	valuationEV.add(theValue);
		    }} catch (FileNotFoundException e) {
				e.printStackTrace();
			} catch (IOException e) {
				e.printStackTrace();
			} catch (Exception e) {
				e.printStackTrace();
			}
		
		File randomFileValue = new File(GlobalVariables.RANDOM_VALUE); // added by xiaowei on 09/29
		CSVReader randomValueReader = null;
		String[] nextLineRandomValue;
		//System.out.println("Load Valuation File");
		randomValue = new ArrayList<Double>();
		try {
			randomValueReader = new CSVReader(new FileReader(randomFileValue),',');
		    while ((nextLineRandomValue = randomValueReader.readNext()) != null) {
		    	//System.out.println("The nextLinevalue "+nextLinevalue);
		    	double theValue = Double.parseDouble(nextLineRandomValue[0]);
		    	randomValue.add(theValue);
		    }} catch (FileNotFoundException e) {
				e.printStackTrace();
			} catch (IOException e) {
				e.printStackTrace();
			} catch (Exception e) {
				e.printStackTrace();
			}
	}
}