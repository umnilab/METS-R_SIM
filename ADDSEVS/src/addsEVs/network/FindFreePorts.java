package addsEVs.network;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;

import java.util.Collections;

import addsEVs.ContextCreator;

public class FindFreePorts {

	public static void main(String[] args) {
		int numOfPorts = 2;
		ArrayList<Integer> freePorts = new ArrayList<Integer>();
		ArrayList<ServerSocket> servers = new ArrayList<ServerSocket>();
		for(int i = 0; i < numOfPorts; i++) {
			ServerSocket s;
			try {
				s = new ServerSocket(0);
				ContextCreator.logger.info("listening on port: " + s.getLocalPort());
				freePorts.add(s.getLocalPort());
				servers.add(s);
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		
		
		for(ServerSocket s : servers) {
			try {
				s.close();
				ContextCreator.logger.info("Closed socket server : " + s.getLocalPort());
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		// Sort
		Collections.sort(freePorts);
		ContextCreator.logger.info("Free ports available on this machine : ");
		ContextCreator.logger.info(freePorts.toString().replaceAll("\\s+",""));

	}

}
