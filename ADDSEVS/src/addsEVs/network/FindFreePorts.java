package addsEVs.network;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;

import java.util.Collections;

public class FindFreePorts {

	public static void main(String[] args) {
		// TODO Auto-generated method stub
		int numOfPorts = 2;
		ArrayList<Integer> freePorts = new ArrayList<Integer>();
		ArrayList<ServerSocket> servers = new ArrayList<ServerSocket>();
		for(int i = 0; i < numOfPorts; i++) {
			ServerSocket s;
			try {
				s = new ServerSocket(0);
				System.out.println("listening on port: " + s.getLocalPort());
				freePorts.add(s.getLocalPort());
				servers.add(s);
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		
		
		for(ServerSocket s : servers) {
			try {
				s.close();
				System.out.println("Closed socket server : " + s.getLocalPort());
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		// sort
		Collections.sort(freePorts);
		System.out.println("Free ports available on this machine : ");
		System.out.println(freePorts.toString().replaceAll("\\s+",""));

	}

}
